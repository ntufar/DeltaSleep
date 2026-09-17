package io.github.ntufar.deltasleep.trends

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TrendMathTest {

    private val zone = ZoneId.of("Europe/Helsinki")

    private fun msOf(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun minutesSinceDayBoundary_unwrapsMidnight() {
        // 18:00 → 0, 23:30 → 330, 00:30 → 390, 07:00 → 780.
        assertEquals(0, minutesSinceDayBoundary(msOf(2026, 9, 1, 18, 0), zone))
        assertEquals(330, minutesSinceDayBoundary(msOf(2026, 9, 1, 23, 30), zone))
        assertEquals(390, minutesSinceDayBoundary(msOf(2026, 9, 2, 0, 30), zone))
        assertEquals(780, minutesSinceDayBoundary(msOf(2026, 9, 2, 7, 0), zone))
    }

    @Test fun sleepDayOf_before18hBelongsToPreviousEvening() {
        val tuesday2am = msOf(2026, 9, 2, 2, 0)
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0).toLocalDate(), sleepDayOf(tuesday2am, zone))
        val tuesday20pm = msOf(2026, 9, 2, 20, 0)
        assertEquals(LocalDateTime.of(2026, 9, 2, 0, 0).toLocalDate(), sleepDayOf(tuesday20pm, zone))
    }

    @Test fun weekdayOf_matchesKnownDate() {
        // 2026-09-01 is a Tuesday.
        assertEquals(DayOfWeek.TUESDAY, weekdayOf(msOf(2026, 9, 1, 23, 0), zone))
    }

    @Test fun median_oddEvenEmpty() {
        assertEquals(2f, median(listOf(3f, 1f, 2f))!!, 0.001f)
        assertEquals(2.5f, median(listOf(1f, 4f, 2f, 3f))!!, 0.001f)
        assertNull(median(emptyList()))
    }

    @Test fun regularityScore_allInsideWindow_isOne() {
        // Bedtimes 23:00 + 23:20 + 23:40 → all within ±30 of median.
        assertEquals(1f, regularityScore(listOf(300, 320, 340))!!, 0.001f)
    }

    @Test fun regularityScore_outlierLowersScore() {
        // Three regular + one 3 h late → 0.75.
        assertEquals(0.75f, regularityScore(listOf(300, 310, 320, 500))!!, 0.001f)
    }

    @Test fun regularityScore_empty_isNull() {
        assertNull(regularityScore(emptyList()))
    }

    @Test fun dailyTotals_groupsBySleepDay() {
        val zoneUtc = ZoneOffset.UTC
        val now = LocalDateTime.of(2026, 9, 4, 12, 0).atZone(zoneUtc).toInstant().toEpochMilli()
        val nights = listOf(
            night(1L, 2026, 9, 2, 23, 0, 2026, 9, 3, 7, 0, zoneUtc), // 480 min, sleep day Sep 2
            night(2L, 2026, 9, 3, 1, 0, 2026, 9, 3, 2, 0, zoneUtc), // 60 min, sleep day Sep 2
        )
        val totals = dailyTotals(nights, 7, zoneUtc, now)
        assertEquals(7, totals.size)
        val sep2 = totals.first { it.date.dayOfMonth == 2 }
        assertEquals(540f, sep2.minutes, 0.001f)
        val sep3 = totals.first { it.date.dayOfMonth == 3 }
        assertEquals(0f, sep3.minutes, 0.001f)
    }

    @Test fun weekdaySnoreAvg_averagesPerWeekday() {
        val zoneUtc = ZoneOffset.UTC
        // 2026-09-01/02 Tue/Wed 23:00 starts.
        val nights = listOf(
            night(1L, 2026, 9, 1, 23, 0, 2026, 9, 2, 7, 0, zoneUtc, snorePct = 10f),
            night(2L, 2026, 9, 2, 23, 0, 2026, 9, 3, 7, 0, zoneUtc, snorePct = 30f),
            night(3L, 2026, 9, 8, 23, 0, 2026, 9, 9, 7, 0, zoneUtc, snorePct = 20f),
        )
        val avg = weekdaySnoreAvg(nights, zoneUtc)
        assertEquals(15f, avg[DayOfWeek.TUESDAY]!!, 0.001f) // (10+20)/2
        assertEquals(30f, avg[DayOfWeek.WEDNESDAY]!!, 0.001f)
        assertNull(avg[DayOfWeek.MONDAY])
    }

    private fun night(
        id: Long, y1: Int, mo1: Int, d1: Int, h1: Int, mi1: Int,
        y2: Int, mo2: Int, d2: Int, h2: Int, mi2: Int,
        zone: ZoneId, snorePct: Float? = null,
    ): NightStat {
        val start = LocalDateTime.of(y1, mo1, d1, h1, mi1).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(y2, mo2, d2, h2, mi2).atZone(zone).toInstant().toEpochMilli()
        return NightStat(id, start, end, (end - start) / 60000f, null, snorePct, null)
    }
}
