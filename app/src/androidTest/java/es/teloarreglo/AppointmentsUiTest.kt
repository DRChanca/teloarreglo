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

    private fun withHistory(a: Appointment) = a.copy(rawProposals = proposals[a.id].orEmpty().toList())
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
}
