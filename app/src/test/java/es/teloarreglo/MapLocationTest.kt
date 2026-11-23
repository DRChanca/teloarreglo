package es.teloarreglo

import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.ConcejoAnchor
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.MapAvailability
import es.teloarreglo.search.SupabaseConcejoDirectory
import es.teloarreglo.search.externalMapsUri
import es.teloarreglo.search.mapAvailability
import es.teloarreglo.search.mapView
import es.teloarreglo.search.publicLocation
import es.teloarreglo.search.searchPoint
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MapLocationTest {
    private fun profile(id: String, address: String? = null, lat: Double? = null, lng: Double? = null) = PublicProfile(
        id, "Name $id", "", listOf("plumbing"), emptyList(), emptyList(), listOf("es"), "+34 000", emptyList(), emptyList(),
        businessAddress = address, businessLat = lat, businessLng = lng,
    )

    @Test fun manualChoiceListsConcejoAnchorsFromThePublicRpc() = runTest {
        val client = createSupabaseClient("https://map.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/rpc/concejo_anchors", request.url.encodedPath)
                respond("""[{"ine_code":"33044","name":"Oviedo","lat":43.36,"lng":-5.85}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            assertEquals(listOf(ConcejoAnchor("33044", "Oviedo", 43.36, -5.85)), SupabaseConcejoDirectory(client).anchors())
        } finally { client.close() }
    }

    @Test fun mapShowsTheListCandidatesInServerOrderOnlyWhereTheirPointIsPublic() {
        val rows = listOf(
            profile("a", "Calle Uría 1, Oviedo", 43.36, -5.85) to 500,
            profile("b") to 1000,
            profile("c", "Calle Corrida 2, Gijón", 43.54, -5.66) to 1500,
        )
        val view = mapView(rows)
        assertEquals(listOf("a", "c"), view.markers.map { it.profileId })
        assertEquals(listOf(500, 1500), view.markers.map { it.distanceBandM })
        assertEquals(GeoPoint(43.36, -5.85), view.markers.first().point)
        assertEquals("profiles without a public point stay in the list", 1, view.listOnly)
    }

    @Test fun aPointWithoutAnApprovedCommercialAddressIsNeverShown() {
        assertNull(profile("x", null, 43.36, -5.85).publicLocation())
        assertNull(profile("y", "  ", 43.36, -5.85).publicLocation())
        assertNull(profile("z", "Calle Uría 1", 43.36, null).publicLocation())
    }

    @Test fun externalNavigationSendsOnlyTheDestination() {
        val uri = externalMapsUri(GeoPoint(43.3655, -5.844))!!
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=43.365500%2C-5.844000", uri)
        assertFalse("the client's own position is never sent", uri.contains("origin"))
    }

    @Test fun impossibleCoordinatesDoNotOpenAnything() {
        assertNull(externalMapsUri(GeoPoint(91.0, 0.0)))
        assertNull(externalMapsUri(GeoPoint(Double.NaN, 0.0)))
    }

    @Test fun mapIsHiddenWithoutAKeyOrWithoutPlayServices() {
        assertEquals(MapAvailability.NO_KEY, mapAvailability(apiKey = "", playServicesReady = true))
        assertEquals(MapAvailability.UNAVAILABLE, mapAvailability(apiKey = "configured", playServicesReady = false))
        assertEquals(MapAvailability.READY, mapAvailability(apiKey = "configured", playServicesReady = true))
    }

    @Test fun withoutAPinTheConcejoAnchorIsTheSearchPoint() {
        val oviedo = ConcejoAnchor("33044", "Oviedo", 43.36, -5.85)
        assertEquals(GeoPoint(43.36, -5.85), searchPoint(oviedo, pin = null))
        assertEquals(GeoPoint(43.37, -5.80), searchPoint(oviedo, pin = GeoPoint(43.37, -5.80)))
    }
}
