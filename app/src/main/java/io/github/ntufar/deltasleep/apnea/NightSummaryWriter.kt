package io.github.ntufar.deltasleep.apnea

import io.github.ntufar.deltasleep.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a computed [NightSummary] to the database, pruning awake-phase acoustic events.
 *
 * Separated from [NightSummarizer] so the pure computation stays testable without Room.
 */
object NightSummaryWriter {

    /**
     * Read epochs and events for [sessionId] from [db], run [NightSummarizer.compute],
     * delete discarded events, and upsert the resulting summary.
     */
    suspend fun summarize(db: AppDatabase, sessionId: Long) = withContext(Dispatchers.IO) {
        val epochs = db.epochDao().getForSession(sessionId)
        val events = db.acousticEventDao().getForSession(sessionId)

        val (summary, idsToDiscard) = NightSummarizer.compute(sessionId, epochs, events)

        if (idsToDiscard.isNotEmpty()) {
            db.acousticEventDao().deleteByIds(idsToDiscard)
        }
        db.nightSummaryDao().upsert(summary)
    }
}
