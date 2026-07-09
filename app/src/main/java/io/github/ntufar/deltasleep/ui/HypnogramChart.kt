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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import java.util.Calendar

private val PHASE_ROWS = listOf(
    SleepPhase.AWAKE to 0,
    SleepPhase.LIGHT to 1,
    SleepPhase.DEEP  to 2,
)
private val PHASE_ROW_MAP = PHASE_ROWS.toMap()

/**
 * Hypnogram: X = time, Y = sleep phase (Awake / Light / Deep).
 * Snore epochs get a semi-transparent magenta column overlay.
 * Hour labels are drawn along the bottom when startMs / endMs are provided.
 *
 * When [events] is non-empty and [startMs]/[endMs] are provided, acoustic events are
 * overlaid as thick markers along the top edge of the chart:
 * - APNEA_LIKE → red segment spanning event start→end
 * - HYPOPNEA_LIKE → orange segment spanning event start→end
 * GASP and SNORE_EPISODE are not drawn (snore already has the magenta overlay).
 *
 * Default rendering (events = emptyList()) is identical to before this change.
 */
@Composable
fun HypnogramChart(
    epochs: List<SleepEpoch>,
    startMs: Long = 0L,
    endMs: Long = 0L,
    events: List<AcousticEvent> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp),
    ) {
        if (epochs.isEmpty()) return@Canvas

        val hasTimeAxis = startMs > 0L && endMs > startMs
        val timeAxisH = if (hasTimeAxis) 22.dp.toPx() else 0f
        val labelW = 52.dp.toPx()
        val chartH = size.height - timeAxisH
        val chartW = size.width - labelW

        val rows = PHASE_ROWS.size.toFloat()
        val rowH = chartH / rows
        val epochW = chartW / epochs.size.toFloat()

        // Phase blocks (offset by labelW on the X axis)
        epochs.forEachIndexed { i, epoch ->
            val row = PHASE_ROW_MAP[epoch.phase] ?: 0
            drawRect(
                color = epoch.phase.color,
                topLeft = Offset(labelW + i * epochW, row * rowH),
                size = Size(epochW, rowH),
            )
            if (epoch.hasSnore) {
                drawRect(
                    color = Color(0x55FF4081),
                    topLeft = Offset(labelW + i * epochW, 0f),
                    size = Size(epochW, chartH),
                )
            }
        }

        // Row dividers
        for (row in 1 until rows.toInt()) {
            drawLine(
                color = Color(0x33000000),
                start = Offset(labelW, row * rowH),
                end = Offset(size.width, row * rowH),
                strokeWidth = 1f,
            )
        }

        // Y-axis labels
        val labelPaint = Paint().apply {
            color = Color(0xFF888888).toArgb()
            textSize = 28f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        PHASE_ROWS.forEach { (phase, row) ->
            val y = row * rowH + rowH / 2f + labelPaint.textSize * 0.35f
            drawContext.canvas.nativeCanvas.drawText(
                phase.label,
                labelW - 6.dp.toPx(),
                y,
                labelPaint,
            )
        }

        // Acoustic event markers — drawn when startMs/endMs are valid, regardless of hasTimeAxis
        if (hasTimeAxis && events.isNotEmpty()) {
            val sessionDurationMs = endMs - startMs
            if (sessionDurationMs > 0) {
                val markerH = 6.dp.toPx()
                for (event in events) {
                    val color = when (event.type) {
                        AcousticEventType.APNEA_LIKE -> Color(0xFFE53935)
                        AcousticEventType.HYPOPNEA_LIKE -> Color(0xFFFF9800)
                        else -> continue
                    }
                    val startFrac = ((event.startUtc - startMs).toFloat() / sessionDurationMs).coerceIn(0f, 1f)
                    val endFrac = ((event.startUtc + event.durationMs - startMs).toFloat() / sessionDurationMs).coerceIn(0f, 1f)
                    val x0 = labelW + startFrac * chartW
                    val x1 = labelW + endFrac * chartW
                    drawRect(
                        color = color,
                        topLeft = Offset(x0, 0f),
                        size = Size((x1 - x0).coerceAtLeast(3.dp.toPx()), markerH),
                    )
                }
            }
        }

        // Time axis
        if (hasTimeAxis) {
            val durationMs = endMs - startMs
            val timePaint = Paint().apply {
                color = Color(0xFF888888).toArgb()
                textSize = 26f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            // Tick at each whole hour within the session
            val cal = Calendar.getInstance().apply {
                timeInMillis = startMs
                add(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            while (cal.timeInMillis <= endMs) {
                val frac = (cal.timeInMillis - startMs).toFloat() / durationMs
                val x = labelW + frac * chartW
                drawLine(
                    color = Color(0x44000000),
                    start = Offset(x, 0f),
                    end = Offset(x, chartH),
                    strokeWidth = 1f,
                )
                val hourLabel = "%d:%02d".format(
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    hourLabel,
                    x,
                    size.height - 4.dp.toPx(),
                    timePaint,
                )
                cal.add(Calendar.HOUR_OF_DAY, 1)
            }
        }
    }
}
