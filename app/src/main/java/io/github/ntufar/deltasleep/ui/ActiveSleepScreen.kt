package io.github.ntufar.deltasleep.ui

import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.viewmodel.LiveSleepViewModel
import java.util.Calendar
import java.util.concurrent.TimeUnit

private val BackgroundColor = Color(0xFF0A0E14)
private val CardColor       = Color(0xFF141A23)
private val GridColor       = Color(0x22FFFFFF)
private val DimTextColor    = Color(0x66FFFFFF)
private val RmsColor        = Color(0xFF00E676)   // audio level
private val ZcrColor        = Color(0xFF40C4FF)   // sound texture (ZCR)
private val BandColor       = Color(0xFFFFAB40)   // snore band
private val SnoreEventColor = Color(0xFFFF4081)   // snore overlay / events

private const val GRAPH_SLOTS    = 90f  // samples shown in live charts (90 × 2 s = 3 min)
private const val SMOOTH_WIN     = 5    // moving-average window for live charts
private const val EPOCH_W_DP     = 20   // dp per epoch block in scrollable charts
private const val LABEL_EVERY_N  = 10   // show x-axis label every N epochs (every 5 min)

@Composable
fun ActiveSleepScreen(
    onStop: (Long) -> Unit,
    vm: LiveSleepViewModel = viewModel(),
) {
    val rmsHistory  by vm.rmsHistory.collectAsState()
    val zcrHistory  by vm.zcrHistory.collectAsState()
    val bandHistory by vm.bandHistory.collectAsState()
    val phase       by vm.currentPhase.collectAsState()
    val hasSnore    by vm.hasRecentSnore.collectAsState()
    val elapsedMs   by vm.elapsedMs.collectAsState()
    val allEpochs   by vm.allEpochs.collectAsState()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BackgroundColor) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PhaseBadge(phase)
            Spacer(Modifier.height(16.dp))
            Text(
                text = formatElapsed(elapsedMs),
                color = Color.White,
                fontSize = 56.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(16.dp))

            // ── Scrollable chart column ───────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Live per-frame signals (horizontal = time, last 3 min)
                ChartCard("Live signal — last 3 min") {
                    MiniSignalGraph("Audio level",    rmsHistory,  RmsColor)
                    Spacer(Modifier.height(6.dp))
                    MiniSignalGraph("Sound texture",  zcrHistory,  ZcrColor)
                    Spacer(Modifier.height(6.dp))
                    MiniSignalGraph("Snore band",     bandHistory, BandColor)
                    LiveXAxis()
                }

                // Phase history — all epochs, scrollable left/right
                ChartCard("Phase history") {
                    PhaseLegendRow()
                    Spacer(Modifier.height(6.dp))
                    EpochScrollableChart(
                        epochs = allEpochs,
                        chartHeight = 56.dp,
                    ) { eps ->
                        drawPhaseHistory(eps)
                    }
                }

                // Snore events timeline — all epochs, scrollable left/right
                ChartCard("Snore events") {
                    val count = allEpochs.count { it.hasSnore }
                    Text(
                        text = if (count == 0) "No snoring detected yet"
                               else "$count event${if (count > 1) "s" else ""} detected",
                        color = if (count > 0) SnoreEventColor else DimTextColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    EpochScrollableChart(
                        epochs = allEpochs,
                        chartHeight = 36.dp,
                    ) { eps ->
                        drawSnoreEvents(eps)
                    }
                }

                // Audio intensity — per-epoch RMS, scrollable left/right
                ChartCard("Audio intensity") {
                    EpochScrollableChart(
                        epochs = allEpochs,
                        chartHeight = 60.dp,
                    ) { eps ->
                        drawAudioIntensity(eps)
                    }
                }

                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(12.dp))
            SnoreIndicator(hasSnore)
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { vm.stopTracking(); onStop(vm.sessionId) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
            ) {
                Text("Stop Tracking", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ── Chart card shell ─────────────────────────────────────────────────────────

@Composable
private fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardColor)
            .padding(16.dp),
    ) {
        Text(title, color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// ── Live signal graphs ────────────────────────────────────────────────────────

@Composable
private fun MiniSignalGraph(label: String, data: List<Float>, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(10.dp, 3.dp)) { drawRect(color) }
        Text(label, color = color.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(4.dp))
    Canvas(modifier = Modifier.fillMaxWidth().height(55.dp)) {
        val w = size.width
        val h = size.height
        val smoothed = smooth(data)

        drawLine(GridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1f)

        if (smoothed.size < 2) return@Canvas

        val sorted = smoothed.sorted()
        val pIdx = (sorted.size * 0.95f).toInt().coerceAtMost(sorted.lastIndex)
        val maxVal = sorted[pIdx].coerceAtLeast(1e-6f)
        val xStep = w / (GRAPH_SLOTS - 1)
        val offset = GRAPH_SLOTS - smoothed.size

        val path = Path()
        smoothed.forEachIndexed { i, v ->
            val x = (offset + i) * xStep
            val y = h * (1f - (v / maxVal).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Shared x-axis label row for the 3 live-signal charts (fixed 3-min window). */
@Composable
private fun LiveXAxis() {
    Spacer(Modifier.height(2.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("3 min ago", "2 min ago", "1 min ago", "now").forEach { label ->
            Text(label, color = DimTextColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun smooth(data: List<Float>, window: Int = SMOOTH_WIN): List<Float> {
    if (data.size < 2) return data
    return data.mapIndexed { i, _ ->
        data.subList(maxOf(0, i - window + 1), i + 1).average().toFloat()
    }
}

// ── Epoch scrollable chart container ─────────────────────────────────────────

/**
 * A horizontally-scrollable canvas for epoch-based charts.
 * The canvas auto-scrolls to the right edge as new epochs arrive.
 * Each epoch occupies [EPOCH_W_DP] dp; content fills the available width
 * when there are too few epochs to scroll.
 * An x-axis with clock-time labels is drawn below the chart.
 */
@Composable
private fun EpochScrollableChart(
    epochs: List<SleepEpoch>,
    chartHeight: Dp,
    draw: DrawScope.(epochs: List<SleepEpoch>) -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(epochs.size) { scrollState.animateScrollTo(scrollState.maxValue) }

    val textSizePx = with(LocalDensity.current) { 10.sp.toPx() }
    val axisTextPaint = remember(textSizePx) {
        Paint().apply {
            color = Color(0x88FFFFFF).toArgb()
            textSize = textSizePx
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val contentWidth: Dp = (EPOCH_W_DP * epochs.size).dp.coerceAtLeast(maxWidth)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        ) {
            Column(Modifier.width(contentWidth)) {
                Canvas(Modifier.width(contentWidth).height(chartHeight)) {
                    draw(epochs)
                }
                // X-axis: clock-time labels every LABEL_EVERY_N epochs
                Canvas(Modifier.width(contentWidth).height(20.dp)) {
                    if (epochs.isEmpty()) return@Canvas
                    val epochW = size.width / epochs.size
                    epochs.forEachIndexed { i, epoch ->
                        if (i % LABEL_EVERY_N == 0) {
                            drawContext.canvas.nativeCanvas.drawText(
                                epochClockLabel(epoch.timestamp),
                                (i + 0.5f) * epochW,
                                textSizePx + 2f,
                                axisTextPaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Epoch draw functions (called inside DrawScope) ────────────────────────────

private fun DrawScope.drawPhaseHistory(epochs: List<SleepEpoch>) {
    if (epochs.isEmpty()) return
    val epochW  = size.width / epochs.size
    val rowH    = size.height / 3f
    val phaseRow = mapOf(SleepPhase.AWAKE to 0, SleepPhase.LIGHT to 1, SleepPhase.DEEP to 2)

    epochs.forEachIndexed { i, epoch ->
        val x   = i * epochW
        val row = phaseRow[epoch.phase] ?: 0
        drawRect(epoch.phase.color, topLeft = Offset(x, row * rowH), size = Size(epochW, rowH))
        if (epoch.hasSnore) {
            drawRect(Color(0x55FF4081), topLeft = Offset(x, 0f), size = Size(epochW, size.height))
        }
    }
    // Row dividers
    for (r in 1..2) {
        drawLine(Color(0x22FFFFFF), Offset(0f, r * rowH), Offset(size.width, r * rowH), 1f)
    }
}

private fun DrawScope.drawSnoreEvents(epochs: List<SleepEpoch>) {
    if (epochs.isEmpty()) return
    val epochW = size.width / epochs.size
    val midY   = size.height / 2f
    val bigR   = 7.dp.toPx()
    val smallR = bigR * 0.4f

    drawLine(Color(0x22FFFFFF), Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1.5f)

    epochs.forEachIndexed { i, epoch ->
        val cx = (i + 0.5f) * epochW
        if (epoch.hasSnore) {
            drawCircle(SnoreEventColor, radius = bigR,   center = Offset(cx, midY))
        } else {
            drawCircle(Color(0x33FFFFFF), radius = smallR, center = Offset(cx, midY))
        }
    }
}

private fun DrawScope.drawAudioIntensity(epochs: List<SleepEpoch>) {
    if (epochs.isEmpty()) return
    val epochW  = size.width / epochs.size
    val maxE    = epochs.maxOf { it.rmsEnergy }.coerceAtLeast(1e-6f)
    val barW    = (epochW * 0.65f).coerceAtLeast(2f)

    drawLine(GridColor, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), 1f)

    epochs.forEachIndexed { i, epoch ->
        val barH = (epoch.rmsEnergy / maxE) * size.height
        val x    = i * epochW + (epochW - barW) / 2f
        drawRect(
            color    = RmsColor.copy(alpha = 0.75f),
            topLeft  = Offset(x, size.height - barH),
            size     = Size(barW, barH),
        )
    }
}

// ── Legends & badges ─────────────────────────────────────────────────────────

@Composable
private fun PhaseLegendRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SleepPhase.entries.forEach { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Canvas(Modifier.size(10.dp, 8.dp)) { drawRect(p.color) }
                Text(p.label, color = DimTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Canvas(Modifier.size(10.dp, 8.dp)) { drawRect(SnoreEventColor.copy(alpha = 0.5f)) }
            Text("Snore", color = DimTextColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PhaseBadge(phase: SleepPhase) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(phase.color.copy(alpha = 0.25f))
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            text = phase.label.uppercase(),
            color = phase.color,
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun SnoreIndicator(active: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (active) Color(0x44E53935) else Color(0x11FFFFFF),
        animationSpec = tween(400), label = "snore-bg",
    )
    val textColor by animateColorAsState(
        targetValue = if (active) Color(0xFFFF5252) else Color(0x66FFFFFF),
        animationSpec = tween(400), label = "snore-text",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = if (active) "Snore detected" else "No snoring",
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

private fun epochClockLabel(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    return "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

private fun formatElapsed(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
