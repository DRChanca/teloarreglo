package es.teloarreglo

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.catalogue.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CoverEditorTest {
    @get:Rule val compose = createComposeRule()
    private val profile = PublicProfile("profile", "Mi negocio", "", listOf("plumbing"),
        listOf("Fugas"), emptyList(), listOf("es"), "+34 000 000 001", emptyList(), emptyList())

    private fun photo(): CoverPhoto {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        bitmap.recycle()
        return CoverPhoto(output.toByteArray(), "request-1")
    }

    @Test fun submissionIsNotShownAsPendingBeforeTheServerConfirmsIt() {
        val response = CompletableDeferred<CoverRequest>()
        val covers = object : ProfessionalCovers {
            override suspend fun state(profileId: String): CoverRequest? = null
            override suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray) = response.await()
            override suspend fun generic(profileId: String, requestId: String) = error("not used")
            override suspend fun imageUrl(path: String) = error("offline preview")
        }
        val selected = photo()
        compose.setContent { CoverEditorScreen(profile, covers, {}, selected = selected, onPickPhoto = {}) }
        compose.onNodeWithTag("cover_submit").performScrollTo().performClick()
        compose.onNodeWithTag("cover_state").assertDoesNotExist()
        compose.onNodeWithTag("cover_submit").assertIsNotEnabled()
        response.complete(CoverRequest("request-1", "pending", "private.jpg"))
        compose.onNodeWithTag("cover_state").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cover_submit").assertIsNotEnabled()
    }

    @Test fun retryKeepsTheSameImageAndIdempotencyKey() {
        val attempts = mutableListOf<String>()
        val covers = object : ProfessionalCovers {
            override suspend fun state(profileId: String): CoverRequest? = null
            override suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray): CoverRequest {
                attempts.add(requestId)
                if (attempts.size == 1) throw IOException("offline")
                return CoverRequest(requestId, "pending", "private.jpg")
            }
            override suspend fun generic(profileId: String, requestId: String) = error("not used")
            override suspend fun imageUrl(path: String) = error("offline preview")
        }
        val selected = photo()
        compose.setContent { CoverEditorScreen(profile, covers, {}, selected = selected, onPickPhoto = {}) }
        compose.onNodeWithTag("cover_submit").performScrollTo().performClick()
        compose.onNodeWithTag("cover_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cover_submit").performScrollTo().performClick()
        compose.onNodeWithTag("cover_state").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("request-1", "request-1"), attempts)
    }
}
