package io.github.ntufar.deltasleep.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepEpochDao {
    @Insert
    suspend fun insert(epoch: SleepEpoch)

    @Query("SELECT * FROM sleep_epochs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getForSession(sessionId: Long): List<SleepEpoch>

    @Query("SELECT * FROM sleep_epochs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeForSession(sessionId: Long): Flow<List<SleepEpoch>>

    @Query("DELETE FROM sleep_epochs")
    suspend fun deleteAll()
}
