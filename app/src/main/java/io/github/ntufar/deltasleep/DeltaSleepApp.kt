package io.github.ntufar.deltasleep

import android.app.Application
import io.github.ntufar.deltasleep.data.db.AppDatabase
import io.github.ntufar.deltasleep.settings.RetentionPolicy
import io.github.ntufar.deltasleep.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DeltaSleepApp : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // C-2: retention purge on app start (the other purge point is
        // session stop in SleepTrackingService).
        scope.launch {
            val store = SettingsStore(this@DeltaSleepApp)
            RetentionPolicy.purgeExpired(
                database,
                System.currentTimeMillis(),
                store.retention,
            )
        }
    }
}
