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
        override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
            calls++
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

    private fun show(search: ProfessionalSearch = FakeSearch(ordered), home: HomeAddress? = this.home) {
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
}
