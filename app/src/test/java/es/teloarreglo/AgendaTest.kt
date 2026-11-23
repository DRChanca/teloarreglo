package es.teloarreglo

import es.teloarreglo.appointments.*
import io.github.jan.supabase.SupabaseClient
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

/** Agenda configuration and free-visit confirmation (#21) through the real supabase-kt client. */
class AgendaTest {
    private val appointmentJson = """{"id":"appt","conversation_id":"conv","profile_id":"profile","client_id":"client",
        "professional_id":"pro","request_id":"key","problem":"Fuga","requested_start":"2030-01-15T10:00:00+00:00",
        "requested_end":"2030-01-15T11:00:00+00:00","address":"Calle 1","latitude":43.05,"longitude":-5.95,
        "version":3,"stage":"STAGE","created_at":"2026-11-23T10:00:00+00:00"}"""
    private fun proposalJson(status: String, amount: Int = 0, fee: String = "null") = """{"id":"p1","appointment_id":"appt",
        "author_id":"pro","version":1,"request_id":"k1","scope":"Visita","start_at":"2030-01-15T10:00:00+00:00",
        "end_at":"2030-01-15T11:00:00+00:00","duration_minutes":60,"amount_cents":$amount,"visit_fee_cents":$fee,
        "materials_included":false,"travel_included":true,"status":"$status","created_at":"2026-11-23T10:05:00+00:00",
        "decided_at":null}"""
    private val agendaJson = """{"profile_id":"profile","timezone":"Europe/Madrid","default_duration_minutes":60,
        "windows":[{"weekday":2,"start":"09:00","end":"14:00"}],
        "blocks":[{"id":"b1","start_at":"2030-01-15T11:00:00+00:00","end_at":"2030-01-15T12:00:00+00:00","reason":"Travel"}],
        "bookings":[{"appointment_id":"appt","start_at":"2030-01-15T09:00:00+00:00","end_at":"2030-01-15T10:00:00+00:00"}]}"""

