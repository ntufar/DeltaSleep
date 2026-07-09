package io.github.ntufar.deltasleep.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One completed STOP-BANG questionnaire entry.
 *
 * STOP-BANG items (FR-4.1):
 * - S: Snoring
 * - T: Tiredness
 * - O: Observed apnea
 * - P: high blood Pressure / hypertension
 * - B: BMI > 35
 * - A: Age > 50
 * - N: Neck circumference > 40 cm
 * - G: male Gender
 *
 * Privacy note (open question 2): booleans only — no height, weight, age, or neck
 * measurements are stored, only the derived yes/no answers. Each row is individually
 * erasable via deleteById (FR-3.3).
 *
 * Table: questionnaire_result
 */
@Entity(tableName = "questionnaire_result")
data class QuestionnaireResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** UTC timestamp when the questionnaire was submitted (ms since epoch). */
    val dateUtc: Long,
    val snoring: Boolean,
    val tiredness: Boolean,
    val observedApnea: Boolean,
    val highPressure: Boolean,
    val bmiOver35: Boolean,
    val ageOver50: Boolean,
    val neckOver40cm: Boolean,
    val maleGender: Boolean,
    /** Computed score 0–8 (sum of true answers). */
    val score: Int,
)
