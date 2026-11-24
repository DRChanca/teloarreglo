package es.teloarreglo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.chat.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Android decoder/encoder: sizes, original untouched, formats. */
class ChatPhotoPrepareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bounds(bytes: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }

    private fun <T> withImage(width: Int, height: Int, format: Bitmap.CompressFormat, block: (File) -> T): T {
        val file = File.createTempFile("chat-original-", if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg",
            context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(format, 95, it) }
            bitmap.recycle()
            return block(file)
        } finally { file.delete() }
    }

    @Test fun aLargeJpegIsReducedTo1600PixelsUnder2MbWithoutGpsAndTheOriginalIsUnchanged() = runBlocking {
        withImage(4000, 3000, Bitmap.CompressFormat.JPEG) { file ->
            ExifInterface(file).apply { setLatLong(43.36, -5.84); saveAttributes() }
            val before = file.readBytes()
            val photo = runBlocking { preparePhoto(context.contentResolver, Uri.fromFile(file)) }
            val size = bounds(photo.bytes)
            assertEquals(1600, size.outWidth)
            assertEquals(1200, size.outHeight)
            assertEquals("image/jpeg", size.outMimeType)
            assertTrue(photo.bytes.size <= 2 * 1024 * 1024)
            assertNull(ExifInterface(ByteArrayInputStream(photo.bytes)).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun aSmallPngIsConvertedButNotEnlarged() = runBlocking {
        withImage(640, 480, Bitmap.CompressFormat.PNG) { file ->
            val before = file.readBytes()
            val photo = runBlocking { preparePhoto(context.contentResolver, Uri.fromFile(file)) }
            val size = bounds(photo.bytes)
            assertEquals(640, size.outWidth)
            assertEquals(480, size.outHeight)
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun anIncompatibleFormatIsRefused() = runBlocking {
        withImage(100, 100, Bitmap.CompressFormat.WEBP) { file ->
            var failure: AttachmentFailure? = null
            try { runBlocking { preparePhoto(context.contentResolver, Uri.fromFile(file)) } }
            catch (error: ChatAttachmentException) { failure = error.failure }
            assertEquals(AttachmentFailure.UNSUPPORTED, failure)
        }
    }
}
