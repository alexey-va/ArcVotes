package ru.ruscrafting.votes.callback

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.votes.config.MonitoringMinecraftSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.NetworkSourcePolicy
import ru.ruscrafting.votes.config.SecretValue
import ru.ruscrafting.votes.config.SourcePresentation
import java.net.InetAddress
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MonitoringMinecraftAdapterTest : StringSpec({
    val now = Instant.parse("2026-08-30T12:00:00Z")
    val settings = MonitoringMinecraftSettings(
        enabled = true,
        presentation = SourcePresentation("MonitoringMinecraft", URI("https://example.test/vote")),
        secret = SecretValue.of("monitoring-secret"),
        expectedServerId = "43",
        maximumAgeSeconds = 600,
        maximumFutureSkewSeconds = 30,
        network = NetworkSourcePolicy(false, emptySet()),
    )
    val adapter = MonitoringMinecraftAdapter(settings, Clock.fixed(now, ZoneOffset.UTC))

    "official bearer JSON callback authenticates and preserves provider time" {
        val result = adapter.authenticate(
            request(
                """{"nickname":"Steve","server_id":43,"timestamp":"2026-08-30T12:00:00Z"}""",
            ),
        ).join() as CallbackAuthenticationResult.Accepted

        result.vote.source shouldBe MonitoringSource.MONITORING_MINECRAFT
        result.vote.playerName.value shouldBe "Steve"
        result.vote.occurredAt shouldBe now
    }

    "test callback is acknowledged without creating a vote" {
        adapter.authenticate(
            request(
                """{"nickname":"Steve","server_id":43,"timestamp":"2026-08-30T12:00:00Z","test":true}""",
            ),
        ).join() shouldBe CallbackAuthenticationResult.TestAcknowledged
    }

    "wrong server identity fails closed" {
        val failure = shouldThrow<CallbackRejected> {
            adapter.authenticate(
                request(
                    """{"nickname":"Steve","server_id":99,"timestamp":"2026-08-30T12:00:00Z"}""",
                ),
            )
        }
        failure.status shouldBe 409
        failure.safeCode shouldBe "server_mismatch"
    }
})

private fun request(body: String): CallbackRequest = CallbackRequest(
    method = "POST",
    path = "/callbacks/monitoring-minecraft",
    headers = mapOf(
        "content-type" to listOf("application/json"),
        "authorization" to listOf("Bearer monitoring-secret"),
    ),
    body = body.toByteArray(),
    clientAddress = InetAddress.getLoopbackAddress(),
)
