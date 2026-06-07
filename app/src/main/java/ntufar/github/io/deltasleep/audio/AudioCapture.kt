package ntufar.github.io.deltasleep.audio

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

private const val SAMPLE_RATE = 16_000
private const val FRAME_SAMPLES = 160  // 10 ms at 16 kHz

/**
 * Emits 160-sample (10 ms) audio frames from the microphone.
 *
 * Uses VOICE_RECOGNITION source + USAGE_UNKNOWN so the session stays in
 * mix-with-others mode — Spotify/Audible continue playing uninterrupted.
 * Raw PCM is never stored; frames are forwarded directly to DspBridge.
 */
class AudioCapture {
    @SuppressLint("MissingPermission")
    fun frames(): Flow<ShortArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, FRAME_SAMPLES * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )

        try {
            recorder.startRecording()
            val buf = ShortArray(FRAME_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buf, 0, FRAME_SAMPLES)
                if (read > 0) emit(buf.copyOf(read))
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)
}
