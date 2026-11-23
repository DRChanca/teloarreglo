package es.teloarreglo

import es.teloarreglo.home.AwayFromHome
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.distanceMeters
import es.teloarreglo.home.HomeDraft
import es.teloarreglo.home.HomeException
import es.teloarreglo.home.HomeFailure
import es.teloarreglo.home.HomeSource
import es.teloarreglo.home.SupabaseHomeAddresses
import es.teloarreglo.search.GeoPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAddressTest {
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://home.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement((body as TextContent).text).jsonObject

    private val oviedo = """{"address":"Calle Uría 10, Oviedo","lat":43.3619,"lng":-5.8494,
        "ine_code":"33044","concejo_name":"Oviedo","source":"places","place_id":"ChIJ-demo"}"""

    @Test fun anAccountWithoutHomeReadsNoAddress() = runTest {
        val client = client { request ->
            assertEquals("/rest/v1/rpc/home_address", request.url.encodedPath)
            json("[]")
        }
        try { assertNull(SupabaseHomeAddresses(client).current()) } finally { client.close() }
    }

    @Test fun theOwnersHomeComesBackWithItsConcejo() = runTest {
        val client = client { json("[$oviedo]") }
        try {
            val home = SupabaseHomeAddresses(client).current()!!
            assertEquals("Calle Uría 10, Oviedo", home.address)
            assertEquals(GeoPoint(43.3619, -5.8494), home.point)
            assertEquals("33044", home.ineCode)
            assertEquals("Oviedo", home.concejoName)
            assertEquals(HomeSource.PLACES, home.source)
        } finally { client.close() }
    }

    @Test fun savingSendsTextPointAndSourceAndReturnsTheServerResolvedConcejo() = runTest {
        var sent: JsonObject? = null
        val client = client { request ->
            assertEquals("/rest/v1/rpc/set_home_address", request.url.encodedPath)
            sent = request.jsonBody()
            json("[$oviedo]")
        }
        try {
            val saved: HomeAddress = SupabaseHomeAddresses(client)
                .save(HomeDraft("  Calle Uría 10, Oviedo ", GeoPoint(43.3619, -5.8494), HomeSource.PLACES, "ChIJ-demo"))
            val body = sent!!
            assertEquals("Calle Uría 10, Oviedo", body["p_address"]!!.jsonPrimitive.content)
            assertEquals(43.3619, body["p_lat"]!!.jsonPrimitive.double, 0.0)
            assertEquals(-5.8494, body["p_lng"]!!.jsonPrimitive.double, 0.0)
            assertEquals("places", body["p_source"]!!.jsonPrimitive.content)
            assertEquals("ChIJ-demo", body["p_place_id"]!!.jsonPrimitive.content)
            assertEquals("33044", saved.ineCode)
        } finally { client.close() }
    }

    @Test fun serverRejectionsAreTellingAndNeverLookLikeLostResponses() = runTest {
        val cases = listOf(
            """{"code":"22023","message":"invalid_home_address"}""" to HttpStatusCode.BadRequest to HomeFailure.INVALID,
            """{"code":"28000","message":"authentication_required"}""" to HttpStatusCode.Unauthorized to HomeFailure.ACCESS,
        )
        for ((response, expected) in cases) {
            val client = client { json(response.first, response.second) }
            try {
                val failure = try {
                    SupabaseHomeAddresses(client).save(HomeDraft("x", GeoPoint(43.0, -5.0), HomeSource.PIN)); null
                } catch (error: HomeException) { error.failure }
                assertEquals(expected, failure)
            } finally { client.close() }
        }
    }

    private val home = GeoPoint(43.3619, -5.8494) // Oviedo centre

    @Test fun distanceBetweenOviedoAndGijonIsAboutTwentyFourKilometres() {
        val km = distanceMeters(home, GeoPoint(43.5322, -5.6611)) / 1000
        assertTrue("was $km km", km in 22.0..26.0)
    }

    @Test fun beingMoreThanFiveKilometresFromHomeAsksOnlyOncePerSession() {
        val session = AwayFromHome()
        assertFalse("4.4 km north is still near home", session.shouldAsk(home, GeoPoint(43.4019, -5.8494)))
        assertTrue("5.6 km north asks", session.shouldAsk(home, GeoPoint(43.4119, -5.8494)))
        assertFalse("already asked in this session", session.shouldAsk(home, GeoPoint(43.5322, -5.6611)))
        session.reset()
        assertTrue("a new session asks again", session.shouldAsk(home, GeoPoint(43.5322, -5.6611)))
    }

    @Test fun withoutHomeThereIsNothingToCompare() {
        assertFalse(AwayFromHome().shouldAsk(null, GeoPoint(43.5322, -5.6611)))
    }
}
