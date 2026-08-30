package ru.ruscrafting.votes.callback

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.GameMonitoringSettings
import ru.ruscrafting.votes.config.NetworkSourcePolicy
import ru.ruscrafting.votes.config.SecretValue
import ru.ruscrafting.votes.config.SourcePresentation
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
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
        val canonical = "event_id=42&event_type=server.vote&is_test=false"
        val body = """{"event_type":"server.vote","event_id":42,"is_test":false,"signature":"${hmac(tokenText, canonical)}"}"""

        val result = adapter.authenticate(jsonRequest(body)).join()
            .shouldBeInstanceOf<CallbackAuthenticationResult.Accepted>()

        calls.get() shouldBe 1
        result.vote.playerName.value shouldBe "Steve"
        result.vote.externalId shouldBe "server.vote:42"
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
