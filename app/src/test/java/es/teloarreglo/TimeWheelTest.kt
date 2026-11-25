package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Test

/** The hour and minute wheels used to choose the time of a cita (#64). */
class TimeWheelTest {
    @Test fun theFormTimeOpensTheWheelsOnTheSameHourAndMinute() {
        assertEquals(WheelTime(9, 30), wheelTime("09:30"))
        assertEquals(WheelTime(18, 5), wheelTime("18:05"))
    }

    @Test fun anUnreadableTimeOpensTheWheelsAtNine() {
        assertEquals(WheelTime(9, 0), wheelTime(""))
        assertEquals(WheelTime(9, 0), wheelTime("25:00"))
    }

    @Test fun theChosenTimeReachesTheFormAsHoursAndMinutes() {
        assertEquals("07:05", WheelTime(7, 5).text)
        assertEquals("23:55", WheelTime(23, 55).text)
    }

    @Test fun minutesGoInStepsOfFive() {
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55), wheelMinutes(current = 30))
    }

    @Test fun aMinuteOffTheStepIsKeptSoTheTimeInForceDoesNotMove() {
        assertEquals(listOf(0, 5, 7, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55), wheelMinutes(current = 7))
    }
}
