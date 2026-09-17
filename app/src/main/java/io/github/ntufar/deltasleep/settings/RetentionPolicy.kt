package io.github.ntufar.deltasleep.settings

import io.github.ntufar.deltasleep.data.db.AppDatabase

/**
 * Data-retention auto-delete (C-2), driven by the D-3 [Retention] setting.
 *
 * No scheduler needed: [purgeExpired] runs on app start and on session stop,
 * deleting finished sessions (and standalone questionnaire rows) older than
 * the retention window. Deletion flows to epochs/events/summaries via
 * ON DELETE CASCADE; a VACUUM follows so freed pages leave the file, the
 * same story as the nuke path. In-progress sessions are never purged.
 */
object RetentionPolicy {
    private const val DAY_MS = 86_400_000L

    /**
     * Cutoff timestamp (ms): rows ending before this expire.
     * Null when [retention] is [Retention.NEVER].
     */
    fun cutoffMs(nowMs: Long, retention: Retention): Long? {
        val days = retention.toDays() ?: return null
        return nowMs - days * DAY_MS
    }

    /**
     * Delete everything older than the retention window.
     *
     * @return sessions removed (questionnaire rows are counted separately
     * and not included — sessions are the unit the UI previews).
     */
    suspend fun purgeExpired(db: AppDatabase, nowMs: Long, retention: Retention): Int {
        val cutoff = cutoffMs(nowMs, retention) ?: return 0
        val removed = db.sessionDao().deleteEndedBefore(cutoff)
        db.questionnaireResultDao().deleteOlderThan(cutoff)
        if (removed > 0) {
            db.openHelper.writableDatabase.execSQL("VACUUM")
        }
        return removed
    }

    /** Settings-screen preview: finished sessions the next purge would remove. */
    suspend fun countExpiring(db: AppDatabase, nowMs: Long, retention: Retention): Int {
        val cutoff = cutoffMs(nowMs, retention) ?: return 0
        return db.sessionDao().countEndedBefore(cutoff)
    }
}
