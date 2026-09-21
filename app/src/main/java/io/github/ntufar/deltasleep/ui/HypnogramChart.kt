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
import io.github.ntufar.deltasleep.audio.SnoreIntensity
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.AcousticEventType
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import java.util.Calendar

// Display order is top-to-bottom Awake, Light, REM, Deep (conventional
// hypnogram staging); SleepPhase ordinals are unchanged (A-1 appends REM=3).
private val PHASE_ROWS = listOf(
    SleepPhase.AWAKE to 0,
    SleepPhase.LIGHT to 1,
    SleepPhase.REM   to 2,
    SleepPhase.DEEP  to 3,
)
private val PHASE_ROW_MAP = PHASE_ROWS.toMap()

/**
 * Hypnogram: X = time, Y = sleep phase (Awake / Light / REM / Deep).
 * Snore epochs get a semi-transparent magenta column overlay.
 * Hour labels are drawn along the bottom when startMs / endMs are provided.
 *
 * When [events] is non-empty and [startMs]/[endMs] are provided, acoustic events are
 * overlaid as thick markers along the top edge of the chart:
 * - APNEA_LIKE → red segment spanning event start→end
 * - HYPOPNEA_LIKE → orange segment spanning event start→end
 * - SNORE_EPISODE → magenta bar spanning event start→end, taller when louder
 *   (A-6 intensity 1–5 from peak dB over floor). GASP is not drawn.
 *   The per-epoch magenta column overlay (snore presence) is unchanged.
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

        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val hasTimeAxis = startMs > 0L && endMs > startMs
        val timeAxisH = if (hasTimeAxis) 22.dp.toPx() else 0f
        val labelW = 52.dp.toPx()
        val chartH = size.height - timeAxisH
        val chartW = size.width - labelW

        val rows = PHASE_ROWS.size.toFloat()
        val rowH = chartH / rows
        val epochW = chartW / epochs.size.toFloat()

        // Phase blocks (offset by labelW on the X axis). Contiguous runs of
        // the same phase merge into one rounded pill — calmer than per-epoch
        // bricks, same data.
        val cornerR = 3.dp.toPx()
        var runStart = 0
        while (runStart < epochs.size) {
            val phase = epochs[runStart].phase
            var runEnd = runStart + 1
            while (runEnd < epochs.size && epochs[runEnd].phase == phase) runEnd++
            val row = PHASE_ROW_MAP[phase] ?: 0
            val inset = 1.dp.toPx()
            drawRoundRect(
                color = phase.color,
                topLeft = Offset(labelW + runStart * epochW + inset / 2f, row * rowH + inset / 2f),
                size = Size((runEnd - runStart) * epochW - inset, rowH - inset),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
            )
            runStart = runEnd
        }
        epochs.forEachIndexed { i, epoch ->
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
                color = Color(0x14000000),
                start = Offset(labelW, row * rowH),
                end = Offset(size.width, row * rowH),
                strokeWidth = 1f,
            )
        }

        // Y-axis labels
        val labelPaint = Paint().apply {
            color = Color(0xFF8A94A8).toArgb()
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
                val apneaMarkerH = 6.dp.toPx()
                for (event in events) {
                    val startFrac = ((event.startUtc - startMs).toFloat() / sessionDurationMs).coerceIn(0f, 1f)
                    val endFrac = ((event.startUtc + event.durationMs - startMs).toFloat() / sessionDurationMs).coerceIn(0f, 1f)
                    val x0 = labelW + startFrac * chartW
                    val x1 = labelW + endFrac * chartW
                    val barW = (x1 - x0).coerceAtLeast(3.dp.toPx())
                    when (event.type) {
                        AcousticEventType.APNEA_LIKE -> drawRect(
                            color = Color(0xFFE53935),
                            topLeft = Offset(x0, 0f),
                            size = Size(barW, apneaMarkerH),
                        )
                        AcousticEventType.HYPOPNEA_LIKE -> drawRect(
                            color = Color(0xFFFF9800),
                            topLeft = Offset(x0, 0f),
                            size = Size(barW, apneaMarkerH),
                        )
                        // A-6: snore bar height encodes intensity 1–5.
                        AcousticEventType.SNORE_EPISODE -> {
                            val barH = (3 + 2 * SnoreIntensity.level(event.peakDbOverFloor)).dp.toPx()
                            drawRect(
                                color = Color(0xFFFF4081),
                                topLeft = Offset(x0, 0f),
                                size = Size(barW, barH),
                            )
                        }
                        else -> continue
                    }
                }
            }
        }

        // Time axis
        if (hasTimeAxis) {
            val durationMs = endMs - startMs
            val timePaint = Paint().apply {
                color = Color(0xFF8A94A8).toArgb()
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
