package ru.ruscrafting.votes.status

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

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

    "an observation during a SQL miss is included in the completed result" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val pending = CompletableFuture<Map<MonitoringSource, Instant>>()
        val player = NetworkPlayerName.of("Steve")
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> pending },
            Clock.fixed(now, ZoneId.of("UTC")),
        )

        val result = service.find(player)
        service.observe(voteEvent(MonitoringSource.HOTMC, player, now))
        pending.complete(emptyMap())

        result.join() shouldBe setOf(MonitoringSource.HOTMC)
    }

    "cancelling a lookup allows a fresh query and isolates the old completion" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val oldQuery = CompletableFuture<Map<MonitoringSource, Instant>>()
        val freshQuery = CompletableFuture<Map<MonitoringSource, Instant>>()
        val queries = ArrayDeque<CompletableFuture<Map<MonitoringSource, Instant>>>()
        queries += oldQuery
        queries += freshQuery
        var count = 0
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> count++; queries.removeFirst() },
            Clock.fixed(now, ZoneId.of("UTC")),
        )
        val player = NetworkPlayerName.of("Steve")
        val old = service.find(player)
        old.cancel(false) shouldBe true
        val fresh = service.find(player)
        count shouldBe 2
        service.find(player) shouldBe fresh
        service.observe(voteEvent(MonitoringSource.GAME_MONITORING, player, now))
        oldQuery.complete(mapOf(MonitoringSource.HOTMC to now)) shouldBe true
        freshQuery.complete(emptyMap())
        fresh.join() shouldBe setOf(MonitoringSource.GAME_MONITORING)
        service.find(player).join() shouldBe setOf(MonitoringSource.GAME_MONITORING)
    }

    "an async history failure drops observations before a retry" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val failed = CompletableFuture<Map<MonitoringSource, Instant>>()
        val retry = CompletableFuture<Map<MonitoringSource, Instant>>()
        val queries = ArrayDeque(listOf(failed, retry))
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> queries.removeFirst() },
            Clock.fixed(now, ZoneId.of("UTC")),
        )
        val player = NetworkPlayerName.of("Steve")
        val first = service.find(player)
        service.observe(voteEvent(MonitoringSource.HOTMC, player, now))
        failed.completeExceptionally(IllegalStateException("database unavailable"))
        shouldThrow<ExecutionException> { first.get(1, TimeUnit.SECONDS) }

        val second = service.find(player)
        retry.complete(emptyMap())
        second.join() shouldBe emptySet()
    }

    "a synchronous history failure is cleaned up for retry" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        var fail = true
        var queries = 0
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ ->
                queries++
                if (fail) {
                    fail = false
                    throw IllegalStateException("database unavailable")
                }
                CompletableFuture.completedFuture(emptyMap())
            },
            Clock.fixed(now, ZoneId.of("UTC")),
        )
        val player = NetworkPlayerName.of("Steve")
        shouldThrow<ExecutionException> { service.find(player).get(1, TimeUnit.SECONDS) }
        service.find(player).join() shouldBe emptySet()
        queries shouldBe 2
    }

    "a newer SQL timestamp wins over an older in-flight observation" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val clock = MutableClock(now)
        val pending = CompletableFuture<Map<MonitoringSource, Instant>>()
        var queries = 0
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> queries++; pending },
            clock,
            cacheTtl = Duration.ofMinutes(5),
        )
        val player = NetworkPlayerName.of("Steve")
        val lookup = service.find(player)
        service.observe(voteEvent(MonitoringSource.GAME_MONITORING, player, now.minus(Duration.ofHours(11).plusMinutes(59))))
        pending.complete(mapOf(MonitoringSource.GAME_MONITORING to now))
        lookup.join() shouldBe setOf(MonitoringSource.GAME_MONITORING)

        clock.current = now.plus(Duration.ofMinutes(2))
        service.find(player).join() shouldBe setOf(MonitoringSource.GAME_MONITORING)
        queries shouldBe 1
    }

    "a failure while transforming a history result completes the lookup" {
        val now = Instant.parse("2026-08-31T12:00:00Z")
        val clock = ThrowingClock(now)
        val service = VoteDailyStatusService(
            VoteHistoryLookup { _, _, _ -> CompletableFuture.completedFuture(emptyMap()) },
            clock,
        )

        val player = NetworkPlayerName.of("Steve")
        shouldThrow<ExecutionException> { service.find(player).get(1, TimeUnit.SECONDS) }
        service.find(player).join() shouldBe emptySet()
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

private class ThrowingClock(private val value: Instant) : Clock() {
    private var calls = 0

    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant {
        calls++
        if (calls == 2) throw IllegalStateException("simulated clock failure")
        return value
    }
}
