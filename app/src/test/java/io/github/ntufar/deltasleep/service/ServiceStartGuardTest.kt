package io.github.ntufar.deltasleep.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartGuardTest {

    @Test fun tryStart_permissionMissing_skipsStartAndReportsFalse() {
        var started = false
        val ok = ServiceStartGuard.tryStart(micGranted = false) { started = true }
        assertFalse(ok)
        assertFalse(started)
    }

    @Test fun tryStart_permissionGranted_runsStartAndReportsTrue() {
        var started = false
        val ok = ServiceStartGuard.tryStart(micGranted = true) { started = true }
        assertTrue(ok)
        assertTrue(started)
    }

    @Test fun tryStart_foregroundRefused_swallowsSecurityExceptionAndReportsFalse() {
        val ok = ServiceStartGuard.tryStart(micGranted = true) {
            throw SecurityException("Starting FGS with type microphone requires RECORD_AUDIO")
        }
        assertFalse(ok)
    }
}
