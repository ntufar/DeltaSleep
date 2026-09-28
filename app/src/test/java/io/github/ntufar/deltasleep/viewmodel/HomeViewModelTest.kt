package io.github.ntufar.deltasleep.viewmodel

import android.Manifest
import android.os.Looper
import io.github.ntufar.deltasleep.DeltaSleepApp
import io.github.ntufar.deltasleep.service.SleepTrackingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression test for the Play Store ForegroundServiceDidNotStartInTimeException:
 * tapping "Start Sleep" without RECORD_AUDIO must neither insert a session row
 * nor start the tracking service — a service that can never promote (mic
 * permission missing) is exactly what the system kills for not promoting in
 * time. With the permission granted, the ACTION_START intent goes out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {

    private val app: DeltaSleepApp
        get() = RuntimeEnvironment.getApplication() as DeltaSleepApp

    @Before
    fun clearSessions() {
        runBlocking { app.database.sessionDao().deleteAll() }
    }

    private fun sessionCount(): Int =
        runBlocking { app.database.sessionDao().observeAll().first().size }

    @Test
    fun startTracking_withoutMicPermission_startsNothingAndSignalsEvent() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val vm = HomeViewModel(app)

        vm.startTracking()

        // One-shot event for the UI permission request.
        val signaled = runBlocking {
            withTimeoutOrNull(5_000) { vm.micPermissionNeeded.first() }
        }
        assertNotNull("expected micPermissionNeeded to be emitted", signaled)
        // No orphan session row and no doomed service start.
        assertEquals(0, sessionCount())
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun startTracking_withMicPermission_sendsServiceStart() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val vm = HomeViewModel(app)

        vm.startTracking()

        // viewModelScope runs on Dispatchers.Main and Room inserts on its own
        // threads — pump the main looper until the start intent lands.
        var action: String? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val next = shadowOf(app).peekNextStartedService()
            if (next != null && next.action == SleepTrackingService.ACTION_START) {
                action = next.action
                break
            }
            Thread.sleep(50)
        }
        assertEquals(SleepTrackingService.ACTION_START, action)
        assertEquals(1, sessionCount())
    }
}
