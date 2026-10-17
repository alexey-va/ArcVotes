package ru.ruscrafting.votes.status

import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.votes.config.MonitoringSource
import ru.ruscrafting.votes.domain.VoteEvent
import ru.ruscrafting.votes.storage.VoteHistoryLookup
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.CompletableFuture

/** Caches callback-derived `/vote` availability using each provider's repeat-vote rule. */
class VoteDailyStatusService(
    private val history: VoteHistoryLookup,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofSeconds(30),
    private val maximumCacheEntries: Int = 2_048,
) {
    private val windows = VoteWindowPolicy()
    private val lock = Any()
    private val cache = linkedMapOf<String, CachedStatus>()
    private val expirations = TreeSet(compareBy<Expiry>({ it.at }, { it.key }))
    private val inFlight = mutableMapOf<String, PendingLookup>()

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
        val pending: PendingLookup
        synchronized(lock) {
            cache[key]?.takeIf { now.isBefore(it.expiresAt) }?.let {
                return CompletableFuture.completedFuture(it.sources)
            }
            removeCachedLocked(key)
            trimExpiredLocked(now)
            inFlight[key]?.let { return it.promise }
            pending = PendingLookup(CompletableFuture())
            inFlight[key] = pending
            pending.promise.whenComplete { _, _ ->
                if (pending.promise.isCancelled) synchronized(lock) {
                    if (inFlight[key] === pending) inFlight.remove(key)
                }
            }
        }

        val query = try {
            history.findLatestVotes(playerName, windows.queryStart(now), now)
        } catch (failure: Throwable) {
            completeLookup(key, pending, null, failure)
            return pending.promise
        }
        query.whenComplete { latest, failure -> completeLookup(key, pending, latest, failure) }
        return pending.promise
    }

    private fun completeLookup(
        key: String,
        pending: PendingLookup,
        latestVotes: Map<MonitoringSource, Instant>?,
        failure: Throwable?,
    ) {
        if (failure != null) {
            synchronized(lock) { if (inFlight[key] === pending) inFlight.remove(key) }
            pending.promise.completeExceptionally(failure)
            return
        }
        try {
            val completedAt = clock.instant()
            val result: Set<MonitoringSource>
            synchronized(lock) {
                if (inFlight[key] !== pending) return
                val votes = latestVotes.orEmpty().toMutableMap()
                pending.observed.forEach { (source, observedAt) ->
                    val storedAt = votes[source]
                    if (storedAt == null || observedAt.isAfter(storedAt)) votes[source] = observedAt
                }
                val activeVotes = votes.filter { (source, voteAt) ->
                    windows.isActive(source, voteAt, completedAt)
                }
                result = activeVotes.keys.toSet()
                val policyExpiry = activeVotes.minOfOrNull { (source, voteAt) ->
                    windows.activeUntil(source, voteAt)
                }
                val expiresAt = minOf(completedAt.plus(cacheTtl), policyExpiry ?: Instant.MAX)
                trimExpiredLocked(completedAt)
                if (cache.size < maximumCacheEntries || cache.containsKey(key)) {
                    putCachedLocked(key, CachedStatus(expiresAt, result))
                }
                inFlight.remove(key)
            }
            pending.promise.complete(result)
        } catch (transformationFailure: Throwable) {
            synchronized(lock) { if (inFlight[key] === pending) inFlight.remove(key) }
            pending.promise.completeExceptionally(transformationFailure)
        }
    }

    fun observe(event: VoteEvent) {
        val now = clock.instant()
        if (!windows.isActive(event.vote.source, event.vote.occurredAt, now)) return
        val key = event.vote.normalizedPlayerName
        synchronized(lock) {
            val status = cache[key]
            if (status != null) {
                if (!now.isBefore(status.expiresAt)) removeCachedLocked(key)
                else putCachedLocked(key, status.copy(
                    expiresAt = minOf(status.expiresAt, windows.activeUntil(event.vote.source, event.vote.occurredAt)),
                    sources = status.sources + event.vote.source,
                ))
            } else {
                inFlight[key]?.let { pending ->
                    val previous = pending.observed[event.vote.source]
                    if (previous == null || event.vote.occurredAt.isAfter(previous)) {
                        pending.observed[event.vote.source] = event.vote.occurredAt
                    }
                }
            }
        }
    }

    private fun trimExpiredLocked(now: Instant) {
        if (cache.size < maximumCacheEntries) return
        val first = if (expirations.isEmpty()) return else expirations.first()
        if (!now.isBefore(first.at)) removeCachedLocked(first.key)
    }

    private fun removeCachedLocked(key: String) {
        cache.remove(key)?.let { previous -> expirations.remove(Expiry(previous.expiresAt, key)) }
    }

    private fun putCachedLocked(key: String, status: CachedStatus) {
        removeCachedLocked(key)
        cache[key] = status
        expirations.add(Expiry(status.expiresAt, key))
    }

    private data class Expiry(val at: Instant, val key: String)

    private data class CachedStatus(val expiresAt: Instant, val sources: Set<MonitoringSource>)

    private class PendingLookup(
        val promise: CompletableFuture<Set<MonitoringSource>>,
        val observed: MutableMap<MonitoringSource, Instant> = linkedMapOf(),
    )

    private companion object {
        val MAXIMUM_CACHE_TTL: Duration = Duration.ofMinutes(5)
        const val MAXIMUM_CACHE_ENTRIES = 10_000
    }
}
