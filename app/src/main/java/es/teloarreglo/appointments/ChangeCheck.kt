package es.teloarreglo.appointments

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Fields of the change form that can be wrong (#63), each shown next to its own input. */
enum class ChangeField { SCOPE, DATE, START, DURATION, AMOUNT, VISIT_FEE, ADDRESS, POINT, MODE }

/** Why a field is refused, so the form can say how to fix it. */
enum class ChangeProblem { MISSING, FORMAT, RANGE, PAST }

/** The change form as typed. [address] is null when the client keeps the address in force. */
data class ChangeInput(
    val scope: String, val date: LocalDate?, val start: String, val minutes: String,
    val amount: String, val visitFee: String, val materials: Boolean, val travel: Boolean,
    val address: AddressInput? = null, val note: String = "", val mode: PaymentMode? = null,
)

data class AddressInput(val address: String, val lat: String, val lng: String)

sealed interface ChangeCheck {
    data class Ready(val draft: ChangeDraft) : ChangeCheck
    data class Wrong(val problems: Map<ChangeField, ChangeProblem>) : ChangeCheck
    /** Everything matches the conditions in force: there is nothing to propose (the server would answer no_change). */
    data object Unchanged : ChangeCheck
}

/**
 * Checks a change against the same rules as the server, so a wrong value is named in its field before sending
 * and a corrected one is sent as it now stands.
 */
fun checkChange(
    input: ChangeInput, base: AppointmentProposal, current: AppointmentLocation?, running: Boolean, asksMode: Boolean,
    zone: ZoneId, now: Instant,
): ChangeCheck {
    val problems = linkedMapOf<ChangeField, ChangeProblem>()
    fun wrong(field: ChangeField, problem: ChangeProblem): Nothing? { problems[field] = problem; return null }

    if (input.scope.isBlank()) wrong(ChangeField.SCOPE, ChangeProblem.MISSING)
    // Once started, the start in force is sent unchanged: the server refuses any other.
    val start = if (running) base.startAt else {
        val date = input.date ?: wrong(ChangeField.DATE, ChangeProblem.MISSING)
        val time = if (input.start.isBlank()) wrong(ChangeField.START, ChangeProblem.MISSING)
            else hourMinute(input.start) ?: wrong(ChangeField.START, ChangeProblem.FORMAT)
        // The form shows the start to the minute: that same minute keeps the start in force exactly.
        val typed = if (date == null || time == null) null else LocalDateTime.of(date, time).atZone(zone).toInstant()
        when {
            typed == null -> null
            typed == base.startAt.truncatedTo(ChronoUnit.MINUTES) -> base.startAt
            typed.isAfter(now) -> typed
            else -> wrong(ChangeField.START, ChangeProblem.PAST)
        }
    }
    val duration = if (input.minutes.isBlank()) wrong(ChangeField.DURATION, ChangeProblem.MISSING)
        else input.minutes.trim().toIntOrNull()?.takeIf { it in 15..1440 } ?: wrong(ChangeField.DURATION, ChangeProblem.RANGE)
    val cents = euros(input.amount, ChangeField.AMOUNT, ::wrong)
    val fee = if (input.visitFee.isBlank()) null else euros(input.visitFee, ChangeField.VISIT_FEE, ::wrong)
    // During the job the place is where the work is: the address in force stays.
    val address = input.address.takeUnless { running }?.let { typed ->
        if (typed.address.isBlank()) wrong(ChangeField.ADDRESS, ChangeProblem.MISSING)
        val lat = coordinate(typed.lat, 90.0, ::wrong)
        val lng = if (ChangeField.POINT in problems) null else coordinate(typed.lng, 180.0, ::wrong)
        if (lat == null || lng == null || typed.address.isBlank()) null else ChangedAddress(typed.address.trim(), lat, lng)
    }
    val needsMode = asksMode && maxOf(cents ?: 0, fee ?: 0) > 0
    if (needsMode && input.mode == null) wrong(ChangeField.MODE, ChangeProblem.MISSING)

    if (problems.isNotEmpty()) return ChangeCheck.Wrong(problems)
    val draft = ChangeDraft(input.scope.trim(), start!!, duration!!, cents!!, fee, input.materials, input.travel,
        address = address, note = input.note.trim().ifEmpty { null }, mode = input.mode.takeIf { needsMode })
    val exact = current as? AppointmentLocation.Exact
    val samePlace = address == null ||
        (exact != null && address.address == exact.address && address.latitude == exact.point.lat && address.longitude == exact.point.lng)
    val sameTerms = draft.scope == base.scope && draft.start == base.startAt && draft.durationMinutes == base.durationMinutes &&
        draft.amountCents == base.amountCents && draft.visitFeeCents == base.visitFeeCents &&
        draft.materialsIncluded == base.materialsIncluded && draft.travelIncluded == base.travelIncluded
    return if (sameTerms && samePlace) ChangeCheck.Unchanged else ChangeCheck.Ready(draft)
}

/** Whether a client adding a cost to a free appointment ([asksMode]) must say now how it will be paid. */
fun needsPaymentMode(asksMode: Boolean, amount: String, visitFee: String) =
    asksMode && maxOf(parseEuroCents(amount) ?: 0, parseEuroCents(visitFee) ?: 0) > 0

/** A typed latitude or longitude; Kotlin reads "NaN" and "Infinity" as numbers, the server does not. */
private fun coordinate(text: String, limit: Double, wrong: (ChangeField, ChangeProblem) -> Nothing?): Double? {
    if (text.isBlank()) return wrong(ChangeField.POINT, ChangeProblem.MISSING)
    val value = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        ?: return wrong(ChangeField.POINT, ChangeProblem.FORMAT)
    return if (value in -limit..limit) value else wrong(ChangeField.POINT, ChangeProblem.RANGE)
}

/** The server's ceiling for a price or a visit fee: 1.000.000,00 €. */
private const val MAX_CENTS = 100_000_000

private fun euros(text: String, field: ChangeField, wrong: (ChangeField, ChangeProblem) -> Nothing?): Int? {
    if (text.isBlank()) return wrong(field, ChangeProblem.MISSING)
    val cents = parseEuroCents(text) ?: return wrong(field, ChangeProblem.FORMAT)
    return if (cents > MAX_CENTS) wrong(field, ChangeProblem.RANGE) else cents
}

private val hourMinuteInput = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

/** "9:00" or "09:00"; anything else (25:00, 9h, 9.30) is not a time. */
private fun hourMinute(text: String): LocalTime? =
    hourMinuteInput.matchEntire(text.trim())?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
