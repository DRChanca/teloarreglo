package es.teloarreglo

import es.teloarreglo.appointments.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** The wire of «informar posición» and «dejar de compartir» (docs/citas-contrato.md, #82). */
class ArrivalPositionsTest {
    private suspend fun <T> withClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
        block: suspend (ArrivalPositions) -> T,
    ): T {
        val client = createSupabaseClient("https://arrival.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
        return try { block(SupabaseArrivalPositions(client)) } finally { client.close() }
    }
    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun reportSendsThePositionAndReadsTheWindow() = runTest {
        var body: JsonObject? = null
        val report = withClient({ request ->
            assertEquals("/rest/v1/rpc/report_arrival_position", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json("""{"window":"open","accepted":true,"opens_at":"2030-01-15T09:00:00+00:00",
                "closes_at":"2030-01-15T11:00:00+00:00","reported_at":"2030-01-15T09:30:00+00:00"}""")
        }) { it.report("appt", ArrivalFix(43.36, -5.85, 12.0)) }
        val sent = body!!
        assertEquals("appt", sent["p_appointment_id"]!!.jsonPrimitive.content)
        assertEquals(43.36, sent["p_latitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(-5.85, sent["p_longitude"]!!.jsonPrimitive.double, 0.0)
        assertEquals(12.0, sent["p_accuracy_m"]!!.jsonPrimitive.double, 0.0)
        assertEquals(ArrivalReport(ArrivalWindow.OPEN, true, Instant.parse("2030-01-15T09:00:00Z"),
            Instant.parse("2030-01-15T11:00:00Z"), Instant.parse("2030-01-15T09:30:00Z")), report)
    }

    @Test fun aClosedWindowComesWithoutTimes() = runTest {
        val report = withClient({ json("""{"window":"closed","accepted":false,"opens_at":null,"closes_at":null,"reported_at":null}""") }) {
            it.report("appt", ArrivalFix(43.36, -5.85, null))
        }
        assertEquals(ArrivalReport(ArrivalWindow.CLOSED, accepted = false), report)
    }

    @Test fun aFixWithoutAccuracySendsANullAccuracy() = runTest {
        var body: JsonObject? = null
        withClient({ request ->
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json("""{"window":"open","accepted":true,"opens_at":null,"closes_at":null,"reported_at":null}""")
        }) { it.report("appt", ArrivalFix(43.36, -5.85, null)) }
        assertEquals(JsonNull, body!!["p_accuracy_m"])
    }

    @Test fun theWindowStateIsAskedWithoutAnyPosition() = runTest {
        var path: String? = null
        var body: JsonObject? = null
        val report = withClient({ request ->
            path = request.url.encodedPath
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json("""{"window":"not_yet_open","opens_at":"2030-01-15T09:00:00+00:00","closes_at":"2030-01-15T11:00:00+00:00"}""")
        }) { it.window("appt") }
        assertEquals("/rest/v1/rpc/arrival_sharing_window", path)
        assertEquals(setOf("p_appointment_id"), body!!.keys)
        assertEquals(ArrivalReport(ArrivalWindow.NOT_YET_OPEN, accepted = false, Instant.parse("2030-01-15T09:00:00Z"),
            Instant.parse("2030-01-15T11:00:00Z")), report)
    }

    @Test fun stopSharingCallsItsOwnFunction() = runTest {
        var path: String? = null
        val report = withClient({ request ->
            path = request.url.encodedPath
            json("""{"window":"not_yet_open","accepted":false,"opens_at":"2030-01-15T09:00:00+00:00",
                "closes_at":"2030-01-15T11:00:00+00:00","reported_at":null}""")
        }) { it.stopSharing("appt") }
        assertEquals("/rest/v1/rpc/stop_sharing_arrival", path)
        assertEquals(ArrivalWindow.NOT_YET_OPEN, report.window)
    }

    @Test fun permissionDeniedIsAFinalRejection() = runTest {
        val failure = withClient({ json("""{"code":"42501","message":"permission_denied","details":null,"hint":null}""",
            HttpStatusCode.Forbidden) }) {
            try { it.report("appt", ArrivalFix(43.36, -5.85, 12.0)); null } catch (e: AppointmentException) { e.failure }
        }
        assertEquals(AppointmentFailure.ACCESS, failure)
    }

    @Test fun anImpossiblePositionIsInvalidNotARejection() = runTest {
        val failure = withClient({ json("""{"code":"22023","message":"invalid_position","details":null,"hint":null}""",
            HttpStatusCode.BadRequest) }) {
            try { it.report("appt", ArrivalFix(43.36, -5.85, -1.0)); null } catch (e: AppointmentException) { e.failure }
        }
        assertEquals(AppointmentFailure.INVALID, failure)
    }
}
