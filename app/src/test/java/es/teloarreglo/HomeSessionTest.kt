package es.teloarreglo

import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeAddresses
import es.teloarreglo.home.HomeDraft
import es.teloarreglo.home.HomeSession
import es.teloarreglo.home.HomeSource
import es.teloarreglo.home.HomeStatus
import es.teloarreglo.search.GeoPoint
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHomes : HomeAddresses {
    val stored = mutableMapOf<String, HomeAddress>()
    var user: String? = null
    var offline = false
    override suspend fun current(): HomeAddress? {
        if (offline) throw IOException("offline")
        return stored[user]
    }
    override suspend fun save(draft: HomeDraft): HomeAddress {
        if (offline) throw IOException("offline")
        return HomeAddress(draft.address.trim(), draft.point.lat, draft.point.lng, "33044", "Oviedo", draft.source, draft.placeId)
            .also { stored[user!!] = it }
    }
}

class HomeSessionTest {
    private val oviedo = GeoPoint(43.3619, -5.8494)
    private val gijon = GeoPoint(43.5322, -5.6611)

    @Test fun anAccountWithoutHomeMustProvideOneAndThenContinues() = runTest {
        val homes = FakeHomes().apply { user = "a" }
        val session = HomeSession(homes)
        session.load("a")
        assertEquals(HomeStatus.Missing, session.status.value)
        assertTrue(session.required)
        session.save(HomeDraft("Calle Uría 10, Oviedo", oviedo, HomeSource.PIN))
        assertEquals("Calle Uría 10, Oviedo", (session.status.value as HomeStatus.Present).home.address)
        assertFalse(session.required)
    }

    @Test fun notKnowingBecauseOfTheNetworkNeverBlocksTheApp() = runTest {
        val session = HomeSession(FakeHomes().apply { user = "a"; offline = true })
        session.load("a")
        assertEquals(HomeStatus.Failed, session.status.value)
        assertFalse(session.required)
    }

    @Test fun signingOutForgetsTheHomeAndTheFarAwayQuestionStartsAgain() = runTest {
        val homes = FakeHomes().apply { user = "a" }
        val session = HomeSession(homes)
        session.load("a")
        session.save(HomeDraft("Calle Uría 10, Oviedo", oviedo, HomeSource.PIN))
        assertTrue(session.askIfAway(gijon))
        assertFalse(session.askIfAway(gijon))
        session.load(null)
        assertEquals(HomeStatus.Unknown, session.status.value)
        homes.user = "a"
        session.load("a")
        assertTrue("new session, new question", session.askIfAway(gijon))
    }
}
