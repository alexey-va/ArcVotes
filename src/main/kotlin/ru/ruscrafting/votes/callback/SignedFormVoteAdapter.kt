package ru.ruscrafting.votes.callback

import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.config.NetworkSourcePolicy
import ru.ruscrafting.votes.config.SignedFormSourceSettings
import ru.ruscrafting.votes.domain.AuthenticatedVote
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture

enum class SignedFormEncoding { URL_ENCODED, MULTIPART, URL_ENCODED_OR_MULTIPART }

data class SignedFormContract(
    val source: MonitoringSource,
    val encoding: SignedFormEncoding,
    val nicknameField: String,
    val timestampField: String,
    val signatureField: String,
    val permittedExtraFields: Set<String> = emptySet(),
)

class SignedFormVoteAdapter(
    private val settings: SignedFormSourceSettings,
    private val contract: SignedFormContract,
    private val clock: Clock = Clock.systemUTC(),
) : VoteCallbackAdapter {
    override val successResponse: CallbackResponse = CallbackResponse.ok()

    init {
        require(settings.enabled) { "Signed form adapter requires an enabled source" }
    }

    override fun authenticate(request: CallbackRequest): CompletableFuture<CallbackAuthenticationResult> {
        enforceNetworkPolicy(request, settings.network)
        val fields = when (contract.encoding) {
            SignedFormEncoding.URL_ENCODED -> {
                ContentTypes.requireForm(request.singleHeader("content-type"))
                FormBodyParser.parse(request.body)
            }
            SignedFormEncoding.MULTIPART -> {
                val boundary = ContentTypes.requireMultipartBoundary(request.singleHeader("content-type"))
                MultipartFormParser.parse(request.body, boundary)
            }
            SignedFormEncoding.URL_ENCODED_OR_MULTIPART -> {
                val contentType = request.singleHeader("content-type")
                if (contentType?.substringBefore(';')?.trim()?.equals("multipart/form-data", ignoreCase = true) == true) {
                    MultipartFormParser.parse(request.body, ContentTypes.requireMultipartBoundary(contentType))
                } else {
                    ContentTypes.requireForm(contentType)
                    FormBodyParser.parse(request.body)
                }
            }
        }
        val expected = setOf(contract.nicknameField, contract.timestampField, contract.signatureField) + contract.permittedExtraFields
        if (fields.keys.any { it !in expected }) throw CallbackRejected(400, "unknown_field")
        val nickname = fields.required(contract.nicknameField)
        val timestampText = fields.required(contract.timestampField)
        val signature = fields.required(contract.signatureField)
        val secret = requireNotNull(settings.secret)
        if (!CallbackCryptography.verifySha1Concatenation(signature, secret, nickname, timestampText)) {
            throw CallbackRejected(403, "invalid_signature")
        }
        val timestampSeconds = timestampText.toLongOrNull() ?: throw CallbackRejected(400, "invalid_timestamp")
        val occurredAt = try {
            Instant.ofEpochSecond(timestampSeconds)
        } catch (_: DateTimeException) {
            throw CallbackRejected(400, "invalid_timestamp")
        }
        validateFreshness(occurredAt)
        val playerName = NetworkPlayerName.parseOrNull(nickname) ?: throw CallbackRejected(400, "invalid_player_name")
        val externalId = CallbackCryptography.sha256Id(
            contract.source.configKey,
            playerName.value.lowercase(Locale.ROOT),
            timestampText,
        )
        return completed(
            CallbackAuthenticationResult.Accepted(
                AuthenticatedVote(contract.source, externalId, playerName, occurredAt),
            ),
        )
    }

    private fun validateFreshness(occurredAt: Instant) {
        val now = clock.instant()
        if (occurredAt.isAfter(now.plusSeconds(settings.maximumFutureSkewSeconds))) {
            throw CallbackRejected(400, "future_vote")
        }
        if (occurredAt.isBefore(now.minusSeconds(settings.maximumAgeSeconds))) {
            throw CallbackRejected(410, "expired_vote")
        }
    }

    companion object {
        val MINECRAFT_RATING = SignedFormContract(
            source = MonitoringSource.MINECRAFT_RATING,
            encoding = SignedFormEncoding.URL_ENCODED_OR_MULTIPART,
            nicknameField = "username",
            timestampField = "timestamp",
            signatureField = "signature",
            permittedExtraFields = setOf("ip"),
        )

        val HOTMC = SignedFormContract(
            source = MonitoringSource.HOTMC,
            encoding = SignedFormEncoding.MULTIPART,
            nicknameField = "nick",
            timestampField = "time",
            signatureField = "sign",
        )
    }
}

internal fun enforceNetworkPolicy(request: CallbackRequest, policy: NetworkSourcePolicy) {
    if (!policy.enforceIpAllowlist) return
    val actual = request.clientAddress.hostAddress.substringBefore('%')
    val allowed = policy.allowedIps.any { configured ->
        runCatching { java.net.InetAddress.getByName(configured).hostAddress.substringBefore('%') == actual }
            .getOrDefault(false)
    }
    if (!allowed) throw CallbackRejected(403, "source_ip_denied")
}

internal fun Map<String, String>.required(name: String): String =
    this[name]?.takeIf(String::isNotEmpty) ?: throw CallbackRejected(400, "missing_field")
