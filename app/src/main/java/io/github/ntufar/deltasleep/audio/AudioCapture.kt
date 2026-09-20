package io.github.ntufar.deltasleep.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException

private const val SAMPLE_RATE = 16_000
private const val FRAME_SAMPLES = 160  // 10 ms at 16 kHz
// 200 consecutive all-zero frames (2 s) means Android revoked mic access — throw so the
// service can restart the recorder rather than silently recording silence as DEEP sleep.
private const val SILENT_FRAME_LIMIT = 200

/**
 * Emits 160-sample (10 ms) audio frames from the microphone.
 *
 * Uses VOICE_RECOGNITION source + USAGE_UNKNOWN so the session stays in
 * mix-with-others mode — Spotify/Audible continue playing uninterrupted.
 * Raw PCM is never stored; frames are forwarded directly to DspBridge.
 */
/**
 * Resolve the native recorder buffer size, rejecting [AudioRecord] error
 * codes instead of masking them. Pure function so the contract is
 * unit-testable without the microphone.
 *
 * @throws IOException when [minBuf] is an [AudioRecord] error
 *         ([AudioRecord.ERROR_BAD_VALUE]/[AudioRecord.ERROR]).
 */
internal fun bufferSizeFor(minBuf: Int): Int {
    if (minBuf <= 0) throw IOException("AudioRecord.getMinBufferSize failed: $minBuf")
    return maxOf(minBuf, FRAME_SAMPLES * 2)
}

class AudioCapture {
    @SuppressLint("MissingPermission")
    fun frames(): Flow<ShortArray> = flow {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeFor(
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            ),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            throw IOException("AudioRecord not initialized (state=${recorder.state})")
        }

        try {
            recorder.startRecording()
            val buf = ShortArray(FRAME_SAMPLES)
            var silentFrames = 0
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buf, 0, FRAME_SAMPLES)
                if (read <= 0) throw IOException("AudioRecord.read() returned $read")
                val allZero = buf.take(read).all { it == 0.toShort() }
                if (allZero) {
                    if (++silentFrames >= SILENT_FRAME_LIMIT)
                        throw IOException("Mic returned silence for $SILENT_FRAME_LIMIT frames; access likely revoked")
                } else {
                    silentFrames = 0
                }
                emit(buf.copyOf(read))
            }
        } finally {
            // Guarded teardown: stop() throws IllegalStateException when the
            // recorder never started, which must never mask the real failure.
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            runCatching { recorder.release() }
        }
    }.flowOn(Dispatchers.IO)
}
