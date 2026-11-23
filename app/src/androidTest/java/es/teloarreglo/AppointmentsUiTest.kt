package es.teloarreglo

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.appointments.*
import es.teloarreglo.chat.Conversation
import es.teloarreglo.search.Located
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** In-memory stand-in with the server's observable rules: keys, versions and kept history. */
private class FakeAppointments : Appointments {
    val items = mutableListOf<Appointment>()
    val requestKeys = mutableListOf<String>()
    var loseNextResponse = false
    private val proposals = mutableMapOf<String, MutableList<AppointmentProposal>>()

    private fun withHistory(a: Appointment) = a.copy(rawProposals = proposals[a.id].orEmpty().toList(),
        rawChanges = changes[a.id].orEmpty().toList())
    override suspend fun list(conversationId: String) = items.map(::withHistory)

    override suspend fun request(conversationId: String, requestId: String, request: AppointmentRequest): Appointment {
        requestKeys += requestId
        val result = items.firstOrNull { it.requestId == requestId } ?: Appointment(
            "a${items.size + 1}", conversationId, "client", "pro", requestId, request.problem, request.start, request.end,
            1, AppointmentStage.PENDING, Instant.now(),
            place = AppointmentPlace("a${items.size + 1}", true, request.address, request.latitude, request.longitude,
                request.latitude, request.longitude, 400, "Oviedo"),
        ).also { items.add(0, it) }
        if (loseNextResponse) { loseNextResponse = false; throw java.io.IOException("response lost") }
        return result
    }

    override suspend fun propose(appointmentId: String, expectedVersion: Int, requestId: String, draft: ProposalDraft): AppointmentProposal {
        val index = items.indexOfFirst { it.id == appointmentId }
        if (items[index].version != expectedVersion) throw AppointmentException(AppointmentFailure.STALE, Exception())
        val history = proposals.getOrPut(appointmentId) { mutableListOf() }
        history.replaceAll { if (it.status == ProposalStatus.PENDING) it.copy(status = ProposalStatus.SUPERSEDED) else it }
        val proposal = AppointmentProposal("p${history.size + 1}", appointmentId, "pro", history.size + 1, requestId,
            draft.scope, draft.start, draft.start.plus(Duration.ofMinutes(draft.durationMinutes.toLong())), draft.durationMinutes,
            draft.amountCents, draft.visitFeeCents, draft.materialsIncluded, draft.travelIncluded, ProposalStatus.PENDING, Instant.now())
        history += proposal
        items[index] = items[index].copy(version = expectedVersion + 1)
        return proposal
    }

    override suspend fun reject(proposalId: String): AppointmentProposal {
        val (appointmentId, history) = proposals.entries.first { (_, list) -> list.any { it.id == proposalId } }
        val i = history.indexOfFirst { it.id == proposalId }
        history[i] = history[i].copy(status = ProposalStatus.REJECTED)
        val index = items.indexOfFirst { it.id == appointmentId }
        items[index] = items[index].copy(version = items[index].version + 1)
        return history[i]
    }

    /** When true, the agenda is taken: like the server, nothing changes and SLOT_UNAVAILABLE is raised. */
    var slotTaken = false
    override suspend fun accept(proposalId: String): AppointmentProposal {
        val (appointmentId, history) = proposals.entries.first { (_, list) -> list.any { it.id == proposalId } }
        val i = history.indexOfFirst { it.id == proposalId }
        if (history[i].status == ProposalStatus.ACCEPTED) return history[i]
        if (slotTaken) throw AppointmentException(AppointmentFailure.SLOT_UNAVAILABLE, Exception())
        history[i] = history[i].copy(status = ProposalStatus.ACCEPTED)
        val index = items.indexOfFirst { it.id == appointmentId }
        items[index] = items[index].copy(version = items[index].version + 1, stage = AppointmentStage.CONFIRMED)
        return history[i]
    }

    val paymentKeys = mutableListOf<String>()
    /** Like the server: one key binds one intention; a conflict or a lost answer never charges twice. */
    override suspend fun confirm(proposalId: String, requestId: String, mode: PaymentMode): AppointmentPayment {
        paymentKeys += requestId
        val (appointmentId, history) = proposals.entries.first { (_, list) -> list.any { it.id == proposalId } }
        val index = items.indexOfFirst { it.id == appointmentId }
        items[index].payment?.let { return it }
        if (slotTaken) throw AppointmentException(AppointmentFailure.SLOT_UNAVAILABLE, Exception())
        val i = history.indexOfFirst { it.id == proposalId }
        history[i] = history[i].copy(status = ProposalStatus.ACCEPTED)
        val total = history[i].payableCents
        val paid = if (mode == PaymentMode.INTEGRATED) total else 0
        val payment = AppointmentPayment(appointmentId, proposalId, requestId, mode, total, paid, paid, 0, 0)
        items[index] = items[index].copy(version = items[index].version + 1, stage = AppointmentStage.CONFIRMED, payment = payment)
        if (loseNextResponse) { loseNextResponse = false; throw java.io.IOException("response lost") }
        return payment
    }

