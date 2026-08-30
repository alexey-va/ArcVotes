package ru.ruscrafting.votes.callback

import com.fasterxml.jackson.databind.JsonNode
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.GameMonitoringSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.CompletableFuture

data class AuthoritativeGameMonitoringVote(
    val eventId: String,
    val playerName: NetworkPlayerName,
    val entityType: String,
    val entityId: String,
    val occurredAt: Instant,
)

fun interface GameMonitoringVoteLookup {
    fun lookup(eventId: String): CompletableFuture<AuthoritativeGameMonitoringVote>
}

class GameMonitoringAdapter(
    private val settings: GameMonitoringSettings,
    private val lookup: GameMonitoringVoteLookup,
) : VoteCallbackAdapter {
    override val successResponse: CallbackResponse = CallbackResponse.noContent()

    init {
        require(settings.enabled) { "GameMonitoring adapter requires an enabled source" }
    }

    override fun authenticate(request: CallbackRequest): CompletableFuture<CallbackAuthenticationResult> {
        enforceNetworkPolicy(request, settings.network)
        ContentTypes.requireJson(request.singleHeader("content-type"))
        val fields = JsonBodyParser.objectFields(request.body)
        val signature = fields["signature"]?.takeIf(JsonNode::isTextual)?.textValue()
            ?: throw CallbackRejected(400, "missing_signature")
        val canonical = fields
            .filterKeys { it != "signature" }
            .toSortedMap()
            .entries
            .joinToString("&") { (key, value) -> "$key=${value.canonicalScalar()}" }
        if (!CallbackCryptography.verifyHmacSha256(signature, requireNotNull(settings.webhookToken), canonical)) {
            throw CallbackRejected(403, "invalid_signature")
        }
        val eventType = fields["event_type"]?.takeIf(JsonNode::isTextual)?.textValue()
            ?: throw CallbackRejected(400, "missing_event_type")
        val eventId = fields["event_id"]?.canonicalIdentifier() ?: throw CallbackRejected(400, "missing_event_id")
        val isTest = fields["is_test"]?.takeIf(JsonNode::isBoolean)?.booleanValue()
            ?: throw CallbackRejected(400, "missing_test_flag")
        if (isTest) return completed(CallbackAuthenticationResult.TestAcknowledged)
        if (eventType !in setOf("server.vote", "project.vote")) {
            return completed(CallbackAuthenticationResult.Ignored)
        }
        if (!eventId.matches(GAME_MONITORING_EVENT_ID)) throw CallbackRejected(400, "invalid_event_id")
        return lookup.lookup(eventId).thenApply { authoritative ->
            if (authoritative.eventId != eventId ||
                authoritative.entityType != settings.expectedEntityType ||
                authoritative.entityId != settings.expectedEntityId
            ) {
                throw CallbackRejected(409, "entity_mismatch")
            }
            CallbackAuthenticationResult.Accepted(
                AuthenticatedVote(
                    source = MonitoringSource.GAME_MONITORING,
                    externalId = "$eventType:$eventId",
                    playerName = authoritative.playerName,
                    occurredAt = authoritative.occurredAt,
                ),
            )
        }
    }
}

class HttpGameMonitoringVoteLookup(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : GameMonitoringVoteLookup {
    override fun lookup(eventId: String): CompletableFuture<AuthoritativeGameMonitoringVote> {
        if (!eventId.matches(GAME_MONITORING_EVENT_ID)) {
            return CompletableFuture.failedFuture(CallbackRejected(400, "invalid_event_id"))
        }
        val request = HttpRequest.newBuilder(URI.create("https://api.gamemonitoring.ru/votes/$eventId"))
            .timeout(Duration.ofSeconds(4))
            .header("Accept", "application/json")
            .header("User-Agent", "ArcVotes/0.1")
            .GET()
            .build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApply { response ->
            response.body().use { body ->
                if (response.statusCode() != 200) throw CallbackUpstreamFailure("upstream_status")
                val bytes = body.readLimited(MAXIMUM_RESPONSE_BYTES)
                val root = JsonBodyParser.parseTree(bytes)
                val payload = root["response"]?.takeIf(JsonNode::isObject)
                    ?: throw CallbackUpstreamFailure("missing_upstream_response")
                val nickname = payload["nickname"]?.takeIf(JsonNode::isTextual)?.textValue()
                    ?: throw CallbackUpstreamFailure("missing_upstream_nickname")
                val playerName = NetworkPlayerName.parseOrNull(nickname)
                    ?: throw CallbackUpstreamFailure("invalid_upstream_nickname")
                val entityType = payload["entity_type"]?.takeIf(JsonNode::isTextual)?.textValue()?.lowercase(Locale.ROOT)
                    ?: throw CallbackUpstreamFailure("missing_upstream_entity")
                val entityId = payload["entity_id"]?.canonicalIdentifier()
                    ?: throw CallbackUpstreamFailure("missing_upstream_entity")
                val responseEventId = payload["id"]?.canonicalIdentifier()
                    ?: payload["event_id"]?.canonicalIdentifier()
                    ?: eventId
                AuthoritativeGameMonitoringVote(
                    eventId = responseEventId,
                    playerName = playerName,
                    entityType = entityType,
                    entityId = entityId,
                    occurredAt = payload["created_at"].parseInstantOrNow(),
                )
            }
        }
    }

    private companion object {
        const val MAXIMUM_RESPONSE_BYTES = 32 * 1_024
    }
}

private val GAME_MONITORING_EVENT_ID = Regex("[A-Za-z0-9-]{1,100}")

private fun JsonNode.canonicalScalar(): String = when {
    isTextual -> textValue()
    isBoolean -> if (booleanValue()) "true" else "false"
    isNumber -> asText()
    else -> throw CallbackRejected(400, "unsupported_json_value")
}

private fun JsonNode.canonicalIdentifier(): String? = when {
    isTextual -> textValue()
    isIntegralNumber -> asText()
    else -> null
}

private fun JsonNode?.parseInstantOrNow(): Instant {
    if (this == null) return Instant.now()
    if (isIntegralNumber) return runCatching { Instant.ofEpochSecond(longValue()) }.getOrElse { Instant.now() }
    if (!isTextual) return Instant.now()
    return try {
        Instant.parse(textValue())
    } catch (_: DateTimeParseException) {
        Instant.now()
    }
}

private fun InputStream.readLimited(maximumBytes: Int): ByteArray {
    val buffer = ByteArray(4_096)
    val output = java.io.ByteArrayOutputStream()
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.size() + read > maximumBytes) throw CallbackUpstreamFailure("upstream_body_too_large")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
