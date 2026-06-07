package io.github.ntufar.deltasleep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.ntufar.deltasleep.MainActivity
import io.github.ntufar.deltasleep.R
import io.github.ntufar.deltasleep.audio.AudioCapture
import io.github.ntufar.deltasleep.audio.DspBridge
import io.github.ntufar.deltasleep.audio.EpochProcessor
import io.github.ntufar.deltasleep.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SleepTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dsp = DspBridge()
    private val capture = AudioCapture()
    private lateinit var processor: EpochProcessor
    private var sessionId: Long = -1L

    override fun onCreate() {
        super.onCreate()
        processor = EpochProcessor(dsp)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                startForeground(NOTIF_ID, buildNotification())
                startCapture()
                _isTracking.value = true
                _activeSessionId.value = sessionId
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startCapture() {
        val db = AppDatabase.getInstance(this)
        scope.launch {
            var emitCounter = 0
            capture.frames().collect { frame ->
                processor.onFrame(frame)?.let { epoch ->
                    db.epochDao().insert(epoch.copy(sessionId = sessionId))
                }
                emitCounter++
                // Emit live metrics at ~1 Hz (every 100 frames × 10 ms = 1 s)
                if (emitCounter >= 100) {
                    emitCounter = 0
                    val m = processor.lastFrameMetrics
                    _liveFrame.value = LiveFrame(rms = m[0], zcr = m[1], bandRatio = m[2])
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

        data class LiveFrame(val rms: Float, val zcr: Float, val bandRatio: Float)

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking

        private val _activeSessionId = MutableStateFlow(-1L)
        val activeSessionId: StateFlow<Long> = _activeSessionId

        private val _liveFrame = MutableStateFlow<LiveFrame?>(null)
        val liveFrame: StateFlow<LiveFrame?> = _liveFrame
    }
}
