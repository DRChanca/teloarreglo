package es.teloarreglo

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.chat.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Media3 Transformer on the device (#19): the emulator/minimum-API compatibility
 * check. Fixtures are synthetic H.264 MP4s made by scripts/make_video_fixture.swift.
 */
class ChatVideoPrepareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun <T> withAsset(name: String, block: (File) -> T): T {
        val file = File.createTempFile("chat-original-", ".mp4", context.cacheDir)
        try {
            assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            return block(file)
        } finally { file.delete() }
    }

    private fun shortSide(bytes: ByteArray): Int {
        val file = File.createTempFile("chat-result-", ".mp4", context.cacheDir)
        val retriever = MediaMetadataRetriever()
        try {
            file.writeBytes(bytes)
            retriever.setDataSource(file.absolutePath)
            return minOf(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
        } finally { retriever.release(); file.delete() }
    }

    private fun failureOf(block: suspend () -> Unit): AttachmentFailure? = runBlocking {
        try { block(); null } catch (error: ChatAttachmentException) { error.failure }
    }

    @Test fun a1080pVideoBecomesA720pMp4UnderTheLimitWithProgressAndTheOriginalIsUnchanged() = withAsset("video-1080p.mp4") { file ->
        val before = file.readBytes()
        val progress = mutableListOf<Float>()
        val video = runBlocking { prepareVideo(context, Uri.fromFile(file)) { progress.add(it) } }
        assertTrue(isMp4(video.bytes))
        assertEquals("video/mp4", video.mime)
        assertEquals(720, shortSide(video.bytes))
        assertTrue(video.bytes.size <= VIDEO_MAX_BYTES)
        assertTrue(video.durationMs in 2_500..3_500)
        assertTrue("progress was reported", progress.isNotEmpty())
        assertArrayEquals(before, file.readBytes())
        assertTrue("temporary copies are removed", File(context.cacheDir, "chat-video").listFiles().isNullOrEmpty())
    }

    @Test fun aSmallVideoIsNotEnlarged() = withAsset("video-360p.mp4") { file ->
        val video = runBlocking { prepareVideo(context, Uri.fromFile(file)) {} }
        assertEquals(360, shortSide(video.bytes))
    }

    @Test fun aVideoLongerThanFortySecondsIsRefusedBeforeCompressing() = withAsset("video-62s.mp4") { file ->
        assertEquals(AttachmentFailure.TOO_LONG, failureOf { prepareVideo(context, Uri.fromFile(file)) {} })
    }

    @Test fun somethingThatIsNotAVideoIsUnsupported() {
        val file = File.createTempFile("chat-original-", ".mp4", context.cacheDir)
        try {
            file.writeText("not a video")
            assertEquals(AttachmentFailure.UNSUPPORTED, failureOf { prepareVideo(context, Uri.fromFile(file)) {} })
        } finally { file.delete() }
    }
}
