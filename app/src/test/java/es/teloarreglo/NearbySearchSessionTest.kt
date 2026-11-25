package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nearby search remembers where it started (#47): retry and a trade change repeat it from there. */
class NearbySearchSessionTest {
    private data class Sent(val point: GeoPoint, val trade: String?, val ineCode: String?)

    private class FakeSearch(vararg outcomes: SearchOutcome) : ProfessionalSearch {
        private val queue = ArrayDeque(outcomes.toList())
        val sent = mutableListOf<Sent>()
        override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
            sent += Sent(GeoPoint(lat, lng), trade, ineCode)
            return queue.removeFirst()
        }
    }

    private val catalogue = object : Catalogue {
        override suspend fun list(trade: String?) = listOf(PublicProfile(
            "p1", "Fontanería Nalón", "d", listOf("plumbing"), listOf("Fugas"), listOf(Coverage("33044", "Oviedo")),
            listOf("es"), "+34 000 000 000", listOf("plumbing"), emptyList()))
        override suspend fun profile(id: String) = null
    }

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
}
