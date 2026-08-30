package ru.ruscrafting.votes.status

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.AuthenticatedVote
import ru.ruscrafting.votes.domain.RewardState
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture

class VoteDailyStatusServiceTest : FreeSpec({
    "repeated lookups share one MySQL result during the cache TTL" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        var queries = 0
        val pending = CompletableFuture<Set<MonitoringSource>>()
        val history = VoteHistoryLookup { _, _, _ ->
            queries += 1
            pending
        }
        val service = VoteDailyStatusService(history, Clock.fixed(now, ZoneId.of("UTC")), Duration.ofSeconds(30))
        val player = NetworkPlayerName.of("Steve")

        val first = service.find(player)
        val concurrent = service.find(player)
        queries shouldBe 1
        concurrent shouldBe first

        pending.complete(setOf(MonitoringSource.HOTMC))
        first.join() shouldBe setOf(MonitoringSource.HOTMC)
        service.find(player).join() shouldBe setOf(MonitoringSource.HOTMC)
        queries shouldBe 1
    }

    "pending reward observation augments an already warm cache" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> CompletableFuture.completedFuture(setOf(MonitoringSource.HOTMC)) },
            Clock.fixed(now, ZoneId.of("UTC")),
        )
        val player = NetworkPlayerName.of("Steve")
        service.find(player).join()

        service.observe(
            VoteEvent(
                id = java.util.UUID.randomUUID(),
                vote = AuthenticatedVote(
                    MonitoringSource.GAME_MONITORING,
                    "daily-status:test",
                    player,
                    now,
                ),
                receivedAt = now,
                reward = null,
                rewardState = RewardState.NONE,
            ),
        )

        service.find(player).join() shouldBe setOf(MonitoringSource.HOTMC, MonitoringSource.GAME_MONITORING)
    }
})
