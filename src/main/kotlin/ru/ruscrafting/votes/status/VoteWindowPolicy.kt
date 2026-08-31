package ru.ruscrafting.votes.status

import ru.ruscrafting.votes.config.MonitoringSource
import java.time.Duration
import java.time.Instant

/** Repeat-vote windows enforced by each monitoring. */
internal class VoteWindowPolicy {
    fun queryStart(now: Instant): Instant = now.minus(MAXIMUM_ROLLING_WINDOW)

    fun isActive(source: MonitoringSource, voteAt: Instant, now: Instant): Boolean =
        now.isBefore(activeUntil(source, voteAt))

    fun activeUntil(source: MonitoringSource, voteAt: Instant): Instant = when (source) {
        MonitoringSource.MINECRAFT_RATING,
        MonitoringSource.HOTMC,
        MonitoringSource.MONITORING_MINECRAFT,
        -> voteAt.plus(Duration.ofHours(24))

        MonitoringSource.GAME_MONITORING -> voteAt.plus(Duration.ofHours(12))
    }

    private companion object {
        val MAXIMUM_ROLLING_WINDOW: Duration = Duration.ofHours(24)
    }
}
