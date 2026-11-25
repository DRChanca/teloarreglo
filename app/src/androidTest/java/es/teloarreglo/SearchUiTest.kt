package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeSource
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.ConcejoAnchor
import es.teloarreglo.search.ConcejoDirectory
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.Located
import es.teloarreglo.search.MapAvailability
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The single search (ADR 0001): the catalogue screen ordered by distance from home, GPS or a concejo.
 * Fakes replace only the remote search, the catalogue and the GPS.
 */
class SearchUiTest {
    @get:Rule val compose = createComposeRule()

    private val gijon = Coverage("33024", "Gijón")
    private val profiles = listOf(fakeProfile("near", "Fontanería Nalón"), fakeProfile("far", "Agua Lejana"),
        fakeProfile("neighbour", "Grifos del Puerto", gijon, featured = true))

    private val catalogue = FakeCatalogue { profiles }

    private val oviedo = Located.Position(43.3625, -5.8434, 20f)
    private val oviedoConcejo = Concejo("33044", "Oviedo")

    private val concejos = object : ConcejoDirectory {
        override suspend fun anchors() = listOf(ConcejoAnchor("33044", "Oviedo", 43.36, -5.85), ConcejoAnchor("33024", "Gijón", 43.53, -5.66))
    }

    // The map is never composed here (no key in tests): the manual choice must work without it.
    private var permissionAsks = 0

    private fun show(
        search: ProfessionalSearch, granted: Boolean = true, located: Located = oviedo,
        home: HomeAddress? = null, askIfAway: (GeoPoint) -> Boolean = { false },
    ) {
        compose.setContent {
            CatalogueScreen(catalogue, proximitySource = ProximitySource(search,
                ensurePermission = { permissionAsks++; granted }, locate = { located },
                concejos = concejos, map = MapAvailability.NO_KEY, home = home, askIfAway = askIfAway))
        }
    }

    private val home = HomeAddress("Calle Uría 1, Oviedo", 43.3619, -5.8494, "33044", "Oviedo", HomeSource.GPS)

    /** The list holds the header and the results: bring a node into view as a user would by scrolling. */
    private fun scrollTo(tag: String) = compose.onNodeWithTag("catalogue_list").performScrollToNode(hasTestTag(tag))

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun none(tag: String) = assertNull(
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull())

    /** A visitor has no home: «Ordenar por cercanía» unfolds the ways to place the search. */
    private fun locateAsVisitor() {
        waitFor("search_sort_nearby")
        compose.onNodeWithTag("search_sort_nearby").performClick()
        scrollTo("nearby_locate")
        compose.onNodeWithTag("nearby_locate").performClick()
    }

