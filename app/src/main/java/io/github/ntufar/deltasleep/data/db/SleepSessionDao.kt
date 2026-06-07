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

    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAll()
}
