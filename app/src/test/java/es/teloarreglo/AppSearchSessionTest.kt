package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeSource
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search lives with the app (#59): it starts from home as soon as the account and its home are known,
 * so that the search screen finds it ready, and it survives leaving the screen.
 */
class AppSearchSessionTest {
    private data class Sent(val point: GeoPoint, val trade: String?, val ineCode: String?)

    private class FakeSearch(vararg outcomes: SearchOutcome) : ProfessionalSearch {
        private val queue = ArrayDeque(outcomes.toList())
        val sent = mutableListOf<Sent>()
        override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
            sent += Sent(GeoPoint(lat, lng), trade, ineCode)
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    private val catalogue = object : Catalogue {
        override suspend fun list(trade: String?) = listOf(PublicProfile(
            "p1", "Fontanería Nalón", "d", listOf("plumbing"), listOf("Fugas"), listOf(Coverage("33044", "Oviedo")),
            listOf("es"), "+34 000 000 000", listOf("plumbing"), emptyList()))
        override suspend fun profile(id: String) = null
    }

    private val home = HomeAddress("Calle Uría 1, Oviedo", 43.3619, -5.8494, "33044", "Oviedo", HomeSource.GPS)
    private val fromHome = Sent(home.point, null, "33044")
    private fun found() = SearchOutcome.Found(Concejo("33044", "Oviedo"), listOf(NearbyResult("p1", 500)))

    private fun TestScope.session(search: ProfessionalSearch) = AppSearchSession(search, catalogue, this)

    @Test fun aSignedInAccountWithAHomeSearchesFromHomeOnceWithEveryTrade() = runTest {
        val search = FakeSearch(found())
        val session = session(search)

        session.onAccount(userId = "u1", administrator = false, home = home)
        advanceUntilIdle()
        session.onAccount(userId = "u1", administrator = false, home = home)
        advanceUntilIdle()

        assertEquals(listOf(fromHome), search.sent)
        assertTrue(session.proximity.state is NearbyState.Results)
        assertEquals(SearchOrigin.Home, session.proximity.origin)
    }

    @Test fun visitorsAccountsWithoutHomeAndAdministratorsDoNotSearch() = runTest {
        val search = FakeSearch(found())
        val session = session(search)

        session.onAccount(userId = null, administrator = false, home = home)
        session.onAccount(userId = "u1", administrator = false, home = null)
        session.onAccount(userId = "admin", administrator = true, home = home)
        advanceUntilIdle()

        assertEquals(emptyList<Sent>(), search.sent)
        assertEquals(NearbyState.Idle, session.proximity.state)
    }

    @Test fun aFailedSearchStaysFailedAndRetryRepeatsItFromHome() = runTest {
        val search = FakeSearch(SearchOutcome.Failed(SearchError.NETWORK), found())
        val session = session(search)

        session.onAccount(userId = "u1", administrator = false, home = home)
        advanceUntilIdle()
        assertEquals(NearbyState.Failed(SearchError.NETWORK), session.proximity.state)

        session.proximity.repeat()
        advanceUntilIdle()

        assertEquals(listOf(fromHome, fromHome), search.sent)
        assertTrue(session.proximity.state is NearbyState.Results)
    }

    @Test fun signingOutForgetsTheSearch() = runTest {
        val session = session(FakeSearch(found()))
        session.onAccount(userId = "u1", administrator = false, home = home)
        advanceUntilIdle()

        session.onAccount(userId = null, administrator = false, home = null)

        assertEquals(NearbyState.Idle, session.proximity.state)
        assertEquals(null, session.proximity.origin)
    }
}
