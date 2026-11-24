package es.teloarreglo

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.catalogue.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test fun aRealSdkNullResponseOpensTheEditorWithoutAConnectionError() {
        val client = createSupabaseClient("https://covers.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine {
                respond("null", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            compose.setContent { CoverEditorScreen(profile, SupabaseProfessionalCovers(client), {}, onPickPhoto = {}) }
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("cover_generic").fetchSemanticsNodes().any {
                    !it.config.contains(SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithTag("cover_error").assertDoesNotExist()
            compose.onNodeWithTag("cover_generic").performScrollTo().assertIsEnabled()
        } finally { kotlinx.coroutines.runBlocking { client.close() } }
    }

    @Test fun anInvalidReplacementCannotSubmitThePreviousSelection() {
        val covers = object : ProfessionalCovers {
            override suspend fun state(profileId: String): CoverRequest? = null
            override suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray) = error("must not submit")
            override suspend fun generic(profileId: String, requestId: String) = error("not used")
            override suspend fun imageUrl(path: String) = error("not used")
        }
        val previous = photo()
        compose.setContent { CoverEditorScreen(profile, covers, {}, selected = previous, invalid = true, onPickPhoto = {}) }
        compose.onNodeWithTag("cover_submit").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aNewPreviewNeverInheritsThePreviousPhotosApprovalMessage() {
        val covers = object : ProfessionalCovers {
            override suspend fun state(profileId: String) = CoverRequest("old", CoverStatus.APPROVED, "old.jpg")
            override suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray) = error("not used")
            override suspend fun generic(profileId: String, requestId: String) = error("not used")
            override suspend fun imageUrl(path: String) = error("not used")
        }
        val next = photo()
        compose.setContent { CoverEditorScreen(profile, covers, {}, selected = next, onPickPhoto = {}) }
        compose.onNodeWithTag("cover_state").assertDoesNotExist()
        compose.onNodeWithTag("cover_unsent").performScrollTo().assertIsDisplayed()
    }

    @Test fun aPhotoSubmissionRetiresTheKeyOfAnEarlierUnconfirmedGenericRequest() {
        val genericKeys = mutableListOf<String>()
        val covers = object : ProfessionalCovers {
            override suspend fun state(profileId: String): CoverRequest? = null
            override suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray) =
                CoverRequest(requestId, CoverStatus.PUBLISHED, "photo.jpg")
            override suspend fun generic(profileId: String, requestId: String): CoverRequest {
                genericKeys.add(requestId)
                if (genericKeys.size == 1) throw IOException("response lost")
                return CoverRequest(requestId, CoverStatus.PUBLISHED)
            }
            override suspend fun imageUrl(path: String) = error("offline preview")
        }
        val next = photo()
        compose.setContent { CoverEditorScreen(profile, covers, {}, selected = next, onPickPhoto = {}) }
        compose.onNodeWithTag("cover_generic").performScrollTo().performClick()
        compose.onNodeWithTag("cover_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cover_submit").performScrollTo().performClick()
        compose.onNodeWithTag("cover_generic").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, genericKeys.size)
            org.junit.Assert.assertNotEquals(genericKeys[0], genericKeys[1])
        }
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
        response.complete(CoverRequest("request-1", CoverStatus.PUBLISHED, "private.jpg"))
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
                return CoverRequest(requestId, CoverStatus.PUBLISHED, "private.jpg")
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
