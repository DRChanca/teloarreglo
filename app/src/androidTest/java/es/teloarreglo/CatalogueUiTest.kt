package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.ReferencePrice
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class CatalogueUiTest {
    @get:Rule val compose = createComposeRule()
    private val plumber = PublicProfile(
        "profile-14", "Fontanería Nalón", "Reparaciones domésticas", listOf("plumbing"),
        listOf("Fugas"), listOf(Coverage("33044", "Oviedo")), listOf("es", "en"),
        "+34 000 000 014", listOf("plumbing"),
        listOf(ReferencePrice("Reparación", "Mano de obra", "hour", 3500, "IVA", "Materiales")),
    )

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun visitorOpensProfileAndReturnsToTheFilteredList() {
        compose.setContent { CatalogueScreen(object : Catalogue {
            override suspend fun list(trade: String?) = if (trade == null || trade == "plumbing")
                listOf(plumber, plumber.copy(id = "profile-15", name = "Agua Clara")) else emptyList()
            override suspend fun profile(id: String) = plumber
        }, onAccount = {}, onLanguageChange = {}) }
        compose.onNodeWithTag("profile_profile-14").assertIsDisplayed()
        screenshot("catalogue-list")
        compose.onNodeWithTag("trade_plumbing").performClick()
        compose.onNodeWithTag("profile_profile-14").performClick()
        compose.onNodeWithText("Fontanería Nalón").assertIsDisplayed()
        screenshot("catalogue-profile")
        compose.onNodeWithText("+34 000 000 014").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reference_prices").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("back_catalogue").performClick()
        compose.onNodeWithTag("profile_profile-14").assertIsDisplayed()
    }

    @Test fun failureCanBeRetriedWithoutShowingAnEmptySuccess() {
        var offline = true
        compose.setContent { CatalogueScreen(object : Catalogue {
            override suspend fun list(trade: String?): List<PublicProfile> {
                if (offline) throw java.io.IOException("offline")
                return emptyList()
            }
            override suspend fun profile(id: String): PublicProfile? = null
        }, onAccount = {}, onLanguageChange = {}) }
        compose.onNodeWithTag("catalogue_error").assertIsDisplayed()
        compose.onNodeWithTag("catalogue_empty").assertDoesNotExist()
        offline = false
        compose.onNodeWithTag("catalogue_retry").performClick()
        compose.onNodeWithTag("catalogue_empty").assertIsDisplayed()
    }

    @Test fun loadingDoesNotPretendTheCatalogueIsEmpty() {
        val response = CompletableDeferred<List<PublicProfile>>()
        compose.setContent { CatalogueScreen(object : Catalogue {
            override suspend fun list(trade: String?) = response.await()
            override suspend fun profile(id: String): PublicProfile? = null
        }, onAccount = {}, onLanguageChange = {}) }
        compose.onNodeWithTag("catalogue_loading").assertIsDisplayed()
        compose.onNodeWithTag("catalogue_empty").assertDoesNotExist()
        response.complete(emptyList())
        compose.onNodeWithTag("catalogue_empty").assertIsDisplayed()
    }
}
