package es.teloarreglo

import es.teloarreglo.appointments.*
import es.teloarreglo.search.GeoPoint
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** #63: the change form names the wrong field and, once corrected, sends the change. */
class ChangeCheckTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val now = Instant.parse("2030-01-10T09:00:00Z")
    // In force: 15 Jan 2030 11:30 in Madrid, 90 minutes, 85,00 €.
    private val base = AppointmentProposal(id = "p1", appointmentId = "appt", authorId = "pro", version = 1, requestId = "k1",
        scope = "Sifón", startAt = Instant.parse("2030-01-15T10:30:00Z"), endAt = Instant.parse("2030-01-15T12:00:00Z"),
        durationMinutes = 90, amountCents = 8500, visitFeeCents = null, materialsIncluded = true, travelIncluded = false,
        status = ProposalStatus.ACCEPTED, createdAt = Instant.parse("2026-11-23T10:05:00Z"))
    private val home = AppointmentLocation.Exact("Calle 1", GeoPoint(43.05, -5.95))
    private val input = ChangeInput(scope = "Sifón y llave", date = LocalDate.of(2030, 1, 15), start = "11:30", minutes = "90",
        amount = "85,00", visitFee = "", materials = true, travel = false)

    private fun check(input: ChangeInput, running: Boolean = false, asksMode: Boolean = false) =
        checkChange(input, base, home, running, asksMode, zone, now)

    @Test fun aWrongDurationIsNamedAndOnceCorrectedTheChangeIsReady() {
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.DURATION to ChangeProblem.RANGE)), check(input.copy(minutes = "5")))
        val ready = check(input.copy(minutes = "120")) as ChangeCheck.Ready
        assertEquals(ChangeDraft("Sifón y llave", Instant.parse("2030-01-15T10:30:00Z"), 120, 8500, null, true, false), ready.draft)
    }

    @Test fun theStartIsReadAsHoursAndMinutesAndAnImpossibleOneIsNamed() {
        assertEquals(Instant.parse("2030-01-15T08:00:00Z"), (check(input.copy(start = "9:00")) as ChangeCheck.Ready).draft.start)
        for (wrong in listOf("25:00", "24:00", "9h", "9.30"))
            assertEquals(wrong, ChangeCheck.Wrong(mapOf(ChangeField.START to ChangeProblem.FORMAT)), check(input.copy(start = wrong)))
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.START to ChangeProblem.MISSING)), check(input.copy(start = " ")))
    }

    @Test fun aNewStartMustBeInTheFutureButTheOneInForceIsKept() {
        val past = input.copy(date = LocalDate.of(2030, 1, 9))
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.START to ChangeProblem.PAST)), check(past))
        // During the job the start in force is sent as it is, even once it has passed.
        val running = checkChange(past.copy(start = "nonsense"), base, home, running = true, asksMode = false, zone,
            Instant.parse("2030-01-15T11:00:00Z")) as ChangeCheck.Ready
        assertEquals(base.startAt, running.draft.start)
    }

    @Test fun eachWrongFieldIsNamedAtOnce() {
        val wrong = input.copy(scope = " ", minutes = "", amount = "1.000,50", visitFee = "50 €", date = null)
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.SCOPE to ChangeProblem.MISSING, ChangeField.DATE to ChangeProblem.MISSING,
            ChangeField.DURATION to ChangeProblem.MISSING, ChangeField.AMOUNT to ChangeProblem.FORMAT,
            ChangeField.VISIT_FEE to ChangeProblem.FORMAT)), check(wrong))
    }

    @Test fun amountsAboveTheServerLimitAreOutOfRange() {
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.AMOUNT to ChangeProblem.RANGE, ChangeField.VISIT_FEE to ChangeProblem.RANGE)),
            check(input.copy(amount = "1000000,01", visitFee = "2000000")))
        val ready = check(input.copy(amount = "1000000", visitFee = "20,5")) as ChangeCheck.Ready
        assertEquals(100_000_000, ready.draft.amountCents)
        assertEquals(2050, ready.draft.visitFeeCents)
    }

    @Test fun aNewAddressNeedsAPlaceAndAValidPoint() {
        val moved = input.copy(address = AddressInput("Calle 2", "43,36", "-5.85"))
        assertEquals(ChangedAddress("Calle 2", 43.36, -5.85), (check(moved) as ChangeCheck.Ready).draft.address)
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.ADDRESS to ChangeProblem.MISSING, ChangeField.POINT to ChangeProblem.MISSING)),
            check(input.copy(address = AddressInput(" ", "", "-5.85"))))
        // "NaN" and "Infinity" read as numbers in Kotlin but are not coordinates.
        for ((lat, lng) in listOf("NaN" to "-5.85", "43.3" to "Infinity", "abc" to "1"))
            assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.POINT to ChangeProblem.FORMAT)),
                check(input.copy(address = AddressInput("Calle 2", lat, lng))))
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.POINT to ChangeProblem.RANGE)),
            check(input.copy(address = AddressInput("Calle 2", "91", "-5.85"))))
        // Once started the address stays; the draft never carries one.
        assertNull((check(moved, running = true) as ChangeCheck.Ready).draft.address)
    }

    @Test fun aClientAddingACostToAFreeAppointmentChoosesHowToPay() {
        assertEquals(ChangeCheck.Wrong(mapOf(ChangeField.MODE to ChangeProblem.MISSING)), check(input, asksMode = true))
        assertEquals(PaymentMode.DIRECT, (check(input.copy(mode = PaymentMode.DIRECT), asksMode = true) as ChangeCheck.Ready).draft.mode)
        // Still free: no mode asked, none sent.
        val free = check(input.copy(amount = "0", mode = PaymentMode.DIRECT), asksMode = true) as ChangeCheck.Ready
        assertNull(free.draft.mode)
        assertNull((check(input.copy(mode = PaymentMode.DIRECT)) as ChangeCheck.Ready).draft.mode)
    }

    @Test fun theNoteIsTrimmedAndAnEmptyOneIsNotSent() {
        assertEquals("Llego antes", (check(input.copy(note = "  Llego antes ")) as ChangeCheck.Ready).draft.note)
        assertNull((check(input.copy(note = "  ")) as ChangeCheck.Ready).draft.note)
    }

    /** Going back to the conditions in force after a wrong value is not a change: said plainly, not as an error. */
    @Test fun theConditionsInForceAreNotAChange() {
        val same = input.copy(scope = " Sifón ")
        assertEquals(ChangeCheck.Unchanged, check(same))
        assertEquals(ChangeCheck.Unchanged, check(same.copy(address = AddressInput("Calle 1", "43.050000", "-5.950000"))))
        assertTrue(check(same.copy(travel = true)) is ChangeCheck.Ready)
        assertTrue(check(same.copy(address = AddressInput("Calle 1, 2º", "43.05", "-5.95"))) is ChangeCheck.Ready)
    }

    /** The form shows hours and minutes: the same minute as the start in force keeps that start, seconds included. */
    @Test fun theStartInForceShownToTheMinuteIsKeptAsItIs() {
        val withSeconds = base.copy(startAt = Instant.parse("2030-01-15T10:30:42.5Z"))
        val same = input.copy(scope = "Sifón")
        assertEquals(ChangeCheck.Unchanged, checkChange(same, withSeconds, home, false, false, zone, now))
        val longer = checkChange(same.copy(minutes = "120"), withSeconds, home, false, false, zone, now) as ChangeCheck.Ready
        assertEquals(withSeconds.startAt, longer.draft.start)
    }
}