    private val changes = mutableMapOf<String, MutableList<AppointmentChange>>()
    private fun change(id: String) = changes.entries.first { (_, list) -> list.any { it.id == id } }

    /** Like the server (#23): a change never touches the conditions in force until accepted. */
    override suspend fun proposeChange(appointmentId: String, expectedVersion: Int, requestId: String, draft: ChangeDraft): AppointmentChange {
        val index = items.indexOfFirst { it.id == appointmentId }
        if (items[index].version != expectedVersion) throw AppointmentException(AppointmentFailure.STALE, Exception())
        val history = changes.getOrPut(appointmentId) { mutableListOf() }
        history.replaceAll { if (it.status == ChangeStatus.PENDING) it.copy(status = ChangeStatus.SUPERSEDED) else it }
        val base = withHistory(items[index]).confirmedProposal!!
        val result = AppointmentChange("c${history.size + 1}", appointmentId, "pro", history.size + 1, requestId, ChangeKind.CHANGE,
            base.id, draft.scope, draft.start, draft.start.plus(Duration.ofMinutes(draft.durationMinutes.toLong())),
            draft.durationMinutes, draft.amountCents, draft.visitFeeCents, draft.materialsIncluded, draft.travelIncluded,
            status = ChangeStatus.PENDING, createdAt = Instant.now())
        history += result
        items[index] = items[index].copy(version = expectedVersion + 1)
        return result
    }

    override suspend fun requestAddressCorrection(appointmentId: String, expectedVersion: Int, requestId: String, note: String) =
        throw UnsupportedOperationException()

    override suspend fun acceptChange(changeId: String, mode: PaymentMode?): AppointmentChange {
        val (appointmentId, history) = change(changeId)
        val i = history.indexOfFirst { it.id == changeId }
        if (history[i].status == ChangeStatus.ACCEPTED) return history[i]
        if (slotTaken) throw AppointmentException(AppointmentFailure.SLOT_UNAVAILABLE, Exception())
        val c = history[i]
        val list = proposals.getValue(appointmentId)
        list.replaceAll { if (it.status == ProposalStatus.ACCEPTED) it.copy(status = ProposalStatus.REPLACED) else it }
        list += AppointmentProposal("p${list.size + 1}", appointmentId, c.authorId, list.size + 1, c.id, c.scope!!, c.startAt!!,
            c.endAt!!, c.durationMinutes!!, c.amountCents!!, c.visitFeeCents, c.materialsIncluded!!, c.travelIncluded!!,
            ProposalStatus.ACCEPTED, Instant.now())
        val index = items.indexOfFirst { it.id == appointmentId }
        val payment = items[index].payment?.let { p ->
            val diff = c.payableCents!! - p.agreedCents
            p.copy(agreedCents = c.payableCents!!, paidCents = p.paidCents + maxOf(diff, 0), pendingCents = p.pendingCents + diff,
                refundedCents = p.refundedCents + maxOf(-diff, 0))
        }
        history[i] = c.copy(status = ChangeStatus.ACCEPTED)
        items[index] = items[index].copy(version = items[index].version + 1, payment = payment)
        return history[i]
    }

    override suspend fun rejectChange(changeId: String): AppointmentChange {
        val (appointmentId, history) = change(changeId)
        val i = history.indexOfFirst { it.id == changeId }
        history[i] = history[i].copy(status = ChangeStatus.REJECTED)
        val index = items.indexOfFirst { it.id == appointmentId }
        items[index] = items[index].copy(version = items[index].version + 1)
        return history[i]
    }

    private fun marks(a: Appointment, refunded: Int = 0) =
        AppointmentMarks(a.id, a.stage, a.version, a.startedAt, a.cancelledAt, a.cancelledBy, refunded)

