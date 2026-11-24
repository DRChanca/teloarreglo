package es.teloarreglo

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.OwnProfile
import es.teloarreglo.catalogue.ProfessionalProfiles
import es.teloarreglo.catalogue.ProfileContent
import es.teloarreglo.catalogue.ProfileReview
import es.teloarreglo.catalogue.ReferencePrice
import es.teloarreglo.catalogue.ReviewStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The fake replaces only the remote calls; the editor is the real UI. */
class ProfileEditorUiTest {
    @get:Rule val compose = createComposeRule()

    private val approved = ProfileContent("Fontanería Ana", "Aprobada", listOf("plumbing"), listOf("Fugas"),
        listOf(Coverage("33044", "Oviedo")), listOf("es"), "+34 600 000 000")

    private class Fake(var own: OwnProfile?) : ProfessionalProfiles {
        val submitted = mutableListOf<Pair<String, ProfileContent>>()
        var failNext = false
        override suspend fun mine() = own
        override suspend fun start() = OwnProfile("p", null, null, ProfileContent(), ProfileReview("p")).also { own = it }
        override suspend fun submit(requestId: String, content: ProfileContent, agenda: es.teloarreglo.catalogue.ProfileAgenda?): ProfileReview {
            submitted += requestId to content
            if (failNext) { failNext = false; error("offline") }
            return ProfileReview("p", ReviewStatus.PENDING, published = own?.public != null)
        }
        override suspend fun savePrivateAddress(address: String?) = own!!.copy(privateAddress = address)
        val offers = mutableListOf<Pair<List<String>, List<ReferencePrice>>>()
        override suspend fun saveOffer(services: List<String>, prices: List<ReferencePrice>): OwnProfile {
            offers += services to prices
            return own!!.copy(editable = own!!.editable.copy(services = services, prices = prices)).also { own = it }
        }
    }

    private fun show(fake: Fake) = compose.setContent {
        ProfileEditorScreen(fake, null, CoroutineScope(Dispatchers.Main), onBack = {}, onStarted = {})
    }

    @Test fun aRejectedProfileShowsTheReasonAndIsCorrectedFromThePresentedVersion() {
        val rejected = approved.copy(description = "Sin cobertura")
        val fake = Fake(OwnProfile("p", null, null, rejected,
            ProfileReview("p", ReviewStatus.REJECTED, "Falta Gijón", "2026-11-23T11:00:00Z", false)))
        show(fake)
        compose.onNodeWithTag("profile_edit_reason").assertTextContains("Falta Gijón", substring = true)
        compose.onNodeWithTag("profile_edit_description").assertTextContains("Sin cobertura", substring = true)
    }

    @Test fun aLostResponseIsRetriedWithTheSameRequestAndNeverShownAsSubmittedBefore() {
        val fake = Fake(OwnProfile("p", null, approved, approved, ProfileReview("p", ReviewStatus.APPROVED, published = true)))
        fake.failNext = true
        show(fake)
        compose.onNodeWithTag("profile_edit_description").performTextReplacement("Nueva descripción")
        compose.onNodeWithTag("profile_edit_submit").performScrollTo().performClick()
        compose.onNodeWithTag("profile_edit_error").assertExists()
        compose.onNodeWithTag("profile_edit_outcome").assertDoesNotExist()
        compose.onNodeWithTag("profile_edit_submit").performScrollTo().performClick()
        compose.onNodeWithTag("profile_edit_outcome").assertExists()
        assertEquals(2, fake.submitted.size)
        assertEquals(fake.submitted[0].first, fake.submitted[1].first)
        assertEquals("Nueva descripción", fake.submitted[1].second.description)
    }

    @Test fun pricesArePublishedDirectlyAndAnIncompletePriceIsNotSent() {
        val fake = Fake(OwnProfile("p", null, approved, approved, ProfileReview("p", ReviewStatus.APPROVED, published = true)))
        show(fake)
        compose.onNodeWithTag("profile_offer_add").performScrollTo().performClick()
        compose.onNodeWithTag("profile_offer_service_0").performScrollTo().performTextReplacement("Reparación de fuga")
        compose.onNodeWithTag("profile_offer_save").performScrollTo().performClick()
        compose.onNodeWithTag("profile_offer_invalid").assertExists()
        assertTrue("an incomplete price is never sent", fake.offers.isEmpty())
        compose.onNodeWithTag("profile_offer_amount_0").performScrollTo().performTextReplacement("35,50")
        compose.onNodeWithTag("profile_offer_unit_0_hour").performScrollTo().performClick()
        compose.onNodeWithTag("profile_offer_save").performScrollTo().performClick()
        compose.onNodeWithTag("profile_edit_outcome").assertExists()
        val (services, prices) = fake.offers.single()
        assertEquals(listOf("Fugas"), services)
        assertEquals(ReferencePrice("Reparación de fuga", "", "hour", 3550, "", ""), prices.single())
        assertTrue("publishing prices presents nothing for review", fake.submitted.isEmpty())
    }

    @Test fun incompleteDataIsNotPresented() {
        val fake = Fake(null)
        show(fake)
        compose.onNodeWithTag("profile_edit_start").performClick()
        compose.onNodeWithTag("profile_edit_submit").performScrollTo().performClick()
        compose.onNodeWithTag("profile_edit_missing").assertExists()
        assertTrue(fake.submitted.isEmpty())
    }
}
