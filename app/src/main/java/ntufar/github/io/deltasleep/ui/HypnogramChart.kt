package ntufar.github.io.deltasleep.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ntufar.github.io.deltasleep.data.model.SleepEpoch
import ntufar.github.io.deltasleep.data.model.SleepPhase

private val PHASE_ROWS = mapOf(
    SleepPhase.AWAKE to 0,
    SleepPhase.LIGHT to 1,
    SleepPhase.DEEP  to 2,
)

/**
 * Draws a standard hypnogram: X=time, Y=sleep phase.
 * Snore epochs are tinted with a semi-transparent magenta overlay.
 */
@Composable
fun HypnogramChart(epochs: List<SleepEpoch>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        if (epochs.isEmpty()) return@Canvas
        val rows = PHASE_ROWS.size.toFloat()
        val rowH = size.height / rows
        val epochW = size.width / epochs.size.toFloat()

        epochs.forEachIndexed { i, epoch ->
            val row = PHASE_ROWS[epoch.phase] ?: 0
            val left = i * epochW
            val top = row * rowH

            // Phase block
            drawRect(
                color = epoch.phase.color,
                topLeft = Offset(left, top),
                size = Size(epochW, rowH),
            )

            // Snore tint
            if (epoch.hasSnore) {
                drawRect(
                    color = Color(0x55FF4081),
                    topLeft = Offset(left, 0f),
                    size = Size(epochW, size.height),
                )
            }
        }

        // Row dividers
        for (row in 1 until rows.toInt()) {
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, row * rowH),
                end = Offset(size.width, row * rowH),
                strokeWidth = 1f,
            )
        }
    }
}
