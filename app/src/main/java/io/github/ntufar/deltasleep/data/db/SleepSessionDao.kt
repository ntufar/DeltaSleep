package io.github.ntufar.deltasleep.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.ntufar.deltasleep.data.model.SleepSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {
    @Insert
    suspend fun insert(session: SleepSession): Long

    @Update
    suspend fun update(session: SleepSession)

    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC")
    fun observeAll(): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_sessions WHERE id = :id")
    suspend fun getById(id: Long): SleepSession?

    /**
     * Completed sessions (endTime set) starting at/after [sinceMs], oldest
     * first. Used by the trends dashboard (D-1). No explicit index on
     * startTime: the table holds at most ~365 rows (retention cap), so the
     * scan is sub-millisecond — a schema bump for an index is not worth
     * taking A-1's reserved v4 slot.
     */
    @Query(
        "SELECT * FROM sleep_sessions WHERE endTime IS NOT NULL " +
        "AND startTime >= :sinceMs ORDER BY startTime ASC"
    )
    suspend fun getCompletedSince(sinceMs: Long): List<SleepSession>

    @Query("SELECT * FROM sleep_sessions WHERE id = :id")
    fun observeById(id: Long): Flow<SleepSession?>

    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAll()

    /**
     * C-2 retention purge: delete sessions that ended before [cutoffMs].
     * Epochs, acoustic events, and night summaries follow via
     * ON DELETE CASCADE. In-progress sessions (endTime NULL) are never
     * purged — only finished nights expire.
     *
     * @return number of sessions removed.
     */
    @Query("DELETE FROM sleep_sessions WHERE endTime IS NOT NULL AND endTime < :cutoffMs")
    suspend fun deleteEndedBefore(cutoffMs: Long): Int

    /** Preview for the settings screen: finished sessions older than [cutoffMs]. */
    @Query("SELECT COUNT(*) FROM sleep_sessions WHERE endTime IS NOT NULL AND endTime < :cutoffMs")
    suspend fun countEndedBefore(cutoffMs: Long): Int
}
