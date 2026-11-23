package es.teloarreglo.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

const val PHOTO_MAX_SIDE = 1600
const val PHOTO_MAX_BYTES = 2 * 1024 * 1024

/** A new compressed copy; the picked original is only read, never written. */
class PreparedPhoto(val bytes: ByteArray, val width: Int, val height: Int) {
    val mime get() = "image/jpeg"
}

/** Detects JPEG/PNG from content, not from the name or the picker's MIME claim. */
fun photoFormat(header: ByteArray): String? = when {
    header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() ->
        "image/jpeg"
    header.size >= 8 && header.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
    else -> null
}

/** Longer side at most [PHOTO_MAX_SIDE]; smaller images keep their size (never enlarged). */
fun photoSize(width: Int, height: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= PHOTO_MAX_SIDE) return width to height
    val scale = PHOTO_MAX_SIDE.toDouble() / longest
    return Math.round(width * scale).toInt().coerceIn(1, PHOTO_MAX_SIDE) to
        Math.round(height * scale).toInt().coerceIn(1, PHOTO_MAX_SIDE)
}

/** A JPEG copy that fits [PHOTO_MAX_BYTES], with the dimensions it was encoded at. */
class EncodedPhoto(val bytes: ByteArray, val width: Int, val height: Int)

private val PHOTO_QUALITIES = intArrayOf(85, 75, 65, 55, 45)
private const val PHOTO_SHRINK_STEP = 0.75

/**
 * Lowers JPEG quality and, if the lowest quality still does not fit, also shrinks the
 * dimensions (x0.75 per step, at the lowest quality) until the copy fits [PHOTO_MAX_BYTES].
 * Never fails because of size and never falls back to the original.
 */
fun encodeWithinLimit(width: Int, height: Int, encode: (width: Int, height: Int, quality: Int) -> ByteArray): EncodedPhoto {
    for (quality in PHOTO_QUALITIES) {
        val bytes = encode(width, height, quality)
        if (bytes.size <= PHOTO_MAX_BYTES) return EncodedPhoto(bytes, width, height)
    }
    val lowest = PHOTO_QUALITIES.last()
    var scale = 1.0
    while (true) {
        scale *= PHOTO_SHRINK_STEP
        val w = Math.round(width * scale).toInt().coerceAtLeast(1)
        val h = Math.round(height * scale).toInt().coerceAtLeast(1)
        val bytes = encode(w, h, lowest)
        if (bytes.size <= PHOTO_MAX_BYTES) return EncodedPhoto(bytes, w, h)
        check(w > 1 || h > 1) { "a 1x1 JPEG cannot exceed $PHOTO_MAX_BYTES bytes" }
    }
}

private fun openSource(resolver: ContentResolver, uri: Uri): InputStream =
    resolver.openInputStream(uri) ?: throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)

/**
 * Decodes JPEG/PNG of any size with bounded memory, streaming from [uri] (bounds first,
 * then a sampled decode), applies EXIF orientation, scales to [PHOTO_MAX_SIDE], flattens
 * transparency on white and writes a fresh JPEG with no EXIF/location that always fits
 * [PHOTO_MAX_BYTES]. Cancellable between steps. Fails only with UNSUPPORTED.
 */
suspend fun preparePhoto(resolver: ContentResolver, uri: Uri): PreparedPhoto = withContext(Dispatchers.Default) {
    val (bounds, options) = withContext(Dispatchers.IO) {
        val header = openSource(resolver, uri).use { input ->
            val buffer = ByteArray(8)
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            buffer.copyOf(read)
        }
        if (photoFormat(header) == null) throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)
        coroutineContext.ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openSource(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= PHOTO_MAX_SIDE) {
            options.inSampleSize *= 2
        }
        bounds to options
    }
    coroutineContext.ensureActive()
    var bitmap = withContext(Dispatchers.IO) {
        openSource(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
    } ?: throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)
    try {
        val exif = withContext(Dispatchers.IO) { openSource(resolver, uri).use { ExifInterface(it) } }
        val transform = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        if (!transform.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
        }
        coroutineContext.ensureActive()
        val (width, height) = photoSize(bitmap.width, bitmap.height)
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(target).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(bitmap, width, height, true).also {
                if (it !== bitmap) { bitmap.recycle(); bitmap = it }
            }, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        bitmap.recycle()
        bitmap = target
        coroutineContext.ensureActive()
        val encoded = encodeWithinLimit(width, height) { w, h, quality ->
            coroutineContext.ensureActive()
            val scaled = if (w == width && h == height) target else Bitmap.createScaledBitmap(target, w, h, true)
            try {
                ByteArrayOutputStream().also { check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, it)) }.toByteArray()
            } finally { if (scaled !== target) scaled.recycle() }
        }
        PreparedPhoto(encoded.bytes, encoded.width, encoded.height)
    } finally { bitmap.recycle() }
}
