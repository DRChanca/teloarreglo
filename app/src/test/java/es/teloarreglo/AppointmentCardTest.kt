package es.teloarreglo

import es.teloarreglo.appointments.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** What the appointment card puts first (#47): whose turn it is and the proposal in force. */
class AppointmentCardTest {
    private val now = Instant.parse("2030-01-15T10:00:00Z")
    private fun proposal(version: Int, status: ProposalStatus) = AppointmentProposal("p$version", "a1", "pro", version,
        "k$version", "Sifón", now, now.plusSeconds(3600), 60, 8500, null, materialsIncluded = true, travelIncluded = false,
        status = status, createdAt = now)
    private fun appointment(vararg proposals: AppointmentProposal) = Appointment("a1", "c", "client", "pro", "key", "Fuga",
        now, now.plusSeconds(7200), 1, AppointmentStage.PENDING, now, rawProposals = proposals.toList())

    @Test fun noticesThatAskThisAccountToActAreItsTurn() {
        listOf(AppointmentNotice.SEND_PROPOSAL, AppointmentNotice.DECIDE_PROPOSAL, AppointmentNotice.SEND_NEW_PROPOSAL,
            AppointmentNotice.DECIDE_CHANGE, AppointmentNotice.CORRECT_ADDRESS, AppointmentNotice.CONFIRM_DONE)
            .forEach { assertEquals(it.name, NoticeTurn.YOURS, it.turn) }
    }

    @Test fun noticesThatWaitForTheOtherParticipantAreNotItsTurn() {
        listOf(AppointmentNotice.AWAITING_PROPOSAL, AppointmentNotice.AWAITING_CLIENT, AppointmentNotice.AWAITING_NEW_PROPOSAL,
            AppointmentNotice.AWAITING_CHANGE, AppointmentNotice.AWAITING_CONFIRMATION, AppointmentNotice.INCIDENT_OPEN)
            .forEach { assertEquals(it.name, NoticeTurn.WAITING, it.turn) }
    }

    @Test fun settledStatesAskNobody() {
        listOf(AppointmentNotice.CONFIRMED, AppointmentNotice.STARTED, AppointmentNotice.CANCELLED, AppointmentNotice.COMPLETED,
            AppointmentNotice.COMPLETED_NO_ANSWER, AppointmentNotice.COMPLETED_BY_ADMINISTRATION)
            .forEach { assertEquals(it.name, NoticeTurn.NONE, it.turn) }
        assertEquals(AppointmentNotice.entries.size, 18)
    }

    @Test fun thePendingProposalIsInForceAndTheRejectedOnesAreHistory() {
        val a = appointment(proposal(1, ProposalStatus.REJECTED), proposal(2, ProposalStatus.SUPERSEDED),
            proposal(3, ProposalStatus.PENDING))
        assertEquals("p3", a.proposalInForce?.id)
        assertEquals(listOf("p2", "p1"), a.earlierProposals.map { it.id })
    }

    @Test fun theAcceptedProposalIsInForceOnceConfirmed() {
        val a = appointment(proposal(1, ProposalStatus.REJECTED), proposal(2, ProposalStatus.ACCEPTED))
        assertEquals("p2", a.proposalInForce?.id)
        assertEquals(listOf("p1"), a.earlierProposals.map { it.id })
    }

    @Test fun withOnlyRejectedProposalsNothingIsInForce() {
        val a = appointment(proposal(1, ProposalStatus.REJECTED))
        assertEquals(null, a.proposalInForce)
        assertEquals(listOf("p1"), a.earlierProposals.map { it.id })
    }
}
