package io.github.ntufar.deltasleep.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_epochs",
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
data class SleepEpoch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val phase: SleepPhase,
    val hasSnore: Boolean,
    val rmsEnergy: Float,
    /**
     * Mean margin between breathing sound level and adaptive noise floor (dB) for this epoch.
     * Populated from DSP computeEpoch() index 6. Added in DB migration 1→2.
     */
    val breathingMarginDb: Float = 0f,
    /**
     * Fraction of frames within this epoch where breathing was detected as present (0–1).
     * Populated from DSP computeEpoch() index 7. Added in DB migration 1→2.
     */
    val breathingPresentFraction: Float = 0f,
    /**
     * Mean autocorrelation breath period in seconds over breathing-present frames,
     * or NULL when breathing was never present in this epoch (or the epoch was
     * recorded before DB v3). Populated from DSP computeEpoch() index 8.
     * Added in DB migration 2→3 (A-7). Displayed as breaths/min (60 / period).
     */
    val breathPeriodS: Float? = null,
    /**
     * Fraction of frames flagged speech-like by the DSP VAD (0–1):
     * speech-band energy AND syllabic-rate modulation must both hold.
     * Populated from DSP computeEpoch() index 9. Added in DB migration 3→4.
     * Combined with [playbackActive] by `ExternalAudio.isExternal`.
     */
    val externalAudioFraction: Float = 0f,
    /**
     * Whether another app was rendering audio when this epoch flushed
     * (`AudioManager.isMusicActive`). Added in DB migration 3→4 (A-4).
     * Lowers the external-audio verdict bar; never a verdict by itself
     * (a white-noise app must not nuke the whole night).
     */
    val playbackActive: Boolean = false,
)
