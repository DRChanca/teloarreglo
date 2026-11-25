package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nearby search remembers where it started (#47): retry and a trade change repeat it from there. */
class NearbySearchSessionTest {
    private fun profile(id: String, zone: String) = PublicProfile(
        id, "Profesional $id", "d", listOf("plumbing"), listOf("Fugas"), listOf(Coverage(zone, zone)),
        listOf("es"), "+34 000 000 000", listOf("plumbing"), emptyList())

    private val catalogue = onePlumberCatalogue

    private val home = GeoPoint(43.36, -5.84)
    private val oviedo = Concejo("33044", "Oviedo")
    private fun found() = SearchOutcome.Found(oviedo, listOf(NearbyResult("p1", 500)))

    @Test fun aFailedSearchFromHomeIsRetriedFromHome() = runTest {
        val search = FakeSearch(SearchOutcome.Failed(SearchError.NETWORK), found())
        val session = NearbySearchSession(search, catalogue)
        val failed = session.search(NearbyQuery(SearchOrigin.Home, home, "33044"), trade = null)
        assertEquals(NearbyState.Failed(SearchError.NETWORK), failed)

        val retried = session.repeat(trade = null)

        assertTrue(retried is NearbyState.Results)
        assertEquals(listOf(Sent(home, null, "33044"), Sent(home, null, "33044")), search.sent)
        assertEquals(SearchOrigin.Home, session.last?.origin)
    }

    @Test fun aFailedManualSearchIsRetriedInThatConcejo() = runTest {
        val pin = GeoPoint(43.53, -5.66)
        val search = FakeSearch(SearchOutcome.Failed(SearchError.SERVICE), found())
        val session = NearbySearchSession(search, catalogue)
        session.search(NearbyQuery(SearchOrigin.Manual("Gijón"), pin, "33024"), trade = "plumbing")

        session.repeat(trade = "plumbing")

        assertEquals(Sent(pin, "plumbing", "33024"), search.sent.last())
    }

    @Test fun changingTheTradeRepeatsTheSearchFromTheSameOrigin() = runTest {
        val gps = GeoPoint(43.37, -5.85)
        val search = FakeSearch(found(), found())
        val session = NearbySearchSession(search, catalogue)
        session.search(NearbyQuery(SearchOrigin.Gps, gps, null), trade = null)

        session.repeat(trade = "electricity")

        assertEquals(listOf(Sent(gps, null, null), Sent(gps, "electricity", null)), search.sent)
    }

    @Test fun aConfirmedConcejoKeepsThePositionAndOrigin() = runTest {
        val gps = GeoPoint(43.37, -5.85)
        val search = FakeSearch(SearchOutcome.ConfirmConcejo(listOf(oviedo, Concejo("33035", "Llanera"))), found())
        val session = NearbySearchSession(search, catalogue)
        session.search(NearbyQuery(SearchOrigin.Gps, gps, null), trade = null)

        session.confirm("33035", trade = null)

        assertEquals(Sent(gps, null, "33035"), search.sent.last())
        // A later retry or trade change asks for the confirmed concejo, not the border again.
        assertEquals(NearbyQuery(SearchOrigin.Gps, gps, "33035"), session.last)
    }

    @Test fun withoutAPreviousSearchThereIsNothingToRepeat() = runTest {
        val search = FakeSearch()
        assertNull(NearbySearchSession(search, catalogue).repeat(trade = "plumbing"))
        assertEquals(emptyList<Sent>(), search.sent)
    }

    @Test fun everyoneIsListedByDistanceAndOutOfZoneIsMarked() = runTest {
        // ADR 0001: a neighbour outside the zone keeps its place by distance; a profile the server could not
        // place (no registered location) is appended without distance, its zone read from the public profile.
        val three = object : Catalogue {
            override suspend fun list(trade: String?) = listOf(profile("near", "33024"), profile("zone", "33044"),
                profile("unlocated", "33044"))
            override suspend fun profile(id: String) = null
        }
        val search = FakeSearch(SearchOutcome.Found(oviedo, listOf(
            NearbyResult("near", 1000, inZone = false), NearbyResult("zone", 3000))))
        val results = NearbySearchSession(search, three).search(NearbyQuery(SearchOrigin.Home, home, "33044"), null)

        results as NearbyState.Results
        assertEquals(listOf(SearchRow(three.list(null)[0], 1000, false), SearchRow(three.list(null)[1], 3000, true),
            SearchRow(three.list(null)[2], null, true)), results.rows)
    }

    @Test fun eachRowShowsTheSearchResponseTimeElseThePublicProfileOneWithoutChangingTheOrder() = runTest {
        // #80: the card prefers the band of the search result; a row the server did not place (or an older
        // server without the field) falls back to the public profile's band. The order stays the server's.
        val withBands = object : Catalogue {
            override suspend fun list(trade: String?) = listOf(
                profile("slow", "33044").copy(responseBand = "under_1h"),
                profile("old", "33044").copy(responseBand = "same_day"),
                profile("unlocated", "33044").copy(responseBand = "under_30m"),
                profile("none", "33044"))
            override suspend fun profile(id: String) = null
        }
        val search = FakeSearch(SearchOutcome.Found(oviedo, listOf(
            NearbyResult("slow", 500, responseBand = "over_day"), NearbyResult("old", 1000),
            NearbyResult("none", 1500, responseBand = null))))
        val results = NearbySearchSession(search, withBands).search(NearbyQuery(SearchOrigin.Home, home, "33044"), null)

        results as NearbyState.Results
        assertEquals(listOf("slow" to "over_day", "old" to "same_day", "none" to null, "unlocated" to "under_30m"),
            results.rows.map { it.profile.id to it.responseBand })
    }

    @Test fun aListWithoutASearchPointShowsThePublicProfileResponseTime() {
        val row = SearchRow(profile("p", "33044").copy(responseBand = "instant"), null, true)
        assertEquals("instant", row.responseBand)
    }

    @Test fun anOlderServerWithoutInZoneMeansEveryResultIsInZone() {
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(NearbyResult.serializer(), """{"profile_id":"p1","distance_band_m":500}""")
        assertTrue(decoded.inZone)
    }
}
