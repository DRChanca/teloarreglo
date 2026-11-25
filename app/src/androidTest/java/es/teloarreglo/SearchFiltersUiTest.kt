package es.teloarreglo

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeSource
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.ConcejoAnchor
import es.teloarreglo.search.ConcejoDirectory
import es.teloarreglo.search.Located
import es.teloarreglo.search.MapAvailability
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchOutcome
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Search filters (#52): trade, maximum distance and minimum rating, applied on the device over the ordered
 * list. Fakes replace only the remote search, the catalogue and the GPS, as in [SearchUiTest].
 */
class SearchFiltersUiTest {
    @get:Rule val compose = createComposeRule()

    private fun fake(id: String, name: String, rating: Double? = 4.5, reviews: Int = if (rating == null) 0 else 3) =
        PublicProfile(id, name, "d", listOf("plumbing"), listOf("Fugas"), listOf(Coverage("33044", "Oviedo")),
            listOf("es"), "+34 000 000 000", listOf("plumbing"), emptyList(), rating = rating, reviewCount = reviews)

    private var profiles = listOf(
        fake("featured", "Destacado Lejano"), fake("near", "Fontanería Nalón"),
        fake("edge", "Al Borde"), fake("beyond", "Pasado el Borde", rating = 3.9),
        fake("unrated", "Sin Reseñas", rating = null), fake("nowhere", "Sin Ubicación"),
    )

    private val catalogue = object : Catalogue {
        override suspend fun list(trade: String?) = profiles
        override suspend fun profile(id: String) = profiles.firstOrNull { it.id == id }
    }

    private class FakeSearch(private val outcome: SearchOutcome) : ProfessionalSearch {
        var calls = 0
        val trades = mutableListOf<String?>()
        override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
            calls++
            trades += trade
            return outcome
        }
    }

    private val oviedo = Concejo("33044", "Oviedo")

    /** Featured of the zone first (12 km away), then by distance; «nowhere» has no location (added last). */
    private val ordered = SearchOutcome.Found(oviedo, listOf(
        NearbyResult("featured", 12000, featured = true), NearbyResult("near", 1000),
        NearbyResult("edge", 5000), NearbyResult("beyond", 5500), NearbyResult("unrated", 7000),
    ))

    private val home = HomeAddress("Calle Uría 1, Oviedo", 43.3619, -5.8494, "33044", "Oviedo", HomeSource.GPS)

    private val concejos = object : ConcejoDirectory {
        override suspend fun anchors() = listOf(ConcejoAnchor("33044", "Oviedo", 43.36, -5.85))
    }

    private val search = FakeSearch(ordered)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun plural(id: Int, n: Int) = context.resources.getQuantityString(id, n, n)

    private fun top(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun show(search: ProfessionalSearch = this.search, home: HomeAddress? = this.home) {
        compose.setContent {
            CatalogueScreen(catalogue, proximitySource = ProximitySource(search,
                ensurePermission = { true }, locate = { Located.Position(43.3625, -5.8434, 20f) },
                concejos = concejos, map = MapAvailability.NO_KEY, home = home, askIfAway = { false }))
        }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("catalogue_list").performScrollToNode(hasTestTag(tag))

    private fun waitFor(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun none(tag: String) = assertNull(
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull())

    private fun present(vararg ids: String) = ids.forEach { scrollTo("profile_$it") }

    /** Steps of the slider: 2 · 5 · 10 · 25 · 50 km · «Sin límite». */
    private fun distance(step: Int) =
        compose.onNodeWithTag("filters_distance").performSemanticsAction(SemanticsActions.SetProgress) { it(step.toFloat()) }

    /** Types in the search field and closes the keyboard, as the search key does, so the list is in view. */
    private fun type(text: String) {
        compose.onNodeWithTag("catalogue_search").performTextInput(text)
        compose.onNodeWithTag("catalogue_search").performImeAction()
    }

    private fun openFilters() = compose.onNodeWithTag("filters_open").performClick()

    private fun apply() = compose.onNodeWithTag("filters_apply").performClick()

    @Test fun aMaximumDistanceComparesTheBandShownOnTheCard() {
        show()
        waitFor("nearby_distance_edge")
        openFilters()
        distance(1)
        apply()
        // 4,7 km is shown as the 5 km band and passes; 5,2 km is the 5,5 km band and does not.
        present("near", "edge")
        none("profile_beyond")
    }

    @Test fun theLimitAlsoAppliesToTheFeaturedOfTheZone() {
        show()
        waitFor("nearby_distance_featured")
        openFilters()
        distance(2)
        apply()
        present("near")
        none("profile_featured")
    }

    @Test fun aProfileWithoutLocationLeavesWithALimitAndReturnsOnReset() {
        show()
        waitFor("nearby_distance_near")
        present("nowhere")
        openFilters()
        distance(4)
        apply()
        present("unrated")
        none("profile_nowhere")
        openFilters()
        compose.onNodeWithTag("filters_reset").performClick()
        apply()
        present("nowhere")
    }

    @Test fun aMinimumRatingLeavesOutProfilesWithoutReviewsAndBelowIt() {
        show()
        waitFor("nearby_distance_near")
        openFilters()
        compose.onNodeWithTag("filters_rating_4").performClick()
        apply()
        present("near")
        none("profile_beyond")
        none("profile_unrated")
        openFilters()
        compose.onNodeWithTag("filters_rating_3").performClick()
        apply()
        present("beyond")
        none("profile_unrated")
    }

    @Test fun aVisitorWithoutAPointCannotLimitDistanceButCanFilterByRating() {
        val search = FakeSearch(ordered)
        show(search, home = null)
        waitFor("profile_near")
        openFilters()
        compose.onNodeWithTag("filters_distance").assertIsNotEnabled()
        compose.onNodeWithTag("filters_distance_needs_point").assertIsDisplayed()
        compose.onNodeWithTag("filters_rating_4").performClick()
        apply()
        present("near", "nowhere")
        none("profile_unrated")
        none("profile_beyond")
        assertEquals(0, search.calls)
    }

    @Test fun theSheetCountsWhatWillRemainAndTheButtonCountsActiveFilters() {
        show()
        waitFor("nearby_distance_near")
        compose.onNodeWithTag("filters_open").assertTextEquals(text(R.string.filters_button))
        openFilters()
        compose.onNodeWithTag("filters_apply").assertTextEquals(plural(R.plurals.filters_apply, 6))
        compose.onNodeWithTag("filters_rating_4").performClick()
        compose.onNodeWithTag("filters_apply").assertTextEquals(plural(R.plurals.filters_apply, 4))
        distance(1)
        compose.onNodeWithTag("filters_apply").assertTextEquals(plural(R.plurals.filters_apply, 2))
        apply()
        compose.onNodeWithTag("filters_open").assertTextEquals(text(R.string.filters_button_active, 2))
        // Picking the trade in the sheet is tapping its chip: the server is asked again and it counts too.
        openFilters()
        compose.onNodeWithTag("filters_trade_plumbing").performClick()
        compose.onNodeWithTag("filters_apply").assertTextEquals(plural(R.plurals.filters_apply, 2))
        apply()
        compose.onNodeWithTag("filters_open").assertTextEquals(text(R.string.filters_button_active, 3))
        compose.onNodeWithTag("trade_plumbing").assertIsSelected()
        compose.waitUntil(5_000) { search.trades.lastOrNull() == "plumbing" }
    }

    @Test fun theOrderIsKeptAndTheTextFilterCombinesWithTheFilters() {
        show()
        waitFor("nearby_distance_near")
        openFilters()
        distance(3)
        apply()
        type("Borde")
        waitFor("profile_beyond")
        none("profile_near")
        none("profile_featured")
        assertTrue(top("profile_edge") < top("profile_beyond"))
    }

    @Test fun whenFiltersLeaveNobodyItSaysSoAndOffersAReset() {
        show()
        waitFor("nearby_distance_near")
        openFilters()
        distance(0)
        compose.onNodeWithTag("filters_rating_3").performClick()
        apply()
        type("Borde")
        waitFor("filters_empty")
        compose.onNodeWithTag("filters_empty").assertTextEquals(text(R.string.filters_empty))
        none("catalogue_empty")
        compose.onNodeWithTag("filters_empty_reset").performClick()
        present("near", "nowhere")
        compose.onNodeWithTag("filters_open").assertTextEquals(text(R.string.filters_button))
    }

    @Test fun filtersSurviveOpeningAProfileAndChangingThePoint() {
        show()
        waitFor("nearby_distance_near")
        openFilters()
        compose.onNodeWithTag("filters_rating_4").performClick()
        apply()
        none("profile_unrated")
        compose.onNodeWithTag("profile_near").performClick()
        waitFor("back_catalogue")
        compose.onNodeWithTag("back_catalogue").performClick()
        waitFor("profile_near")
        none("profile_unrated")
        // From home to one GPS fix: a new search point, the same filters.
        compose.onNodeWithTag("nearby_other_ways").performClick()
        scrollTo("nearby_locate")
        compose.onNodeWithTag("nearby_locate").performClick()
        compose.waitUntil(5_000) { search.calls == 2 }
        waitFor("nearby_distance_near")
        none("profile_unrated")
        compose.onNodeWithTag("filters_open").assertTextEquals(text(R.string.filters_button_active, 1))
    }

    @Test fun theMapViewCountsTheSameFilteredList() {
        profiles = profiles.map {
            if (it.id == "near") it.copy(businessAddress = "Calle Uría 2", businessLat = 43.362, businessLng = -5.849) else it
        }
        show()
        waitFor("nearby_distance_near")
        openFilters()
        distance(1)
        apply()
        compose.onNodeWithTag("map_toggle_map").performClick()
        scrollTo("map_coverage")
        // Two cards remain (near, edge); only edge lacks a public location.
        compose.onNodeWithTag("map_coverage").assertTextEquals(text(R.string.map_list_only, 1, 2))
    }
}
