package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
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
        listOf(
            ReferencePrice("Reparación", "Mano de obra", "hour", 3500, "IVA", "Materiales"),
            ReferencePrice("Visita de valoración", "Se cobra aunque no se repare", "job", 2000, "Valoración e IVA", "Reparación"),
            ReferencePrice("Cambio de grifería", "Montaje de un grifo", "unit", 4500, "IVA", "Grifo"),
        ),
        businessAddress = "Calle Gascona, local ficticio A, Oviedo",
    )

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun returningFromAProfileKeepsTheListPosition() {
        val rows = (0..29).map { plumber.copy(id = "profile-$it", name = "Negocio $it") }
        compose.setContent { CatalogueScreen(object : Catalogue {
            override suspend fun list(trade: String?) = rows
            override suspend fun profile(id: String) = rows.first { it.id == id }
        }, onAccount = {}, onLanguageChange = {}) }
        compose.onNodeWithTag("catalogue_list").performScrollToNode(hasTestTag("profile_profile-20"))
        compose.onNodeWithTag("profile_profile-20").performClick()
        compose.onNodeWithText("Negocio 20").assertIsDisplayed()
        compose.onNodeWithTag("back_catalogue").performClick()
        compose.onNodeWithTag("profile_profile-20").assertIsDisplayed()
        compose.onNodeWithTag("profile_profile-0").assertDoesNotExist()
    }

    @Test fun aSearchWithoutMatchesOffersAResetInsteadOfATechnicalError() {
        compose.setContent { CatalogueScreen(object : Catalogue {
            override suspend fun list(trade: String?) = listOf(plumber)
            override suspend fun profile(id: String) = plumber
        }, onAccount = {}, onLanguageChange = {}) }
        compose.onNodeWithTag("catalogue_search").performTextInput("No existe")
        compose.onNodeWithTag("catalogue_empty").assertIsDisplayed()
        compose.onNodeWithTag("catalogue_error").assertDoesNotExist()
        compose.onNodeWithTag("catalogue_reset").performClick()
        compose.onNodeWithTag("profile_profile-14").assertIsDisplayed()
    }

    @Test fun commercialAddressIsVisibleAndSpanishOnlyDoesNotShowLanguages() {
        val profile = plumber.copy(languages = listOf("es"),
            businessAddress = "Calle Gascona, local ficticio A, Oviedo")
        compose.setContent { CatalogueScreen(object : Catalogue {
            override suspend fun list(trade: String?) = listOf(profile)
            override suspend fun profile(id: String) = profile
        }, onAccount = {}, onLanguageChange = {}, initialProfileId = profile.id) }
        compose.onNodeWithText("Calle Gascona, local ficticio A, Oviedo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile_languages").assertDoesNotExist()
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
        compose.onNodeWithTag("profile_detail").performScrollToNode(hasTestTag("profile_languages"))
        compose.onNodeWithTag("profile_languages").assertIsDisplayed()
        compose.onNodeWithTag("profile_detail").performScrollToNode(hasText("+34 000 000 014"))
        compose.onNodeWithText("+34 000 000 014").assertIsDisplayed()
        compose.onNodeWithTag("profile_detail").performScrollToNode(hasTestTag("reference_prices"))
        compose.onNodeWithTag("reference_prices").assertIsDisplayed()
        compose.onNodeWithTag("profile_detail").performScrollToNode(hasText("Visita de valoración"))
        compose.onNodeWithText("Visita de valoración").assertIsDisplayed()
        compose.onNodeWithTag("profile_detail").performScrollToNode(hasText("Cambio de grifería"))
        compose.onNodeWithText("Cambio de grifería").assertIsDisplayed()
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
