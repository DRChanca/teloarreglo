package es.teloarreglo

import es.teloarreglo.appointments.*
import es.teloarreglo.search.GeoPoint
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

class AppointmentsTest {
    private val appointmentJson = """{"id":"appt","conversation_id":"conv","profile_id":"profile","client_id":"client",
        "professional_id":"pro","request_id":"key","problem":"Fuga","requested_start":"2030-01-15T10:00:00+00:00",
        "requested_end":"2030-01-15T12:00:00+00:00","area_lat":43.051,"area_lng":-5.948,"area_radius_m":400,
        "area_ine_code":"33044","location_shared_at":null,"version":1,"stage":"pending","created_at":"2026-11-23T10:00:00+00:00"}"""
    private fun proposalJson(version: Int, status: String) = """{"id":"p$version","appointment_id":"appt",
        "author_id":"pro","version":$version,"request_id":"k$version","scope":"Sifón","start_at":"2030-01-15T10:30:00+00:00",
        "end_at":"2030-01-15T12:00:00+00:00","duration_minutes":90,"amount_cents":8500,"visit_fee_cents":null,
        "materials_included":true,"travel_included":false,"status":"$status","created_at":"2026-11-23T10:05:00+00:00",
        "decided_at":null}"""

    private suspend fun <T> withClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
        block: suspend (Appointments) -> T,
    ): T {
        val client = createSupabaseClient("https://appointments.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
        return try { block(SupabaseAppointments(client)) } finally { client.close() }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun placeJson(exact: Boolean) = if (exact)
        """{"appointment_id":"appt","exact":true,"address":"Calle 1","latitude":43.05,"longitude":-5.95,
        "area_lat":43.051,"area_lng":-5.948,"area_radius_m":400,"ine_code":"33044","concejo_name":"Oviedo"}"""
    else """{"appointment_id":"appt","exact":false,"address":null,"latitude":null,"longitude":null,
        "area_lat":43.051,"area_lng":-5.948,"area_radius_m":400,"ine_code":"33044","concejo_name":"Oviedo"}"""

    /** The list reads the table and then the places; [places] answers the second call. */
    private fun MockRequestHandleScope.listing(request: HttpRequestData, proposals: String, places: String = "[]") =
        if (request.url.encodedPath == "/rest/v1/rpc/appointment_places") json(places)
        else json("[" + appointmentJson.dropLast(1) + ""","appointment_proposals":[$proposals]}]""")

    private fun error(code: String, message: String) =
        """{"code":"$code","message":"$message","details":null,"hint":null}"""

    @Test fun requestSharesExactAddressAndPinWithUtcInstantsAndNoPrice() = runTest {
        var body: JsonObject? = null
        val appointment = withClient({ request ->
            assertEquals("/rest/v1/rpc/request_appointment", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json(appointmentJson)
        }) {
            it.request("conv", "key", AppointmentRequest("Fuga", Instant.parse("2030-01-15T10:00:00Z"),
                Instant.parse("2030-01-15T12:00:00Z"), "Calle 1", 43.05, -5.95))
        }
        val sent = body!!
        assertEquals("2030-01-15T10:00:00Z", sent["p_start"]!!.jsonPrimitive.content)
        assertEquals("Calle 1", sent["p_address"]!!.jsonPrimitive.content)
        assertEquals(43.05, sent["p_lat"]!!.jsonPrimitive.double, 0.0)
        assertFalse(sent.keys.any { "amount" in it || "price" in it })
        assertEquals(AppointmentStage.PENDING, appointment.stage)
        assertEquals(Instant.parse("2030-01-15T10:00:00Z"), appointment.requestedStart)
    }

    @Test fun historyKeepsRejectedVersionsAndShowsLatestPendingAsCurrent() = runTest {
        val appointments = withClient({ request ->
            if (request.url.encodedPath == "/rest/v1/appointments")
                assertEquals("eq.conv", request.url.parameters["conversation_id"])
            listing(request, "${proposalJson(2, "pending")},${proposalJson(1, "rejected")}")
        }) { it.list("conv") }
        val appointment = appointments.single()
        assertEquals(listOf(1, 2), appointment.proposals.map { it.version })
        assertEquals(2, appointment.currentProposal?.version)
        assertEquals(ProposalStatus.REJECTED, appointment.proposals.first().status)
    }

    @Test fun listNeverAsksTheTableForTheExactPlaceAndReadsItThroughThePlaceFunction() = runTest {
        var placesBody: JsonObject? = null
        val appointment = withClient({ request ->
            if (request.url.encodedPath == "/rest/v1/appointments") {
                val select = request.url.parameters["select"]!!
                // The server refuses these columns to every account (#38); asking for them fails the whole read.
                assertFalse(select, Regex("""(^|,)(\*|address|latitude|longitude)(,|$)""").containsMatchIn(select))
                assertTrue(select.contains("appointment_proposals(*)"))
            } else placesBody = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            listing(request, "", "[${placeJson(exact = false)}]")
        }) { it.list("conv") }.single()
        assertEquals("conv", placesBody!!["p_conversation_id"]!!.jsonPrimitive.content)
        assertEquals(AppointmentLocation.Approximate(GeoPoint(43.051, -5.948), 400, "Oviedo"), appointment.location)
    }

    @Test fun confirmedPlaceGivesTheExactAddressAndPin() = runTest {
        val appointment = withClient({ listing(it, "", "[${placeJson(exact = true)}]") }) { it.list("conv") }.single()
        assertEquals(AppointmentLocation.Exact("Calle 1", GeoPoint(43.05, -5.95)), appointment.location)
    }

    @Test fun locationIsUnknownUntilThePlaceArrivesAndNeverExactWithoutAnAddress() = runTest {
        val missing = withClient({ listing(it, "") }) { it.list("conv") }.single()
        assertNull(missing.location)
        val incomplete = withClient({
            listing(it, "", "[" + placeJson(exact = true).replace(""""address":"Calle 1"""", """"address":null""") + "]")
        }) { it.list("conv") }.single()
        assertTrue(incomplete.location is AppointmentLocation.Approximate)
    }

    @Test fun noCurrentProposalAfterTheOnlyOneIsRejected() = runTest {
        val appointment = withClient({ listing(it, proposalJson(1, "rejected")) }) { it.list("conv") }.single()
        assertNull(appointment.currentProposal)
        assertEquals(AppointmentStage.PENDING, appointment.stage)
    }

    @Test fun proposalSendsCentsDurationAndExpectedVersion() = runTest {
        var body: JsonObject? = null
        val proposal = withClient({ request ->
            assertEquals("/rest/v1/rpc/propose_appointment", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json(proposalJson(1, "pending"))
        }) {
            it.propose("appt", 1, "k1", ProposalDraft("Sifón", Instant.parse("2030-01-15T10:30:00Z"), 90, 8500,
                null, materialsIncluded = true, travelIncluded = false))
        }
        assertEquals(1, body!!["p_expected_version"]!!.jsonPrimitive.int)
        assertEquals(8500, body!!["p_amount_cents"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, body!!["p_visit_fee_cents"])
        assertEquals(Instant.parse("2030-01-15T12:00:00Z"), proposal.endAt)
    }

    @Test fun serverRejectionsMapToStableFailures() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Forbidden, error("42501", "permission_denied"), AppointmentFailure.ACCESS),
            Triple(HttpStatusCode.Conflict, error("PT409", "stale_version"), AppointmentFailure.STALE),
            Triple(HttpStatusCode.BadRequest, error("22023", "out_of_coverage"), AppointmentFailure.OUT_OF_COVERAGE),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_appointment"), AppointmentFailure.INVALID),
            Triple(HttpStatusCode.BadRequest, error("22023", "retry_conflict"), AppointmentFailure.RETRY_CONFLICT),
        )
        for ((status, body, expected) in cases) {
            val failure = withClient({ json(body, status) }) {
                try { it.reject("p1"); null } catch (e: AppointmentException) { e.failure }
            }
            assertEquals(expected, failure)
        }
    }

    @Test fun lostResponseIsRetriedWithTheSameRequestKey() = runTest {
        var attempts = 0
        withClient({ request ->
            val sent = (request.body as TextContent).text
            assertTrue(sent.contains("\"p_request_id\":\"same-key\""))
            if (++attempts == 1) throw java.io.IOException("response lost")
            json(appointmentJson)
        }) { appointments ->
            val request = AppointmentRequest("Fuga", Instant.parse("2030-01-15T10:00:00Z"),
                Instant.parse("2030-01-15T12:00:00Z"), "Calle 1", 43.05, -5.95)
            var lost = false
            try { appointments.request("conv", "same-key", request) } catch (_: java.io.IOException) { lost = true }
            assertTrue(lost)
            assertEquals("appt", appointments.request("conv", "same-key", request).id)
        }
    }

    @Test fun eachAccountSeesTheNoticeThatMatchesItsRole() = runTest {
        suspend fun noticeFor(proposals: String, account: String) = withClient({ listing(it, proposals) }) {
            it.list("conv")
        }.single().notice(account)
        assertEquals(AppointmentNotice.AWAITING_PROPOSAL, noticeFor("", "client"))
        assertEquals(AppointmentNotice.SEND_PROPOSAL, noticeFor("", "pro"))
        assertEquals(AppointmentNotice.DECIDE_PROPOSAL, noticeFor(proposalJson(1, "pending"), "client"))
        assertEquals(AppointmentNotice.AWAITING_CLIENT, noticeFor(proposalJson(1, "pending"), "pro"))
        assertEquals(AppointmentNotice.AWAITING_NEW_PROPOSAL, noticeFor(proposalJson(1, "rejected"), "client"))
        assertEquals(AppointmentNotice.SEND_NEW_PROPOSAL, noticeFor(proposalJson(1, "rejected"), "pro"))
    }

    @Test fun euroInputIsParsedToExactCentsWithoutFloatingPoint() {
        assertEquals(8550, parseEuroCents("85,5"))
        assertEquals(8550, parseEuroCents("85.50"))
        assertEquals(0, parseEuroCents("0"))
        assertEquals(12, parseEuroCents("0,12"))
        assertNull(parseEuroCents("-1"))
        assertNull(parseEuroCents("1,234"))
        assertNull(parseEuroCents("abc"))
        assertNull(parseEuroCents(""))
    }
}
