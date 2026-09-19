package io.github.ntufar.deltasleep.service

/**
 * Guards [android.app.Service.startForeground] for the microphone-type tracking service.
 *
 * A microphone foreground service throws [SecurityException] from `startForeground`
 * when [android.Manifest.permission.RECORD_AUDIO] is not currently granted (user denied
 * or later revoked it), and likewise when the OS refuses a background start. An
 * uncaught throw here crashes the whole process via `handleServiceArgs`, so both start
 * paths ([SleepTrackingService.ACTION_START] and the `START_STICKY` resume) go through
 * [tryStart], which reports failure instead of throwing.
 */
internal object ServiceStartGuard {

    /**
     * Runs [start] only when [micGranted] is true. Returns true when [start] ran
     * without throwing; returns false (swallowing [SecurityException]) when the mic
     * permission is missing or the foreground start is refused.
     */
    fun tryStart(micGranted: Boolean, start: () -> Unit): Boolean {
        if (!micGranted) return false
        return try {
            start()
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
