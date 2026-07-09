package io.github.ntufar.deltasleep.data.model

/**
 * Signal quality classification for a night's recording.
 *
 * Derived from the fraction of sleep epochs where breathing margin < 6 dB
 * (breathing level – adaptive noise floor):
 * - GOOD  : < 10 % of epochs below threshold
 * - FAIR  : 10–20 % of epochs below threshold
 * - LOW   : > 20 % of epochs below threshold (night excluded from trending)
 */
enum class SignalQuality {
    GOOD,
    FAIR,
    LOW,
}
