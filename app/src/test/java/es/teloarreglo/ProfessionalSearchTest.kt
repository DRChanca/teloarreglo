package es.teloarreglo

import es.teloarreglo.search.Concejo
import es.teloarreglo.search.HttpProfessionalSearch
import es.teloarreglo.search.NearbyResult
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Edge Function contract seen from Android, with only the HTTP service replaced. */
class ProfessionalSearchTest {
    private var lastBody = ""
    private var lastKey: String? = null
    private var lastPath = ""

    private fun search(status: HttpStatusCode, body: String) = HttpProfessionalSearch(
        "https://project.example.org/", "sb_publishable_test",
        HttpClient(MockEngine { request ->
            lastBody = request.body.toByteArray().decodeToString()
            lastKey = request.headers["apikey"]
            lastPath = request.url.encodedPath
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
    )

    @Test fun resultsKeepServerOrderAndOnlyTheRoundedDistance() = runTest {
        val outcome = search(HttpStatusCode.OK, """{"status":"ok","concejo":{"ine_code":"33044","name":"Oviedo"},
            "method":"h3","results":[{"profile_id":"a","distance_band_m":500},{"profile_id":"b","distance_band_m":1500}]}""")
            .search(43.36, -5.84, "plumbing")
        assertEquals(
            SearchOutcome.Found(Concejo("33044", "Oviedo"), listOf(NearbyResult("a", 500), NearbyResult("b", 1500))),
            outcome,
        )
        assertEquals("/functions/v1/search-professionals", lastPath)
        assertEquals("sb_publishable_test", lastKey)
        val sent = Json.parseToJsonElement(lastBody).jsonObject
        assertEquals("plumbing", sent["trade"]?.jsonPrimitive?.content)
        assertFalse("no concejo is guessed by the app", sent.containsKey("ine_code"))
    }

    @Test fun aBorderAsksToConfirmAndTheChoiceIsSent() = runTest {
        val api = search(HttpStatusCode.OK, """{"status":"confirm_concejo","options":[{"ine_code":"33044","name":"Oviedo"},
            {"ine_code":"33035","name":"Llanera"}],"results":[]}""")
        assertEquals(
            SearchOutcome.ConfirmConcejo(listOf(Concejo("33044", "Oviedo"), Concejo("33035", "Llanera"))),
            api.search(43.41, -5.84, null),
        )
        api.search(43.41, -5.84, null, ineCode = "33035")
        assertEquals("33035", Json.parseToJsonElement(lastBody).jsonObject["ine_code"]?.jsonPrimitive?.content)
        assertFalse(Json.parseToJsonElement(lastBody).jsonObject.containsKey("trade"))
    }

    @Test fun outsideAsturiasIsNotAnEmptySuccess() = runTest {
        assertEquals(SearchOutcome.OutsideArea, search(HttpStatusCode.OK, """{"status":"outside_area","results":[]}""").search(40.4, -3.7, null))
    }

    @Test fun serverFailuresAreNeverAnEmptyList() = runTest {
        assertEquals(SearchOutcome.Failed(SearchError.SERVICE),
            search(HttpStatusCode.ServiceUnavailable, """{"error":"service_unavailable"}""").search(43.36, -5.84, null))
        assertEquals(SearchOutcome.Failed(SearchError.UNKNOWN_CONCEJO),
            search(HttpStatusCode.BadRequest, """{"error":"unknown_concejo"}""").search(43.36, -5.84, null, "33999"))
        assertEquals(SearchOutcome.Failed(SearchError.INPUT),
            search(HttpStatusCode.BadRequest, """{"error":"invalid_input"}""").search(43.36, -5.84, null))
        assertEquals(SearchOutcome.Failed(SearchError.SERVICE),
            search(HttpStatusCode.OK, """{"unexpected":true}""").search(43.36, -5.84, null))
    }

    @Test fun lostConnectivityIsReportedAsNetwork() = runTest {
        val api = HttpProfessionalSearch("https://project.example.org", "sb_publishable_test",
            HttpClient(MockEngine { throw IOException("offline") }))
        assertEquals(SearchOutcome.Failed(SearchError.NETWORK), api.search(43.36, -5.84, null))
    }
}
