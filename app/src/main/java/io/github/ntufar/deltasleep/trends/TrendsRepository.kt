package io.github.ntufar.deltasleep.trends

import io.github.ntufar.deltasleep.audio.BreathingRate
import io.github.ntufar.deltasleep.data.db.AppDatabase
import io.github.ntufar.deltasleep.data.model.SleepPhase
import java.time.LocalDate

/**
 * One completed night, with per-night aggregates for trend charts.
 * Percentage fields are null when the night has no epoch rows.
 */
data class NightStat(
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val durationMin: Float,
    val deepPct: Float?,
    val snorePct: Float?,
    val medianBpm: Float?,
)

/** Total sleep minutes on one sleep-day calendar date. */
data class DayTotal(
    val date: LocalDate,
    val minutes: Float,
)

/** Everything the trends screen renders. Nights are ascending by start time. */
data class TrendsData(
    val nights: List<NightStat>,
    /** Last-7-days sleep totals, oldest first. */
    val weekBars: List<DayTotal>,
    /** Median bedtime (minutes since 18:00) over the window, if any. */
    val medianBedtimeMin: Int?,
    /** Regularity score 0–1, if any bedtimes. */
    val regularity: Float?,
)

/**
 * Loads trend data with one query per chart input (D-1): completed sessions,
 * per-session epoch aggregates, and breath periods. Callers pass the window
 * in days (screens use 30 for lines/scatter and 90 for the weekday heatmap).
 *
 * Performance: sessions are retention-capped (~365 rows); aggregates come
 * from GROUP BY so per-night epoch rows are never materialised. Well under
 * the 50 ms budget for a full year.
 */
class TrendsRepository(private val db: AppDatabase) {

    suspend fun load(windowDays: Int, nowMs: Long = System.currentTimeMillis()): TrendsData {
        val sinceMs = nowMs - windowDays * 24L * 3600L * 1000L
        val sessions = db.sessionDao().getCompletedSince(sinceMs)
        if (sessions.isEmpty()) {
            return TrendsData(emptyList(), emptyList(), null, null)
        }
        val ids = sessions.map { it.id }
        val aggregates = db.epochDao()
            .getAggregates(ids, SleepPhase.DEEP.ordinal)
            .associateBy { it.sessionId }
        val periods = db.epochDao().getBreathPeriods(ids).groupBy({ it.sessionId }, { it.breathPeriodS })

        val nights = sessions.map { session ->
            val agg = aggregates[session.id]
            val endMs = session.endTime ?: session.startTime
            NightStat(
                sessionId = session.id,
                startMs = session.startTime,
                endMs = endMs,
                durationMin = (endMs - session.startTime) / 60000f,
                deepPct = agg?.takeIf { it.epochCount > 0 }
                    ?.let { it.deepCount * 100f / it.epochCount },
                snorePct = agg?.takeIf { it.epochCount > 0 }
                    ?.let { it.snoreCount * 100f / it.epochCount },
                medianBpm = periods[session.id]?.let { BreathingRate.medianBpmFromPeriods(it) },
            )
        }

        val zone = java.time.ZoneId.systemDefault()
        val bedtimes = nights.map { minutesSinceDayBoundary(it.startMs, zone) }
        val sorted = bedtimes.sorted()
        val medianBed = sorted.getOrNull(sorted.size / 2)
        return TrendsData(
            nights = nights,
            weekBars = dailyTotals(nights, 7, zone, nowMs),
            medianBedtimeMin = medianBed,
            regularity = regularityScore(bedtimes),
        )
    }
}
