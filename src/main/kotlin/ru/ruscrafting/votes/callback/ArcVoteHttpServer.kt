package ru.ruscrafting.votes.callback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.ruscrafting.votes.config.HttpSettings
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/** Loopback-only feature ingress. Public TLS/routing belongs to the managed reverse proxy. */
class ArcVoteHttpServer(
    private val settings: HttpSettings,
    private val ingress: VoteIngressService,
    private val logger: Logger,
) : AutoCloseable {
    private val threadSequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        settings.workerThreads,
        settings.workerThreads,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(settings.queueCapacity),
        { task -> Thread(task, "arc-votes-http-${threadSequence.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val server = HttpServer.create(InetSocketAddress(settings.bindAddress, settings.port), 0).apply {
        executor = this@ArcVoteHttpServer.executor
        createContext("/", ::handle)
    }

    fun start() {
        server.start()
    }

    override fun close() {
        server.stop(1)
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun handle(exchange: HttpExchange) {
        val response = try {
            val path = exchange.requestURI.rawPath
            if (exchange.requestURI.rawQuery != null || path !in VoteIngressService.KNOWN_PATHS) {
                CallbackResponse.error(404, "not_found")
            } else if (exchange.requestMethod != "POST") {
                exchange.responseHeaders.set("Allow", "POST")
                CallbackResponse.error(405, "method_not_allowed")
            } else {
                val headers = validatedHeaders(exchange)
                val request = CallbackRequest(
                    method = exchange.requestMethod,
                    path = path,
                    headers = headers,
                    body = readBody(exchange, headers),
                    clientAddress = clientAddress(exchange, headers),
                )
                ingress.handle(request).get(settings.persistenceTimeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (rejected: CallbackRejected) {
            CallbackResponse.error(rejected.status, rejected.safeCode)
        } catch (_: TimeoutException) {
            CallbackResponse.error(503, "processing_timeout")
        } catch (failure: ExecutionException) {
            logger.log(Level.SEVERE, "ArcVotes callback execution failed", failure.cause ?: failure)
            CallbackResponse.error(500, "internal_error")
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "ArcVotes callback request failed", failure)
            CallbackResponse.error(500, "internal_error")
        }
        send(exchange, response)
    }

    private fun validatedHeaders(exchange: HttpExchange): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        var count = 0
        var characters = 0
        exchange.requestHeaders.forEach { (rawName, rawValues) ->
            val name = rawName.lowercase(Locale.ROOT)
            if (!name.matches(Regex("[a-z0-9-]{1,64}"))) throw CallbackRejected(400, "invalid_header")
            val values = rawValues.toList()
            count += values.size
            characters += name.length + values.sumOf(String::length)
            if (count > 32 || characters > 8_192 || values.any { it.length > 4_096 || '\r' in it || '\n' in it }) {
                throw CallbackRejected(431, "headers_too_large")
            }
            result[name] = values
        }
        return result
    }

    private fun readBody(exchange: HttpExchange, headers: Map<String, List<String>>): ByteArray {
        val declared = headers["content-length"]?.let { values ->
            if (values.size != 1) throw CallbackRejected(400, "duplicate_header")
            values.single().toLongOrNull() ?: throw CallbackRejected(400, "invalid_content_length")
        }
        if (declared != null && (declared < 0 || declared > settings.maximumBodyBytes)) {
            throw CallbackRejected(413, "body_too_large")
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        exchange.requestBody.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > settings.maximumBodyBytes) throw CallbackRejected(413, "body_too_large")
                output.write(buffer, 0, read)
            }
        }
        if (declared != null && declared != output.size().toLong()) throw CallbackRejected(400, "content_length_mismatch")
        return output.toByteArray()
    }

    private fun clientAddress(exchange: HttpExchange, headers: Map<String, List<String>>): InetAddress {
        val direct = exchange.remoteAddress.address ?: throw CallbackRejected(400, "missing_client_address")
        if (!settings.trustSingleForwardedClientIp || !direct.isLoopbackAddress) return direct
        val forwarded = headers["x-forwarded-for"] ?: return direct
        if (forwarded.size != 1) throw CallbackRejected(400, "invalid_forwarded_address")
        val literal = forwarded.single().trim()
        if (',' in literal) throw CallbackRejected(400, "invalid_forwarded_address")
        return parseIpLiteral(literal) ?: throw CallbackRejected(400, "invalid_forwarded_address")
    }

    private fun send(exchange: HttpExchange, response: CallbackResponse) {
        runCatching {
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            response.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
            if (response.body.isEmpty()) {
                exchange.sendResponseHeaders(response.status, -1)
            } else {
                exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                exchange.responseBody.use { it.write(response.body) }
            }
        }.onFailure { logger.log(Level.FINE, "Could not write callback response", it) }
        exchange.close()
    }

    private fun parseIpLiteral(value: String): InetAddress? {
        val ipv4 = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
        if (ipv4.matches(value) && value.split('.').all { it.toInt() in 0..255 }) {
            return InetAddress.getByAddress(value.split('.').map(String::toInt).map(Int::toByte).toByteArray())
        }
        if (value.length !in 2..45 || ':' !in value || value.any { it !in "0123456789abcdefABCDEF:" }) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()?.takeIf { it is Inet6Address }
    }
}
