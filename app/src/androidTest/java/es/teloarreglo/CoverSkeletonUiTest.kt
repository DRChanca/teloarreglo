package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.CoverRequest
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.ProfessionalCovers
import es.teloarreglo.catalogue.PublicProfile
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** While a Foto de portada loads, a pulsing block takes its place; the trade illustration is only a fallback (#51). */
class CoverSkeletonUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val plumber = PublicProfile(
        "profile-14", "Fontanería Nalón", "Reparaciones domésticas", listOf("plumbing"),
        listOf("Fugas"), listOf(Coverage("33044", "Oviedo")), listOf("es"), "+34 000 000 014",
        listOf("plumbing"), emptyList(),
    )

    private class FakeCovers(private val url: suspend (String) -> String) : ProfessionalCovers {
        override suspend fun state(profileId: String): CoverRequest? = null
        override suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray) = error("unused")
        override suspend fun generic(profileId: String, requestId: String) = error("unused")
        override suspend fun imageUrl(path: String) = url(path)
    }

    private val pending = FakeCovers { awaitCancellation() }

    private fun show(profile: PublicProfile, covers: ProfessionalCovers, openProfile: Boolean = false) {
        compose.setContent {
            CatalogueScreen(object : Catalogue {
                override suspend fun list(trade: String?) = listOf(profile)
                override suspend fun profile(id: String) = profile
            }, covers = covers, initialProfileId = if (openProfile) profile.id else null)
        }
        compose.waitUntil(5_000) { count(hasText(profile.name)) > 0 }
    }

    private fun count(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size
    private val skeleton = hasTestTag("cover_skeleton")
    private val illustration = hasContentDescription(
        context.getString(R.string.cover_generic_description, context.getString(R.string.trade_plumbing)))
    private val unavailable = hasText(context.getString(R.string.cover_image_unavailable))

    @Test fun aCardWithACoverShowsTheSkeletonWhileTheRestIsAlreadyReadable() {
        show(plumber.copy(coverPath = "profile-14/cover.jpg"), pending)
        assertEquals(1, count(skeleton))
        assertEquals(0, count(illustration))
        compose.onNode(hasText(plumber.name), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun theProfileShowsTheSameSkeletonOnItsLargeCover() {
        show(plumber.copy(coverPath = "profile-14/cover.jpg"), pending, openProfile = true)
        assertEquals(1, count(skeleton))
        assertEquals(0, count(illustration))
    }

    @Test fun withoutACoverTheIllustrationShowsWithoutSkeleton() {
        show(plumber, pending)
        assertEquals(0, count(skeleton))
        assertEquals(1, count(illustration))
    }

    @Test fun aCoverThatFailsFallsBackToTheIllustration() {
        show(plumber.copy(coverPath = "profile-14/cover.jpg"), FakeCovers { error("offline") })
        compose.waitUntil(5_000) { count(unavailable) > 0 }
        assertEquals(0, count(skeleton))
        assertEquals(1, count(illustration))
    }
}
