package io.github.ntufar.deltasleep.data.model

/**
 * Headline risk band shown to the user on the report screen.
 *
 * Derived from combining the per-night acoustic band (median REI-a over ≥ 5 nights)
 * with the STOP-BANG questionnaire score band via the documented 3×3 matrix in RiskModel.
 *
 * Per R1.1.1: all UI copy must say "screening" / "risk indication", never "diagnosis".
 */
enum class RiskBand {
    LOW,
    ELEVATED,
    HIGH,
}
