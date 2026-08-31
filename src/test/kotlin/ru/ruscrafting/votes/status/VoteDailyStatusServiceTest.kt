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
        val pending = CompletableFuture<Map<MonitoringSource, Instant>>()
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

        pending.complete(mapOf(MonitoringSource.HOTMC to now))
        first.join() shouldBe setOf(MonitoringSource.HOTMC)
        service.find(player).join() shouldBe setOf(MonitoringSource.HOTMC)
        queries shouldBe 1
    }

    "pending reward observation augments an already warm cache" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ ->
                CompletableFuture.completedFuture(mapOf(MonitoringSource.HOTMC to now))
            },
            Clock.fixed(now, ZoneId.of("UTC")),
        )
        val player = NetworkPlayerName.of("Steve")
        service.find(player).join()

        service.observe(voteEvent(MonitoringSource.GAME_MONITORING, player, now))

        service.find(player).join() shouldBe setOf(MonitoringSource.HOTMC, MonitoringSource.GAME_MONITORING)
    }

    "HotMC resets at UTC midnight without waiting for cache TTL" {
        val clock = MutableClock(Instant.parse("2026-08-31T23:59:59Z"))
        var queries = 0
        val voteAt = Instant.parse("2026-08-31T20:17:00Z")
        val service = VoteDailyStatusService(
            history = VoteHistoryLookup { _, _, _ ->
                queries += 1
                CompletableFuture.completedFuture(mapOf(MonitoringSource.HOTMC to voteAt))
            },
            clock = clock,
            cacheTtl = Duration.ofMinutes(5),
        )
        val player = NetworkPlayerName.of("Steve")

        service.find(player).join() shouldBe setOf(MonitoringSource.HOTMC)
        clock.current = Instant.parse("2026-09-01T00:00:00Z")
        service.find(player).join() shouldBe emptySet()
        queries shouldBe 2
    }

    "each provider uses its published repeat-vote window" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val service = VoteDailyStatusService(
            history = VoteHistoryLookup { _, _, _ ->
                CompletableFuture.completedFuture(
                    mapOf(
                        MonitoringSource.MINECRAFT_RATING to now.minus(Duration.ofHours(23)),
                        MonitoringSource.HOTMC to now.minus(Duration.ofHours(3)),
                        MonitoringSource.MONITORING_MINECRAFT to now.minus(Duration.ofHours(24)),
                        MonitoringSource.GAME_MONITORING to now.minus(Duration.ofHours(12)),
                    ),
                )
            },
            clock = Clock.fixed(now, ZoneId.of("UTC")),
        )

        service.find(NetworkPlayerName.of("Steve")).join() shouldBe setOf(
            MonitoringSource.MINECRAFT_RATING,
            MonitoringSource.HOTMC,
        )
    }

    "lookup covers the longest rolling provider window" {
        val now = Instant.parse("2026-08-31T12:34:56Z")
        var requestedFrom: Instant? = null
        var requestedUntil: Instant? = null
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, from, until ->
                requestedFrom = from
                requestedUntil = until
                CompletableFuture.completedFuture(emptyMap())
            },
            Clock.fixed(now, ZoneId.of("UTC")),
        )

        service.find(NetworkPlayerName.of("Steve")).join()

        requestedFrom shouldBe Instant.parse("2026-08-30T12:34:56Z")
        requestedUntil shouldBe now
    }

    "observation outside its provider window does not block voting" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> CompletableFuture.completedFuture(emptyMap()) },
            Clock.fixed(now, ZoneId.of("UTC")),
        )
        val player = NetworkPlayerName.of("Steve")
        service.find(player).join()

        service.observe(
            voteEvent(
                MonitoringSource.GAME_MONITORING,
                player,
                now.minus(Duration.ofHours(12)),
            ),
        )

        service.find(player).join() shouldBe emptySet()
    }
})

private fun voteEvent(source: MonitoringSource, player: NetworkPlayerName, occurredAt: Instant) = VoteEvent(
    id = java.util.UUID.randomUUID(),
    vote = AuthenticatedVote(source, "status:${source.configKey}:$occurredAt", player, occurredAt),
    receivedAt = occurredAt,
    reward = null,
    rewardState = RewardState.NONE,
)

private class MutableClock(
    var current: Instant,
    private val clockZone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = clockZone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    override fun instant(): Instant = current
}
