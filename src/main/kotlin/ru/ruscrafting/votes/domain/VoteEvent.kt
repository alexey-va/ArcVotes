package ru.ruscrafting.votes.domain

import ru.arc.network.NetworkPlayerName
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.ruscrafting.votes.config.MonitoringSource
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class AuthenticatedVote(
    val source: MonitoringSource,
    val externalId: String,
    val playerName: NetworkPlayerName,
    val occurredAt: Instant,
) {
    val normalizedPlayerName: String = playerName.value.lowercase(Locale.ROOT)

    init {
        require(externalId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "External vote id is unsafe" }
    }
}

enum class RewardState {
    NONE,
    PENDING,
    GRANTED,
    RECOVERY,
}

data class VoteEvent(
    val id: UUID,
    val vote: AuthenticatedVote,
    val receivedAt: Instant,
    val rewardAmount: BigDecimal?,
    val rewardState: RewardState,
    val playerId: UUID? = null,
) {
    fun oneTimeUseIdentity(): OneTimeUseIdentity = OneTimeUseIdentity(
        useId = id,
        fingerprint = OneTimeUseFingerprint.sha256Fields(
            vote.source.configKey,
            vote.externalId,
            vote.normalizedPlayerName,
            rewardAmount?.toPlainString() ?: "none",
        ),
    )
}

sealed interface VoteRecordResult {
    data class Inserted(val event: VoteEvent) : VoteRecordResult
    data class Duplicate(val event: VoteEvent) : VoteRecordResult
    data object IdentityConflict : VoteRecordResult
}
