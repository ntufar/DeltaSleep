package io.github.ntufar.deltasleep.export

import android.content.Context
import android.net.Uri
import io.github.ntufar.deltasleep.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter

/**
 * Exports sleep data to CSV via a URI obtained from the system file picker
 * (ACTION_CREATE_DOCUMENT). The URI is passed in by the caller; this class
 * only writes to it.
 *
 * CSV layout:
 *   1. Epoch rows (existing format + two new columns at the end):
 *      session_id, start_time_ms, end_time_ms, epoch_timestamp_ms,
 *      phase, has_snore, rms_energy, breathing_margin_db, breathing_present_fraction
 *
 *   2. (blank line)
 *      # acoustic_events
 *      id, session_id, type, start_utc_ms, duration_ms, confidence,
 *      peak_db_over_floor, envelope_reduction_pct, terminated_by_gasp, mean_db_over_floor
 *
 *   3. (blank line)
 *      # night_summary
 *      session_id, total_sleep_time_min, rei_a, apnea_like_count, hypopnea_like_count,
 *      longest_event_s, snore_pct_of_sleep, mean_snore_db_over_floor, signal_quality, acoustic_band
 */
object CsvExporter {
    suspend fun export(context: Context, uri: Uri, sessionId: Long) =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val session = db.sessionDao().getById(sessionId) ?: return@withContext
            val epochs = db.epochDao().getForSession(sessionId)
            val events = db.acousticEventDao().getForSession(sessionId)
            val summary = db.nightSummaryDao().getBySession(sessionId)

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                PrintWriter(stream).use { writer ->
                    // ── Section 1: Epochs ──────────────────────────────────────────────
                    writer.println(
                        "session_id,start_time_ms,end_time_ms,epoch_timestamp_ms," +
                        "phase,has_snore,rms_energy," +
                        "breathing_margin_db,breathing_present_fraction"
                    )
                    for (epoch in epochs) {
                        writer.println(
                            "${session.id},${session.startTime},${session.endTime ?: ""}," +
                            "${epoch.timestamp},${epoch.phase.name},${epoch.hasSnore}," +
                            "${epoch.rmsEnergy}," +
                            "${epoch.breathingMarginDb},${epoch.breathingPresentFraction}"
                        )
                    }

                    // ── Section 2: Acoustic events ─────────────────────────────────────
                    writer.println()
                    writer.println("# acoustic_events")
                    writer.println(
                        "id,session_id,type,start_utc_ms,duration_ms,confidence," +
                        "peak_db_over_floor,envelope_reduction_pct,terminated_by_gasp,mean_db_over_floor"
                    )
                    for (event in events) {
                        writer.println(
                            "${event.id},${event.sessionId},${event.type.name}," +
                            "${event.startUtc},${event.durationMs},${event.confidence}," +
                            "${event.peakDbOverFloor},${event.envelopeReductionPct}," +
                            "${event.terminatedByGasp},${event.meanDbOverFloor}"
                        )
                    }

                    // ── Section 3: Night summary ───────────────────────────────────────
                    writer.println()
                    writer.println("# night_summary")
                    writer.println(
                        "session_id,total_sleep_time_min,rei_a,apnea_like_count," +
                        "hypopnea_like_count,longest_event_s,snore_pct_of_sleep," +
                        "mean_snore_db_over_floor,signal_quality,acoustic_band"
                    )
                    if (summary != null) {
                        writer.println(
                            "${summary.sessionId},${summary.totalSleepTimeMin}," +
                            "${summary.reiA},${summary.apneaLikeCount}," +
                            "${summary.hypopneaLikeCount},${summary.longestEventS}," +
                            "${summary.snorePctOfSleep},${summary.meanSnoreDbOverFloor}," +
                            "${summary.signalQuality.name},${summary.acousticBand.name}"
                        )
                    }
                }
            }
        }
}
