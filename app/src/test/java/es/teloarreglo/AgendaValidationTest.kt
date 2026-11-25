package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The schedule editor says which day and which hour is wrong, not only that something is (#47). */
class AgendaValidationTest {
    private val closed = DayRow(false, "09:00", "14:00")
    private fun week(vararg changes: Pair<Int, DayRow>) = (1..7).map { day -> changes.toMap()[day] ?: closed }

    @Test fun aValidWeekHasNoProblems() {
        val problems = agendaProblems("60", week(1 to DayRow(true, "09:00", "14:00"), 3 to DayRow(true, "16:00", "20:00")))
        assertTrue(problems.none)
    }

    @Test fun aMalformedStartMarksOnlyThatDaysStart() {
        assertEquals(AgendaProblems(duration = false, from = setOf(2), to = emptySet()),
            agendaProblems("60", week(2 to DayRow(true, "9", "14:00"))))
    }

    @Test fun anEndBeforeTheStartMarksTheEnd() {
        assertEquals(AgendaProblems(duration = false, from = emptySet(), to = setOf(5)),
            agendaProblems("60", week(5 to DayRow(true, "14:00", "09:00"))))
    }

    @Test fun closedDaysAreNotChecked() {
        assertTrue(agendaProblems("60", week(4 to DayRow(false, "xx", "yy"))).none)
    }

    @Test fun aDurationOutsideFifteenToADayIsMarked() {
        listOf("", "10", "1441").forEach {
            assertEquals(it, AgendaProblems(duration = true, from = emptySet(), to = emptySet()), agendaProblems(it, week()))
        }
    }

    @Test fun theDaysWithProblemsAreListedInWeekOrder() {
        val problems = agendaProblems("60", week(6 to DayRow(true, "25:00", "26:00"), 1 to DayRow(true, "10:00", "10:00")))
        assertEquals(listOf(1, 6), problems.days)
    }
}
