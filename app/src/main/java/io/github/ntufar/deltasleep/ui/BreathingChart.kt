package io.github.ntufar.deltasleep.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ntufar.deltasleep.audio.BreathingRate
import io.github.ntufar.deltasleep.data.model.SleepEpoch

private const val MIN_BPM = 5f
private const val MAX_BPM = 35f
private val GRID_BPMS = listOf(10f, 20f, 30f)
private val LineColor = Color(0xFF26A69A)

/**
 * Breathing-rate line chart (A-7): X = epoch, Y = breaths/min derived from
 * the per-epoch DSP breath period. Epochs without a measured period
 * (NULL — no breathing detected, or recorded before DB v3) break the line
 * so gaps are visible instead of fake zeros.
 *
 * The caller must gate on data: render only when at least one epoch has
 * a non-null [SleepEpoch.breathPeriodS].
 */
@Composable
fun BreathingChart(
    epochs: List<SleepEpoch>,
    modifier: Modifier = Modifier,
) {
    val median = remember(epochs) { BreathingRate.medianBpm(epochs) }
    val description = if (median != null) {
        "Breathing rate chart, median %.0f breaths per minute".format(median)
    } else {
        "Breathing rate chart, no data"
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics { contentDescription = description },
    ) {
        if (epochs.isEmpty()) return@Canvas

        val labelW = 40.dp.toPx()
        val chartH = size.height
        val chartW = size.width - labelW
        val epochW = chartW / epochs.size.toFloat()

        fun yFor(bpm: Float): Float {
            val frac = ((bpm.coerceIn(MIN_BPM, MAX_BPM) - MIN_BPM) / (MAX_BPM - MIN_BPM))
            return chartH - frac * chartH
        }

        val labelPaint = Paint().apply {
            color = Color(0xFF888888).toArgb()
            textSize = 26f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        // Gridlines + Y labels
        for (grid in GRID_BPMS) {
            val y = yFor(grid)
            drawLine(
                color = Color(0x44000000),
                start = Offset(labelW, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                grid.toInt().toString(),
                labelW - 6.dp.toPx(),
                y + labelPaint.textSize * 0.35f,
                labelPaint,
            )
        }

        // Line segments between consecutive measured epochs; NULL breaks the line.
        val path = Path()
        var started = false
        var prevX = 0f
        var prevY = 0f
        epochs.forEachIndexed { i, epoch ->
            val bpm = BreathingRate.bpm(epoch.breathPeriodS) ?: run {
                started = false
                return@forEachIndexed
            }
            val x = labelW + (i + 0.5f) * epochW
            val y = yFor(bpm)
            if (started) {
                path.moveTo(prevX, prevY)
                path.lineTo(x, y)
            }
            // Dot marks each measured epoch so sparse data stays visible.
            drawCircle(color = LineColor, radius = 3.dp.toPx(), center = Offset(x, y))
            prevX = x
            prevY = y
            started = true
        }
        drawPath(path = path, color = LineColor, style = Stroke(width = 2.dp.toPx()))
    }
}
