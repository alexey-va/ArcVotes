package ru.ruscrafting.votes.status

import ru.ruscrafting.votes.config.MonitoringSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Documented repeat-vote windows; HotMC's calendar boundary uses the configured zone. */
internal class VoteWindowPolicy(private val calendarDayZone: ZoneId) {
    fun queryStart(now: Instant): Instant = minOf(
        now.minus(MAXIMUM_ROLLING_WINDOW),
        now.atZone(calendarDayZone).toLocalDate().atStartOfDay(calendarDayZone).toInstant(),
    )

    fun isActive(source: MonitoringSource, voteAt: Instant, now: Instant): Boolean =
        now.isBefore(activeUntil(source, voteAt))

    fun activeUntil(source: MonitoringSource, voteAt: Instant): Instant = when (source) {
        MonitoringSource.MINECRAFT_RATING,
        MonitoringSource.MONITORING_MINECRAFT,
        -> voteAt.plus(Duration.ofHours(24))

        MonitoringSource.HOTMC -> voteAt
            .atZone(calendarDayZone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(calendarDayZone)
            .toInstant()

        MonitoringSource.GAME_MONITORING -> voteAt.plus(Duration.ofHours(12))
    }

    private companion object {
        val MAXIMUM_ROLLING_WINDOW: Duration = Duration.ofHours(24)
    }
}
