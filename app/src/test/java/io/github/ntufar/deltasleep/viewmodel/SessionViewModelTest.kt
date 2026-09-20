package io.github.ntufar.deltasleep.viewmodel

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.data.model.SleepSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Missing navigation arguments must degrade to error/empty UI, never crash
 * in checkNotNull; failed exports must surface instead of silent success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionViewModelTest {

    private fun app(): DeltaSleepApp =
        RuntimeEnvironment.getApplication() as DeltaSleepApp

    private fun pumpUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            looper.idle()
            Thread.sleep(25)
        }
    }

    @Test fun missingSessionArg_reportsLoadFailedInsteadOfCrashing() {
        val vm = SessionViewModel(app(), SavedStateHandle())
        pumpUntil { vm.loadFailed.value }
        assertTrue("expected loadFailed for missing sessionId", vm.loadFailed.value)
    }

    @Test fun unknownSessionId_reportsLoadFailed() {
        val vm = SessionViewModel(app(), SavedStateHandle(mapOf("sessionId" to -42L)))
        pumpUntil { vm.loadFailed.value }
        assertTrue("expected loadFailed for unknown session", vm.loadFailed.value)
    }

    @Test fun exportToUnresolvableUri_reportsExportError() {
        val db = app().database
        val id = runBlocking {
            db.sessionDao().deleteAll()
            db.sessionDao().insert(SleepSession(startTime = 1_000L, endTime = 2_000L))
        }
        val vm = SessionViewModel(app(), SavedStateHandle(mapOf("sessionId" to id)))
        pumpUntil { vm.summary.value != null || vm.loadFailed.value }
        vm.exportCsv(Uri.parse("content://invalid/export.csv"))
        pumpUntil { vm.exportError.value }
        assertTrue("expected exportError for unresolvable URI", vm.exportError.value)
    }

    @Test fun liveSleepViewModel_missingArg_usesSentinelId() {
        val vm = LiveSleepViewModel(app(), SavedStateHandle())
        assertEquals(-1L, vm.sessionId)
    }
}
