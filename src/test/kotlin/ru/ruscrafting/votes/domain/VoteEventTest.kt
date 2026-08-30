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
    "one-time reward identity is stable and binds source, player and amount" {
        val id = UUID.randomUUID()
        val vote = AuthenticatedVote(
            MonitoringSource.HOTMC,
            "external:42",
            NetworkPlayerName.of("Steve"),
            Instant.EPOCH,
        )
        val first = VoteEvent(id, vote, Instant.EPOCH, BigDecimal("100.00"), RewardState.PENDING)
        val same = first.copy(receivedAt = Instant.ofEpochSecond(10))
        val changed = first.copy(rewardAmount = BigDecimal("200.00"))

        first.oneTimeUseIdentity() shouldBe same.oneTimeUseIdentity()
        first.oneTimeUseIdentity() shouldNotBe changed.oneTimeUseIdentity()
    }
})