    override suspend fun startWork(appointmentId: String): AppointmentMarks {
        val index = items.indexOfFirst { it.id == appointmentId }
        val a = items[index]
        if (a.stage == AppointmentStage.STARTED) return marks(a)
        if (a.stage != AppointmentStage.CONFIRMED) throw AppointmentException(AppointmentFailure.STALE, Exception())
        items[index] = a.copy(stage = AppointmentStage.STARTED, startedAt = Instant.now(), version = a.version + 1)
        return marks(items[index])
    }

    override suspend fun cancel(appointmentId: String): AppointmentMarks {
        val index = items.indexOfFirst { it.id == appointmentId }
        val a = items[index]
        if (a.stage == AppointmentStage.CANCELLED) return marks(a)
        if (a.stage == AppointmentStage.STARTED) throw AppointmentException(AppointmentFailure.ALREADY_STARTED, Exception())
        if (a.stage != AppointmentStage.CONFIRMED) throw AppointmentException(AppointmentFailure.STALE, Exception())
        val refund = a.payment?.takeIf { it.mode == PaymentMode.INTEGRATED }?.pendingCents ?: 0
        items[index] = a.copy(stage = AppointmentStage.CANCELLED, cancelledAt = Instant.now(), cancelledBy = a.clientId,
            version = a.version + 1, payment = a.payment?.let {
                it.copy(pendingCents = it.pendingCents - refund, refundedCents = it.refundedCents + refund) })
        return marks(items[index], refund)
    }
}

class AppointmentsUiTest {
    @get:Rule val compose = createComposeRule()
    private val conversation = Conversation("conversation", "profile", "client", "pro", "Fontanería")
    private val noPin = PinSource({ false }, { Located.Unavailable })

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun clientRequestsWithoutPriceAndALostResponseIsRetriedWithTheSameKey() {
        val fake = FakeAppointments().apply { loseNextResponse = true }
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_request")
        compose.onNodeWithTag("appointment_request").performClick()
        compose.onNodeWithTag("appointment_problem").performTextInput("Fuga bajo el fregadero")
        compose.onNodeWithTag("appointment_address").performTextInput("Calle Ficticia 1")
        compose.onNodeWithTag("appointment_lat").performTextInput("43.36")
        compose.onNodeWithTag("appointment_lng").performTextInput("-5.85")
        compose.onNodeWithTag("appointment_send_request").performScrollTo().performClick()
        waitForTag("appointment_error")
        // Either the user retries or the next poll recognises the key in the history; never a second request.
        if (compose.onAllNodesWithTag("appointment_send_request").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("appointment_problem").assertIsNotEnabled()
            compose.onNodeWithTag("appointment_send_request").performScrollTo().performClick()
        }
        waitForTag("appointment_a1")
        assertEquals(1, fake.items.size)
        assertEquals(1, fake.requestKeys.distinct().size)
    }

    private fun seeded() = FakeAppointments().apply {
        kotlinx.coroutines.runBlocking {
            request("conversation", "key", AppointmentRequest("Fuga", Instant.now().plusSeconds(86_400),
                Instant.now().plusSeconds(93_600), "Calle Ficticia 1", 43.36, -5.85))
        }
    }

