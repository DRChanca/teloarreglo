package es.teloarreglo

import es.teloarreglo.chat.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatVideoTest {
    private fun failureOf(block: () -> Unit): AttachmentFailure? = try { block(); null }
        catch (error: ChatAttachmentException) { error.failure }

    private val mp4 = byteArrayOf(0, 0, 0, 0x1c) + "ftypmp42".toByteArray()

    @Test fun limitsAreOneMinute720pAnd20MiB() {
        assertEquals(60_000L, VIDEO_MAX_DURATION_MS)
        assertEquals(720, VIDEO_MAX_SHORT_SIDE)
        assertEquals(20L * 1024 * 1024, VIDEO_MAX_BYTES)
        assertEquals("video/mp4", PreparedVideo(byteArrayOf(1), 1_000, 2, 2).mime)
        assertEquals(AttachmentKind.VIDEO, PreparedVideo(byteArrayOf(1), 1_000, 2, 2).kind)
        assertEquals("video", AttachmentKind.VIDEO.wire)
    }

    @Test fun aVideoUpToOneMinuteIsAcceptedAndLongerIsRefusedBeforeCompressing() {
        assertNull(failureOf { checkVideoSource(VideoSource(60_000, 1920, 1080, 0, null)) })
        // A clip shown as «1:00» by the gallery can be a few milliseconds longer.
        assertNull(failureOf { checkVideoSource(VideoSource(60_400, 1920, 1080, 0, null)) })
        assertEquals(AttachmentFailure.TOO_LONG, failureOf { checkVideoSource(VideoSource(61_000, 1920, 1080, 0, null)) })
    }

    @Test fun somethingThatIsNotAReadableVideoIsUnsupported() {
        assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { checkVideoSource(null) })
        assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { checkVideoSource(VideoSource(null, 1920, 1080, 0, null)) })
        assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { checkVideoSource(VideoSource(5_000, 0, 0, 0, null)) })
        assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { checkVideoSource(VideoSource(0, 640, 480, 0, null)) })
    }

    @Test fun largerVideosAreScaledTo720pKeepingOrientationAndAspect() {
        assertEquals(1280 to 720, videoTargetSize(1920, 1080, 0))
        // Portrait phone recording: coded landscape with 90° rotation is displayed upright.
        assertEquals(720 to 1280, videoTargetSize(1920, 1080, 90))
        assertEquals(720 to 1280, videoTargetSize(1080, 1920, 0))
        assertEquals(960 to 720, videoTargetSize(1440, 1080, 180))
        assertEquals(720 to 1280, videoTargetSize(2160, 3840, 0))
        // Odd results are rounded to even sizes, as encoders require.
        assertEquals(720 to 1080, videoTargetSize(1000, 1501, 0))
    }

    @Test fun videosAlreadyAt720pOrSmallerAreNeverEnlarged() {
        assertNull(videoTargetSize(1280, 720, 0))
        assertNull(videoTargetSize(640, 480, 90))
        assertNull(videoTargetSize(720, 720, 0))
    }

    @Test fun bitrateFitsAFullMinuteIn20MiBAndNeverExceedsTheSource() {
        val full = videoBitrate(60_000, null)
        val bytesPerMinute = (full + VIDEO_AUDIO_BITRATE).toLong() * 60 / 8
        assertTrue("a minute fits with margin: $bytesPerMinute", bytesPerMinute <= VIDEO_MAX_BYTES * 9 / 10)
        assertTrue("short clips keep 720p quality", videoBitrate(10_000, null) <= VIDEO_MAX_BITRATE)
        assertEquals(VIDEO_MAX_BITRATE, videoBitrate(10_000, null))
        assertEquals(800_000, videoBitrate(10_000, 800_000))
        assertEquals(VIDEO_MIN_BITRATE, videoBitrate(10_000, 50_000))
    }

    @Test fun compressionRetriesOnceAtALowerBitrateAndOtherwiseRefusesInsteadOfSendingTheOriginal() = runTest {
        val tried = mutableListOf<Int>()
        assertEquals(1_200_000, encodeVideoWithinLimit(2_000_000) { tried.add(it); if (it > 1_500_000) VIDEO_MAX_BYTES + 1 else 10L })
        assertEquals(listOf(2_000_000, 1_200_000), tried)
        tried.clear()
        val failure = try { encodeVideoWithinLimit(2_000_000) { tried.add(it); VIDEO_MAX_BYTES + 1 }; null }
            catch (error: ChatAttachmentException) { error.failure }
        assertEquals(AttachmentFailure.TOO_LARGE, failure)
        assertEquals(2, tried.size)
    }

    @Test fun mp4IsDetectedFromContent() {
        assertTrue(isMp4(mp4))
        assertFalse(isMp4("RIFF....WEBP".toByteArray()))
        assertFalse(isMp4(byteArrayOf(0, 0, 0)))
    }

    @Test fun theCompressedResultIsCheckedBeforeUploading() {
        assertNull(failureOf { checkVideoResult(mp4, 5_000_000, 42_000, 1280, 720, sourceShortSide = 1080) })
        // The stored size may be coded with rotation (720×1280): orientation does not matter.
        assertNull(failureOf { checkVideoResult(mp4, 5_000_000, 42_000, 720, 1280, sourceShortSide = 1080) })
        assertEquals(AttachmentFailure.UNSUPPORTED,
            failureOf { checkVideoResult("not a video".toByteArray(), 5_000, 42_000, 1280, 720, 1080) })
        assertEquals(AttachmentFailure.TOO_LARGE,
            failureOf { checkVideoResult(mp4, VIDEO_MAX_BYTES + 1, 42_000, 1280, 720, 1080) })
        assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { checkVideoResult(mp4, 5_000, 42_000, 1920, 1080, 1080) })
        assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { checkVideoResult(mp4, 5_000, 42_000, 1280, 720, 480) })
        assertEquals(AttachmentFailure.TOO_LONG, failureOf { checkVideoResult(mp4, 5_000, 61_000, 1280, 720, 1080) })
        assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { checkVideoResult(mp4, 0, 42_000, 1280, 720, 1080) })
    }
}
