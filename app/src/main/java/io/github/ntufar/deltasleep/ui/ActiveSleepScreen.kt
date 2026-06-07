package io.github.ntufar.deltasleep.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ntufar.deltasleep.data.model.SleepPhase
import io.github.ntufar.deltasleep.viewmodel.LiveSleepViewModel
import java.util.concurrent.TimeUnit

private val BackgroundColor = Color(0xFF0A0E14)
private val GridColor = Color(0x22FFFFFF)
private val RmsColor = Color(0xFF00E676)   // green — audio level
private val ZcrColor = Color(0xFF40C4FF)   // light-blue — zero-crossing rate
private val BandColor = Color(0xFFFFAB40)  // amber — snore-band power

private const val GRAPH_SLOTS = 60f  // 60 samples × 500 ms = 30 s window
private const val SMOOTH_WINDOW = 5  // samples for moving-average smoothing

@Composable
fun ActiveSleepScreen(
    onStop: (Long) -> Unit,
    vm: LiveSleepViewModel = viewModel(),
) {
    val rmsHistory by vm.rmsHistory.collectAsState()
    val zcrHistory by vm.zcrHistory.collectAsState()
    val bandHistory by vm.bandHistory.collectAsState()
    val phase by vm.currentPhase.collectAsState()
    val hasSnore by vm.hasRecentSnore.collectAsState()
    val elapsedMs by vm.elapsedMs.collectAsState()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BackgroundColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PhaseBadge(phase)

            Spacer(Modifier.height(24.dp))

            Text(
                text = formatElapsed(elapsedMs),
                color = Color.White,
                fontSize = 56.sp,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(32.dp))

            // Three separate sensor graphs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF141A23))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MiniSignalGraph(label = "Audio level",    data = rmsHistory,  color = RmsColor)
                MiniSignalGraph(label = "Zero-crossing",  data = zcrHistory,  color = ZcrColor)
                MiniSignalGraph(label = "Snore band",     data = bandHistory, color = BandColor)
            }

            Spacer(Modifier.height(24.dp))

            SnoreIndicator(hasSnore)

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    vm.stopTracking()
                    onStop(vm.sessionId)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
            ) {
                Text("Stop Tracking", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun MiniSignalGraph(
    label: String,
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(10.dp, 3.dp)) { drawRect(color) }
            Text(label, color = color.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
        ) {
            val w = size.width
            val h = size.height
            val smoothed = smooth(data)

            // Grid — single mid-line
            drawLine(
                color = GridColor,
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1f,
            )

            if (smoothed.size < 2) return@Canvas

            // Stable y-scale: 95th-percentile max to suppress transient spikes
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
}

private fun smooth(data: List<Float>, window: Int = SMOOTH_WINDOW): List<Float> {
    if (data.size < 2) return data
    return data.mapIndexed { i, _ ->
        val from = maxOf(0, i - window + 1)
        data.subList(from, i + 1).average().toFloat()
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
        animationSpec = tween(durationMillis = 400),
        label = "snore-bg",
    )
    val textColor by animateColorAsState(
        targetValue = if (active) Color(0xFFFF5252) else Color(0x66FFFFFF),
        animationSpec = tween(durationMillis = 400),
        label = "snore-text",
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
