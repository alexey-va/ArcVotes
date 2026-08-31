package ru.ruscrafting.votes.status

import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches callback-derived `/vote` availability for the rolling 24-hour vote window.
 *
 * Cache misses use asynchronous SQL through [VoteHistoryLookup]. Concurrent
 * misses for one player share a future; reward reconciliation augments an
 * already warm entry without creating a second source of truth.
 */
class VoteDailyStatusService(
    private val history: VoteHistoryLookup,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofSeconds(30),
    private val maximumCacheEntries: Int = 2_048,
) {
    private val cache = ConcurrentHashMap<String, CachedStatus>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Set<MonitoringSource>>>()

    init {
        require(!cacheTtl.isNegative && !cacheTtl.isZero && cacheTtl <= MAXIMUM_CACHE_TTL) {
            "Vote status cache TTL must be between 1 second and 5 minutes"
        }
        require(maximumCacheEntries in 128..MAXIMUM_CACHE_ENTRIES) {
            "Vote status maximum cache entries must be between 128 and $MAXIMUM_CACHE_ENTRIES"
        }
    }

    fun find(playerName: NetworkPlayerName): CompletableFuture<Set<MonitoringSource>> {
        val now = clock.instant()
        val key = playerName.value.lowercase(Locale.ROOT)
        val cached = cache[key]
        cached?.takeIf { now.isBefore(it.expiresAt) }?.let {
            return CompletableFuture.completedFuture(it.sources)
        }
        if (cached != null) cache.remove(key, cached)
        trimExpired(now)

        val from = now.minus(VOTE_COOLDOWN)
        val promise = CompletableFuture<Set<MonitoringSource>>()
        inFlight.putIfAbsent(key, promise)?.let { return it }
        try {
            history.findVotedSources(playerName, from, now).whenComplete { sources, failure ->
                if (failure != null) {
                    promise.completeExceptionally(failure)
                } else {
                    val immutable = sources.orEmpty().toSet()
                    val completedAt = clock.instant()
                    if (cache.size < maximumCacheEntries || cache.containsKey(key)) {
                        cache[key] = CachedStatus(completedAt.plus(cacheTtl), immutable)
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
        if (event.vote.occurredAt.isBefore(now.minus(VOTE_COOLDOWN))) return
        val key = event.vote.normalizedPlayerName
        cache.computeIfPresent(key) { _, status ->
            if (!now.isBefore(status.expiresAt)) null else status.copy(sources = status.sources + event.vote.source)
        }
    }

    private fun trimExpired(now: Instant) {
        if (cache.size < maximumCacheEntries) return
        cache.entries.removeIf { (_, status) -> !now.isBefore(status.expiresAt) }
    }

    private data class CachedStatus(
        val expiresAt: Instant,
        val sources: Set<MonitoringSource>,
    )

    private companion object {
        val VOTE_COOLDOWN: Duration = Duration.ofHours(24)
        val MAXIMUM_CACHE_TTL: Duration = Duration.ofMinutes(5)
        const val MAXIMUM_CACHE_ENTRIES = 10_000
    }
}
