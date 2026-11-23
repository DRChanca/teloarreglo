package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.Located
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Nearby search screen; fakes replace only the remote search, the catalogue and the GPS. */
class NearbySearchUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fake(id: String, name: String) = PublicProfile(
        id, name, "d", listOf("plumbing"), listOf("Fugas"), listOf(Coverage("33044", "Oviedo")),
        listOf("es"), "+34 000 000 000", listOf("plumbing"), emptyList(),
    )

    private val catalogue = object : Catalogue {
        override suspend fun list(trade: String?) = listOf(fake("near", "Fontanería Nalón"), fake("far", "Agua Lejana"))
        override suspend fun profile(id: String) = null
    }

    private class FakeSearch(vararg outcomes: SearchOutcome) : ProfessionalSearch {
        private val queue = ArrayDeque(outcomes.toList())
        val sentConcejos = mutableListOf<String?>()
        override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
            sentConcejos += ineCode
            return queue.removeFirst()
        }
    }

    private val oviedo = Located.Position(43.3625, -5.8434, 20f)

    private fun show(search: ProfessionalSearch, granted: Boolean = true, located: Located = oviedo, opened: MutableList<String> = mutableListOf()) {
        compose.setContent {
            NearbySearchScreen(search, catalogue, ensurePermission = { granted }, locate = { located },
                onBack = {}, onOpenProfile = { opened += it })
        }
    }

    /** The whole screen is one list: bring a node into view as a user would by scrolling. */
    private fun scrollTo(tag: String) = compose.onNodeWithTag("nearby_screen").performScrollToNode(hasTestTag(tag))

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun resultsAreListedInServerOrderWithRoundedDistance() {
        val opened = mutableListOf<String>()
        show(FakeSearch(SearchOutcome.Found(Concejo("33044", "Oviedo"),
            listOf(NearbyResult("near", 500), NearbyResult("far", 12500)))), opened = opened)
        compose.onNodeWithTag("nearby_locate").performClick()
        waitFor("nearby_concejo")
        // Each result is a clickable card: its texts are merged into the card for accessibility,
        // so the distance is looked up in the unmerged tree once the card is in view.
        scrollTo("nearby_far")
        compose.onNodeWithTag("nearby_distance_far", useUnmergedTree = true).assertIsDisplayed()
        scrollTo("nearby_near")
        compose.onNodeWithTag("nearby_near").assertIsDisplayed().performClick()
        assertEquals(listOf("near"), opened)
    }

    @Test fun aBorderAsksWhereTheJobIsAndSearchesThatConcejo() {
        val search = FakeSearch(
            SearchOutcome.ConfirmConcejo(listOf(Concejo("33044", "Oviedo"), Concejo("33035", "Llanera"))),
            SearchOutcome.Found(Concejo("33035", "Llanera"), listOf(NearbyResult("near", 6000))),
        )
        show(search)
        compose.onNodeWithTag("nearby_locate").performClick()
        waitFor("nearby_confirm")
        scrollTo("nearby_confirm_33035")
        compose.onNodeWithTag("nearby_confirm_33035").performClick()
        waitFor("nearby_concejo")
        assertEquals(listOf(null, "33035"), search.sentConcejos)
    }

    @Test fun deniedPermissionExplainsTheManualAlternativeWithoutSearching() {
        val search = FakeSearch()
        show(search, granted = false)
        compose.onNodeWithTag("nearby_locate").performClick()
        waitFor("nearby_denied")
        scrollTo("nearby_denied")
        compose.onNodeWithTag("nearby_denied").assertIsDisplayed()
        assertEquals(emptyList<String?>(), search.sentConcejos)
    }

    @Test fun anImprecisePositionIsNotUsedToGuessAConcejo() {
        val search = FakeSearch()
        show(search, located = Located.Position(43.36, -5.84, 5000f))
        compose.onNodeWithTag("nearby_locate").performClick()
        waitFor("nearby_uncertain")
        scrollTo("nearby_uncertain")
        compose.onNodeWithTag("nearby_uncertain").assertIsDisplayed()
        assertEquals(emptyList<String?>(), search.sentConcejos)
    }

    @Test fun aServerFailureOffersRetryInsteadOfAnEmptyList() {
        show(FakeSearch(SearchOutcome.Failed(SearchError.SERVICE)))
        compose.onNodeWithTag("nearby_locate").performClick()
        waitFor("nearby_error")
        scrollTo("nearby_retry")
        compose.onNodeWithTag("nearby_retry").assertIsDisplayed()
        assertNull(compose.onAllNodes(hasTestTag("nearby_concejo")).fetchSemanticsNodes().firstOrNull())
    }
}
