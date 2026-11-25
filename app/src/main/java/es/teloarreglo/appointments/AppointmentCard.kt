package es.teloarreglo.appointments

/** Whose move a notice announces (#47): the card highlights this account's turn above everything else. */
enum class NoticeTurn {
    /** This account has to act: propose, decide, correct or confirm. */
    YOURS,
    /** The other participant (or the incident review) has to act. */
    WAITING,
    /** Nothing is pending: confirmed, started, cancelled or closed. */
    NONE,
}

val AppointmentNotice.turn: NoticeTurn get() = when (this) {
    AppointmentNotice.SEND_PROPOSAL, AppointmentNotice.DECIDE_PROPOSAL, AppointmentNotice.SEND_NEW_PROPOSAL,
    AppointmentNotice.DECIDE_CHANGE, AppointmentNotice.CORRECT_ADDRESS, AppointmentNotice.CONFIRM_DONE -> NoticeTurn.YOURS
    AppointmentNotice.AWAITING_PROPOSAL, AppointmentNotice.AWAITING_CLIENT, AppointmentNotice.AWAITING_NEW_PROPOSAL,
    AppointmentNotice.AWAITING_CHANGE, AppointmentNotice.AWAITING_CONFIRMATION, AppointmentNotice.INCIDENT_OPEN -> NoticeTurn.WAITING
    AppointmentNotice.CONFIRMED, AppointmentNotice.STARTED, AppointmentNotice.CANCELLED, AppointmentNotice.COMPLETED,
    AppointmentNotice.COMPLETED_NO_ANSWER, AppointmentNotice.COMPLETED_BY_ADMINISTRATION -> NoticeTurn.NONE
}

/** The proposal the card shows unfolded: the one awaiting the client or, once confirmed, the accepted one. */
val Appointment.proposalInForce: AppointmentProposal? get() = currentProposal ?: confirmedProposal

/** Every other proposal, newest first: kept as history behind «Ver historial (n)». */
val Appointment.earlierProposals: List<AppointmentProposal>
    get() = proposals.asReversed().filter { it.id != proposalInForce?.id }
