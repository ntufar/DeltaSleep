package io.github.ntufar.deltasleep.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionnaireResultDao {
    @Insert
    suspend fun insert(result: QuestionnaireResult): Long

    /** Latest questionnaire entry (for pre-filling the form). */
    @Query("SELECT * FROM questionnaire_result ORDER BY dateUtc DESC LIMIT 1")
    suspend fun getLatest(): QuestionnaireResult?

    @Query("SELECT * FROM questionnaire_result ORDER BY dateUtc DESC")
    fun observeAll(): Flow<List<QuestionnaireResult>>

    /** Individual erasure of a single entry (FR-3.3). */
    @Query("DELETE FROM questionnaire_result WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM questionnaire_result")
    suspend fun deleteAll()
}
