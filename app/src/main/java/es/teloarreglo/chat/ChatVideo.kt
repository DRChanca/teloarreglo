package es.teloarreglo.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4LocationData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val VIDEO_MAX_DURATION_MS = 60_000L
/** A clip the gallery shows as «1:00» may last a few milliseconds more. */
const val VIDEO_DURATION_TOLERANCE_MS = 499L
const val VIDEO_MAX_SHORT_SIDE = 720
const val VIDEO_MAX_BYTES = 20L * 1024 * 1024
const val VIDEO_MAX_BITRATE = 2_500_000
const val VIDEO_MIN_BITRATE = 300_000
const val VIDEO_AUDIO_BITRATE = 128_000

/** A new compressed H.264/AAC MP4 copy; the picked original is only read, never written. */
class PreparedVideo(override val bytes: ByteArray, val durationMs: Long, val width: Int, val height: Int) :
    PreparedAttachment {
    override val kind get() = AttachmentKind.VIDEO
    override val mime get() = "video/mp4"
}

/** What the platform can read of the picked file. Sizes are coded (before [rotation]). */
class VideoSource(val durationMs: Long?, val width: Int, val height: Int, val rotation: Int, val bitrate: Int?)

private fun refuse(failure: AttachmentFailure): Nothing = throw ChatAttachmentException(failure)

/** Rejects early, before compressing: unreadable files and videos over one minute. */
fun checkVideoSource(source: VideoSource?) {
    if (source == null || source.width <= 0 || source.height <= 0 || source.durationMs == null || source.durationMs <= 0) {
        refuse(AttachmentFailure.UNSUPPORTED)
    }
    if (source.durationMs > VIDEO_MAX_DURATION_MS + VIDEO_DURATION_TOLERANCE_MS) refuse(AttachmentFailure.TOO_LONG)
}

private fun even(value: Double) = maxOf(2, (value / 2).roundToInt() * 2)

/**
 * Display size (rotation applied) with the shorter side at [VIDEO_MAX_SHORT_SIDE] and the
 * aspect kept, or null when the video is already 720p or smaller: it is never enlarged.
 */
fun videoTargetSize(width: Int, height: Int, rotation: Int): Pair<Int, Int>? {
    val (displayWidth, displayHeight) = if (rotation % 180 != 0) height to width else width to height
    val short = minOf(displayWidth, displayHeight)
    if (short <= VIDEO_MAX_SHORT_SIDE) return null
    val scale = VIDEO_MAX_SHORT_SIDE.toDouble() / short
    return if (displayWidth <= displayHeight) VIDEO_MAX_SHORT_SIDE to even(displayHeight * scale)
    else even(displayWidth * scale) to VIDEO_MAX_SHORT_SIDE
}

/** Video bitrate so the whole clip plus audio fits in 85 % of [VIDEO_MAX_BYTES], never above the source. */
fun videoBitrate(durationMs: Long, sourceBitrate: Int?): Int {
    val seconds = maxOf(durationMs, 1_000L) / 1000.0
    val budget = (VIDEO_MAX_BYTES * 8 * 0.85 / seconds).toInt() - VIDEO_AUDIO_BITRATE
    return minOf(budget, VIDEO_MAX_BITRATE, sourceBitrate ?: Int.MAX_VALUE).coerceAtLeast(VIDEO_MIN_BITRATE)
}

/**
 * Encoders may overshoot the requested bitrate: one more attempt at 60 % and then a
 * TOO_LARGE refusal. Never falls back to the original. Returns the bitrate that fitted.
 */
suspend fun encodeVideoWithinLimit(bitrate: Int, encode: suspend (bitrate: Int) -> Long): Int {
    for (attempt in listOf(bitrate, (bitrate * 0.6).toInt())) {
        if (encode(attempt) <= VIDEO_MAX_BYTES) return attempt
    }
    refuse(AttachmentFailure.TOO_LARGE)
}

/** ISO base media (MP4) from content: an `ftyp` box first. */
fun isMp4(header: ByteArray): Boolean = header.size >= 8 &&
    header.copyOfRange(4, 8).contentEquals(byteArrayOf(0x66, 0x74, 0x79, 0x70))

