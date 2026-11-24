package es.teloarreglo

import es.teloarreglo.appointments.AvailabilityWindow
import es.teloarreglo.catalogue.AgendaDay
import es.teloarreglo.catalogue.AgendaDraft
import es.teloarreglo.catalogue.ProfileAgenda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The agenda is filled in when presenting the profile (#41). */
class ProfileAgendaTest {
    private fun week(vararg open: Pair<Int, Pair<String, String>>) = List(7) { i ->
        open.firstOrNull { it.first == i + 1 }?.let { AgendaDay(true, it.second.first, it.second.second) } ?: AgendaDay(false)
    }

    @Test fun openDaysBecomeWeeklyWindowsWithTheUsualDuration() {
        val draft = AgendaDraft(" 90 ", week(1 to ("09:00" to "14:00"), 3 to ("16:00" to "20:00")))
        assertEquals(ProfileAgenda(90, listOf(AvailabilityWindow(1, "09:00", "14:00"), AvailabilityWindow(3, "16:00", "20:00"))),
            draft.toAgenda())
    }

    @Test fun aPresentationNeedsAtLeastOneOpenDay() {
        assertNull(AgendaDraft("60", week()).toAgenda())
    }

    @Test fun badHoursOrDurationCannotBeSent() {
        assertNull(AgendaDraft("10", week(1 to ("09:00" to "14:00"))).toAgenda())
        assertNull(AgendaDraft("", week(1 to ("09:00" to "14:00"))).toAgenda())
        assertNull(AgendaDraft("60", week(2 to ("14:00" to "09:00"))).toAgenda())
        assertNull(AgendaDraft("60", week(2 to ("9:00" to "14:00"))).toAgenda())
    }

    @Test fun theSavedAgendaIsWhereTheEditorStarts() {
        val draft = AgendaDraft.from(45, listOf(AvailabilityWindow(5, "08:00", "13:00")))
        assertEquals("45", draft.duration)
        assertEquals(AgendaDay(true, "08:00", "13:00"), draft.days[4])
        assertEquals(6, draft.days.count { !it.open })
        assertEquals("60", AgendaDraft.from(null, emptyList()).duration)
    }
}
