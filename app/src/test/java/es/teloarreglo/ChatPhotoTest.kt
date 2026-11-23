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
        val tried = mutableListOf<Triple<Int, Int, Int>>()
        val result = encodeWithinLimit(1600, 1200) { width, height, quality ->
            tried.add(Triple(width, height, quality))
            ByteArray(if (quality > 70) 3 * 1024 * 1024 else 1024 * 1024)
        }
        assertEquals(1024 * 1024, result.bytes.size)
        assertEquals(1600 to 1200, result.width to result.height)
        assertEquals(listOf(Triple(1600, 1200, 85), Triple(1600, 1200, 75), Triple(1600, 1200, 65)), tried)
    }

    @Test fun whenTheLowestQualityStillDoesNotFitTheDimensionsShrinkUntilItFits() {
        val tried = mutableListOf<Pair<Int, Int>>()
        val result = encodeWithinLimit(1600, 1200) { width, height, _ ->
            tried.add(width to height)
            ByteArray(if (width > 900) 2 * 1024 * 1024 + 1 else 1024 * 1024)
        }
        assertEquals(900 to 675, result.width to result.height)
        assertEquals(1024 * 1024, result.bytes.size)
        assertEquals(listOf(1600 to 1200, 1200 to 900, 900 to 675), tried.distinct())
    }

    @Test fun aHugeCopyNeverFailsAndNeverExceedsTwoMegabytes() {
        // Fake encoder: about 4 bytes per pixel at any quality, so only shrinking can make it fit.
        val result = encodeWithinLimit(1600, 1600) { width, height, _ -> ByteArray(width * height * 4) }
        assertTrue(result.bytes.size <= PHOTO_MAX_BYTES)
        assertTrue(result.width < 1600 && result.height < 1600)
        assertEquals(result.width, result.height)
    }
}