/** Checks the compressed copy itself before uploading; sizes may be stored rotated. */
fun checkVideoResult(header: ByteArray, size: Long, durationMs: Long?, width: Int, height: Int, sourceShortSide: Int) {
    if (!isMp4(header) || size <= 0 || width <= 0 || height <= 0 || durationMs == null) refuse(AttachmentFailure.UNSUPPORTED)
    if (size > VIDEO_MAX_BYTES) refuse(AttachmentFailure.TOO_LARGE)
    if (durationMs > VIDEO_MAX_DURATION_MS + VIDEO_DURATION_TOLERANCE_MS) refuse(AttachmentFailure.TOO_LONG)
    if (minOf(width, height) > minOf(VIDEO_MAX_SHORT_SIDE, sourceShortSide)) refuse(AttachmentFailure.UNSUPPORTED)
}

private fun MediaMetadataRetriever.number(key: Int) = extractMetadata(key)?.toLongOrNull()

private fun readVideo(read: MediaMetadataRetriever.() -> Unit): VideoSource? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.read()
        if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") null
        else VideoSource(
            retriever.number(MediaMetadataRetriever.METADATA_KEY_DURATION),
            retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0,
            retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0,
            retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0,
            retriever.number(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt())
    } catch (error: RuntimeException) { null } finally { retriever.release() }
}

/**
 * Compresses the picked video into a new H.264/AAC MP4 with Media3 Transformer: at most
 * one minute, 720p (never enlarged) and [VIDEO_MAX_BYTES], without location metadata.
 * Reports progress (0..1); cancelling the coroutine cancels the export. The temporary
 * copy lives in the app cache and is always deleted. Fails with UNSUPPORTED, TOO_LONG
 * or TOO_LARGE and never returns the original.
 */
suspend fun prepareVideo(context: Context, uri: Uri, onProgress: (Float) -> Unit): PreparedVideo {
    val app = context.applicationContext
    val source = withContext(Dispatchers.IO) { readVideo { setDataSource(app, uri) } }
    checkVideoSource(source)
    source!!
    val sourceShort = minOf(source.width, source.height)
    val target = videoTargetSize(source.width, source.height, source.rotation)
    val output = File(File(app.cacheDir, "chat-video").apply { mkdirs() }, UUID.randomUUID().toString() + ".mp4")
    try {
        encodeVideoWithinLimit(videoBitrate(source.durationMs!!, source.bitrate)) { bitrate ->
            output.delete()
            export(app, uri, target, bitrate, output, onProgress)
            output.length()
        }
        return withContext(Dispatchers.IO) {
            val bytes = output.readBytes()
            val result = readVideo { setDataSource(output.absolutePath) } ?: refuse(AttachmentFailure.UNSUPPORTED)
            checkVideoResult(bytes.copyOf(minOf(bytes.size, 12)), bytes.size.toLong(), result.durationMs,
                result.width, result.height, sourceShort)
            PreparedVideo(bytes, result.durationMs!!, result.width, result.height)
        }
    } finally { output.delete() }
}

@OptIn(UnstableApi::class)
private suspend fun export(
    context: Context, uri: Uri, target: Pair<Int, Int>?, bitrate: Int, output: File, onProgress: (Float) -> Unit,
) = withContext(Dispatchers.Main) {
    // Transformer lives on the main looper: build, poll and cancel it there.
    val done = CompletableDeferred<Unit>()
    val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setEncoderFactory(DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(VIDEO_AUDIO_BITRATE).build())
            .setEnableFallback(true)
            .build())
        .setMuxerFactory(InAppMp4Muxer.Factory { entries -> entries.removeAll { it is Mp4LocationData } })
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                done.completeExceptionally(ChatAttachmentException(AttachmentFailure.UNSUPPORTED, exportException))
            }
        })
        .build()
    val effects = target?.let { (width, height) ->
        listOf<Effect>(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
    } ?: emptyList()
    transformer.start(EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(), effects)).build(),
        output.absolutePath)
    try {
        val progress = ProgressHolder()
        while (!done.isCompleted) {
            if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress / 100f)
            withTimeoutOrNull(250) { done.join() }
        }
        done.await()
    } catch (error: CancellationException) {
        transformer.cancel()
        throw error
    }
}
