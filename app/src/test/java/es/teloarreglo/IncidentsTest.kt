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

class IncidentsTest {
    private fun incidentJson(stage: String = "started", kind: String = "cancellation", status: String = "open",
                             blocks: Boolean = true, evidence: String = "") = """{"id":"inc","appointment_id":"appt",
        "opened_by":"client","request_id":"key","kind":"$kind","description":"Ya no quiero seguir",
        "performance":"${if (kind == "quality") "acknowledged" else "disputed"}","status":"$status",
        "appointment_stage":"$stage","payment_mode":"integrated","pending_cents":8500,"delivered_cents":0,
        "blocks_delivery":$blocks,"created_at":"2026-11-23T10:00:00+00:00"$evidence}"""
    private val photoEvidence = """{"id":"ev1","incident_id":"inc","author_id":"pro","request_id":"k1","kind":"message",
        "note":null,"message_id":"m1","message_author_id":"pro","message_body":"","message_created_at":"2026-11-23T09:00:00+00:00",
        "attachment_kind":"photo","attachment_path":"conv/m1.jpg","attachment_type":"image/jpeg","created_at":"2026-11-23T10:01:00+00:00"}"""
    private val noteEvidence = """{"id":"ev2","incident_id":"inc","author_id":"client","request_id":"k2","kind":"note",
        "note":"Llegó tarde","message_id":null,"message_author_id":null,"message_body":null,"message_created_at":null,
        "attachment_kind":null,"attachment_path":null,"attachment_type":null,"created_at":"2026-11-23T10:02:00+00:00"}"""

