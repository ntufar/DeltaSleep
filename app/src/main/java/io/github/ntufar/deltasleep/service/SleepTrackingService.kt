package io.github.ntufar.deltasleep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.ntufar.deltasleep.MainActivity
import io.github.ntufar.deltasleep.R
import io.github.ntufar.deltasleep.apnea.ApneaPrefs
import io.github.ntufar.deltasleep.apnea.NightSummaryWriter
import io.github.ntufar.deltasleep.audio.AudioCapture
import io.github.ntufar.deltasleep.audio.DspBridge
import io.github.ntufar.deltasleep.audio.EpochProcessor
import io.github.ntufar.deltasleep.data.db.AppDatabase
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SleepTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dsp = DspBridge()
    private val capture = AudioCapture()
    private lateinit var processor: EpochProcessor
    private var sessionId: Long = -1L
    private var captureJob: Job? = null

    /** Wall-clock time (ms) when dsp.startSession() was last called. Used for event timestamping. */
    private var dspStartWallMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        processor = EpochProcessor(dsp)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (intent?.action) {
            ACTION_START -> {
                sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                prefs.edit().putLong(KEY_SESSION_ID, sessionId).apply()
                // Full DSP session reset; record wall time for event offset conversion
                dsp.startSession()
                dspStartWallMs = System.currentTimeMillis()
                startForeground(NOTIF_ID, buildNotification())
                startCapture()
                _isTracking.value = true
                _activeSessionId.value = sessionId
            }
            ACTION_STOP -> {
                prefs.edit().remove(KEY_SESSION_ID).apply()
                val apneaPrefs = ApneaPrefs(this)
                if (apneaPrefs.screeningEnabled && sessionId != -1L) {
                    val db = AppDatabase.getInstance(this)
                    val capturedSessionId = sessionId
                    val jobToCancel = captureJob
                    scope.launch(Dispatchers.IO) {
                        // Cancel capture first so no new epochs land in the DB
                        // after summarize begins reading.
                        jobToCancel?.cancelAndJoin()
                        NightSummaryWriter.summarize(db, capturedSessionId)
                        stopSelf()
                    }
                } else {
                    stopSelf()
                }
            }
            // null intent = system restarted the service via START_STICKY; resume capture
            null -> {
                sessionId = prefs.getLong(KEY_SESSION_ID, -1L)
                if (sessionId != -1L) {
                    // Full DSP reset on sticky-restart; previous session state is lost
                    dsp.startSession()
                    dspStartWallMs = System.currentTimeMillis()
                    startForeground(NOTIF_ID, buildNotification())
                    startCapture()
                    _isTracking.value = true
                    _activeSessionId.value = sessionId
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startCapture() {
        val db = AppDatabase.getInstance(this)
        val apneaPrefs = ApneaPrefs(this)
        captureJob = scope.launch {
            var backoffMs = 5_000L
            while (isActive) {
                try {
                    var emitCounter = 0
                    capture.frames().collect { frame ->
                        processor.onFrame(frame)?.let { result ->
                            // Insert epoch with the correct sessionId
                            db.epochDao().insert(result.epoch.copy(sessionId = sessionId))

                            // Insert acoustic events only when screening is enabled (FR-8.1)
                            if (apneaPrefs.screeningEnabled && result.events.isNotEmpty()) {
                                val wallStartMs = dspStartWallMs
                                val acousticEvents = result.events.map { parsed ->
                                    AcousticEvent(
                                        sessionId = sessionId,
                                        type = parsed.type,
                                        startUtc = wallStartMs + parsed.startOffsetMs,
                                        durationMs = parsed.durationMs,
                                        confidence = parsed.confidence,
                                        peakDbOverFloor = parsed.peakDbOverFloor,
                                        envelopeReductionPct = parsed.envelopeReductionPct,
                                        terminatedByGasp = parsed.terminatedByGasp,
                                        meanDbOverFloor = parsed.meanDbOverFloor,
                                    )
                                }
                                db.acousticEventDao().insertAll(acousticEvents)
                            }
                        }

                        emitCounter++
                        // Emit live metrics at ~0.5 Hz (every 200 frames × 10 ms = 2 s)
                        if (emitCounter >= 200) {
                            emitCounter = 0
                            val m = processor.lastFrameMetrics
                            // lastFrameMetrics is now 6 elements:
                            // [0]=rms, [1]=zcr, [2]=bandRatio, [3]=noiseFloorDb,
                            // [4]=breathingMarginDb, [5]=breathingPresent
                            _liveFrame.value = LiveFrame(
                                rms = m[0],
                                zcr = m[1],
                                bandRatio = m[2],
                                noiseFloorDb = if (m.size > 3) m[3] else 0f,
                                breathingMarginDb = if (m.size > 4) m[4] else 0f,
                                breathingPresent = if (m.size > 5) m[5] != 0f else false,
                            )
                        }
                    }
                    break  // flow completed normally — shouldn't happen during tracking
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    processor.reset()
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, 60_000L)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        processor.reset()
        _isTracking.value = false
        _activeSessionId.value = -1L
        _liveFrame.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SleepTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(getString(R.string.notif_tracking_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.ntufar.deltasleep.START"
        const val ACTION_STOP  = "io.github.ntufar.deltasleep.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "sleep_tracking"
        private const val NOTIF_ID = 1
        private const val PREFS_NAME = "sleep_service"
        private const val KEY_SESSION_ID = "active_session_id"

        /**
         * Live per-frame metrics emitted at ~0.5 Hz for the active-sleep screen.
         *
         * Existing fields (rms, zcr, bandRatio) are preserved for UI backward compatibility.
         * New fields (noiseFloorDb, breathingMarginDb, breathingPresent) power the live
         * calibration meter (FR-8.2).
         */
        data class LiveFrame(
            val rms: Float,
            val zcr: Float,
            val bandRatio: Float,
            val noiseFloorDb: Float = 0f,
            val breathingMarginDb: Float = 0f,
            val breathingPresent: Boolean = false,
        )

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking

        private val _activeSessionId = MutableStateFlow(-1L)
        val activeSessionId: StateFlow<Long> = _activeSessionId

        private val _liveFrame = MutableStateFlow<LiveFrame?>(null)
        val liveFrame: StateFlow<LiveFrame?> = _liveFrame
    }
}
