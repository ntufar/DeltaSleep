package io.github.ntufar.deltasleep.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single acoustic event detected by the DSP state machine during a sleep session.
 *
 * Events are polled from the Rust side via DspBridge.pollEvents() after each epoch flush
 * and inserted here. Events whose midpoint falls within an AWAKE epoch are discarded
 * by NightSummarizer (FR-2.3).
 *
 * Table: acoustic_event
 */
@Entity(
    tableName = "acoustic_event",
    foreignKeys = [
        ForeignKey(
            entity = SleepSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class AcousticEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: AcousticEventType,
    /** Wall-clock start time (ms since epoch). */
    val startUtc: Long,
    val durationMs: Long,
    /** Classifier confidence 0–1. */
    val confidence: Float,
    /** Peak amplitude above adaptive noise floor (dB). */
    val peakDbOverFloor: Float,
    /** Fraction of envelope reduction (0–1). */
    val envelopeReductionPct: Float,
    /** Whether a gasp was detected within 5 s after the event ended. */
    val terminatedByGasp: Boolean,
    /** Mean amplitude over event duration relative to adaptive noise floor (dB). */
    val meanDbOverFloor: Float,
)
