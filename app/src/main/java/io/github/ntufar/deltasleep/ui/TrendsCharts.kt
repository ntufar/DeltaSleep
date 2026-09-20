package io.github.ntufar.deltasleep.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ntufar.deltasleep.trends.DayTotal
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tiny internal chart kit for the trends dashboard (D-1). Same approach as
 * [HypnogramChart]: Compose Canvas, no third-party chart lib. Every chart
 * labels its axes in text (never color-only) and exposes a text summary
 * via contentDescription.
 */

private fun axisPaint() = Paint().apply {
    color = Color(0xFF888888).toArgb()
    textSize = 26f
    isAntiAlias = true
    textAlign = Paint.Align.RIGHT
}

private fun centerPaint() = Paint().apply {
    color = Color(0xFF888888).toArgb()
    textSize = 26f
    isAntiAlias = true
    textAlign = Paint.Align.CENTER
}

// ── Weekly sleep-duration bars ───────────────────────────────────────────────

/** Bars of total sleep minutes per day (last 7 days, oldest first). */
@Composable
fun WeekBars(
    days: List<DayTotal>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics {
                contentDescription = if (days.all { it.minutes <= 0f }) {
                    "Weekly sleep chart, no data yet"
                } else {
                    "Weekly sleep chart, total " +
                        "%.1f hours over %d nights".format(days.sumOf { it.minutes.toDouble() } / 60, days.count { it.minutes > 0f })
                }
            },
    ) {
        if (days.isEmpty()) return@Canvas
        val labelW = 44.dp.toPx()
        val xLabelH = 22.dp.toPx()
        val chartH = size.height - xLabelH
        val chartW = size.width - labelW
        if (chartW <= 0f || chartH <= 0f) return@Canvas
        val maxMin = maxOf(480f, days.maxOf { it.minutes })
        val gridHours = listOf(0, 4, 8, 12).filter { it * 60 <= maxMin + 1 }

        val paint = axisPaint()
        for (h in gridHours) {
            val y = chartH - (h * 60 / maxMin) * chartH
            drawLine(Color(0x44000000), Offset(labelW, y), Offset(size.width, y), 1f)
            drawContext.canvas.nativeCanvas.drawText(
                "${h}h", labelW - 6.dp.toPx(), y + paint.textSize * 0.35f, paint,
            )
        }

        val slotW = chartW / days.size
        val center = centerPaint()
        days.forEachIndexed { i, day ->
            val barH = (day.minutes / maxMin) * chartH
            val barW = (slotW * 0.55f).coerceAtLeast(4.dp.toPx())
            val x = labelW + i * slotW + (slotW - barW) / 2
            drawRect(
                color = Color(0xFF42A5F5),
                topLeft = Offset(x, chartH - barH),
                size = Size(barW, barH),
            )
            drawContext.canvas.nativeCanvas.drawText(
                day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                labelW + i * slotW + slotW / 2,
                size.height - 4.dp.toPx(),
                center,
            )
        }
    }
}

// ── Generic line with gaps ───────────────────────────────────────────────────

/**
 * Line chart over evenly spaced per-night values; nulls break the line
 * (nights without data render as gaps, never zeros).
 */
