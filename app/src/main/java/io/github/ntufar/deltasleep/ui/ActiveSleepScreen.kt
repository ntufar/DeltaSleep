package io.github.ntufar.deltasleep.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.viewmodel.LiveSleepViewModel
import java.util.concurrent.TimeUnit

private val BackgroundColor = Color(0xFF0A0E14)
private val CardColor       = Color(0xFF141A23)
private val GridColor       = Color(0x22FFFFFF)
private val RmsColor        = Color(0xFF00E676)
private val ZcrColor        = Color(0xFF40C4FF)
private val BandColor       = Color(0xFFFFAB40)
private val SnoreEventColor = Color(0xFFFF4081)

private const val GRAPH_SLOTS  = 60f   // 60 s live signal window
private const val EPOCH_SLOTS  = 40    // 20 min epoch window
private const val SMOOTH_WIN   = 5

@Composable
fun ActiveSleepScreen(
    onStop: (Long) -> Unit,
    vm: LiveSleepViewModel = viewModel(),
) {
    val rmsHistory    by vm.rmsHistory.collectAsState()
    val zcrHistory    by vm.zcrHistory.collectAsState()
    val bandHistory   by vm.bandHistory.collectAsState()
    val phase         by vm.currentPhase.collectAsState()
    val hasSnore      by vm.hasRecentSnore.collectAsState()
    val elapsedMs     by vm.elapsedMs.collectAsState()
    val recentEpochs  by vm.recentEpochs.collectAsState()

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

            // Scrollable chart stack
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ── Live signal graphs ──────────────────────────────────────────
                ChartCard("Live signal — last 60 s") {
                    MiniSignalGraph("Audio level",   rmsHistory,  RmsColor)
                    Spacer(Modifier.height(6.dp))
                    MiniSignalGraph("Zero-crossing", zcrHistory,  ZcrColor)
                    Spacer(Modifier.height(6.dp))
                    MiniSignalGraph("Snore band",    bandHistory, BandColor)
                }

                // ── Phase history ───────────────────────────────────────────────
                ChartCard("Phase history — last 20 min") {
                    PhaseHistoryChart(recentEpochs)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SleepPhase.entries.forEach { p ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Canvas(Modifier.size(10.dp, 8.dp)) { drawRect(p.color) }
                                Text(p.label, color = Color(0xAAFFFFFF),
                                     style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // ── Snore events ────────────────────────────────────────────────
                ChartCard("Snore events — last 20 min") {
                    SnoreEventTimeline(recentEpochs)
                }

                // ── Epoch energy ────────────────────────────────────────────────
                ChartCard("Epoch energy — last 20 min") {
                    EpochEnergyChart(recentEpochs)
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

// ── Live signal (per-frame) ──────────────────────────────────────────────────

@Composable
private fun MiniSignalGraph(label: String, data: List<Float>, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

        val xStep  = w / (GRAPH_SLOTS - 1)
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

private fun smooth(data: List<Float>, window: Int = SMOOTH_WIN): List<Float> {
    if (data.size < 2) return data
    return data.mapIndexed { i, _ ->
        data.subList(maxOf(0, i - window + 1), i + 1).average().toFloat()
    }
}

// ── Epoch-based charts ───────────────────────────────────────────────────────

@Composable
private fun PhaseHistoryChart(epochs: List<SleepEpoch>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(52.dp)) {
        val slotW     = size.width / EPOCH_SLOTS
        val empty     = EPOCH_SLOTS - epochs.size
        val rowCount  = 3f  // AWAKE / LIGHT / DEEP
        val rowH      = size.height / rowCount
        val phaseRow  = mapOf(SleepPhase.AWAKE to 0, SleepPhase.LIGHT to 1, SleepPhase.DEEP to 2)

        // Empty leading space
        if (empty > 0) {
            drawRect(Color(0x11FFFFFF), topLeft = Offset(0f, 0f),
                     size = Size(empty * slotW, size.height))
        }

        epochs.forEachIndexed { i, epoch ->
            val x   = (empty + i) * slotW
            val row = phaseRow[epoch.phase] ?: 0
            drawRect(epoch.phase.color,
                     topLeft = Offset(x, row * rowH),
                     size = Size(slotW, rowH))
            if (epoch.hasSnore) {
                drawRect(Color(0x55FF4081),
                         topLeft = Offset(x, 0f),
                         size = Size(slotW, size.height))
            }
        }

        // Row dividers
        for (r in 1 until rowCount.toInt()) {
            drawLine(Color(0x22FFFFFF), Offset(0f, r * rowH), Offset(size.width, r * rowH), 1f)
        }
    }
}

@Composable
private fun SnoreEventTimeline(epochs: List<SleepEpoch>) {
    Text(
        text = if (epochs.any { it.hasSnore }) "Snore detected in highlighted epochs"
               else "No snoring detected yet",
        color = Color(0x77FFFFFF),
        style = MaterialTheme.typography.labelSmall,
    )
    Spacer(Modifier.height(6.dp))
    Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
        val slotW  = size.width / EPOCH_SLOTS
        val empty  = EPOCH_SLOTS - epochs.size
        val midY   = size.height / 2f
        val dotR   = 7.dp.toPx()

        // Timeline baseline
        drawLine(Color(0x22FFFFFF), Offset(0f, midY), Offset(size.width, midY), 1.5f)

        epochs.forEachIndexed { i, epoch ->
            val cx = (empty + i + 0.5f) * slotW
            if (epoch.hasSnore) {
                drawCircle(SnoreEventColor, radius = dotR, center = Offset(cx, midY))
            } else {
                drawCircle(Color(0x33FFFFFF), radius = dotR * 0.45f, center = Offset(cx, midY))
            }
        }
    }
}

@Composable
private fun EpochEnergyChart(epochs: List<SleepEpoch>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(60.dp)) {
        if (epochs.isEmpty()) return@Canvas
        val slotW   = size.width / EPOCH_SLOTS
        val empty   = EPOCH_SLOTS - epochs.size
        val maxE    = epochs.maxOf { it.rmsEnergy }.coerceAtLeast(1e-6f)
        val barW    = (slotW * 0.65f).coerceAtLeast(2f)

        // Grid at 50%
        drawLine(GridColor, Offset(0f, size.height * 0.5f),
                 Offset(size.width, size.height * 0.5f), 1f)

        epochs.forEachIndexed { i, epoch ->
            val barH = (epoch.rmsEnergy / maxE) * size.height
            val x    = (empty + i) * slotW + (slotW - barW) / 2f
            drawRect(
                color    = RmsColor.copy(alpha = 0.75f),
                topLeft  = Offset(x, size.height - barH),
                size     = Size(barW, barH),
            )
        }
    }
}

// ── Misc composables ─────────────────────────────────────────────────────────

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

private fun formatElapsed(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
