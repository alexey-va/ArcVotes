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

    "Moscow midnight does not reset or duplicate the rolling lookup" {
        val clock = MutableClock(Instant.parse("2026-08-31T20:59:59Z"))
        val pending = CompletableFuture<Set<MonitoringSource>>()
        var queries = 0
        val history = VoteHistoryLookup { _, _, _ ->
            queries += 1
            pending
        }
        val service = VoteDailyStatusService(history, clock, Duration.ofSeconds(30))
        val player = NetworkPlayerName.of("Steve")

        val beforeMidnight = service.find(player)
        clock.current = Instant.parse("2026-08-31T21:00:01Z")
        val afterMidnight = service.find(player)

        queries shouldBe 1
        (afterMidnight === beforeMidnight) shouldBe true
        pending.complete(setOf(MonitoringSource.HOTMC))
        beforeMidnight.join() shouldBe setOf(MonitoringSource.HOTMC)
        afterMidnight.join() shouldBe setOf(MonitoringSource.HOTMC)
    }

    "lookup covers the previous rolling 24 hours" {
        val now = Instant.parse("2026-08-31T12:34:56Z")
        var requestedFrom: Instant? = null
        var requestedUntil: Instant? = null
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, from, until ->
                requestedFrom = from
                requestedUntil = until
                CompletableFuture.completedFuture(emptySet())
            },
            Clock.fixed(now, ZoneId.of("UTC")),
        )

        service.find(NetworkPlayerName.of("Steve")).join()

        requestedFrom shouldBe Instant.parse("2026-08-30T12:34:56Z")
        requestedUntil shouldBe now
    }

    "observation older than 24 hours does not block voting" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> CompletableFuture.completedFuture(emptySet()) },
            Clock.fixed(now, ZoneId.of("UTC")),
        )
        val player = NetworkPlayerName.of("Steve")
        service.find(player).join()

        service.observe(
            VoteEvent(
                id = java.util.UUID.randomUUID(),
                vote = AuthenticatedVote(
                    MonitoringSource.GAME_MONITORING,
                    "rolling-status:expired",
                    player,
                    now.minus(Duration.ofHours(24)).minusMillis(1),
                ),
                receivedAt = now,
                reward = null,
                rewardState = RewardState.NONE,
            ),
        )

        service.find(player).join() shouldBe emptySet()
    }
})

private class MutableClock(
    var current: Instant,
    private val clockZone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = clockZone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    override fun instant(): Instant = current
}
