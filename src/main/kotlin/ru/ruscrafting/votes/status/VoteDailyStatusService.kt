package ru.ruscrafting.votes.status

import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches the callback-derived `/vote` state for one Moscow calendar day.
 *
 * Cache misses use asynchronous SQL through [VoteHistoryLookup]. Concurrent
 * misses for one player share a future; reward reconciliation augments an
 * already warm entry without creating a second source of truth.
 */
class VoteDailyStatusService(
    private val history: VoteHistoryLookup,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofSeconds(30),
) {
    private val cache = ConcurrentHashMap<String, CachedStatus>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Set<MonitoringSource>>>()

    init {
        require(!cacheTtl.isNegative && !cacheTtl.isZero && cacheTtl <= MAXIMUM_CACHE_TTL) {
            "Vote status cache TTL must be between 1 second and 5 minutes"
        }
    }

    fun find(playerName: NetworkPlayerName): CompletableFuture<Set<MonitoringSource>> {
        val now = clock.instant()
        val day = day(now)
        val key = playerName.value.lowercase(Locale.ROOT)
        cache[key]?.takeIf { it.day == day && now.isBefore(it.expiresAt) }?.let {
            return CompletableFuture.completedFuture(it.sources)
        }
        cache.remove(key)
        trimExpired(now)

        val from = day.atStartOfDay(VOTE_DAY_ZONE).toInstant()
        val until = day.plusDays(1).atStartOfDay(VOTE_DAY_ZONE).toInstant()
        val promise = CompletableFuture<Set<MonitoringSource>>()
        inFlight.putIfAbsent(key, promise)?.let { return it }
        try {
            history.findVotedSources(playerName, from, until).whenComplete { sources, failure ->
                if (failure != null) {
                    promise.completeExceptionally(failure)
                } else {
                    val immutable = sources.orEmpty().toSet()
                    val completedAt = clock.instant()
                    if (cache.size < MAXIMUM_CACHE_ENTRIES) {
                        cache[key] = CachedStatus(day, completedAt.plus(cacheTtl), immutable)
                    }
                    promise.complete(immutable)
                }
            }
        } catch (failure: Throwable) {
            promise.completeExceptionally(failure)
        }
        promise.whenComplete { _, _ -> inFlight.remove(key, promise) }
        return promise
    }

    fun observe(event: VoteEvent) {
        val now = clock.instant()
        val currentDay = day(now)
        if (day(event.vote.occurredAt) != currentDay) return
        val key = event.vote.normalizedPlayerName
        cache.computeIfPresent(key) { _, status ->
            if (status.day != currentDay || !now.isBefore(status.expiresAt)) null
            else status.copy(sources = status.sources + event.vote.source)
        }
    }

    private fun trimExpired(now: Instant) {
        if (cache.size < MAXIMUM_CACHE_ENTRIES) return
        cache.entries.removeIf { (_, status) -> !now.isBefore(status.expiresAt) }
    }

    private fun day(instant: Instant): LocalDate = instant.atZone(VOTE_DAY_ZONE).toLocalDate()

    private data class CachedStatus(
        val day: LocalDate,
        val expiresAt: Instant,
        val sources: Set<MonitoringSource>,
    )

    private companion object {
        val VOTE_DAY_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
        val MAXIMUM_CACHE_TTL: Duration = Duration.ofMinutes(5)
        const val MAXIMUM_CACHE_ENTRIES = 2_048
    }
}
