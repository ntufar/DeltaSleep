package io.github.ntufar.deltasleep.data.model

/** Acoustic event types emitted by the DSP state machine. */
enum class AcousticEventType {
    /** Silence/amplitude decrement ≥ 10 s between confirmed respiratory sound periods. */
    APNEA_LIKE,
    /** Partial envelope reduction (30–50 %) ≥ 10 s — not full silence. */
    HYPOPNEA_LIKE,
    /** Loud broadband burst within 5 s after a decrement — post-event arousal sound. */
    GASP,
    /** Contiguous snoring episode with start, duration, and band power. */
    SNORE_EPISODE,
}
