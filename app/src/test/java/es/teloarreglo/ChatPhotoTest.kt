package es.teloarreglo

import es.teloarreglo.chat.*
import org.junit.Assert.*
import org.junit.Test

class ChatPhotoTest {
    @Test fun onlyJpegAndPngContentIsAcceptedWhateverTheFileClaims() {
        assertEquals("image/jpeg", photoFormat(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/png", photoFormat(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertNull(photoFormat("GIF89a".toByteArray()))
        assertNull(photoFormat(byteArrayOf(0x52, 0x49, 0x46, 0x46))) // WebP/RIFF
        assertNull(photoFormat(ByteArray(0)))
    }

    @Test fun theLongerSideIsReducedTo1600PixelsKeepingProportions() {
        assertEquals(1600 to 1200, photoSize(4000, 3000))
        assertEquals(900 to 1600, photoSize(1800, 3200))
        assertEquals(1600 to 1600, photoSize(1601, 1601))
    }

    @Test fun aSmallerImageIsNeverEnlarged() {
        assertEquals(640 to 480, photoSize(640, 480))
        assertEquals(1600 to 10, photoSize(1600, 10))
        assertEquals(1 to 1, photoSize(1, 1))
    }

    @Test fun compressionLowersQualityUntilTheCopyFitsTwoMegabytes() {
        val tried = mutableListOf<Int>()
        val result = encodeWithinLimit { quality ->
            tried.add(quality)
            ByteArray(if (quality > 70) 3 * 1024 * 1024 else 1024 * 1024)
        }
        assertEquals(1024 * 1024, result.size)
        assertEquals(listOf(85, 75, 65), tried)
    }

    @Test fun aCopyThatCannotFitIsRefusedInsteadOfSendingTheOriginal() {
        var failure: AttachmentFailure? = null
        try { encodeWithinLimit { ByteArray(2 * 1024 * 1024 + 1) } }
        catch (error: ChatAttachmentException) { failure = error.failure }
        assertEquals(AttachmentFailure.TOO_LARGE, failure)
    }
}
