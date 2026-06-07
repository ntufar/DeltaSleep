package ntufar.github.io.deltasleep.data.model

import androidx.compose.ui.graphics.Color

enum class SleepPhase(val label: String, val color: Color) {
    AWAKE("Awake", Color(0xFFE53935)),
    LIGHT("Light", Color(0xFF42A5F5)),
    DEEP("Deep",  Color(0xFF1565C0)),
}