    private suspend fun <T> withClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
        block: suspend (SupabaseClient) -> T,
    ): T {
        val client = createSupabaseClient("https://agenda.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
        return try { block(client) } finally { client.close() }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun error(code: String, message: String) =
        """{"code":"$code","message":"$message","details":null,"hint":null}"""

    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test fun clientAcceptsTheExactProposalVersionItSaw() = runTest {
        var body: JsonObject? = null
        val accepted = withClient({ request ->
            assertEquals("/rest/v1/rpc/accept_proposal", request.url.encodedPath)
            body = request.jsonBody()
            json(proposalJson("accepted"))
        }) { SupabaseAppointments(it).accept("p1") }
        assertEquals(JsonPrimitive("p1"), body!!["p_proposal_id"])
        assertEquals(ProposalStatus.ACCEPTED, accepted.status)
    }

    @Test fun conflictsAndOutdatedProposalsAreReportedWithoutConfirming() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Conflict, error("PT409", "slot_unavailable"), AppointmentFailure.SLOT_UNAVAILABLE),
            Triple(HttpStatusCode.Conflict, error("PT409", "stale_version"), AppointmentFailure.STALE),
            Triple(HttpStatusCode.Conflict, error("PT409", "proposal_expired"), AppointmentFailure.STALE),
            Triple(HttpStatusCode.BadRequest, error("22023", "payment_required"), AppointmentFailure.INVALID),
            Triple(HttpStatusCode.Forbidden, error("42501", "permission_denied"), AppointmentFailure.ACCESS),
        )
        for ((status, body, expected) in cases) {
            val failure = withClient({ json(body, status) }) {
                try { SupabaseAppointments(it).accept("p1"); null } catch (e: AppointmentException) { e.failure }
            }
            assertEquals(expected, failure)
        }
    }

    @Test fun bothAccountsSeeAConfirmedAppointmentWithItsAcceptedProposal() = runTest {
        val appointment = withClient({
            json("[" + appointmentJson.replace("STAGE", "confirmed").dropLast(1) +
                ""","appointment_proposals":[${proposalJson("accepted")}]}]""")
        }) { SupabaseAppointments(it).list("conv") }.single()
        assertEquals(AppointmentStage.CONFIRMED, appointment.stage)
        assertNull(appointment.currentProposal)
        assertEquals("p1", appointment.confirmedProposal?.id)
        assertEquals(AppointmentNotice.CONFIRMED, appointment.notice("client"))
        assertEquals(AppointmentNotice.CONFIRMED, appointment.notice("pro"))
    }

    @Test fun onlyFreeProposalsCanBeAcceptedWithoutPayment() {
        val json = Json { ignoreUnknownKeys = true }
        fun proposal(amount: Int, fee: String) = json.decodeFromString<AppointmentProposal>(proposalJson("pending", amount, fee))
        assertTrue(proposal(0, "null").isFree)
        assertTrue(proposal(0, "0").isFree)
        assertFalse(proposal(4000, "null").isFree)
        assertFalse(proposal(0, "1500").isFree)
    }

    @Test fun agendaLoadsWindowsBlocksAndBookings() = runTest {
        val agenda = withClient({ request ->
            assertEquals("/rest/v1/rpc/my_agenda", request.url.encodedPath)
            json(agendaJson)
        }) { SupabaseAgenda(it).load() }
        assertEquals(60, agenda.defaultDurationMinutes)
        assertEquals(listOf(AvailabilityWindow(2, "09:00", "14:00")), agenda.windows)
        assertEquals(Instant.parse("2030-01-15T11:00:00Z"), agenda.blocks.single().startAt)
        assertEquals("appt", agenda.bookings.single().appointmentId)
    }

    @Test fun savingSendsTheWholeWeeklyScheduleAndDuration() = runTest {
        var body: JsonObject? = null
        withClient({ request ->
            assertEquals("/rest/v1/rpc/save_agenda", request.url.encodedPath)
            body = request.jsonBody()
            json(agendaJson)
        }) { SupabaseAgenda(it).save(45, listOf(AvailabilityWindow(1, "08:00", "13:30"), AvailabilityWindow(2, "09:00", "14:00"))) }
        assertEquals(45, body!!["p_default_duration_minutes"]!!.jsonPrimitive.int)
        val windows = body!!["p_windows"]!!.jsonArray
        assertEquals(2, windows.size)
        assertEquals(JsonPrimitive(1), windows[0].jsonObject["weekday"])
        assertEquals(JsonPrimitive("13:30"), windows[0].jsonObject["end"])
    }

    @Test fun blocksUseARetryKeyAndHalfOpenInstants() = runTest {
        var body: JsonObject? = null
        withClient({ request ->
            assertEquals("/rest/v1/rpc/add_agenda_block", request.url.encodedPath)
            body = request.jsonBody()
            json("""{"id":"b1","during":"[\"2030-01-15 11:00:00+00\",\"2030-01-15 12:00:00+00\")","reason":"Travel"}""")
        }) { SupabaseAgenda(it).addBlock("key", Instant.parse("2030-01-15T11:00:00Z"), Instant.parse("2030-01-15T12:00:00Z"), "Travel") }
        assertEquals("key", body!!["p_request_id"]!!.jsonPrimitive.content)
        assertEquals("2030-01-15T11:00:00Z", body!!["p_start"]!!.jsonPrimitive.content)
        assertEquals("2030-01-15T12:00:00Z", body!!["p_end"]!!.jsonPrimitive.content)
    }

    @Test fun agendaRejectionsMapToStableFailures() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Conflict, error("PT409", "slot_unavailable"), AgendaFailure.SLOT_UNAVAILABLE),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_agenda"), AgendaFailure.INVALID),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_block"), AgendaFailure.INVALID),
            Triple(HttpStatusCode.BadRequest, error("22023", "retry_conflict"), AgendaFailure.RETRY_CONFLICT),
            Triple(HttpStatusCode.Forbidden, error("42501", "permission_denied"), AgendaFailure.ACCESS),
        )
        for ((status, body, expected) in cases) {
            val failure = withClient({ json(body, status) }) {
                try { SupabaseAgenda(it).addBlock("k", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), null); null }
                catch (e: AgendaException) { e.failure }
            }
            assertEquals(expected, failure)
        }
    }

    @Test fun removingABlockSendsItsId() = runTest {
        var body: JsonObject? = null
        withClient({ request ->
            assertEquals("/rest/v1/rpc/remove_agenda_block", request.url.encodedPath)
            body = request.jsonBody()
            json("""{"id":"b1","removed":true}""")
        }) { SupabaseAgenda(it).removeBlock("b1") }
        assertEquals(JsonPrimitive("b1"), body!!["p_block_id"])
    }
}
