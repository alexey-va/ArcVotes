package ru.ruscrafting.votes.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringSource
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class VoteEventTest : StringSpec({
    "component identities are stable, independent and bind provider amounts" {
        val id = UUID.randomUUID()
        val vote = AuthenticatedVote(
            MonitoringSource.HOTMC,
            "external:42",
            NetworkPlayerName.of("Steve"),
            Instant.EPOCH,
        )
        val standard = VoteRewardComponent("standard", RewardProvider.VAULT, BigDecimal("1000.00"))
        val premium = VoteRewardComponent("premium", RewardProvider.REDIS_ECONOMY, BigDecimal("3.00"), "tokens")
        val first = VoteEvent(id, vote, Instant.EPOCH, VoteRewardBundle(listOf(standard, premium)), RewardState.PENDING)
        val same = first.copy(receivedAt = Instant.ofEpochSecond(10))
        val changedPremium = premium.copy(amount = BigDecimal("4.00"))
        val changed = first.copy(reward = VoteRewardBundle(listOf(standard, changedPremium)))

        first.oneTimeUseIdentity(standard) shouldBe same.oneTimeUseIdentity(standard)
        first.oneTimeUseIdentity(premium) shouldNotBe changed.oneTimeUseIdentity(changedPremium)
        first.oneTimeUseIdentity(standard).useId shouldNotBe first.oneTimeUseIdentity(premium).useId
    }
})