    @Test fun professionalProposesAmountInExactCents() {
        val fake = seeded()
        compose.setContent { AppointmentsPanel(fake, "pro", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("appointment_propose").performScrollTo().performClick()
        compose.onNodeWithTag("proposal_scope").performTextInput("Cambio de sifón")
        compose.onNodeWithTag("proposal_amount").performTextInput("85,50")
        compose.onNodeWithTag("proposal_send").performScrollTo().performClick()
        waitForTag("proposal_1")
        val current = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single().currentProposal
        assertEquals(8550, current?.amountCents)
    }

    @Test fun clientRejectsTheFirstProposalAndItStaysInTheHistory() {
        val fake = seeded()
        kotlinx.coroutines.runBlocking {
            fake.propose("a1", 1, "p-key", ProposalDraft("Cambio de sifón", Instant.now().plusSeconds(90_000), 60, 6000,
                null, materialsIncluded = true, travelIncluded = true))
        }
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("appointment_reject").performScrollTo().performClick()
        compose.onNodeWithTag("appointment_reject_confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("appointment_reject").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("proposal_1").assertExists()
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(listOf(ProposalStatus.REJECTED), appointment.proposals.map { it.status })
        assertEquals(AppointmentStage.PENDING, appointment.stage)
    }

    private fun withFreeProposal(fake: FakeAppointments) = fake.apply {
        kotlinx.coroutines.runBlocking {
            propose("a1", 1, "free-key", ProposalDraft("Visita gratuita", Instant.now().plusSeconds(90_000), 60, 0,
                null, materialsIncluded = false, travelIncluded = true))
        }
    }

    @Test fun clientAcceptsAFreeProposalAndBothSidesSeeItConfirmed() {
        val fake = withFreeProposal(seeded())
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("appointment_accept").performScrollTo().performClick()
        compose.onNodeWithTag("appointment_accept_confirm").performClick()
        waitForTag("appointment_confirmed_a1")
        compose.onAllNodesWithTag("appointment_accept").assertCountEquals(0)
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(AppointmentStage.CONFIRMED, appointment.stage)
        assertEquals(AppointmentNotice.CONFIRMED, appointment.notice("pro"))
    }

    @Test fun aTakenSlotIsExplainedAndTheRequestStaysPending() {
        val fake = withFreeProposal(seeded()).apply { slotTaken = true }
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("appointment_accept").performScrollTo().performClick()
        compose.onNodeWithTag("appointment_accept_confirm").performClick()
        waitForTag("appointment_decision_error")
        compose.onAllNodesWithTag("appointment_confirmed_a1").assertCountEquals(0)
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(AppointmentStage.PENDING, appointment.stage)
        assertEquals(ProposalStatus.PENDING, appointment.currentProposal?.status)
    }

    private fun withPaidProposal(fake: FakeAppointments) = fake.apply {
        kotlinx.coroutines.runBlocking {
            propose("a1", 1, "paid-key", ProposalDraft("Cambio de sifón", Instant.now().plusSeconds(90_000), 60, 8550,
                null, materialsIncluded = true, travelIncluded = true))
        }
    }

    @Test fun clientSeesTermsPaysAndALostAnswerIsNotChargedTwice() {
        val fake = withPaidProposal(seeded()).apply { loseNextResponse = true }
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onAllNodesWithTag("appointment_accept").assertCountEquals(0)
        compose.onNodeWithTag("payment_integrated").performScrollTo().performClick()
        compose.onNodeWithTag("payment_terms").assertExists()
        compose.onNodeWithText("Cambio de sifón", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("payment_confirm").performClick()
        waitForTag("payment_summary_a1")
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(AppointmentStage.CONFIRMED, appointment.stage)
        assertEquals(8550, appointment.payment?.pendingCents)
        assertEquals(1, fake.paymentKeys.distinct().size)
    }

    @Test fun directPaymentConfirmsWithoutIntegratedMoney() {
        val fake = withPaidProposal(seeded())
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("payment_direct").performScrollTo().performClick()
        compose.onNodeWithTag("payment_confirm").performClick()
        waitForTag("payment_summary_a1")
        val payment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single().payment
        assertEquals(PaymentMode.DIRECT, payment?.mode)
        assertEquals(0, payment?.paidCents)
    }

    @Test fun aTakenSlotIsNotChargedAndStaysPending() {
        val fake = withPaidProposal(seeded()).apply { slotTaken = true }
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("payment_integrated").performScrollTo().performClick()
        compose.onNodeWithTag("payment_confirm").performClick()
        waitForTag("appointment_decision_error")
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(AppointmentStage.PENDING, appointment.stage)
        assertEquals(null, appointment.payment)
    }

    /** A paid, confirmed appointment (85,50 €) with a pending change from the professional to 120 €, 90 min (#23). */
    private fun withPendingChange() = withPaidProposal(seeded()).apply {
        kotlinx.coroutines.runBlocking {
            confirm("p1", "pay", PaymentMode.INTEGRATED)
            val start = list("conversation").single().confirmedProposal!!.startAt
            proposeChange("a1", list("conversation").single().version, "change-key", ChangeDraft("Sifón y llave de paso",
                start, 90, 12000, null, materialsIncluded = true, travelIncluded = true))
        }
    }

    @Test fun clientSeesBeforeAndAfterAndTheDifferenceBeforeAccepting() {
        val fake = withPendingChange()
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("change_pending").performScrollTo().assertExists()
        compose.onNodeWithTag("change_field_amount").assertExists()
        compose.onNodeWithTag("change_field_when").assertExists()
        compose.onAllNodesWithTag("change_field_address").assertCountEquals(0)
        compose.onNodeWithTag("change_effect").assertExists()
        // Still the agreed conditions until accepted.
        assertEquals(8550, kotlinx.coroutines.runBlocking { fake.list("conversation") }.single().confirmedProposal?.amountCents)
        compose.onNodeWithTag("change_accept").performScrollTo().performClick()
        compose.onNodeWithTag("change_decision_confirm").performClick()
        waitForTag("change_history_1")
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(12000, appointment.confirmedProposal?.amountCents)
        assertEquals(12000, appointment.payment?.paidCents)
    }

    @Test fun professionalCannotDecideItsOwnChange() {
        val fake = withPendingChange()
        compose.setContent { AppointmentsPanel(fake, "pro", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("change_pending").performScrollTo().assertExists()
        compose.onAllNodesWithTag("change_accept").assertCountEquals(0)
        compose.onAllNodesWithTag("change_reject").assertCountEquals(0)
    }

    @Test fun aTakenSlotKeepsTheWholePreviousAppointment() {
        val fake = withPendingChange().apply { slotTaken = true }
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("change_accept").performScrollTo().performClick()
        compose.onNodeWithTag("change_decision_confirm").performClick()
        waitForTag("change_error")
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(8550, appointment.confirmedProposal?.amountCents)
        assertEquals(ChangeStatus.PENDING, appointment.pendingChange?.status)
    }

    /** A paid, confirmed appointment (85,50 € integrated, still pending of delivery) (#24). */
    private fun withPaidConfirmation() = withPaidProposal(seeded()).apply {
        kotlinx.coroutines.runBlocking { confirm("p1", "pay", PaymentMode.INTEGRATED) }
    }

    @Test fun onlyTheProfessionalStartsAndThenNobodyCancelsWithARefund() {
        val fake = withPaidConfirmation()
        var account by mutableStateOf("client")
        compose.setContent { AppointmentsPanel(fake, account, conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onAllNodesWithTag("start_work").assertCountEquals(0)
        account = "pro"
        waitForTag("start_work")
        compose.onNodeWithTag("start_work").performScrollTo().performClick()
        compose.onNodeWithTag("start_work_confirm").performClick()
        waitForTag("started_mark_a1")
        compose.onAllNodesWithTag("cancel_appointment").assertCountEquals(0)
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(AppointmentStage.STARTED, appointment.stage)
        assertEquals(8550, appointment.payment?.pendingCents)
    }

    /** Extra work agreed on the spot (2026-11-23): after the start the start time is fixed, the rest can change. */
    @Test fun afterTheStartTheProfessionalProposesExtraWorkAndTheClientPaysTheDifference() {
        val fake = withPaidConfirmation().apply {
            items[0] = items[0].copy(referencePrices = listOf(
                es.teloarreglo.catalogue.ReferencePrice("Fuga", "Una hora", "hour", 3500)))
            kotlinx.coroutines.runBlocking { startWork("a1") }
        }
        var account by mutableStateOf("pro")
        compose.setContent { AppointmentsPanel(fake, account, conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        waitForTag("kept_prices_a1")
        compose.onNodeWithTag("running_change_hint").assertExists()
        compose.onAllNodesWithTag("change_request_correction").assertCountEquals(0)
        compose.onNodeWithTag("change_propose").performScrollTo().performClick()
        waitForTag("change_start_fixed")
        compose.onAllNodesWithTag("change_start").assertCountEquals(0)
        compose.onNodeWithTag("change_amount").performTextReplacement("95,50")
        compose.onNodeWithTag("change_send").performScrollTo().performClick()
        waitForTag("change_pending")
        account = "client"
        waitForTag("change_accept")
        compose.onNodeWithTag("change_accept").performScrollTo().performClick()
        compose.onNodeWithTag("change_decision_confirm").performClick()
        compose.waitUntil(5_000) { kotlinx.coroutines.runBlocking { fake.list("conversation") }.single().pendingChange == null }
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(AppointmentStage.STARTED, appointment.stage)
        assertEquals(9550, appointment.payment?.paidCents)
        // The new conditions keep the original start time.
        assertEquals(appointment.proposals.first().startAt, appointment.confirmedProposal?.startAt)
    }

    @Test fun clientSeesTheFullRefundBeforeCancellingAndTheHourIsReleased() {
        val fake = withPaidConfirmation()
        compose.setContent { AppointmentsPanel(fake, "client", conversation, rememberCoroutineScope(), noPin) }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onNodeWithTag("cancel_appointment").performScrollTo().performClick()
        compose.onNodeWithTag("cancel_effect").assertExists()
        compose.onNodeWithTag("cancel_appointment_confirm").performClick()
        waitForTag("cancelled_mark_a1")
        val appointment = kotlinx.coroutines.runBlocking { fake.list("conversation") }.single()
        assertEquals(AppointmentStage.CANCELLED, appointment.stage)
        assertEquals(8550, appointment.payment?.refundedCents)
        assertEquals(0, appointment.payment?.pendingCents)
    }
}
