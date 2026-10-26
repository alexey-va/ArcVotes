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

data class ArcVoteHttpState(
    val settings: HttpSettings,
    val ingress: VoteIngressService?,
)

/** Loopback-only feature ingress. Public TLS/routing belongs to the managed reverse proxy. */
class ArcVoteHttpServer internal constructor(
    initialSettings: HttpSettings,
    private val currentState: () -> ArcVoteHttpState,
    private val logger: Logger,
    createServer: (InetSocketAddress) -> HttpServer,
) : AutoCloseable {
    private val threadSequence = AtomicInteger()
    private var closed = false
    private var closeFailure: IllegalStateException? = null
    private val executor = ThreadPoolExecutor(
        initialSettings.workerThreads,
        initialSettings.workerThreads,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(initialSettings.queueCapacity),
        { task -> Thread(task, "arc-votes-http-${threadSequence.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    constructor(settings: HttpSettings, currentState: () -> ArcVoteHttpState, logger: Logger) : this(
        settings, currentState, logger, { HttpServer.create(it, 0) },
    )

    constructor(settings: HttpSettings, ingress: VoteIngressService, logger: Logger) : this(
        settings,
        { ArcVoteHttpState(settings, ingress) },
        logger,
    )
    private val server = try { createServer(InetSocketAddress(initialSettings.bindAddress, initialSettings.port)).apply {
        executor = this@ArcVoteHttpServer.executor
        createContext("/", ::handle)
    } } catch (failure: Throwable) { executor.shutdownNow(); throw failure }

    fun start() {
        server.start()
    }

    /**
     * Maintenance-worker only, never an owned HTTP handler or Paper's thread.
     * Handlers wait for bounded detached ingress persistence, never Paper callbacks.
     */
    @Synchronized
    override fun close() {
        if (closed) {
            closeFailure?.let { throw it }
            return
        }
        closed = true
        var failure: Throwable? = null
        var interrupted = Thread.interrupted()
        fun record(error: Throwable) {
            if (error is InterruptedException) interrupted = true
            val previous = failure
            if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
        }
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) { record(error) }
        }
        fun terminated(): Boolean = try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            record(error)
            false
        }
        if (interrupted) record(InterruptedException("ArcVotes HTTP shutdown was already interrupted"))
        try {
            attempt { server.stop(1) }
            if (Thread.interrupted()) record(InterruptedException("ArcVotes HTTP listener shutdown was interrupted"))
            attempt { executor.shutdown() }
            if (interrupted || !terminated()) {
                attempt { executor.shutdownNow() }
                if (!terminated()) record(IllegalStateException("ArcVotes HTTP callback workers did not terminate"))
            }
            closeFailure = failure?.let { IllegalStateException("Could not close ArcVotes HTTP ingress", it) }
            closeFailure?.let { throw it }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun handle(exchange: HttpExchange) {
        val response = try {
            val state = currentState()
            val settings = state.settings
            val ingress = state.ingress
            if (!settings.enabled || ingress == null) {
                CallbackResponse.error(503, "service_disabled")
            } else handleEnabled(exchange, settings, ingress)
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

    private fun handleEnabled(exchange: HttpExchange, settings: HttpSettings, ingress: VoteIngressService): CallbackResponse {
        val path = exchange.requestURI.rawPath
        return if (exchange.requestURI.rawQuery != null || path !in VoteIngressService.KNOWN_PATHS) {
            CallbackResponse.error(404, "not_found")
        } else if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.set("Allow", "POST")
            CallbackResponse.error(405, "method_not_allowed")
        } else {
            val headers = validatedHeaders(exchange, settings)
            val request = CallbackRequest(
                method = exchange.requestMethod,
                path = path,
                headers = headers,
                body = readBody(exchange, headers, settings),
                clientAddress = clientAddress(exchange, headers, settings),
            )
            ingress.handle(request).get(settings.persistenceTimeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun validatedHeaders(exchange: HttpExchange, settings: HttpSettings): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        var count = 0
        var characters = 0
        exchange.requestHeaders.forEach { (rawName, rawValues) ->
            val name = rawName.lowercase(Locale.ROOT)
            if (!name.matches(Regex("[a-z0-9-]{1,64}"))) throw CallbackRejected(400, "invalid_header")
            val values = rawValues.toList()
            count += values.size
            characters += name.length + values.sumOf(String::length)
            if (count > settings.maximumHeaderCount || characters > settings.maximumHeaderBytes ||
                values.any { it.length > settings.maximumHeaderValueBytes || '\r' in it || '\n' in it }) {
                throw CallbackRejected(431, "headers_too_large")
            }
            result[name] = values
        }
        return result
    }

    private fun readBody(exchange: HttpExchange, headers: Map<String, List<String>>, settings: HttpSettings): ByteArray {
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

    private fun clientAddress(exchange: HttpExchange, headers: Map<String, List<String>>, settings: HttpSettings): InetAddress {
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
