package io.github.ntufar.deltasleep.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.SignalQuality
import kotlinx.coroutines.flow.Flow

@Dao
interface NightSummaryDao {
    /** Insert or replace (NightSummarizer may re-run after more data arrives). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: NightSummary)

    @Query("SELECT * FROM night_summary WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: Long): NightSummary?

    /**
     * Observe the most recent [limit] summaries ordered by sessionId descending.
     * Used for REI-a trending on the report screen.
     */
    @Query(
        "SELECT * FROM night_summary ORDER BY sessionId DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<NightSummary>>

    /**
     * Return the most recent [limit] GOOD or FAIR nights for risk-band computation.
     * LOW-quality nights are excluded (FR-2.4).
     */
    @Query(
        "SELECT * FROM night_summary WHERE signalQuality != :excludedQuality " +
        "ORDER BY sessionId DESC LIMIT :limit"
    )
    suspend fun getRecentGoodFairNights(
        limit: Int,
        excludedQuality: SignalQuality = SignalQuality.LOW,
    ): List<NightSummary>

    @Query("DELETE FROM night_summary")
    suspend fun deleteAll()
}
