package ru.ruscrafting.votes.callback

import com.fasterxml.jackson.databind.JsonNode
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringMinecraftAuthenticationMode
import ru.ruscrafting.votes.config.MonitoringMinecraftBodyFormat
import ru.ruscrafting.votes.config.MonitoringMinecraftSettings
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import java.time.Clock
import java.util.concurrent.CompletableFuture

class MonitoringMinecraftAdapter(
    private val settings: MonitoringMinecraftSettings,
    private val clock: Clock = Clock.systemUTC(),
) : VoteCallbackAdapter {
    override val successResponse: CallbackResponse = CallbackResponse.ok()

    init {
        require(settings.enabled && settings.protocolConfirmed) {
            "MonitoringMinecraft adapter requires an enabled, confirmed protocol"
        }
    }

    override fun authenticate(request: CallbackRequest): CompletableFuture<CallbackAuthenticationResult> {
        enforceNetworkPolicy(request, settings.network)
        val fields = when (settings.bodyFormat) {
            MonitoringMinecraftBodyFormat.FORM -> {
                ContentTypes.requireForm(request.singleHeader("content-type"))
                FormBodyParser.parse(request.body)
            }
            MonitoringMinecraftBodyFormat.JSON -> {
                ContentTypes.requireJson(request.singleHeader("content-type"))
                JsonBodyParser.objectFields(request.body).mapValues { (_, value) -> value.requireText() }
            }
        }
        val permitted = buildSet {
            add(settings.nicknameField)
            add(settings.eventIdField)
            if (settings.authenticationMode == MonitoringMinecraftAuthenticationMode.FIELD) add(settings.authenticationName)
        }
        if (fields.keys.any { it !in permitted }) throw CallbackRejected(400, "unknown_field")
        val suppliedSecret = when (settings.authenticationMode) {
            MonitoringMinecraftAuthenticationMode.HEADER ->
                request.singleHeader(settings.authenticationName) ?: throw CallbackRejected(401, "missing_authentication")
            MonitoringMinecraftAuthenticationMode.FIELD -> fields.required(settings.authenticationName)
        }
        if (!CallbackCryptography.constantTimeEquals(requireNotNull(settings.secret), suppliedSecret)) {
            throw CallbackRejected(403, "invalid_authentication")
        }
        val playerName = NetworkPlayerName.parseOrNull(fields.required(settings.nicknameField))
            ?: throw CallbackRejected(400, "invalid_player_name")
        val providerEventId = fields.required(settings.eventIdField)
        if (providerEventId.length !in 1..256 || providerEventId.any { it == '\r' || it == '\n' }) {
            throw CallbackRejected(400, "invalid_event_id")
        }
        val externalId = CallbackCryptography.sha256Id(
            MonitoringSource.MONITORING_MINECRAFT.configKey,
            providerEventId,
        )
        return completed(
            CallbackAuthenticationResult.Accepted(
                AuthenticatedVote(
                    source = MonitoringSource.MONITORING_MINECRAFT,
                    externalId = externalId,
                    playerName = playerName,
                    occurredAt = clock.instant(),
                ),
            ),
        )
    }
}

private fun JsonNode.requireText(): String =
    if (isTextual) textValue() else throw CallbackRejected(400, "invalid_field_type")
