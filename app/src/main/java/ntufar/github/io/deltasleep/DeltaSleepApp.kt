package ntufar.github.io.deltasleep

import android.app.Application
import ntufar.github.io.deltasleep.data.db.AppDatabase

class DeltaSleepApp : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}
