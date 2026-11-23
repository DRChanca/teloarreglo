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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

const val PHOTO_MAX_SIDE = 1600
const val PHOTO_MAX_BYTES = 2 * 1024 * 1024
private const val SOURCE_MAX_BYTES = 40 * 1024 * 1024

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

/** Lowers JPEG quality until the copy fits; refuses rather than falling back to the original. */
fun encodeWithinLimit(encode: (quality: Int) -> ByteArray): ByteArray {
    for (quality in intArrayOf(85, 75, 65, 55, 45)) {
        val bytes = encode(quality)
        if (bytes.size <= PHOTO_MAX_BYTES) return bytes
    }
    throw ChatAttachmentException(AttachmentFailure.TOO_LARGE)
}

/**
 * Decodes JPEG/PNG with bounded memory, applies EXIF orientation, scales to
 * [PHOTO_MAX_SIDE], flattens transparency on white and writes a fresh JPEG with
 * no EXIF/location. Cancellable between steps. Fails with UNSUPPORTED or TOO_LARGE.
 */
suspend fun preparePhoto(resolver: ContentResolver, uri: Uri): PreparedPhoto = withContext(Dispatchers.Default) {
    val source = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > SOURCE_MAX_BYTES) throw ChatAttachmentException(AttachmentFailure.TOO_LARGE)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)
    }
    if (photoFormat(source.copyOfRange(0, minOf(8, source.size))) == null) {
        throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= PHOTO_MAX_SIDE) {
        options.inSampleSize *= 2
    }
    coroutineContext.ensureActive()
    var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
        ?: throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED)
    try {
        val exif = ExifInterface(ByteArrayInputStream(source))
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
        val jpeg = encodeWithinLimit { quality ->
            ByteArrayOutputStream().also { check(target.compress(Bitmap.CompressFormat.JPEG, quality, it)) }.toByteArray()
        }
        PreparedPhoto(jpeg, width, height)
    } finally { bitmap.recycle() }
}
