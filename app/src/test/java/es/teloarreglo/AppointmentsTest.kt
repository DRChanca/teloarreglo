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
    private fun MockRequestHandleScope.listing(request: HttpRequestData, proposals: String, places: String = "[]",
                                               payment: String = "null", stage: String = "pending") =
        if (request.url.encodedPath == "/rest/v1/rpc/appointment_places") json(places)
        else json("[" + appointmentJson.dropLast(1).replace("\"stage\":\"pending\"", "\"stage\":\"$stage\"") +
            ""","appointment_proposals":[$proposals],"appointment_payments":$payment}]""")

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
            if (request.url.encodedPath == "/rest/v1/appointments") {
                assertEquals("eq.conv", request.url.parameters["conversation_id"])
                assertTrue(request.url.parameters["select"]!!.endsWith(",appointment_proposals(*),appointment_payments(*),appointment_changes(*)"))
            }
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

    private val paymentJson = """{"appointment_id":"appt","proposal_id":"p1","client_id":"client","professional_id":"pro",
        "request_id":"pay-key","mode":"integrated","agreed_cents":8500,"paid_cents":8500,"pending_cents":8500,
        "refunded_cents":0,"delivered_cents":0,"simulated":true,"created_at":"2026-11-23T10:10:00+00:00"}"""

    @Test fun integratedConfirmationSendsModeAndKeyAndReturnsTheSimulatedBalance() = runTest {
        var body: JsonObject? = null
        val payment = withClient({ request ->
            assertEquals("/rest/v1/rpc/confirm_proposal", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json(paymentJson)
        }) { it.confirm("p1", "pay-key", PaymentMode.INTEGRATED) }
        assertEquals("p1", body!!["p_proposal_id"]!!.jsonPrimitive.content)
        assertEquals("pay-key", body!!["p_request_id"]!!.jsonPrimitive.content)
        assertEquals("integrated", body!!["p_mode"]!!.jsonPrimitive.content)
        assertEquals(PaymentMode.INTEGRATED, payment.mode)
        assertEquals(8500, payment.pendingCents)
        assertEquals(0, payment.deliveredCents)
    }

    @Test fun directConfirmationIsSentAsDirect() = runTest {
        var mode: String? = null
        withClient({ request ->
            mode = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["p_mode"]!!.jsonPrimitive.content
            json(paymentJson.replace("\"integrated\"", "\"direct\"").replace("8500,\"pending", "0,\"pending")
                .replace("\"pending_cents\":8500", "\"pending_cents\":0"))
        }) { it.confirm("p1", "pay-key", PaymentMode.DIRECT) }
        assertEquals("direct", mode)
    }

    @Test fun confirmedAppointmentCarriesItsPaymentAndFreeOnesHaveNone() = runTest {
        val paid = withClient({ listing(it, proposalJson(1, "accepted"), payment = paymentJson, stage = "confirmed") }) {
            it.list("conv") }.single()
        assertEquals(8500, paid.payment?.paidCents)
        assertEquals(AppointmentNotice.CONFIRMED, paid.notice("client"))
        val free = withClient({ listing(it, "") }) { it.list("conv") }.single()
        assertNull(free.payment)
    }

    @Test fun amountPaidUpFrontIsThePriceOrTheVisitFeeAlone() = runTest {
        val proposals = withClient({ listing(it,
            proposalJson(1, "superseded").replace("\"visit_fee_cents\":null", "\"visit_fee_cents\":3000") + "," +
            proposalJson(2, "pending").replace("\"amount_cents\":8500", "\"amount_cents\":0")
                .replace("\"visit_fee_cents\":null", "\"visit_fee_cents\":2500")) }) { it.list("conv") }
            .single().proposals
        assertEquals(8500, proposals[0].payableCents)
        assertEquals(2500, proposals[1].payableCents)
        assertFalse(proposals[1].isFree)
    }

    @Test fun paymentRejectionsMapToStableFailures() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Conflict, error("PT409", "slot_unavailable"), AppointmentFailure.SLOT_UNAVAILABLE),
            Triple(HttpStatusCode.BadRequest, error("22023", "retry_conflict"), AppointmentFailure.RETRY_CONFLICT),
            Triple(HttpStatusCode.BadRequest, error("22023", "payment_not_required"), AppointmentFailure.INVALID),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_payment"), AppointmentFailure.INVALID),
            Triple(HttpStatusCode.Conflict, error("PT409", "incompatible_state"), AppointmentFailure.STALE),
        )
        for ((status, body, expected) in cases) {
            val failure = withClient({ json(body, status) }) {
                try { it.confirm("p1", "k", PaymentMode.INTEGRATED); null } catch (e: AppointmentException) { e.failure }
            }
            assertEquals(expected, failure)
        }
    }

    private fun changeJson(version: Int, status: String, author: String = "pro", amount: Int = 12000,
                           kind: String = "change", mode: String? = null) = """{"id":"c$version","appointment_id":"appt",
        "author_id":"$author","version":$version,"request_id":"ck$version","kind":"$kind","base_proposal_id":"p1",
        "scope":${if (kind == "change") "\"Sifón y llave\"" else "null"},
        "start_at":${if (kind == "change") "\"2030-01-15T10:30:00+00:00\"" else "null"},
        "end_at":${if (kind == "change") "\"2030-01-15T12:30:00+00:00\"" else "null"},
        "duration_minutes":${if (kind == "change") 120 else "null"},"amount_cents":${if (kind == "change") amount else "null"},
        "visit_fee_cents":null,"materials_included":${if (kind == "change") "true" else "null"},
        "travel_included":${if (kind == "change") "false" else "null"},"address":null,"latitude":null,"longitude":null,
        "previous_address":null,"previous_latitude":null,"previous_longitude":null,
        "note":${if (kind == "change") "null" else "\"Falta el piso\""},"payment_mode":${mode?.let { "\"$it\"" } ?: "null"},
        "status":"$status","result_proposal_id":null,"created_at":"2026-11-23T11:00:00+00:00","decided_at":null}"""

    private fun MockRequestHandleScope.confirmedListing(request: HttpRequestData, changes: String, payment: String = paymentJson) =
        if (request.url.encodedPath == "/rest/v1/rpc/appointment_places") json("[]")
        else json("[" + appointmentJson.dropLast(1).replace("\"stage\":\"pending\"", "\"stage\":\"confirmed\"") +
            ""","appointment_proposals":[${proposalJson(1, "accepted")}],"appointment_payments":$payment,
            "appointment_changes":[$changes]}]""")

    @Test fun listReadsChangesAndThePendingOneLeavesTheAgreedConditionsInForce() = runTest {
        val appointment = withClient({ request ->
            if (request.url.encodedPath == "/rest/v1/appointments")
                assertTrue(request.url.parameters["select"]!!.contains("appointment_changes(*)"))
            confirmedListing(request, "${changeJson(2, "pending")},${changeJson(1, "rejected")}")
        }) { it.list("conv") }.single()
        assertEquals(listOf(1, 2), appointment.changes.map { it.version })
        assertEquals("c2", appointment.pendingChange?.id)
        assertEquals(8500, appointment.confirmedProposal?.amountCents)
        assertEquals(AppointmentNotice.DECIDE_CHANGE, appointment.notice("client"))
        assertEquals(AppointmentNotice.AWAITING_CHANGE, appointment.notice("pro"))
    }

    @Test fun addressCorrectionAsksTheClientAndNoticeReturnsToConfirmedOnceDecided() = runTest {
        val asked = withClient({ confirmedListing(it, changeJson(1, "pending", kind = "address_correction")) }) {
            it.list("conv") }.single()
        assertEquals(AppointmentNotice.CORRECT_ADDRESS, asked.notice("client"))
        assertEquals(AppointmentNotice.AWAITING_CHANGE, asked.notice("pro"))
        val decided = withClient({ confirmedListing(it, changeJson(1, "rejected")) }) { it.list("conv") }.single()
        assertEquals(AppointmentNotice.CONFIRMED, decided.notice("client"))
    }

    @Test fun acceptingShowsTheMoneyEffectBeforeTheDecision() = runTest {
        suspend fun effect(change: String, payment: String = paymentJson) =
            withClient({ confirmedListing(it, change, payment) }) { it.list("conv") }.single().let { it.changeEffect(it.pendingChange!!) }
        assertEquals(ChangeEffect.PayDifference(3500), effect(changeJson(1, "pending")))
        assertEquals(ChangeEffect.Refund(2500), effect(changeJson(1, "pending", amount = 6000)))
        assertEquals(ChangeEffect.None, effect(changeJson(1, "pending", amount = 8500)))
        val direct = paymentJson.replace("\"integrated\"", "\"direct\"")
        assertEquals(ChangeEffect.DirectAgreement(12000), effect(changeJson(1, "pending"), direct))
        assertEquals(ChangeEffect.ChooseMode(12000), effect(changeJson(1, "pending"), "null"))
        assertEquals(ChangeEffect.NewPayment(PaymentMode.DIRECT, 12000),
            effect(changeJson(1, "pending", author = "client", mode = "direct"), "null"))
        assertEquals(ChangeEffect.None, effect(changeJson(1, "pending", amount = 0), "null"))
    }

    @Test fun proposedChangeSendsExpectedVersionKeyTermsAndOnlyTheClientsAddress() = runTest {
        var body: JsonObject? = null
        val change = withClient({ request ->
            assertEquals("/rest/v1/rpc/propose_change", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json(changeJson(1, "pending"))
        }) {
            it.proposeChange("appt", 4, "ck1", ChangeDraft("Sifón y llave", Instant.parse("2030-01-15T10:30:00Z"), 120,
                12000, null, materialsIncluded = true, travelIncluded = false,
                address = ChangedAddress("Calle 1, 3º", 43.06, -5.94), note = "Piso", mode = PaymentMode.INTEGRATED))
        }
        val sent = body!!
        assertEquals(4, sent["p_expected_version"]!!.jsonPrimitive.int)
        assertEquals("ck1", sent["p_request_id"]!!.jsonPrimitive.content)
        assertEquals(12000, sent["p_amount_cents"]!!.jsonPrimitive.int)
        assertEquals("Calle 1, 3º", sent["p_address"]!!.jsonPrimitive.content)
        assertEquals(-5.94, sent["p_lng"]!!.jsonPrimitive.double, 0.0)
        assertEquals("integrated", sent["p_mode"]!!.jsonPrimitive.content)
        assertEquals(ChangeStatus.PENDING, change.status)
        assertEquals(Instant.parse("2030-01-15T12:30:00Z"), change.endAt)
    }

    @Test fun unchangedAddressIsSentAsNull() = runTest {
        var body: JsonObject? = null
        withClient({ request -> body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json(changeJson(1, "pending")) }) {
            it.proposeChange("appt", 4, "ck1", ChangeDraft("Sifón", Instant.parse("2030-01-15T10:30:00Z"), 60, 9000,
                null, materialsIncluded = false, travelIncluded = true))
        }
        assertEquals(JsonNull, body!!["p_address"])
        assertEquals(JsonNull, body!!["p_mode"])
    }

    @Test fun decisionsAndCorrectionsCallTheirFunctions() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        withClient({ request ->
            calls += request.url.encodedPath to Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json(changeJson(1, "accepted"))
        }) {
            it.acceptChange("c1", PaymentMode.DIRECT)
            it.acceptChange("c1", null)
            it.rejectChange("c1")
            it.requestAddressCorrection("appt", 5, "ck2", "Falta el piso")
        }
        assertEquals(listOf("/rest/v1/rpc/accept_change", "/rest/v1/rpc/accept_change", "/rest/v1/rpc/reject_change",
            "/rest/v1/rpc/request_address_correction"), calls.map { it.first })
        assertEquals("direct", calls[0].second["p_mode"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, calls[1].second["p_mode"])
        assertEquals("c1", calls[2].second["p_change_id"]!!.jsonPrimitive.content)
        assertEquals("Falta el piso", calls[3].second["p_note"]!!.jsonPrimitive.content)
        assertEquals(5, calls[3].second["p_expected_version"]!!.jsonPrimitive.int)
    }

    @Test fun changeRejectionsMapToStableFailures() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Forbidden, error("42501", "address_owner_only"), AppointmentFailure.ACCESS),
            Triple(HttpStatusCode.BadRequest, error("22023", "no_change"), AppointmentFailure.INVALID),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_change"), AppointmentFailure.INVALID),
            Triple(HttpStatusCode.BadRequest, error("22023", "out_of_coverage"), AppointmentFailure.OUT_OF_COVERAGE),
            Triple(HttpStatusCode.Conflict, error("PT409", "slot_unavailable"), AppointmentFailure.SLOT_UNAVAILABLE),
            Triple(HttpStatusCode.Conflict, error("PT409", "stale_version"), AppointmentFailure.STALE),
        )
        for ((status, body, expected) in cases) {
            val failure = withClient({ json(body, status) }) {
                try { it.acceptChange("c1", null); null } catch (e: AppointmentException) { e.failure }
            }
            assertEquals(expected, failure)
        }
    }

    private fun marksJson(stage: String, refunded: Int = 0) = """{"id":"appt","stage":"$stage","version":5,
        "started_at":${if (stage == "started") "\"2030-01-15T10:31:00+00:00\"" else "null"},
        "cancelled_at":${if (stage == "cancelled") "\"2030-01-14T09:00:00+00:00\"" else "null"},
        "cancelled_by":${if (stage == "cancelled") "\"client\"" else "null"},"refunded_cents":$refunded}"""

    @Test fun startAndCancelCallTheirFunctionsAndReturnTheServerMarks() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val (started, cancelled) = withClient({ request ->
            calls += request.url.encodedPath to Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            if (request.url.encodedPath.endsWith("start_work")) json(marksJson("started"))
            else json(marksJson("cancelled", refunded = 8500))
        }) { it.startWork("appt") to it.cancel("appt") }
        assertEquals(listOf("/rest/v1/rpc/start_work", "/rest/v1/rpc/cancel_appointment"), calls.map { it.first })
        assertEquals("appt", calls[1].second["p_appointment_id"]!!.jsonPrimitive.content)
        assertEquals(AppointmentStage.STARTED, started.stage)
        assertEquals(Instant.parse("2030-01-15T10:31:00Z"), started.startedAt)
        assertEquals(AppointmentStage.CANCELLED, cancelled.stage)
        assertEquals("client", cancelled.cancelledBy)
        assertEquals(8500, cancelled.refundedCents)
    }

    private fun MockRequestHandleScope.markedListing(request: HttpRequestData, stage: String, payment: String = paymentJson) =
        if (request.url.encodedPath == "/rest/v1/rpc/appointment_places") json("[]")
        else json("[" + appointmentJson.dropLast(1).replace("\"stage\":\"pending\"", "\"stage\":\"$stage\"") +
            ""","started_at":${if (stage == "started") "\"2030-01-15T10:31:00+00:00\"" else "null"},
            "cancelled_at":${if (stage == "cancelled") "\"2030-01-14T09:00:00+00:00\"" else "null"},
            "cancelled_by":${if (stage == "cancelled") "\"pro\"" else "null"},
            "appointment_proposals":[${proposalJson(1, "accepted")}],"appointment_payments":$payment}]""")

    @Test fun bothAccountsSeeTheStartAndTheCancellationMarkedByTheServer() = runTest {
        val started = withClient({ request ->
            if (request.url.encodedPath == "/rest/v1/appointments")
                assertTrue(request.url.parameters["select"]!!.contains("started_at,cancelled_at,cancelled_by"))
            markedListing(request, "started")
        }) { it.list("conv") }.single()
        assertEquals(Instant.parse("2030-01-15T10:31:00Z"), started.startedAt)
        assertEquals(AppointmentNotice.STARTED, started.notice("client"))
        assertEquals(AppointmentNotice.STARTED, started.notice("pro"))
        val cancelled = withClient({ markedListing(it, "cancelled") }) { it.list("conv") }.single()
        assertEquals("pro", cancelled.cancelledBy)
        assertEquals(AppointmentNotice.CANCELLED, cancelled.notice("client"))
    }

    @Test fun onlyTheProfessionalStartsAndNobodyCancelsWithARefundAfterTheStart() = runTest {
        val confirmed = withClient({ markedListing(it, "confirmed") }) { it.list("conv") }.single()
        assertTrue(confirmed.canStart("pro"))
        assertFalse(confirmed.canStart("client"))
        assertTrue(confirmed.canCancel("client") && confirmed.canCancel("pro"))
        assertFalse(confirmed.canCancel("stranger"))
        val started = withClient({ markedListing(it, "started") }) { it.list("conv") }.single()
        assertFalse(started.canStart("pro"))
        assertFalse(started.canCancel("client") || started.canCancel("pro"))
        val pending = withClient({ listing(it, proposalJson(1, "pending")) }) { it.list("conv") }.single()
        assertFalse(pending.canStart("pro") || pending.canCancel("client"))
    }

    @Test fun cancellingRefundsOnlyThePendingIntegratedMoney() = runTest {
        suspend fun effect(payment: String) = withClient({ markedListing(it, "confirmed", payment) }) { it.list("conv") }
            .single().cancellationEffect
        assertEquals(CancellationEffect.Refund(8500), effect(paymentJson))
        assertEquals(CancellationEffect.Refund(6000), effect(paymentJson.replace("\"pending_cents\":8500", "\"pending_cents\":6000")
            .replace("\"refunded_cents\":0", "\"refunded_cents\":2500")))
        assertEquals(CancellationEffect.DirectNotRefunded(8500), effect(paymentJson.replace("\"integrated\"", "\"direct\"")
            .replace("\"paid_cents\":8500", "\"paid_cents\":0").replace("\"pending_cents\":8500", "\"pending_cents\":0")))
        assertEquals(CancellationEffect.None, effect("null"))
    }

    @Test fun startAndCancelRejectionsMapToStableFailures() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Conflict, error("PT409", "already_started"), AppointmentFailure.ALREADY_STARTED),
            Triple(HttpStatusCode.Conflict, error("PT409", "incompatible_state"), AppointmentFailure.STALE),
            Triple(HttpStatusCode.Forbidden, error("42501", "permission_denied"), AppointmentFailure.ACCESS),
        )
        for ((status, body, expected) in cases) {
            val failure = withClient({ json(body, status) }) {
                try { it.cancel("appt"); null } catch (e: AppointmentException) { e.failure }
            }
            assertEquals(expected, failure)
        }
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
