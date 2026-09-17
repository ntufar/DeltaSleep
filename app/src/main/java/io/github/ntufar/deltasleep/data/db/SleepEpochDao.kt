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

    /**
     * Per-session epoch aggregates for the trends dashboard (D-1): total
     * epochs, deep-phase epochs, and snoring epochs. One row per queried
     * session that has epochs. Pass `SleepPhase.DEEP.ordinal` as
     * [deepOrdinal] — never hardcode it in SQL.
     */
    @Query(
        "SELECT sessionId AS sessionId, COUNT(*) AS epochCount, " +
        "SUM(CASE WHEN phase = :deepOrdinal THEN 1 ELSE 0 END) AS deepCount, " +
        "SUM(CASE WHEN hasSnore THEN 1 ELSE 0 END) AS snoreCount " +
        "FROM sleep_epochs WHERE sessionId IN (:sessionIds) GROUP BY sessionId"
    )
    suspend fun getAggregates(sessionIds: List<Long>, deepOrdinal: Int): List<EpochAggregate>

    /**
     * All measured breath periods per session (A-7 remainder feeds the
     * 30-day respiratory-rate trend). NULLs (no breathing / pre-v3) excluded.
     */
    @Query(
        "SELECT sessionId AS sessionId, breathPeriodS AS breathPeriodS " +
        "FROM sleep_epochs WHERE sessionId IN (:sessionIds) AND breathPeriodS IS NOT NULL"
    )
    suspend fun getBreathPeriods(sessionIds: List<Long>): List<SessionBreathPeriod>
}

/** Per-session epoch counts for trend charts (D-1 query POJO, not an entity). */
data class EpochAggregate(
    val sessionId: Long,
    val epochCount: Int,
    val deepCount: Int,
    val snoreCount: Int,
)

/** One measured breath period belonging to a session (D-1 query POJO). */
data class SessionBreathPeriod(
    val sessionId: Long,
    val breathPeriodS: Float,
)
