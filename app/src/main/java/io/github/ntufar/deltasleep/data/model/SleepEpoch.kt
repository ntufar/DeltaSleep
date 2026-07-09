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
)
