package io.github.ntufar.deltasleep

import android.app.Application
import io.github.ntufar.deltasleep.data.db.AppDatabase

class DeltaSleepApp : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
}
