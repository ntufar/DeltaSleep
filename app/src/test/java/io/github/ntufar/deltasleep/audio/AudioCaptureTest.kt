package io.github.ntufar.deltasleep.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class AudioCaptureTest {

    @Test fun errorCodes_throwInsteadOfMasking() {
        // AudioRecord.ERROR_BAD_VALUE (-2) and ERROR (-1).
        assertThrows(IOException::class.java) { bufferSizeFor(-2) }
        assertThrows(IOException::class.java) { bufferSizeFor(-1) }
        assertThrows(IOException::class.java) { bufferSizeFor(0) }
    }

    @Test fun validMinimum_usesAtLeastOneFrame() {
        assertEquals(160 * 2, bufferSizeFor(100))
        assertEquals(4096, bufferSizeFor(4096))
    }
}
