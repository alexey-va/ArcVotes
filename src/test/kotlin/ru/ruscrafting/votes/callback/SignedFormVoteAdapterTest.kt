package ru.ruscrafting.votes.callback

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.assertions.throwables.shouldThrow
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.NetworkSourcePolicy
import ru.ruscrafting.votes.config.SecretValue
import ru.ruscrafting.votes.config.SignedFormSourceSettings
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SignedFormVoteAdapterTest : StringSpec({
    val now = Instant.ofEpochSecond(1_900_000_000)
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val secretText = UUID.randomUUID().toString()
    val secret = SecretValue.of(secretText)

    fun settings(source: MonitoringSource) = SignedFormSourceSettings(
        enabled = true,
        presentation = ru.ruscrafting.votes.config.SourcePresentation(source.configKey, URI("https://example.test/vote")),
        secret = secret,
        maximumAgeSeconds = 691_200,
        maximumFutureSkewSeconds = 300,
        network = NetworkSourcePolicy(false, emptySet()),
    )

    "MinecraftRating accepts its signed URL-encoded contract" {
        val timestamp = now.epochSecond.toString()
        val signature = sha1("Steve$timestamp$secretText")
        val adapter = SignedFormVoteAdapter(
            settings(MonitoringSource.MINECRAFT_RATING),
            SignedFormVoteAdapter.MINECRAFT_RATING,
            clock,
        )
        val result = adapter.authenticate(
            request(
                "application/x-www-form-urlencoded; charset=UTF-8",
                "username=Steve&ip=198.51.100.8&timestamp=$timestamp&signature=$signature".toByteArray(),
            ),
        ).join().shouldBeInstanceOf<CallbackAuthenticationResult.Accepted>()

        result.vote.source shouldBe MonitoringSource.MINECRAFT_RATING
        result.vote.playerName.value shouldBe "Steve"
        result.vote.occurredAt shouldBe now
    }

    "MinecraftRating also accepts a signed multipart form from PHP-style handlers" {
        val timestamp = now.epochSecond.toString()
        val signature = sha1("Steve$timestamp$secretText")
        val boundary = "MinecraftRatingBoundary"
        val adapter = SignedFormVoteAdapter(
            settings(MonitoringSource.MINECRAFT_RATING),
            SignedFormVoteAdapter.MINECRAFT_RATING,
            clock,
        )

        val result = adapter.authenticate(
            request(
                "multipart/form-data; boundary=$boundary",
                multipart(
                    boundary,
                    mapOf("username" to "Steve", "timestamp" to timestamp, "signature" to signature),
                ),
            ),
        ).join().shouldBeInstanceOf<CallbackAuthenticationResult.Accepted>()

        result.vote.source shouldBe MonitoringSource.MINECRAFT_RATING
        result.vote.playerName.value shouldBe "Steve"
    }

    "HotMC accepts the exact multipart nick-time-sign contract" {
        val timestamp = now.epochSecond.toString()
        val signature = sha1("Alex_42$timestamp$secretText")
        val boundary = "ArcVotesBoundary"
        val body = multipart(
            boundary,
            mapOf("nick" to "Alex_42", "time" to timestamp, "sign" to signature),
        )
        val adapter = SignedFormVoteAdapter(
            settings(MonitoringSource.HOTMC),
            SignedFormVoteAdapter.HOTMC,
            clock,
        )
        val result = adapter.authenticate(
            request("multipart/form-data; boundary=$boundary", body),
        ).join().shouldBeInstanceOf<CallbackAuthenticationResult.Accepted>()

        result.vote.source shouldBe MonitoringSource.HOTMC
        result.vote.playerName.value shouldBe "Alex_42"
    }

    "HotMC rejects a forged signature before recording anything" {
        val timestamp = now.epochSecond.toString()
        val boundary = "ArcVotesBoundary"
        val adapter = SignedFormVoteAdapter(
            settings(MonitoringSource.HOTMC),
            SignedFormVoteAdapter.HOTMC,
            clock,
        )

        shouldThrow<CallbackRejected> {
            adapter.authenticate(
                request(
                    "multipart/form-data; boundary=$boundary",
                    multipart(boundary, mapOf("nick" to "Steve", "time" to timestamp, "sign" to "0".repeat(40))),
                ),
            )
        }.status shouldBe 403
    }

    "signed callbacks outside the bounded retry window fail closed" {
        val timestamp = now.minusSeconds(691_201).epochSecond.toString()
        val signature = sha1("Steve$timestamp$secretText")
        val adapter = SignedFormVoteAdapter(
            settings(MonitoringSource.MINECRAFT_RATING),
            SignedFormVoteAdapter.MINECRAFT_RATING,
            clock,
        )

        shouldThrow<CallbackRejected> {
            adapter.authenticate(
                request(
                    "application/x-www-form-urlencoded",
                    "username=Steve&timestamp=$timestamp&signature=$signature".toByteArray(),
                ),
            )
        }.status shouldBe 410
    }
})

private fun request(contentType: String, body: ByteArray): CallbackRequest = CallbackRequest(
    method = "POST",
    path = "/callbacks/test",
    headers = mapOf("content-type" to listOf(contentType)),
    body = body,
    clientAddress = InetAddress.getByName("127.0.0.1"),
)

private fun multipart(boundary: String, fields: Map<String, String>): ByteArray = buildString {
    fields.forEach { (name, value) ->
        append("--$boundary\r\n")
        append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        append(value)
        append("\r\n")
    }
    append("--$boundary--\r\n")
}.toByteArray(Charsets.UTF_8)

private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
