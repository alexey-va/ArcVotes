package ru.ruscrafting.votes.callback

import com.fasterxml.jackson.databind.JsonNode
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.GameMonitoringSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

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
        val expectedEventEntityType = eventType.substringBefore('.')
        if (!eventId.matches(GAME_MONITORING_EVENT_ID)) throw CallbackRejected(400, "invalid_event_id")
        return lookup.lookup(eventId).thenApply { authoritative ->
            if (authoritative.eventId != eventId ||
                authoritative.entityType != expectedEventEntityType ||
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
    private val voteApiBase: URI = URI.create("https://api.gamemonitoring.ru/votes/"),
    private val overallTimeout: Duration = Duration.ofSeconds(4),
) : GameMonitoringVoteLookup {
    init {
        require(!overallTimeout.isNegative && !overallTimeout.isZero && overallTimeout <= Duration.ofSeconds(30)) {
            "GameMonitoring overall timeout must be between 1ns and 30 seconds"
        }
        require(voteApiBase.isAbsolute && voteApiBase.rawQuery == null && voteApiBase.rawFragment == null) {
            "GameMonitoring API base must be an absolute URI without query or fragment"
        }
        require(voteApiBase.path.endsWith('/')) { "GameMonitoring API base path must end with /" }
    }

    override fun lookup(eventId: String): CompletableFuture<AuthoritativeGameMonitoringVote> {
        if (!eventId.matches(GAME_MONITORING_EVENT_ID)) {
            return CompletableFuture.failedFuture(CallbackRejected(400, "invalid_event_id"))
        }
        val request = HttpRequest.newBuilder(voteApiBase.resolve(eventId))
            .timeout(overallTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "ArcVotes/0.1")
            .GET()
            .build()
        val bodyHandler = HttpResponse.BodyHandlers.limiting(
            HttpResponse.BodyHandlers.ofByteArray(),
            MAXIMUM_RESPONSE_BYTES.toLong(),
        )
        val transport = client.sendAsync(request, bodyHandler)
        val result = CompletableFuture<AuthoritativeGameMonitoringVote>()
        CompletableFuture.delayedExecutor(overallTimeout.toNanos(), TimeUnit.NANOSECONDS).execute {
            if (result.completeExceptionally(CallbackUpstreamFailure("upstream_timeout"))) {
                transport.cancel(true)
            }
        }
        transport.whenComplete { response, failure ->
            if (result.isDone) return@whenComplete
            if (failure != null) {
                val cause = failure.unwrapCompletion()
                result.completeExceptionally(
                    when (cause) {
                        is HttpTimeoutException -> CallbackUpstreamFailure("upstream_timeout")
                        is CancellationException -> CallbackUpstreamFailure("upstream_cancelled")
                        else -> cause
                    },
                )
                return@whenComplete
            }
            try {
                if (response.statusCode() != 200) throw CallbackUpstreamFailure("upstream_status")
                result.complete(parseAuthoritativeGameMonitoringVote(eventId, JsonBodyParser.parseTree(response.body())))
            } catch (parseFailure: Throwable) {
                result.completeExceptionally(parseFailure)
            }
        }
        result.whenComplete { _, _ -> if (result.isCancelled) transport.cancel(true) }
        return result
    }

    private companion object {
        const val MAXIMUM_RESPONSE_BYTES = 32 * 1_024
    }
}

private tailrec fun Throwable.unwrapCompletion(): Throwable =
    if ((this is CompletionException || this is ExecutionException) && cause != null) cause!!.unwrapCompletion() else this

internal fun parseAuthoritativeGameMonitoringVote(eventId: String, root: JsonNode): AuthoritativeGameMonitoringVote {
    val payload = root["response"]?.takeIf(JsonNode::isObject)
        ?: throw CallbackUpstreamFailure("missing_upstream_response")
    val responseEventId = payload["id"]?.canonicalIdentifier()
        ?: throw CallbackUpstreamFailure("missing_upstream_event_id")
    val nickname = payload["nickname"]?.takeIf(JsonNode::isTextual)?.textValue()
        ?: throw CallbackUpstreamFailure("missing_upstream_nickname")
    val playerName = NetworkPlayerName.parseOrNull(nickname)
        ?: throw CallbackUpstreamFailure("invalid_upstream_nickname")
    val entityType = payload["entity_type"]?.takeIf(JsonNode::isTextual)?.textValue()?.lowercase(Locale.ROOT)
        ?: throw CallbackUpstreamFailure("missing_upstream_entity")
    val entityId = payload["entity_id"]?.canonicalIdentifier()
        ?: throw CallbackUpstreamFailure("missing_upstream_entity")
    return AuthoritativeGameMonitoringVote(
        eventId = responseEventId,
        playerName = playerName,
        entityType = entityType,
        entityId = entityId,
        occurredAt = payload["created_at"].parseProviderInstant(),
    )
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

private fun JsonNode?.parseProviderInstant(): Instant {
    if (this == null) throw CallbackUpstreamFailure("missing_upstream_created_at")
    if (!isIntegralNumber || !canConvertToLong()) throw CallbackUpstreamFailure("invalid_upstream_created_at")
    return try {
        Instant.ofEpochSecond(longValue())
    } catch (_: java.time.DateTimeException) {
        throw CallbackUpstreamFailure("invalid_upstream_created_at")
    }
}
