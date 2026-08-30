package ru.ruscrafting.votes.domain

import ru.arc.network.NetworkPlayerName
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.ruscrafting.votes.config.MonitoringSource
import java.math.BigDecimal
import java.nio.ByteBuffer
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

enum class RewardProvider(val storageKey: String) {
    VAULT("vault"),
    REDIS_ECONOMY("redis_economy"),
}

data class VoteRewardComponent(
    val key: String,
    val provider: RewardProvider,
    val amount: BigDecimal,
    val currencyId: String? = null,
    val legacyIdentity: Boolean = false,
) {
    init {
        require(key in setOf("standard", "premium")) { "Reward component key is unsupported" }
        require(amount.signum() > 0 && amount.scale() <= 2 && amount <= BigDecimal("1000000.00")) {
            "Reward component amount must be positive, at most 1000000.00, with at most two decimal places"
        }
        require(provider != RewardProvider.REDIS_ECONOMY || currencyId?.matches(Regex("[A-Za-z0-9_-]{1,16}")) == true) {
            "RedisEconomy reward currency id is unsafe"
        }
        require(provider != RewardProvider.VAULT || currencyId == null) {
            "Vault reward must use the registered default currency"
        }
    }
}

data class VoteRewardBundle(val components: List<VoteRewardComponent>) {
    init {
        require(components.size in 1..4) { "A vote reward must contain between one and four components" }
        require(components.map(VoteRewardComponent::key).distinct().size == components.size) {
            "Vote reward component keys must be unique"
        }
        require(components.count(VoteRewardComponent::legacyIdentity) <= 1) {
            "A vote reward may contain at most one legacy component"
        }
    }

    fun component(key: String): VoteRewardComponent? = components.singleOrNull { it.key == key }
}

data class VoteEvent(
    val id: UUID,
    val vote: AuthenticatedVote,
    val receivedAt: Instant,
    val reward: VoteRewardBundle?,
    val rewardState: RewardState,
    val playerId: UUID? = null,
) {
    init {
        require((reward == null) == (rewardState == RewardState.NONE)) {
            "Only reward-free votes may use the NONE reward state"
        }
    }

    fun oneTimeUseIdentity(component: VoteRewardComponent): OneTimeUseIdentity =
        if (component.legacyIdentity) {
            OneTimeUseIdentity(
                useId = id,
                fingerprint = OneTimeUseFingerprint.sha256Fields(
                    vote.source.configKey,
                    vote.externalId,
                    vote.normalizedPlayerName,
                    component.amount.toPlainString(),
                ),
            )
        } else {
            OneTimeUseIdentity(
                useId = componentUseId(component.key),
                fingerprint = OneTimeUseFingerprint.sha256Fields(
                    "arcvotes-reward-v2",
                    id.toString(),
                    vote.source.configKey,
                    vote.externalId,
                    vote.normalizedPlayerName,
                    component.key,
                    component.provider.storageKey,
                    component.currencyId ?: "default",
                    component.amount.toPlainString(),
                ),
            )
        }

    private fun componentUseId(componentKey: String): UUID {
        val bytes = OneTimeUseFingerprint.sha256Fields(
            "arcvotes-reward-component-v2",
            id.toString(),
            componentKey,
        ).bytes().copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}

sealed interface VoteRecordResult {
    data class Inserted(val event: VoteEvent) : VoteRecordResult
    data class Duplicate(val event: VoteEvent) : VoteRecordResult
    data object IdentityConflict : VoteRecordResult
}
