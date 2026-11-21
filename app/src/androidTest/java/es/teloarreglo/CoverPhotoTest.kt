package es.teloarreglo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.catalogue.prepareCoverPhoto
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoverPhotoTest {
    @Test fun uploadedCopyIsResizedOrientedAndHasNoGpsWhileTheOriginalIsUnchanged() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("cover-original-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(4000, 2000, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(file).apply {
                setLatLong(43.36, -5.84)
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val before = file.readBytes()
            val prepared = prepareCoverPhoto(context.contentResolver, Uri.fromFile(file))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(prepared.jpeg, 0, prepared.jpeg.size, bounds)
            assertEquals(800, bounds.outWidth)
            assertEquals(1600, bounds.outHeight)
            assertTrue(prepared.jpeg.size <= 2 * 1024 * 1024)
            assertNull(ExifInterface(ByteArrayInputStream(prepared.jpeg)).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertArrayEquals(before, file.readBytes())
        } finally { file.delete() }
    }
}
