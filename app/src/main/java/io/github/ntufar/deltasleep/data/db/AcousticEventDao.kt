package io.github.ntufar.deltasleep.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface AcousticEventDao {
    @Insert
    suspend fun insert(event: AcousticEvent)

    @Insert
    suspend fun insertAll(events: List<AcousticEvent>)

    @Query("SELECT * FROM acoustic_event WHERE sessionId = :sessionId ORDER BY startUtc ASC")
    suspend fun getForSession(sessionId: Long): List<AcousticEvent>

    @Query("SELECT * FROM acoustic_event WHERE sessionId = :sessionId ORDER BY startUtc ASC")
    fun observeForSession(sessionId: Long): Flow<List<AcousticEvent>>

    /** Delete a single event by id (supports individual erasure, FR-3.3 spirit). */
    @Query("DELETE FROM acoustic_event WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Delete all events for a session (used by NightSummarizer to prune awake-phase events). */
    @Query("DELETE FROM acoustic_event WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM acoustic_event WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM acoustic_event")
    suspend fun deleteAll()
}