@Composable
fun TrendLine(
    points: List<Float?>,
    yMin: Float,
    yMax: Float,
    gridLines: List<Float>,
    yLabel: (Float) -> String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = description },
    ) {
        if (points.isEmpty()) return@Canvas
        val labelW = 44.dp.toPx()
        val chartH = size.height
        val chartW = size.width - labelW
        if (chartW <= 0f || chartH <= 0f) return@Canvas
        val span = (yMax - yMin).coerceAtLeast(1f)
        fun yFor(v: Float) = chartH - ((v.coerceIn(yMin, yMax) - yMin) / span) * chartH

        val paint = axisPaint()
        for (grid in gridLines) {
            val y = yFor(grid)
            drawLine(Color(0x44000000), Offset(labelW, y), Offset(size.width, y), 1f)
            drawContext.canvas.nativeCanvas.drawText(
                yLabel(grid), labelW - 6.dp.toPx(), y + paint.textSize * 0.35f, paint,
            )
        }

        val color = Color(0xFF26A69A)
        val path = Path()
        var started = false
        var prevX = 0f
        var prevY = 0f
        points.forEachIndexed { i, p ->
            if (p == null) {
                started = false
                return@forEachIndexed
            }
            val x = labelW + (i + 0.5f) * (chartW / points.size)
            val y = yFor(p)
            if (started) {
                path.moveTo(prevX, prevY)
                path.lineTo(x, y)
            }
            drawCircle(color, 3.dp.toPx(), Offset(x, y))
            prevX = x
            prevY = y
            started = true
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

// ── Weekday snore heatmap ────────────────────────────────────────────────────

/** Seven cells (Mon–Sun) with mean snore %; empty days show "–". */
@Composable
fun WeekdayHeatmap(
    avgs: Map<DayOfWeek, Float?>,
    modifier: Modifier = Modifier,
) {
    val order = listOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .semantics {
                contentDescription = "Snore by weekday: " + order.joinToString(", ") { dow ->
                    val v = avgs[dow]
                    dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " +
                        (if (v == null) "no data" else "%.0f percent".format(v))
                }
            },
    ) {
        if (size.width <= 0f) return@Canvas
        val cellW = size.width / 7
        val center = centerPaint()
        order.forEachIndexed { i, dow ->
            val v = avgs[dow]
            val x = i * cellW
            if (v != null) {
                drawRect(
                    color = Color(0xFFFF4081).copy(alpha = (v / 50f).coerceIn(0.12f, 1f)),
                    topLeft = Offset(x + 2.dp.toPx(), 0f),
                    size = Size(cellW - 4.dp.toPx(), size.height),
                )
            }
            drawContext.canvas.nativeCanvas.drawText(
                dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                x + cellW / 2,
                22.dp.toPx(),
                center,
            )
            drawContext.canvas.nativeCanvas.drawText(
                if (v == null) "–" else "%.0f%%".format(v),
                x + cellW / 2,
                52.dp.toPx(),
                center,
            )
        }
    }
}

// ── Bedtime/wake consistency scatter ─────────────────────────────────────────

/**
 * Bedtimes and wake times as minutes-since-18:00, one dot per night (x =
 * night order). Shaded band is median bedtime ± 30 min. Y labels are clock
 * hours so the unwrapped axis stays readable.
 */
@Composable
fun BedtimeScatter(
    bedtimesMin: List<Int>,
    wakesMin: List<Int>,
    medianBedMin: Int?,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics {
                contentDescription = if (bedtimesMin.isEmpty()) {
                    "Consistency chart, no data yet"
                } else {
                    "Consistency chart over %d nights".format(bedtimesMin.size)
                }
            },
    ) {
        if (bedtimesMin.isEmpty()) return@Canvas
        val labelW = 44.dp.toPx()
        val chartH = size.height
        val chartW = size.width - labelW
        if (chartW <= 0f || chartH <= 0f) return@Canvas
        val yMax = 1080f // 18:00 → 12:00 next day
        fun yFor(m: Int) = chartH - (m.coerceIn(0, 1080).toFloat() / yMax) * chartH

        // Target band around median bedtime
        if (medianBedMin != null) {
            drawRect(
                color = Color(0x2242A5F5),
                topLeft = Offset(labelW, yFor(medianBedMin + 30)),
                size = Size(chartW, yFor(medianBedMin - 30) - yFor(medianBedMin + 30)),
            )
        }

        val paint = axisPaint()
        for ((label, m) in listOf("18" to 0, "0" to 360, "6" to 720, "12" to 1080)) {
            val y = yFor(m)
            drawLine(Color(0x44000000), Offset(labelW, y), Offset(size.width, y), 1f)
            drawContext.canvas.nativeCanvas.drawText(
                label, labelW - 6.dp.toPx(), y + paint.textSize * 0.35f, paint,
            )
        }

        val n = maxOf(bedtimesMin.size, wakesMin.size).coerceAtLeast(1)
        fun xFor(i: Int) = labelW + (i + 0.5f) * (chartW / n)
        bedtimesMin.forEachIndexed { i, m ->
            drawCircle(Color(0xFF42A5F5), 4.dp.toPx(), Offset(xFor(i), yFor(m)))
        }
        wakesMin.forEachIndexed { i, m ->
            drawCircle(Color(0xFFFF9800), 4.dp.toPx(), Offset(xFor(i), yFor(m)))
        }
    }
}
