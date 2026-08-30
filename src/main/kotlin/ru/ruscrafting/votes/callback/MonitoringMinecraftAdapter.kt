package ru.ruscrafting.votes.callback

import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringMinecraftSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture

class MonitoringMinecraftAdapter(
    private val settings: MonitoringMinecraftSettings,
    private val clock: Clock = Clock.systemUTC(),
) : VoteCallbackAdapter {
    override val successResponse: CallbackResponse = CallbackResponse.ok()

    init {
        require(settings.enabled) { "MonitoringMinecraft adapter requires an enabled source" }
    }

    override fun authenticate(request: CallbackRequest): CompletableFuture<CallbackAuthenticationResult> {
        enforceNetworkPolicy(request, settings.network)
        val authorization = request.singleHeader("authorization")
            ?: throw CallbackRejected(401, "missing_authentication")
        val separator = authorization.indexOf(' ')
        if (separator <= 0 || !authorization.substring(0, separator).equals("Bearer", ignoreCase = true)) {
            throw CallbackRejected(401, "invalid_authentication")
        }
        val suppliedSecret = authorization.substring(separator + 1).trim()
        if (!CallbackCryptography.constantTimeEquals(requireNotNull(settings.secret), suppliedSecret)) {
            throw CallbackRejected(401, "invalid_authentication")
        }

        ContentTypes.requireJson(request.singleHeader("content-type"))
        val fields = JsonBodyParser.objectFields(request.body)
        if (fields.keys.any { it !in PERMITTED_FIELDS }) throw CallbackRejected(400, "unknown_field")
        val isTest = fields["test"]?.takeIf { it.isBoolean }?.booleanValue() ?: false
        if (fields["test"] != null && !fields.getValue("test").isBoolean) {
            throw CallbackRejected(400, "invalid_test_flag")
        }
        if (isTest) return completed(CallbackAuthenticationResult.TestAcknowledged)

        val nickname = fields["nickname"]?.takeIf { it.isTextual }?.textValue()
            ?: throw CallbackRejected(400, "missing_nickname")
        val playerName = NetworkPlayerName.parseOrNull(nickname)
            ?: throw CallbackRejected(400, "invalid_player_name")
        val serverId = when (val node = fields["server_id"]) {
            null -> throw CallbackRejected(400, "missing_server_id")
            else -> when {
                node.isIntegralNumber -> node.asText()
                node.isTextual -> node.textValue()
                else -> throw CallbackRejected(400, "invalid_server_id")
            }
        }
        if (serverId != settings.expectedServerId) throw CallbackRejected(409, "server_mismatch")
        val timestampText = fields["timestamp"]?.takeIf { it.isTextual }?.textValue()
            ?: throw CallbackRejected(400, "missing_timestamp")
        val occurredAt = try {
            Instant.parse(timestampText)
        } catch (_: DateTimeException) {
            throw CallbackRejected(400, "invalid_timestamp")
        }
        validateFreshness(occurredAt)
        val externalId = CallbackCryptography.sha256Id(
            MonitoringSource.MONITORING_MINECRAFT.configKey,
            playerName.value.lowercase(Locale.ROOT),
            serverId,
            timestampText,
        )
        return completed(
            CallbackAuthenticationResult.Accepted(
                AuthenticatedVote(
                    source = MonitoringSource.MONITORING_MINECRAFT,
                    externalId = externalId,
                    playerName = playerName,
                    occurredAt = occurredAt,
                ),
            ),
        )
    }

    private fun validateFreshness(occurredAt: Instant) {
        val now = clock.instant()
        if (occurredAt.isAfter(now.plusSeconds(settings.maximumFutureSkewSeconds))) {
            throw CallbackRejected(400, "future_vote")
        }
        if (occurredAt.isBefore(now.minusSeconds(settings.maximumAgeSeconds))) {
            throw CallbackRejected(400, "expired_vote")
        }
    }

    private companion object {
        val PERMITTED_FIELDS = setOf("nickname", "server_id", "timestamp", "test")
    }
}
