package ntufar.github.io.deltasleep.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ntufar.github.io.deltasleep.MainActivity
import ntufar.github.io.deltasleep.R
import ntufar.github.io.deltasleep.audio.AudioCapture
import ntufar.github.io.deltasleep.audio.DspBridge
import ntufar.github.io.deltasleep.audio.EpochProcessor
import ntufar.github.io.deltasleep.data.db.AppDatabase
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
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startCapture() {
        val db = AppDatabase.getInstance(this)
        scope.launch {
            capture.frames().collect { frame ->
                processor.onFrame(frame)?.let { epoch ->
                    db.epochDao().insert(epoch.copy(sessionId = sessionId))
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        processor.reset()
        _isTracking.value = false
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
        const val ACTION_START = "ntufar.github.io.deltasleep.START"
        const val ACTION_STOP  = "ntufar.github.io.deltasleep.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "sleep_tracking"
        private const val NOTIF_ID = 1

        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking
    }
}