    @Test fun withAHomeTheListIsOrderedByDistanceWithoutAskingForGps() {
        show(FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500), NearbyResult("far", 12500)))), home = home)
        waitFor("nearby_distance_near")
        compose.onNodeWithTag("nearby_origin_text").assertTextContains("Oviedo", substring = true)
        scrollTo("profile_far")
        compose.onNodeWithTag("nearby_distance_far", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, permissionAsks)
        none("nearby_locate")
    }

    @Test fun aNeighbourOutsideTheZoneKeepsItsPlaceAndSaysSoWithoutHighlight() {
        // ADR 0001: closer than someone of the concejo, listed before them, never raised as «Destacado».
        show(FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(
            NearbyResult("near", 500), NearbyResult("neighbour", 1500, featured = false, inZone = false),
            NearbyResult("far", 12500)))), home = home)
        waitFor("out_of_zone_neighbour")
        none("out_of_zone_near")
        none("catalogue_featured_neighbour")
    }

    /** Whether the card's top-left corner is painted like the middle of its top edge: a square corner. */
    private fun squareCorners(tag: String): Boolean {
        val image = compose.onAllNodes(hasTestTag(tag))[0].captureToImage().asAndroidBitmap()
        return image.getPixel(1, 1) == image.getPixel(image.width / 2, 1)
    }

    @Test fun onlyTheHighlightedCardIsRoundedAndSaysPerfilDestacado() {
        val featured = profiles + fakeProfile("star", "Fontanería Estrella", featured = true)
        compose.setContent {
            CatalogueScreen(FakeCatalogue { featured }, proximitySource = ProximitySource(FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(
                NearbyResult("star", 300, featured = true), NearbyResult("near", 500),
                NearbyResult("neighbour", 1500, featured = false, inZone = false)))),
                ensurePermission = { true }, locate = { oviedo }, concejos = concejos, map = MapAvailability.NO_KEY, home = home))
        }
        waitFor("nearby_distance_star")
        compose.onNodeWithTag("catalogue_featured_star", useUnmergedTree = true).assertIsDisplayed()
        none("catalogue_featured_near")
        none("catalogue_featured_neighbour")
        assertFalse(squareCorners("profile_star"))
        assertTrue(squareCorners("profile_near"))
        scrollTo("profile_neighbour")
        assertTrue(squareCorners("profile_neighbour"))
    }

    @Test fun theLoadingSkeletonHasTheShapeOfANormalCard() {
        val pending = object : ProfessionalSearch {
            override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome =
                awaitCancellation()
        }
        show(pending, home = home)
        waitFor("search_skeleton")
        assertTrue(squareCorners("search_skeleton"))
    }

    @Test fun visitorsSeeTheCatalogueUntilTheyAskToSortByDistance() {
        val search = FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("far", 500), NearbyResult("near", 1000))))
        show(search)
        waitFor("profile_near")
        none("nearby_distance_near")
        assertEquals(emptyList<String?>(), search.sentConcejos)
        locateAsVisitor()
        waitFor("nearby_distance_far")
        assertEquals(listOf<String?>(null), search.sentConcejos)
    }

    @Test fun theTextFilterAppliesOverTheDistanceOrder() {
        show(FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500), NearbyResult("far", 12500)))), home = home)
        waitFor("nearby_distance_far")
        compose.onNodeWithTag("catalogue_search").performTextInput("Lejana")
        waitFor("profile_far")
        none("profile_near")
    }

    @Test fun aBorderAsksWhereTheJobIsAndSearchesThatConcejo() {
        val search = FakeSearch(
            SearchOutcome.ConfirmConcejo(listOf(oviedoConcejo, Concejo("33035", "Llanera"))),
            SearchOutcome.Found(Concejo("33035", "Llanera"), listOf(NearbyResult("near", 6000))),
        )
        show(search)
        locateAsVisitor()
        waitFor("nearby_confirm_33035")
        compose.onNodeWithTag("nearby_confirm_33035").performClick()
        waitFor("nearby_distance_near")
        assertEquals(listOf(null, "33035"), search.sentConcejos)
    }

    @Test fun deniedPermissionExplainsTheManualAlternativeWithoutSearching() {
        val search = FakeSearch()
        show(search, granted = false)
        locateAsVisitor()
        waitFor("nearby_denied")
        assertEquals(emptyList<String?>(), search.sentConcejos)
    }

    @Test fun anImprecisePositionIsNotUsedToGuessAConcejo() {
        val search = FakeSearch()
        show(search, located = Located.Position(43.36, -5.84, 5000f))
        locateAsVisitor()
        waitFor("nearby_uncertain")
        assertEquals(emptyList<String?>(), search.sentConcejos)
    }

    @Test fun aFailedOrderKeepsTheCatalogueAndOffersRetry() {
        show(FakeSearch(SearchOutcome.Failed(SearchError.SERVICE)), home = home)
        waitFor("nearby_retry")
        // Never an invented empty list: the catalogue order stays, without distances.
        waitFor("profile_near")
        none("nearby_distance_near")
    }

    @Test fun withoutPermissionTheManualConcejoSearchesFromInsideThatConcejo() {
        val search = FakeSearch(SearchOutcome.Found(Concejo("33024", "Gijón"), listOf(NearbyResult("near", 1000))))
        show(search, granted = false)
        locateAsVisitor()
        waitFor("map_concejo")
        scrollTo("map_concejo")
        compose.onNodeWithTag("map_concejo").performClick()
        compose.onNodeWithTag("map_concejo_33024").performClick()
        scrollTo("map_no_map_anchor")
        compose.onNodeWithTag("map_no_map_anchor").assertIsDisplayed()
        scrollTo("map_search_here")
        compose.onNodeWithTag("map_search_here").performClick()
        waitFor("nearby_distance_near")
        assertEquals(listOf<String?>("33024"), search.sentConcejos)
        assertEquals(listOf(43.53 to -5.66), search.sentPoints)
    }

    @Test fun mapViewWithoutAMapExplainsItAndKeepsTheSameList() {
        show(FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500), NearbyResult("far", 12500)))), home = home)
        waitFor("map_toggle_map")
        compose.onNodeWithTag("map_toggle_map").performClick()
        scrollTo("map_unavailable")
        compose.onNodeWithTag("map_unavailable").assertIsDisplayed()
        scrollTo("map_coverage")
        compose.onNodeWithTag("map_coverage").assertIsDisplayed()
        scrollTo("profile_far")
        compose.onNodeWithTag("profile_far").assertIsDisplayed()
    }

    @Test fun tappingATradeRepeatsTheSearchFromTheSameOrigin() {
        val search = FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500))),
            SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("far", 12500))))
        show(search, home = home)
        waitFor("nearby_distance_near")
        compose.onNodeWithTag("trade_electrical").performClick()
        compose.waitUntil(5_000) { search.sentTrades.size == 2 }
        assertEquals(listOf(null, "electrical"), search.sentTrades)
        assertEquals(listOf(home.lat to home.lng, home.lat to home.lng), search.sentPoints)
        assertEquals(listOf<String?>("33044", "33044"), search.sentConcejos)
        waitFor("nearby_distance_far")
    }

    @Test fun retryingAFailedSearchFromHomeDoesNotAskForTheLocation() {
        val search = FakeSearch(SearchOutcome.Failed(SearchError.NETWORK),
            SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500))))
        show(search, home = home)
        waitFor("nearby_retry")
        compose.onNodeWithTag("nearby_retry").performClick()
        waitFor("nearby_distance_near")
        assertEquals(0, permissionAsks)
        assertEquals(listOf(home.lat to home.lng, home.lat to home.lng), search.sentPoints)
    }

    @Test fun retryingAManualSearchKeepsTheChosenConcejo() {
        val search = FakeSearch(SearchOutcome.Failed(SearchError.SERVICE),
            SearchOutcome.Found(Concejo("33024", "Gijón"), listOf(NearbyResult("near", 1000))))
        show(search)
        waitFor("search_sort_nearby")
        compose.onNodeWithTag("search_sort_nearby").performClick()
        scrollTo("map_manual")
        compose.onNodeWithTag("map_manual").performClick()
        waitFor("map_concejo")
        scrollTo("map_concejo")
        compose.onNodeWithTag("map_concejo").performClick()
        compose.onNodeWithTag("map_concejo_33024").performClick()
        scrollTo("map_search_here")
        compose.onNodeWithTag("map_search_here").performClick()
        waitFor("nearby_retry")
        compose.onNodeWithTag("nearby_retry").performClick()
        waitFor("nearby_distance_near")
        assertEquals(0, permissionAsks)
        assertEquals(listOf<String?>("33024", "33024"), search.sentConcejos)
    }

    @Test fun farFromHomeShowsABannerAndSearchesFromHereOnlyWhenAsked() {
        val search = FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500))),
            SearchOutcome.Found(Concejo("33024", "Gijón"), listOf(NearbyResult("far", 1000))))
        show(search, home = home, located = Located.Position(43.53, -5.66, 20f), askIfAway = { true })
        waitFor("search_away")
        // The banner does not block: the list from home is already there.
        waitFor("nearby_distance_near")
        compose.onNodeWithTag("search_away_here").performClick()
        waitFor("nearby_distance_far")
        assertEquals(listOf(home.lat to home.lng, 43.53 to -5.66), search.sentPoints)
        none("search_away")
    }

    @Test fun otherWaysAreFoldedUntilTheUserAsksToChange() {
        show(FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500)))), home = home)
        waitFor("nearby_distance_near")
        none("nearby_locate")
        compose.onNodeWithTag("nearby_other_ways").performClick()
        scrollTo("nearby_locate")
        compose.onNodeWithTag("nearby_locate").assertIsDisplayed()
    }

    @Test fun aSearchAlreadyPreloadedIsShownWithoutSkeletonsNorAnotherCallAndSurvivesChangingTab() {
        // #59: the app searched from home during the start animation; the screen only shows it.
        val search = FakeSearch(SearchOutcome.Found(oviedoConcejo, listOf(NearbyResult("near", 500), NearbyResult("far", 12500))))
        val session = AppSearchSession(search, catalogue, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        compose.runOnUiThread { session.onAccount(userId = "u1", administrator = false, home = home) }
        compose.waitUntil(5_000) { session.proximity.state is NearbyState.Results }
        var onSearch by mutableStateOf(true)
        compose.setContent {
            if (onSearch) CatalogueScreen(catalogue, searchSession = session, proximitySource = ProximitySource(search,
                ensurePermission = { permissionAsks++; true }, locate = { oviedo },
                concejos = concejos, map = MapAvailability.NO_KEY, home = home))
            else Text("Mensajes")
        }
        compose.onNodeWithTag("nearby_distance_near", useUnmergedTree = true).assertIsDisplayed()
        none("search_skeleton")

        compose.runOnUiThread { onSearch = false }
        compose.waitForIdle()
        compose.runOnUiThread { onSearch = true }
        compose.waitForIdle()

        compose.onNodeWithTag("nearby_distance_near", useUnmergedTree = true).assertIsDisplayed()
        none("search_skeleton")
        assertEquals(1, search.calls)
        assertEquals(0, permissionAsks)
    }
}
