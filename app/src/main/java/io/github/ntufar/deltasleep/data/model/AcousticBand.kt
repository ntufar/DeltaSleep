package io.github.ntufar.deltasleep.data.model

/**
 * Per-night acoustic banding derived from the Respiratory Event Index – acoustic
 * (REI-a), which is the count of APNEA_LIKE events per hour of non-AWAKE sleep.
 *
 * Thresholds mirror the clinical AHI severity categories (FR-5.1):
 * - NONE     : REI-a < 5
 * - MILD     : 5 ≤ REI-a < 15
 * - MODERATE : 15 ≤ REI-a < 30
 * - SEVERE   : REI-a ≥ 30
 *
 * UI wording MUST say "in the range associated with mild/moderate/severe OSA",
 * never a diagnosis.
 */
enum class AcousticBand {
    NONE,
    MILD,
    MODERATE,
    SEVERE,
}
