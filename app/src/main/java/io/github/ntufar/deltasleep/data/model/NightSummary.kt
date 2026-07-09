package io.github.ntufar.deltasleep.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Computed nightly summary for a sleep session.
 *
 * Populated by NightSummarizer after session end (or retroactively).
 * Sessions with signalQuality == LOW are excluded from the ≥5-night risk trending.
 *
 * Table: night_summary
 */
@Entity(
    tableName = "night_summary",
    foreignKeys = [
        ForeignKey(
            entity = SleepSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class NightSummary(
    /** Same value as the parent SleepSession.id — one summary per session. */
    @PrimaryKey val sessionId: Long,
    /** Total non-AWAKE sleep time in minutes. */
    val totalSleepTimeMin: Int,
    /**
     * Respiratory Event Index – acoustic: APNEA_LIKE events per hour of sleep.
     * Hypopnea-like events are tracked separately (hypopneaLikeCount) per open question 1.
     */
    val reiA: Float,
    val apneaLikeCount: Int,
    val hypopneaLikeCount: Int,
    /** Duration of the longest APNEA_LIKE or HYPOPNEA_LIKE event in seconds. */
    val longestEventS: Float,
    /** Percentage of non-AWAKE epochs that contain a snore (0–100). */
    val snorePctOfSleep: Float,
    /** Mean dB-over-noise-floor for SNORE_EPISODE events in this session. */
    val meanSnoreDbOverFloor: Float,
    val signalQuality: SignalQuality,
    /** Per-night acoustic band derived from reiA (FR-5.1). */
    val acousticBand: AcousticBand,
)
