package io.github.ntufar.deltasleep.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.ntufar.deltasleep.data.model.AcousticEvent
import io.github.ntufar.deltasleep.data.model.NightSummary
import io.github.ntufar.deltasleep.data.model.QuestionnaireResult
import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepSession

@Database(
    entities = [
        SleepSession::class,
        SleepEpoch::class,
        AcousticEvent::class,
        NightSummary::class,
        QuestionnaireResult::class,
    ],
    version = 2,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SleepSessionDao
    abstract fun epochDao(): SleepEpochDao
    abstract fun acousticEventDao(): AcousticEventDao
    abstract fun nightSummaryDao(): NightSummaryDao
    abstract fun questionnaireResultDao(): QuestionnaireResultDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Migration 1 → 2: adds apnea-screening tables and two new columns to sleep_epochs.
         *
         * Uses ALTER TABLE … ADD COLUMN with DEFAULT 0 so existing rows get a safe default
         * without a full table rebuild.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // New columns on sleep_epochs (breathingMarginDb, breathingPresentFraction)
                db.execSQL(
                    "ALTER TABLE sleep_epochs ADD COLUMN breathingMarginDb REAL NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE sleep_epochs ADD COLUMN breathingPresentFraction REAL NOT NULL DEFAULT 0"
                )

                // New table: acoustic_event
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `acoustic_event` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `type` INTEGER NOT NULL,
                        `startUtc` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `peakDbOverFloor` REAL NOT NULL,
                        `envelopeReductionPct` REAL NOT NULL,
                        `terminatedByGasp` INTEGER NOT NULL,
                        `meanDbOverFloor` REAL NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `sleep_sessions`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_acoustic_event_sessionId` ON `acoustic_event` (`sessionId`)"
                )

                // New table: night_summary
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `night_summary` (
                        `sessionId` INTEGER PRIMARY KEY NOT NULL,
                        `totalSleepTimeMin` INTEGER NOT NULL,
                        `reiA` REAL NOT NULL,
                        `apneaLikeCount` INTEGER NOT NULL,
                        `hypopneaLikeCount` INTEGER NOT NULL,
                        `longestEventS` REAL NOT NULL,
                        `snorePctOfSleep` REAL NOT NULL,
                        `meanSnoreDbOverFloor` REAL NOT NULL,
                        `signalQuality` INTEGER NOT NULL,
                        `acousticBand` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `sleep_sessions`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // New table: questionnaire_result
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `questionnaire_result` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dateUtc` INTEGER NOT NULL,
                        `snoring` INTEGER NOT NULL,
                        `tiredness` INTEGER NOT NULL,
                        `observedApnea` INTEGER NOT NULL,
                        `highPressure` INTEGER NOT NULL,
                        `bmiOver35` INTEGER NOT NULL,
                        `ageOver50` INTEGER NOT NULL,
                        `neckOver40cm` INTEGER NOT NULL,
                        `maleGender` INTEGER NOT NULL,
                        `score` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "deltasleep.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
