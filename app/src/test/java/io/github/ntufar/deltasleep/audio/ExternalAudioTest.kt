package io.github.ntufar.deltasleep.audio

import io.github.ntufar.deltasleep.data.model.SleepEpoch
import io.github.ntufar.deltasleep.data.model.SleepPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAudioTest {

    @Test fun dominantSpeechFraction_isExternalWithoutPlayback() {
        assertTrue(ExternalAudio.isExternal(0.8f, false))
    }

    @Test fun moderateFraction_withPlaybackConfirmation_isExternal() {
        assertTrue(ExternalAudio.isExternal(0.4f, true))
    }

    @Test fun moderateFraction_withoutPlayback_isClean() {
        assertFalse(ExternalAudio.isExternal(0.4f, false))
    }

    @Test fun zeroFraction_withPlayback_isClean_whiteNoiseAid() {
        // A white-noise sleep aid keeps isMusicActive true all night but the
        // DSP fraction stays ~0 — the night must stay included.
        assertFalse(ExternalAudio.isExternal(0f, true))
    }

    @Test fun boundary_isExclusive() {
        assertFalse(ExternalAudio.isExternal(0.5f, false))
        assertFalse(ExternalAudio.isExternal(0.3f, true))
        assertTrue(ExternalAudio.isExternal(0.51f, false))
        assertTrue(ExternalAudio.isExternal(0.31f, true))
    }

    @Test fun epochOverload_readsEpochFields() {
        val epoch = makeEpoch(0.9f, false)
        assertTrue(ExternalAudio.isExternal(epoch))
        assertFalse(ExternalAudio.isExternal(makeEpoch(0.1f, false)))
    }

    private fun makeEpoch(fraction: Float, playback: Boolean) = SleepEpoch(
        sessionId = 1L,
        timestamp = 0L,
        phase = SleepPhase.LIGHT,
        hasSnore = false,
        rmsEnergy = 0.01f,
        externalAudioFraction = fraction,
        playbackActive = playback,
    )
}
