package es.teloarreglo

import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeSource
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.AccessState
import es.teloarreglo.identity.Account
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.Located
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search lives with the app (#59): it starts from home as soon as the account and its home are known,
 * so that the search screen finds it ready, and it survives leaving the screen.
 */
class AppSearchSessionTest {
    private val home = HomeAddress("Calle Uría 1, Oviedo", 43.3619, -5.8494, "33044", "Oviedo", HomeSource.GPS)
    private val fromHome = Sent(home.point, null, "33044")
    private fun found() = SearchOutcome.Found(Concejo("33044", "Oviedo"), listOf(NearbyResult("p1", 500)))

    private fun connected(userId: String, administrator: Boolean = false) =
        AccessState(AccessStage.CONNECTED, Account(userId, "$userId@example.com", isAdministrator = administrator))
    private val signedOut = AccessState(AccessStage.SIGNED_OUT)

    private fun TestScope.session(search: ProfessionalSearch) = AppSearchSession(search, onePlumberCatalogue, this)

    @Test fun aSignedInAccountWithAHomeSearchesFromHomeOnceWithEveryTrade() = runTest {
        val search = FakeSearch(found())
        val session = session(search)

        session.onAccess(connected("u1"), home)
        advanceUntilIdle()
        session.onAccess(connected("u1"), home)
        advanceUntilIdle()

        assertEquals(listOf(fromHome), search.sent)
        assertTrue(session.proximity.state is NearbyState.Results)
        assertEquals(SearchOrigin.Home, session.proximity.origin)
    }

    @Test fun visitorsAccountsWithoutHomeAndAdministratorsDoNotSearch() = runTest {
        val search = FakeSearch(found())
        val session = session(search)

        session.onAccess(signedOut, home)
        session.onAccess(connected("u1"), null)
        session.onAccess(connected("admin", administrator = true), home)
        advanceUntilIdle()

        assertEquals(emptyList<Sent>(), search.sent)
        assertEquals(NearbyState.Idle, session.proximity.state)
    }

    @Test fun whileTheSessionIsBeingCheckedTheSearchIsNeitherStartedNorForgotten() = runTest {
        // Every return to the foreground checks the session again, sometimes without its account yet.
        val search = FakeSearch(found())
        val session = session(search)

        session.onAccess(AccessState(AccessStage.CHECKING, Account("u1", "u1@example.com")), home)
        advanceUntilIdle()
        assertEquals(emptyList<Sent>(), search.sent)

        session.onAccess(connected("u1"), home)
        advanceUntilIdle()
        session.onAccess(AccessState(AccessStage.CHECKING), null)
        advanceUntilIdle()

        assertEquals(listOf(fromHome), search.sent)
        assertTrue(session.proximity.state is NearbyState.Results)
    }

    @Test fun aFailedSearchStaysFailedAndRetryRepeatsItFromHome() = runTest {
        val search = FakeSearch(SearchOutcome.Failed(SearchError.NETWORK), found())
        val session = session(search)

        session.onAccess(connected("u1"), home)
        advanceUntilIdle()
        assertEquals(NearbyState.Failed(SearchError.NETWORK), session.proximity.state)

        session.proximity.repeat()
        advanceUntilIdle()

        assertEquals(listOf(fromHome, fromHome), search.sent)
        assertTrue(session.proximity.state is NearbyState.Results)
    }

    @Test fun signingOutForgetsTheSearch() = runTest {
        val session = session(FakeSearch(found()))
        session.onAccess(connected("u1"), home)
        advanceUntilIdle()

        session.onAccess(signedOut, null)

        assertEquals(NearbyState.Idle, session.proximity.state)
        assertNull(session.proximity.origin)
    }

    @Test fun theChosenTradeIsKeptWithTheSearchAndForgottenWithTheAccount() = runTest {
        val search = FakeSearch(found())
        val session = session(search)
        session.onAccess(connected("u1"), home)
        advanceUntilIdle()

        session.trade.value = "plumbing"
        advanceUntilIdle()

        assertEquals(listOf(fromHome, fromHome.copy(trade = "plumbing")), search.sent)
        assertEquals("plumbing", session.trade.value)

        session.onAccess(connected("u2"), null)

        assertNull(session.trade.value)
    }

    @Test fun theScreensLocationIsOnlyUsedWhileTheScreenIsShown() = runTest {
        val session = session(FakeSearch(found()))
        session.onAccess(connected("u1"), home)
        advanceUntilIdle()
        var screenAsks = 0
        val screen = ProximitySource(FakeSearch(found()), ensurePermission = { screenAsks++; true },
            locate = { awaitCancellation() }, home = home)

        val shown = CoroutineScope(coroutineContext + Job())
        session.proximity.attach(screen, shown)
        session.proximity.useLocation()
        advanceUntilIdle()
        assertEquals(NearbyState.Locating, session.proximity.state)

        // Leaving the screen stops its location request and gives back what the search had.
        session.proximity.detach()
        advanceUntilIdle()
        assertTrue(session.proximity.state is NearbyState.Results)

        // Without the screen there is no permission prompt nor GPS.
        session.proximity.useLocation()
        advanceUntilIdle()
        assertEquals(1, screenAsks)
        assertEquals(NearbyState.PermissionDenied, session.proximity.state)
        shown.cancel()
    }
}
