package es.teloarreglo

import es.teloarreglo.catalogue.PublicProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «Tiempo de respuesta» (#76): the server sends only a band, and the app turns it into a sentence. */
class ResponseTimeTest {
    @Test fun eachBandHasItsSentence() {
        assertEquals(R.string.response_band_instant, responseBandLabel("instant"))
        assertEquals(R.string.response_band_under_30m, responseBandLabel("under_30m"))
        assertEquals(R.string.response_band_under_1h, responseBandLabel("under_1h"))
        assertEquals(R.string.response_band_under_3h, responseBandLabel("under_3h"))
        assertEquals(R.string.response_band_same_day, responseBandLabel("same_day"))
        assertEquals(R.string.response_band_over_day, responseBandLabel("over_day"))
    }

    @Test fun withoutDataNothingIsShown() {
        assertNull(responseBandLabel(null))
    }

    @Test fun anUnknownBandIsNotShown() {
        assertNull(responseBandLabel("under_10s"))
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val profile = """{"profile_id":"p","name":"N","description":"D","trades":[],"services":[],"coverage":[],
        "languages":[],"phone":"0","images":[],"prices":[]"""

    @Test fun thePublicProfileCarriesTheBand() {
        assertEquals("under_1h", json.decodeFromString<PublicProfile>("$profile,\"response_band\":\"under_1h\"}").responseBand)
    }

    @Test fun anOlderServerWithoutTheBandStillWorks() {
        assertNull(json.decodeFromString<PublicProfile>("$profile}").responseBand)
    }
}
