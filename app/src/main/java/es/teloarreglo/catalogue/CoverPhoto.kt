package es.teloarreglo.catalogue

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CoverPhoto(val jpeg: ByteArray, val requestId: String = UUID.randomUUID().toString())

/** Bounded decode and a new JPEG strip EXIF/location; the phone's original is never changed. */
suspend fun prepareCoverPhoto(resolver: ContentResolver, uri: Uri): CoverPhoto = withContext(Dispatchers.IO) {
    val source = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 20 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("image_unavailable")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    val options = BitmapFactory.Options()
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 3200) {
        options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
    }
    var bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: error("image_invalid")
    try {
        val orientation = ExifInterface(ByteArrayInputStream(source))
        val transform = Matrix().apply {
            if (orientation.isFlipped) postScale(-1f, 1f)
            postRotate(orientation.rotationDegrees.toFloat())
        }
        if (!transform.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
        }
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > 1600) {
            val scale = 1600.0 / longest
            val scaled = Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
        }
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
        val jpeg = output.toByteArray()
        require(jpeg.size <= 2 * 1024 * 1024)
        CoverPhoto(jpeg)
    } finally { bitmap.recycle() }
}
