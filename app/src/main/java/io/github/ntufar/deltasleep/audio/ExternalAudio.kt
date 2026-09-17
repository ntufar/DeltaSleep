package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.SleepEpoch

/**
 * External-audio verdict (A-4: podcasts/audiobooks/TV playing while tracking).
 *
 * Two independent evidence sources combine:
 * - [SleepEpoch.externalAudioFraction]: DSP VAD speech fraction (0–1).
 *   High-precision speech evidence — speech-band energy AND syllabic-rate
 *   modulation must both hold per frame — but quiet passages dip, so the
 *   bar lowers when the OS confirms playback.
 * - [SleepEpoch.playbackActive]: `AudioManager.isMusicActive` at flush.
 *   Never a verdict by itself: a white-noise sleep aid must not exclude
 *   the whole night (its DSP fraction stays ~0, so it stays included).
 *
 * Dominant-external epochs are excluded from REI-a/snore denominators
 * (NightSummarizer) and counted as filtered minutes on the session screen.
 */
object ExternalAudio {
    /** Verdict bar without OS playback confirmation. */
    const val THRESHOLD_SPEECH = 0.5f

    /** Verdict bar when another app was rendering audio this epoch. */
    const val THRESHOLD_WITH_PLAYBACK = 0.3f

    fun isExternal(epoch: SleepEpoch): Boolean =
        isExternal(epoch.externalAudioFraction, epoch.playbackActive)

    fun isExternal(fraction: Float, playbackActive: Boolean): Boolean {
        val bar = if (playbackActive) THRESHOLD_WITH_PLAYBACK else THRESHOLD_SPEECH
        return fraction > bar
    }
}