    private suspend fun <T> withClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
        block: suspend (Incidents, Appointments) -> T,
    ): T {
        val client = createSupabaseClient("https://incidents.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
        return try { block(SupabaseIncidents(client), SupabaseAppointments(client)) } finally { client.close() }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    private fun error(code: String, message: String) = """{"code":"$code","message":"$message","details":null,"hint":null}"""
    private fun HttpRequestData.jsonBody() = Json.parseToJsonElement((body as TextContent).text).jsonObject

    private fun appointment(stage: String, incidents: String = "", payment: String = "null") = Json { ignoreUnknownKeys = true }
        .decodeFromString<Appointment>("""{"id":"appt","conversation_id":"conv","client_id":"client","professional_id":"pro",
        "request_id":"key","problem":"Fuga","requested_start":"2030-01-15T10:00:00+00:00","requested_end":"2030-01-15T12:00:00+00:00",
        "version":3,"stage":"$stage","created_at":"2026-11-23T10:00:00+00:00","appointment_payments":$payment,
        "incidents":[$incidents]}""")

    @Test fun openingSendsKindDescriptionAndTheAcknowledgedNoticeWithItsKey() = runTest {
        var body: JsonObject? = null
        val incident = withClient({ request ->
            assertEquals("/rest/v1/rpc/open_incident", request.url.encodedPath)
            body = request.jsonBody()
            json(incidentJson())
        }) { incidents, _ -> incidents.open("appt", "key", IncidentKind.CANCELLATION, "Ya no quiero seguir") }
        assertEquals(mapOf("p_appointment_id" to "appt", "p_request_id" to "key", "p_kind" to "cancellation",
            "p_description" to "Ya no quiero seguir"), body!!.filterKeys { it != "p_visibility_acknowledged" }
            .mapValues { it.value.jsonPrimitive.content })
        assertTrue("only sent after the visibility notice", body!!["p_visibility_acknowledged"]!!.jsonPrimitive.boolean)
        assertEquals(IncidentKind.CANCELLATION, incident.kind)
        assertEquals(Performance.DISPUTED, incident.performance)
        assertEquals(AppointmentStage.STARTED, incident.appointmentStage)
        assertTrue(incident.blocksDelivery)
        assertEquals(8500, incident.pendingCents)
    }

    @Test fun evidenceIsEitherAConcreteMessageOrANote() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val evidence = withClient({ request ->
            assertEquals("/rest/v1/rpc/add_incident_evidence", request.url.encodedPath)
            bodies += request.jsonBody()
            json(if (bodies.size == 1) photoEvidence else noteEvidence)
        }) { incidents, _ ->
            listOf(incidents.addEvidence("inc", "k1", EvidenceDraft.Message("m1")),
                incidents.addEvidence("inc", "k2", EvidenceDraft.Note("Llegó tarde")))
        }
        assertEquals("m1", bodies[0]["p_message_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, bodies[0]["p_note"])
        assertEquals(JsonNull, bodies[1]["p_message_id"])
        assertEquals("Llegó tarde", bodies[1]["p_note"]!!.jsonPrimitive.content)
        assertTrue(bodies.all { it["p_visibility_acknowledged"]!!.jsonPrimitive.boolean })
        assertEquals("conv/m1.jpg", evidence[0].attachmentPath)
        assertEquals(EvidenceKind.MESSAGE, evidence[0].kind)
        assertEquals("Llegó tarde", evidence[1].note)
    }

    @Test fun serverRejectionsAreKeptApartFromNetworkUncertainty() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Forbidden, error("42501", "permission_denied"), IncidentFailure.ACCESS),
            Triple(HttpStatusCode.Conflict, error("PT409", "incident_open"), IncidentFailure.ALREADY_OPEN),
            Triple(HttpStatusCode.Conflict, error("PT409", "incident_closed"), IncidentFailure.CLOSED),
            Triple(HttpStatusCode.Conflict, error("PT409", "incompatible_state"), IncidentFailure.STALE),
            Triple(HttpStatusCode.BadRequest, error("22023", "retry_conflict"), IncidentFailure.RETRY_CONFLICT),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_incident"), IncidentFailure.INVALID),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_evidence"), IncidentFailure.INVALID),
        )
        for ((status, body, expected) in cases) {
            val failure = try {
                withClient({ json(body, status) }) { incidents, _ -> incidents.open("appt", "key", IncidentKind.QUALITY, "x") }
                null
            } catch (e: IncidentException) { e.failure }
            assertEquals(body, expected, failure)
        }
    }

    @Test fun theAppointmentListBringsItsIncidentsAndTheirEvidence() = runTest {
        var select = ""
        val listed = withClient({ request ->
            if (request.url.encodedPath == "/rest/v1/rpc/appointment_places") json("[]") else {
                select = request.url.parameters["select"].orEmpty()
                json("""[{"id":"appt","conversation_id":"conv","client_id":"client","professional_id":"pro","request_id":"key",
                    "problem":"Fuga","requested_start":"2030-01-15T10:00:00+00:00","requested_end":"2030-01-15T12:00:00+00:00",
                    "version":3,"stage":"started","created_at":"2026-11-23T10:00:00+00:00",
                    "incidents":[${incidentJson(evidence = ""","incident_evidence":[$noteEvidence,$photoEvidence]""")}]}]""")
            }
        }) { _, appointments -> appointments.list("conv") }
        assertTrue(select, "incidents(*,incident_evidence(*))" in select)
        val incident = listed.single().openIncident!!
        assertEquals(listOf("ev1", "ev2"), incident.evidence.map { it.id })
    }

    @Test fun onlyParticipantsOfAStartedDoneOrCompletedAppointmentOpenAnIncident() {
        assertFalse(appointment("confirmed").canOpenIncident("client"))
        assertFalse(appointment("cancelled").canOpenIncident("client"))
        assertTrue(appointment("started").canOpenIncident("pro"))
        assertTrue(appointment("done").canOpenIncident("client"))
        assertTrue(appointment("completed").canOpenIncident("client"))
        assertFalse(appointment("started").canOpenIncident("other"))
        assertFalse("while one is open, evidence goes to it", appointment("done", incidentJson("done")).canOpenIncident("client"))
        assertTrue(appointment("completed", incidentJson("done", status = "resolved")).canOpenIncident("client"))
    }

    @Test fun aCancellationRequestOnlyExistsWhileTheJobIsStarted() {
        assertEquals(IncidentKind.entries.toList(), appointment("started").incidentKinds)
        assertEquals(listOf(IncidentKind.QUALITY, IncidentKind.NOT_PERFORMED), appointment("done").incidentKinds)
        assertEquals(listOf(IncidentKind.QUALITY, IncidentKind.NOT_PERFORMED), appointment("completed").incidentKinds)
    }

    @Test fun aBlockingIncidentHidesTheConfirmationAndTellsBothParticipants() {
        val blocked = appointment("done", incidentJson("done", "quality"))
        assertFalse(blocked.canConfirmDone("client"))
        assertEquals(AppointmentNotice.INCIDENT_OPEN, blocked.notice("client"))
        assertEquals(AppointmentNotice.INCIDENT_OPEN, blocked.notice("pro"))
        val afterClose = appointment("completed", incidentJson("completed", "not_performed", blocks = false))
        assertEquals("a communication after the close does not hide the completion",
            AppointmentNotice.COMPLETED, afterClose.notice("client"))
        assertTrue(appointment("done").canConfirmDone("client"))
    }

    @Test fun theSilentCloseIsAnnouncedOnlyWhileTheServerClockWillApplyIt() {
        val marks = ""","done_at":"2026-11-23T10:00:00+00:00","reminder_due_at":"2026-11-24T10:00:00+00:00",
            "close_due_at":"2026-11-25T10:00:00+00:00""""
        fun withMarks(stage: String, incidents: String = "") = Json { ignoreUnknownKeys = true }.decodeFromString<Appointment>(
            """{"id":"appt","conversation_id":"conv","client_id":"client","professional_id":"pro","request_id":"key",
            "problem":"Fuga","requested_start":"2030-01-15T10:00:00+00:00","requested_end":"2030-01-15T12:00:00+00:00",
            "version":3,"stage":"$stage","created_at":"2026-11-23T10:00:00+00:00","appointment_payments":null,
            "incidents":[$incidents]$marks}""")
        assertEquals("2026-11-25T10:00:00Z", withMarks("done").silentCloseAt.toString())
        assertNull("a blocking incident stops the clock (#27)", withMarks("done", incidentJson("done", "quality")).silentCloseAt)
        assertNull("already closed", withMarks("completed").silentCloseAt)
    }

    @Test fun anOpenIncidentAcceptsEvidenceOnlyFromTheParticipants() {
        val open = appointment("started", incidentJson())
        assertTrue(open.canAddEvidence("client"))
        assertTrue(open.canAddEvidence("pro"))
        assertFalse(open.canAddEvidence("other"))
        assertFalse(appointment("started", incidentJson(status = "resolved")).canAddEvidence("client"))
    }

    private val resolution = ""","settlement":"split","settlement_refunded_cents":3000,"settlement_delivered_cents":5500,
        "settlement_reason":"Trabajo incompleto","settled_by":"admin","settled_at":"2026-11-24T10:00:00+00:00",
        "performance_decision":"performed","performance_reason":"Las fotos muestran el trabajo","performance_decided_by":"admin",
        "performance_decided_at":"2026-11-24T11:00:00+00:00","resolved_at":"2026-11-24T11:00:00+00:00""""

    @Test fun theResolutionKeepsTheMoneyAndThePerformanceDecisionApart() {
        val closed = appointment("completed", incidentJson("done", "not_performed", "resolved", evidence = resolution))
        val incident = closed.incidents.single()
        assertEquals(Settlement.SPLIT, incident.settlement)
        assertEquals(3000, incident.settlementRefundedCents)
        assertEquals(5500, incident.settlementDeliveredCents)
        assertEquals("Trabajo incompleto", incident.settlementReason)
        assertEquals(PerformanceDecision.PERFORMED, incident.performanceDecision)
        assertEquals("Las fotos muestran el trabajo", incident.performanceReason)
        assertEquals("2026-11-24T11:00:00Z", incident.resolvedAt.toString())
        assertNull("an open incident has no decision yet", appointment("done", incidentJson("done", "quality")).incidents.single().performanceDecision)
    }

    @Test fun aSettledIncidentNoLongerBlocksWhilePerformanceIsStillUnderReview() {
        val settledOnly = ""","settlement":"deliver","settlement_refunded_cents":0,"settlement_delivered_cents":8500,
            "settlement_reason":"Acuerdo","settled_at":"2026-11-24T10:00:00+00:00""""
        val appt = Json { ignoreUnknownKeys = true }.decodeFromString<Appointment>("""{"id":"appt","conversation_id":"conv",
            "client_id":"client","professional_id":"pro","request_id":"key","problem":"Fuga",
            "requested_start":"2030-01-15T10:00:00+00:00","requested_end":"2030-01-15T12:00:00+00:00","version":5,
            "stage":"completed","created_at":"2026-11-23T10:00:00+00:00","completed_at":"2026-11-24T10:00:00+00:00",
            "completion":"administration","appointment_payments":null,
            "incidents":[${incidentJson("done", "not_performed", evidence = settledOnly)}]}""")
        assertNull(appt.blockingIncident)
        assertEquals(Completion.ADMINISTRATION, appt.completion)
        assertEquals(AppointmentNotice.COMPLETED_BY_ADMINISTRATION, appt.notice("client"))
        assertEquals(AppointmentNotice.COMPLETED_BY_ADMINISTRATION, appt.notice("pro"))
        assertTrue("evidence can still be added until performance is decided", appt.canAddEvidence("client"))
    }
}
