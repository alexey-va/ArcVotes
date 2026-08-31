package ru.ruscrafting.votes.callback

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.GameMonitoringSettings
import ru.ruscrafting.votes.config.NetworkSourcePolicy
import ru.ruscrafting.votes.config.SecretValue
import ru.ruscrafting.votes.config.SourcePresentation
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class GameMonitoringAdapterTest : StringSpec({
    val tokenText = UUID.randomUUID().toString()
    val settings = GameMonitoringSettings(
        enabled = true,
        presentation = SourcePresentation("GameMonitoring", URI("https://example.test/vote")),
        webhookToken = SecretValue.of(tokenText),
        expectedEntityType = "server",
        expectedEntityId = "14210383",
        network = NetworkSourcePolicy(false, emptySet()),
    )

    "signed vote webhook is resolved through the authoritative API result" {
        val eventId = "9824cabb-2203-437e-9b6c-aba43dde3e4b"
        val calls = AtomicInteger()
        val adapter = GameMonitoringAdapter(settings) { eventId ->
            calls.incrementAndGet()
            CompletableFuture.completedFuture(
                AuthoritativeGameMonitoringVote(
                    eventId,
                    NetworkPlayerName.of("Steve"),
                    "server",
                    "14210383",
                    Instant.parse("2026-08-30T12:00:00Z"),
                ),
            )
        }
        val canonical = "event_id=$eventId&event_type=server.vote&is_test=false"
        val body = """{"event_type":"server.vote","event_id":"$eventId","is_test":false,"signature":"${hmac(tokenText, canonical)}"}"""

        val result = adapter.authenticate(jsonRequest(body)).join()
            .shouldBeInstanceOf<CallbackAuthenticationResult.Accepted>()

        calls.get() shouldBe 1
        result.vote.playerName.value shouldBe "Steve"
        result.vote.externalId shouldBe "server.vote:$eventId"
    }

    "signed test webhook acknowledges without creating a vote or API lookup" {
        val calls = AtomicInteger()
        val adapter = GameMonitoringAdapter(settings) {
            calls.incrementAndGet()
            CompletableFuture.failedFuture(AssertionError("lookup must not run"))
        }
        val canonical = "event_id=9&event_type=server.vote&is_test=true"
        val body = """{"event_type":"server.vote","event_id":9,"is_test":true,"signature":"${hmac(tokenText, canonical)}"}"""

        adapter.authenticate(jsonRequest(body)).join() shouldBe CallbackAuthenticationResult.TestAcknowledged
        calls.get() shouldBe 0
        adapter.successResponse.status shouldBe 204
    }

    "signed event type must agree with the authoritative entity type" {
        val eventId = "9824cabb-2203-437e-9b6c-aba43dde3e4b"
        val adapter = GameMonitoringAdapter(settings) {
            CompletableFuture.completedFuture(
                AuthoritativeGameMonitoringVote(
                    eventId,
                    NetworkPlayerName.of("Steve"),
                    "server",
                    "14210383",
                    Instant.parse("2026-08-30T12:00:00Z"),
                ),
            )
        }
        val canonical = "event_id=$eventId&event_type=project.vote&is_test=false"
        val body = """{"event_type":"project.vote","event_id":"$eventId","is_test":false,"signature":"${hmac(tokenText, canonical)}"}"""

        val failure = shouldThrow<CompletionException> { adapter.authenticate(jsonRequest(body)).join() }
        val rejected = failure.cause.shouldBeInstanceOf<CallbackRejected>()
        rejected.status shouldBe 409
        rejected.safeCode shouldBe "entity_mismatch"
    }

    "authoritative response requires its own event id and integer provider timestamp" {
        val missingId = JsonBodyParser.parseTree(
            """{"response":{"nickname":"Steve","entity_type":"server","entity_id":14210383,"created_at":1783200000}}""".toByteArray(),
        )
        shouldThrow<CallbackUpstreamFailure> {
            parseAuthoritativeGameMonitoringVote("event-42", missingId)
        }.safeCode shouldBe "missing_upstream_event_id"

        val missingTimestamp = JsonBodyParser.parseTree(
            """{"response":{"id":"event-42","nickname":"Steve","entity_type":"server","entity_id":14210383}}""".toByteArray(),
        )
        shouldThrow<CallbackUpstreamFailure> {
            parseAuthoritativeGameMonitoringVote("event-42", missingTimestamp)
        }.safeCode shouldBe "missing_upstream_created_at"

        val textualTimestamp = JsonBodyParser.parseTree(
            """{"response":{"id":"event-42","nickname":"Steve","entity_type":"server","entity_id":14210383,"created_at":"2026-08-30T12:00:00Z"}}""".toByteArray(),
        )
        shouldThrow<CallbackUpstreamFailure> {
            parseAuthoritativeGameMonitoringVote("event-42", textualTimestamp)
        }.safeCode shouldBe "invalid_upstream_created_at"
    }

    "authoritative lookup cancels a response body that stalls after headers" {
        val bodyStarted = CountDownLatch(1)
        val releaseBody = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "game-monitoring-stall-test").apply { isDaemon = true }
        }
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/votes/event-42") { exchange ->
                try {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(byteArrayOf('{'.code.toByte()))
                    exchange.responseBody.flush()
                    bodyStarted.countDown()
                    releaseBody.await(5, TimeUnit.SECONDS)
                } catch (_: IOException) {
                    // Expected when the client cancels the transport at the whole-response deadline.
                } finally {
                    exchange.close()
                }
            }
        }
        var pending: CompletableFuture<AuthoritativeGameMonitoringVote>? = null

        try {
            server.start()
            val lookup = HttpGameMonitoringVoteLookup(
                client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                voteApiBase = URI("http://127.0.0.1:${server.address.port}/votes/"),
                overallTimeout = Duration.ofMillis(500),
            )
            val future = lookup.lookup("event-42")
            pending = future

            bodyStarted.await(2, TimeUnit.SECONDS) shouldBe true
            val failure = shouldThrow<ExecutionException> { future.get(3, TimeUnit.SECONDS) }
            val upstream = failure.cause.shouldBeInstanceOf<CallbackUpstreamFailure>()
            upstream.safeCode shouldBe "upstream_timeout"
        } finally {
            pending?.cancel(true)
            releaseBody.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
})

private fun jsonRequest(body: String): CallbackRequest = CallbackRequest(
    "POST",
    "/callbacks/gamemonitoring",
    mapOf("content-type" to listOf("application/json")),
    body.toByteArray(),
    InetAddress.getByName("127.0.0.1"),
)

private fun hmac(secret: String, canonical: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(canonical.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
