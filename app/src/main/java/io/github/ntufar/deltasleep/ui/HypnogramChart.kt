package io.github.ntufar.deltasleep.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Marker colors deliberately differ from the Awake-phase red (#E53935):
 * previously apnea markers used the exact same red, and snore magenta sat
 * in the same lane, so the three were indistinguishable. Apnea is now a
 * much darker maroon drawn with a light outline, and snore bars sit in
 * their own lane below the apnea lane instead of overlapping it.
 */
internal val ApneaMarkerColor = Color(0xFFB71C1C)
private val ApneaMarkerOutline = Color(0xFFFFEBEE)
private val HypopneaMarkerColor = Color(0xFFFF9800)
private val SnoreMarkerColor = Color(0xFFFF4081)
// Faint wash for snore epochs (was 0x55 — a pink flood on snory nights
// that buried the phase blocks). Event bars carry the snore signal now.
private val SnoreWashColor = Color(0x14FF4081)

private val LabelColor = Color(0xFF8A94A8)
private val ChartLabelWidth = 64.dp
private val EventStripH = 20.dp
private val ChartTotalH = 240.dp

/**
 * Hypnogram: X = time, Y = sleep phase (Awake / Light / REM / Deep).
 *
 * Readability design:
 * - The chart area scrolls horizontally with at least [minEpochWidth] per
 *   30 s epoch, so a full night (~950 epochs) is ~3800 dp wide instead of
 *   squeezing every epoch into a sub-pixel sliver. Y-axis labels stay
 *   fixed on the left while the data scrolls.
 * - The top [EventStripH] is reserved for acoustic-event markers:
 *   apnea/hypopnea bars in the top lane, snore bars (height = intensity
 *   1–5) in a lane below. Phase blocks start under the strip, so markers
 *   never paint over the Awake row.
 * - Hour gridlines + labels scroll with the data along the bottom.
 *
 * When [events] is non-empty and [startMs]/[endMs] are provided, acoustic events are
 * overlaid as markers in the reserved top strip of the chart:
 * - APNEA_LIKE → dark-maroon segment spanning event start→end
 * - HYPOPNEA_LIKE → orange segment spanning event start→end
 * - SNORE_EPISODE → magenta bar spanning event start→end, taller when louder
 *   (A-6 intensity 1–5 from peak dB over floor). GASP is not drawn.
 */
@Composable
fun HypnogramChart(
    epochs: List<SleepEpoch>,
    startMs: Long = 0L,
    endMs: Long = 0L,
    events: List<AcousticEvent> = emptyList(),
    modifier: Modifier = Modifier,
    minEpochWidth: Dp = 4.dp,
) {
    if (epochs.isEmpty()) {
        Box(modifier.fillMaxWidth().height(ChartTotalH))
        return
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(ChartTotalH),
    ) {
        if (maxWidth <= 0.dp || maxHeight <= 0.dp) return@BoxWithConstraints
        val hasTimeAxis = startMs > 0L && endMs > startMs
        val timeAxisH = if (hasTimeAxis) 22.dp else 0.dp
        val labelW = minOf(ChartLabelWidth, maxWidth / 3)
        val chartH = ChartTotalH - timeAxisH
        val stripH = EventStripH
        val phaseH = chartH - stripH
        if (phaseH <= 0.dp) return@BoxWithConstraints
        val rowH = phaseH / PHASE_ROWS.size

        // Wide enough that every epoch gets at least minEpochWidth;
        // never narrower than the visible viewport (fills short naps).
        val viewportW = (maxWidth - labelW).coerceAtLeast(48.dp)
        val contentW = maxOf(viewportW, minEpochWidth * epochs.size)

        Row(Modifier.fillMaxWidth().height(ChartTotalH)) {
            // Fixed Y-axis labels.
            Column(
                Modifier
                    .width(labelW)
                    .height(chartH)
                    .padding(top = stripH),
            ) {
                PHASE_ROWS.forEach { (phase, _) ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(rowH),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            phase.label,
                            fontSize = 11.sp,
                            color = LabelColor,
                            maxLines = 1,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
            // Scrollable data area.
            Box(
                Modifier
                    .weight(1f)
                    .height(ChartTotalH)
                    .horizontalScroll(rememberScrollState()),
            ) {
                ChartCanvas(
                    epochs = epochs,
                    startMs = startMs,
                    endMs = endMs,
                    events = events,
                    hasTimeAxis = hasTimeAxis,
                    timeAxisH = timeAxisH,
                    stripH = stripH,
                    chartH = chartH,
                    phaseH = phaseH,
                    modifier = Modifier
                        .width(contentW)
                        .height(ChartTotalH),
                )
            }
        }
    }
}

@Composable
private fun ChartCanvas(
    epochs: List<SleepEpoch>,
    startMs: Long,
    endMs: Long,
    events: List<AcousticEvent>,
    hasTimeAxis: Boolean,
    timeAxisH: Dp,
    stripH: Dp,
    chartH: Dp,
    phaseH: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val stripPx = stripH.toPx()
        val chartHPx = chartH.toPx()
        val rowH = phaseH.toPx() / PHASE_ROWS.size.toFloat()
        if (rowH <= 0f) return@Canvas
        val epochW = size.width / epochs.size.toFloat()

        // Snore-epoch wash — faint, phase area only (not the event strip).
        epochs.forEachIndexed { i, epoch ->
            if (epoch.hasSnore) {
                drawRect(
                    color = SnoreWashColor,
                    topLeft = Offset(i * epochW, stripPx),
                    size = Size(epochW, chartHPx - stripPx),
                )
            }
        }

        // Phase blocks (contiguous runs of the same phase merge into one
        // rounded pill — calmer than per-epoch bricks, same data).
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
                topLeft = Offset(
                    runStart * epochW + inset / 2f,
                    stripPx + row * rowH + inset / 2f,
                ),
                size = Size((runEnd - runStart) * epochW - inset, rowH - inset),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
            )
            runStart = runEnd
        }

        // Row dividers (phase area only).
        for (row in 1 until PHASE_ROWS.size) {
            drawLine(
                color = Color(0x14000000),
                start = Offset(0f, stripPx + row * rowH),
                end = Offset(size.width, stripPx + row * rowH),
                strokeWidth = 1f,
            )
        }

        // Acoustic event markers in the reserved top strip.
        if (hasTimeAxis && events.isNotEmpty()) {
            val sessionDurationMs = endMs - startMs
            if (sessionDurationMs > 0) {
                val apneaMarkerH = 6.dp.toPx()
                val snoreTop = apneaMarkerH + 2.dp.toPx()
                for (event in events) {
                    val startFrac =
                        ((event.startUtc - startMs).toFloat() / sessionDurationMs).coerceIn(0f, 1f)
                    val endFrac =
                        ((event.startUtc + event.durationMs - startMs).toFloat() / sessionDurationMs)
                            .coerceIn(0f, 1f)
                    val x0 = startFrac * size.width
                    val x1 = endFrac * size.width
                    val barW = (x1 - x0).coerceAtLeast(3.dp.toPx())
                    when (event.type) {
                        AcousticEventType.APNEA_LIKE -> {
                            drawRect(
                                color = ApneaMarkerColor,
                                topLeft = Offset(x0, 0f),
                                size = Size(barW, apneaMarkerH),
                            )
                            // Light outline separates the dark maroon from
                            // Awake red / snore pink around it.
                            drawRect(
                                color = ApneaMarkerOutline,
                                topLeft = Offset(x0, 0f),
                                size = Size(barW, apneaMarkerH),
                                style = Stroke(width = 1f),
                            )
                        }
                        AcousticEventType.HYPOPNEA_LIKE -> drawRect(
                            color = HypopneaMarkerColor,
                            topLeft = Offset(x0, 0f),
                            size = Size(barW, apneaMarkerH),
                        )
                        // A-6: snore bar height encodes intensity 1–5, in
                        // its own lane below the apnea lane.
                        AcousticEventType.SNORE_EPISODE -> {
                            val barH = (3 + 2 * SnoreIntensity.level(event.peakDbOverFloor)).dp.toPx()
                            drawRect(
                                color = SnoreMarkerColor,
                                topLeft = Offset(x0, snoreTop),
                                size = Size(barW, barH),
                            )
                        }
                        else -> continue
                    }
                }
            }
        }

        // Time axis — hour gridlines span the full chart height.
        if (hasTimeAxis) {
            val durationMs = endMs - startMs
            val timePaint = Paint().apply {
                color = LabelColor.toArgb()
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
                val x = frac * size.width
                drawLine(
                    color = Color(0x44000000),
                    start = Offset(x, 0f),
                    end = Offset(x, chartHPx),
                    strokeWidth = 1f,
                )
                val hourLabel = "%d:%02d".format(
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                )
                // Keep edge-hour labels inside the canvas instead of
                // clipping them at the edge.
                val halfText = timePaint.measureText(hourLabel) / 2f
                val tx = if (halfText * 2f < size.width) {
                    x.coerceIn(halfText, size.width - halfText)
                } else {
                    x
                }
                drawContext.canvas.nativeCanvas.drawText(
                    hourLabel,
                    tx,
                    size.height - 4.dp.toPx(),
                    timePaint,
                )
                cal.add(Calendar.HOUR_OF_DAY, 1)
            }
        }
    }
}
