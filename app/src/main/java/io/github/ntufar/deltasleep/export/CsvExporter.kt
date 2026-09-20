package io.github.ntufar.deltasleep.export

import android.content.Context
import android.net.Uri
import io.github.ntufar.deltasleep.data.db.AppDatabase
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter

/**
 * Exports sleep data to CSV via a URI obtained from the system file picker
 * (ACTION_CREATE_DOCUMENT). The URI is passed in by the caller; this class
 * only writes to it.
 *
 * CSV layout:
 *   1. Epoch rows (new columns appended at the end, never inserted):
 *      session_id, start_time_ms, end_time_ms, epoch_timestamp_ms,
 *      phase, has_snore, rms_energy, breathing_margin_db, breathing_present_fraction,
 *      breath_period_s, external_audio_fraction, playback_active
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
    /**
     * @return true when the CSV was written; false when there was nothing to
     *         write (unknown session) or the stream could not be opened, so
     *         callers can show a failure instead of a silent success.
     */
    suspend fun export(context: Context, uri: Uri, sessionId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val session = db.sessionDao().getById(sessionId) ?: return@withContext false
            val epochs = db.epochDao().getForSession(sessionId)
            val events = db.acousticEventDao().getForSession(sessionId)
            val summary = db.nightSummaryDao().getBySession(sessionId)

            val stream = context.contentResolver.openOutputStream(uri) ?: return@withContext false
            stream.use {
                PrintWriter(it).use { writer ->
                    writer.print(buildCsv(session, epochs, events, summary))
                }
            }
            true
        }

    /** Pure CSV builder — unit-testable without Android framework. */
    internal fun buildCsv(
        session: SleepSession,
        epochs: List<SleepEpoch>,
        events: List<AcousticEvent>,
        summary: NightSummary?,
    ): String = buildString {
        // ── Section 1: Epochs ──────────────────────────────────────────────
        appendLine(
            "session_id,start_time_ms,end_time_ms,epoch_timestamp_ms," +
            "phase,has_snore,rms_energy," +
            "breathing_margin_db,breathing_present_fraction," +
            "breath_period_s,external_audio_fraction,playback_active"
        )
        for (epoch in epochs) {
            appendLine(
                "${session.id},${session.startTime},${session.endTime ?: ""}," +
                "${epoch.timestamp},${epoch.phase.name},${epoch.hasSnore}," +
                "${epoch.rmsEnergy}," +
                "${epoch.breathingMarginDb},${epoch.breathingPresentFraction}," +
                "${epoch.breathPeriodS ?: ""}," +
                "${epoch.externalAudioFraction},${epoch.playbackActive}"
            )
        }

        // ── Section 2: Acoustic events ─────────────────────────────────────
        appendLine()
        appendLine("# acoustic_events")
        appendLine(
            "id,session_id,type,start_utc_ms,duration_ms,confidence," +
            "peak_db_over_floor,envelope_reduction_pct,terminated_by_gasp,mean_db_over_floor"
        )
        for (event in events) {
            appendLine(
                "${event.id},${event.sessionId},${event.type.name}," +
                "${event.startUtc},${event.durationMs},${event.confidence}," +
                "${event.peakDbOverFloor},${event.envelopeReductionPct}," +
                "${event.terminatedByGasp},${event.meanDbOverFloor}"
            )
        }

        // ── Section 3: Night summary ───────────────────────────────────────
        appendLine()
        appendLine("# night_summary")
        appendLine(
            "session_id,total_sleep_time_min,rei_a,apnea_like_count," +
            "hypopnea_like_count,longest_event_s,snore_pct_of_sleep," +
            "mean_snore_db_over_floor,signal_quality,acoustic_band"
        )
        if (summary != null) {
            appendLine(
                "${summary.sessionId},${summary.totalSleepTimeMin}," +
                "${summary.reiA},${summary.apneaLikeCount}," +
                "${summary.hypopneaLikeCount},${summary.longestEventS}," +
                "${summary.snorePctOfSleep},${summary.meanSnoreDbOverFloor}," +
                "${summary.signalQuality.name},${summary.acousticBand.name}"
            )
        }
    }
}
