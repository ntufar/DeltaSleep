package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.trends.DayTotal
import io.github.ntufar.deltasleep.trends.NightStat
import io.github.ntufar.deltasleep.trends.median
import io.github.ntufar.deltasleep.trends.minutesSinceDayBoundary
import io.github.ntufar.deltasleep.trends.regularityScore
import io.github.ntufar.deltasleep.trends.weekdaySnoreAvg
import io.github.ntufar.deltasleep.viewmodel.TrendsViewModel
import java.time.ZoneId

private const val MONTH_MS = 30L * 24 * 3600 * 1000

/**
 * Trends dashboard (D-1): weekly duration bars, 30-day deep-% line, snore
 * heatmap by weekday, bedtime/wake consistency scatter with regularity
 * score, and the 30-day median nightly respiratory-rate trend (A-7).
 *
 * The loading/empty states are plain if/else branches: a non-local return
 * out of the Column scope here unbalanced the composition and crashed on
 * entry (slot-table corruption in endRoot).
 */
@Composable
fun TrendsScreen(
    vm: TrendsViewModel = viewModel(),
) {
    val data by vm.data.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Trends", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))

        val d = data
        if (d == null) {
            CircularProgressIndicator()
        } else if (d.nights.isEmpty()) {
            Text(
                "Not enough data yet — track a night to see trends.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TrendsContent(nights = d.nights, weekBars = d.weekBars)
        }
    }
}

@Composable
private fun TrendsContent(
    nights: List<NightStat>,
    weekBars: List<DayTotal>,
) {
    val zone = remember { ZoneId.systemDefault() }
    val cutoff = remember { System.currentTimeMillis() - MONTH_MS }
    val month = remember(nights) { nights.filter { it.startMs >= cutoff } }

    Text(
        "Based on ${nights.size} nights (last 90 days)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))
    Text("Sleep this week", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    WeekBars(days = weekBars)

    Spacer(Modifier.height(24.dp))
    Text("Deep sleep % · 30 days", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    TrendLine(
        points = month.map { it.deepPct },
        yMin = 0f,
        yMax = 100f,
        gridLines = listOf(0f, 25f, 50f, 75f, 100f),
        yLabel = { "%.0f%%".format(it) },
        description = "Deep sleep percent, last 30 days",
    )

    Spacer(Modifier.height(24.dp))
    Text("Snore by weekday · 90 days", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    WeekdayHeatmap(avgs = remember(nights) { weekdaySnoreAvg(nights, zone) })

    val rrPoints = remember(month) { month.map { it.medianBpm } }
    if (rrPoints.any { it != null }) {
        Spacer(Modifier.height(24.dp))
        Text("Breathing rate · 30 days", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Median breaths/min per night",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TrendLine(
            points = rrPoints,
            yMin = 5f,
            yMax = 35f,
            gridLines = listOf(10f, 20f, 30f),
            yLabel = { "%.0f".format(it) },
            description = "Median nightly breathing rate, last 30 days",
        )
    }

    Spacer(Modifier.height(24.dp))
    Text("Bedtime consistency · 30 days", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    val beds = remember(month) { month.map { minutesSinceDayBoundary(it.startMs, zone) } }
    val wakes = remember(month) { month.map { minutesSinceDayBoundary(it.endMs, zone) } }
    val medianBed = remember(beds) { median(beds.map { it.toFloat() })?.toInt() }
    BedtimeScatter(bedtimesMin = beds, wakesMin = wakes, medianBedMin = medianBed)
    Row {
        LegendDot(Color(0xFF42A5F5), "Bedtime")
        LegendDot(Color(0xFFFF9800), "Wake")
    }
    Spacer(Modifier.height(4.dp))
    val regularity = remember(beds) { regularityScore(beds) }
    Text(
        if (regularity != null) {
            "Regularity %.0f%% — bedtimes within ±30 min of median".format(regularity * 100)
        } else {
            "Regularity unavailable"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        Modifier.padding(end = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
            drawCircle(color, radius = size.minDimension / 2)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
