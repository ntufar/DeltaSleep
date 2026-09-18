package io.github.ntufar.deltasleep.data.model

import androidx.compose.ui.graphics.Color

enum class SleepPhase(val label: String, val color: Color) {
    AWAKE("Awake", Color(0xFFE53935)),
    LIGHT("Light", Color(0xFF42A5F5)),
    DEEP("Deep",  Color(0xFF1565C0)),
    /**
     * REM sleep, heuristic estimate from the DSP (still + irregular
     * breathing, A-1). Appended last so the stored ordinals of
     * AWAKE/LIGHT/DEEP are unchanged; phase value 3 in `sleep_epochs`.
     * Labelled "estimated" until validated (A-2); PRD color is purple.
     */
    REM("REM (est.)", Color(0xFF9C27B0)),
}
