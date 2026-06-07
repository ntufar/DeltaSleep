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
 * CSV columns: session_id, start_time_ms, end_time_ms, epoch_timestamp_ms,
 *              phase, has_snore, rms_energy
 */
object CsvExporter {
    suspend fun export(context: Context, uri: Uri, sessionId: Long) =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val session = db.sessionDao().getById(sessionId) ?: return@withContext
            val epochs = db.epochDao().getForSession(sessionId)

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                PrintWriter(stream).use { writer ->
                    writer.println("session_id,start_time_ms,end_time_ms,epoch_timestamp_ms,phase,has_snore,rms_energy")
                    for (epoch in epochs) {
                        writer.println(
                            "${session.id},${session.startTime},${session.endTime ?: ""}," +
                            "${epoch.timestamp},${epoch.phase.name},${epoch.hasSnore},${epoch.rmsEnergy}"
                        )
                    }
                }
            }
        }
}
