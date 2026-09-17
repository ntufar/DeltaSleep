package io.github.ntufar.deltasleep.trends

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure date/time math for the trends dashboard (D-1). All functions are
 * timezone-explicit and unit-tested; see TrendsMathTest.
 */

/** Hour of day (0–23) at which the "sleep day" rolls over. 18:00 keeps normal
 * evening bedtimes and early-morning wakes on the same sleep day, and lets
 * 23:30 and 00:30 plot adjacently on the consistency scatter. */
const val DAY_BOUNDARY_HOUR = 18

/** Minutes within ± this of the median bedtime count as "regular". */
const val REGULARITY_WINDOW_MIN = 30

/** Calendar date of the sleep day containing [epochMs]: times before 18:00
 * belong to the previous evening's night. */
fun sleepDayOf(epochMs: Long, zone: ZoneId): LocalDate {
    val dt = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
    val date = dt.toLocalDate()
    return if (dt.hour < DAY_BOUNDARY_HOUR) date.minusDays(1) else date
}

/**
 * Clock time as minutes since the day boundary (18:00). 23:30 → 330,
 * 00:30 → 390, 07:00 → 780 — a full night unwraps to one increasing axis.
 */
fun minutesSinceDayBoundary(epochMs: Long, zone: ZoneId): Int {
    val dt = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
    val mins = dt.hour * 60 + dt.minute - DAY_BOUNDARY_HOUR * 60
    return ((mins % 1440) + 1440) % 1440
}

/** Day of week of a timestamp (Monday=1..Sunday=7). */
fun weekdayOf(epochMs: Long, zone: ZoneId): DayOfWeek =
    Instant.ofEpochMilli(epochMs).atZone(zone).dayOfWeek

/** Median of a non-empty list, null when empty. */
fun median(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid]
    else (sorted[mid - 1] + sorted[mid]) / 2f
}

/**
 * Regularity score (simplified Sleep Regularity Index): fraction of bedtimes
 * within ±[REGULARITY_WINDOW_MIN] of the median bedtime, or null when empty.
 * Inputs are minutes-since-day-boundary so the median is well-defined.
 */
fun regularityScore(bedtimeMinutes: List<Int>): Float? {
    if (bedtimeMinutes.isEmpty()) return null
    val sorted = bedtimeMinutes.sorted()
    val medianBed = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    val inside = bedtimeMinutes.count { kotlin.math.abs(it - medianBed) <= REGULARITY_WINDOW_MIN }
    return inside.toFloat() / bedtimeMinutes.size
}

/** Total sleep minutes per calendar sleep-day over the last [days] days
 * (oldest first), counting sessions by their sleep day. */
fun dailyTotals(
    nights: List<NightStat>,
    days: Int,
    zone: ZoneId,
    nowMs: Long,
): List<DayTotal> {
    val today = sleepDayOf(nowMs, zone)
    val totals = LinkedHashMap<LocalDate, Float>()
    for (i in days - 1 downTo 0) {
        totals[today.minusDays(i.toLong())] = 0f
    }
    for (night in nights) {
        val day = sleepDayOf(night.startMs, zone)
        if (day in totals) {
            totals[day] = totals.getValue(day) + night.durationMin
        }
    }
    return totals.map { (date, minutes) -> DayTotal(date, minutes) }
}

/** Mean snore % per weekday over the given nights (null = no nights that day). */
fun weekdaySnoreAvg(
    nights: List<NightStat>,
    zone: ZoneId,
): Map<DayOfWeek, Float?> {
    val byDay = nights
        .mapNotNull { night -> night.snorePct?.let { weekdayOf(night.startMs, zone) to it } }
        .groupBy({ it.first }, { it.second })
    return DayOfWeek.entries.associateWith { dow ->
        byDay[dow]?.let { it.sum() / it.size }
    }
}
