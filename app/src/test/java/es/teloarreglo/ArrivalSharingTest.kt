package es.teloarreglo

import es.teloarreglo.appointments.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** What the professional's phone does in the ventana de llegada (#85): send, wait for the opening or stop. */
class ArrivalSharingTest {
    private val now = Instant.parse("2030-01-15T10:00:00Z")
    private fun at(minutes: Long) = now.plusSeconds(minutes * 60)

    @Test fun anOpenWindowKeepsSendingAboutEveryThirtySeconds() {
        val report = ArrivalReport(ArrivalWindow.OPEN, accepted = true, opensAt = at(-10), closesAt = at(110))
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), nextArrivalStep(true, ReportOutcome.Answered(report), now))
    }

    @Test fun aClosedWindowStopsForGood() {
        val closed = ArrivalReport(ArrivalWindow.CLOSED, accepted = false)
        assertEquals(ArrivalStep.Stop, nextArrivalStep(true, ReportOutcome.Answered(closed), now))
    }

    @Test fun aWindowNotYetOpenWaitsForTheOpeningTheServerGives() {
        // Another appointment started: the window opens 30 min before instead of 60.
        val notYet = ArrivalReport(ArrivalWindow.NOT_YET_OPEN, accepted = false, opensAt = at(20), closesAt = at(110))
        assertEquals(ArrivalStep.WaitUntil(at(20)), nextArrivalStep(true, ReportOutcome.Answered(notYet), now))
    }

    @Test fun withoutTheProfessionalsChoiceNothingIsSentEvenWithTheWindowOpen() {
        val open = ArrivalReport(ArrivalWindow.OPEN, accepted = true, opensAt = at(-10), closesAt = at(110))
        assertEquals(ArrivalStep.Stop, nextArrivalStep(false, ReportOutcome.Answered(open), now))
    }

    @Test fun aServerRejectionStops() {
        assertEquals(ArrivalStep.Stop, nextArrivalStep(true, ReportOutcome.Rejected, now))
    }

    @Test fun choosingHoursBeforeWaitsUntilTheEarliestOpeningWithoutSending() {
        // Nothing is sent before an hour ahead; at that time the server says whether it is open (or opens at -30 min).
        assertEquals(ArrivalStep.WaitUntil(at(120)), firstArrivalStep(start = at(180), now = now))
    }

    @Test fun choosingInsideTheHourBeforeSendsAtOnce() {
        assertEquals(ArrivalStep.SendAt(now), firstArrivalStep(start = at(20), now = now))
        // Late professional: up to an hour after the start the window can still be open.
        assertEquals(ArrivalStep.SendAt(now), firstArrivalStep(start = at(-50), now = now))
    }

    @Test fun anHourAfterTheStartThereIsNothingLeftToShare() {
        assertEquals(ArrivalStep.Stop, firstArrivalStep(start = at(-60), now = now))
    }

    @Test fun noAnswerOrNoFixTriesAgainOnTheNextRound() {
        // A lost connection or a GPS without a fix is not a rejection: the window may still be open.
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), nextArrivalStep(true, ReportOutcome.Failed, now))
    }
}

/** One round of the professional's phone (#85): position, server answer, next step, and the choice kept or forgotten. */
class ArrivalRoundTest {
    private val now = Instant.parse("2030-01-15T10:00:00Z")
    private val choice = ArrivalChoice("a1", "Lucía", now.plusSeconds(1800))
    private val open = ArrivalReport(ArrivalWindow.OPEN, accepted = true, opensAt = now.minusSeconds(1800), closesAt = now.plusSeconds(5400))

    private class FakePositions(var answer: () -> ArrivalReport) : ArrivalPositions {
        val reported = mutableListOf<Triple<String, Double, Double>>()
        val stopped = mutableListOf<String>()
        /** What «estado de la ventana» answers; by default the same window as the report. */
        var window: () -> ArrivalReport = { answer().copy(accepted = false, reportedAt = null) }
        override suspend fun window(appointmentId: String): ArrivalReport = window()
        override suspend fun report(appointmentId: String, fix: ArrivalFix): ArrivalReport {
            reported += Triple(appointmentId, fix.latitude, fix.longitude); return answer()
        }
        override suspend fun stopSharing(appointmentId: String): ArrivalReport {
            stopped += appointmentId; return ArrivalReport(ArrivalWindow.OPEN, accepted = false)
        }
    }
    private class MemoryChoices : ArrivalChoices {
        val saved = mutableMapOf<String, ArrivalChoice>()
        override fun all() = saved.values.toList()
        override fun get(appointmentId: String) = saved[appointmentId]
        override fun save(choice: ArrivalChoice) { saved[choice.appointmentId] = choice }
        override fun forget(appointmentId: String) { saved.remove(appointmentId) }
    }

    private val positions = FakePositions { open }
    private val choices = MemoryChoices().apply { save(choice) }
    private var fix: ArrivalFix? = ArrivalFix(43.36, -5.85, 12.0)
    private val round = ArrivalRound(positions, choices) { fix }

    @Test fun anOpenWindowSendsThePositionAndKeepsGoing() = kotlinx.coroutines.test.runTest {
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), round.run("a1", now))
        assertEquals(listOf(Triple("a1", 43.36, -5.85)), positions.reported)
        assertEquals(choice, choices.get("a1"))
    }

    @Test fun beforeTheOpeningNoPositionIsSent() = kotlinx.coroutines.test.runTest {
        val opens = now.plusSeconds(600)
        positions.window = { ArrivalReport(ArrivalWindow.NOT_YET_OPEN, accepted = false, opensAt = opens) }
        assertEquals(ArrivalStep.WaitUntil(opens), round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
    }

    @Test fun aClosedWindowIsNeverSentAPosition() = kotlinx.coroutines.test.runTest {
        positions.window = { ArrivalReport(ArrivalWindow.CLOSED, accepted = false) }
        assertEquals(ArrivalStep.Stop, round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
        assertEquals(null, choices.get("a1"))
    }

    @Test fun withoutKnowingTheWindowNoPositionIsSent() = kotlinx.coroutines.test.runTest {
        positions.window = { throw java.io.IOException("offline") }
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
        assertEquals(choice, choices.get("a1"))
    }

    @Test fun aRefusedWindowStateStops() = kotlinx.coroutines.test.runTest {
        positions.window = { throw AppointmentException(AppointmentFailure.ACCESS, IllegalStateException("permission_denied")) }
        assertEquals(ArrivalStep.Stop, round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
    }

    @Test fun aClosedWindowStopsAndForgetsTheChoice() = kotlinx.coroutines.test.runTest {
        positions.answer = { ArrivalReport(ArrivalWindow.CLOSED, accepted = false) }
        assertEquals(ArrivalStep.Stop, round.run("a1", now))
        assertEquals(null, choices.get("a1"))
    }

    @Test fun aWindowNotYetOpenKeepsTheChoiceForTheOpening() = kotlinx.coroutines.test.runTest {
        val opens = now.plusSeconds(600)
        positions.answer = { ArrivalReport(ArrivalWindow.NOT_YET_OPEN, accepted = false, opensAt = opens) }
        assertEquals(ArrivalStep.WaitUntil(opens), round.run("a1", now))
        assertEquals(choice, choices.get("a1"))
    }

    @Test fun aServerRejectionStopsAndForgetsTheChoice() = kotlinx.coroutines.test.runTest {
        positions.answer = { throw AppointmentException(AppointmentFailure.ACCESS, IllegalStateException("permission_denied")) }
        assertEquals(ArrivalStep.Stop, round.run("a1", now))
        assertEquals(null, choices.get("a1"))
    }

    @Test fun aLostAnswerKeepsSharing() = kotlinx.coroutines.test.runTest {
        positions.answer = { throw java.io.IOException("offline") }
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), round.run("a1", now))
        assertEquals(choice, choices.get("a1"))
    }

    @Test fun withoutAFixNothingIsSentAndItTriesAgain() = kotlinx.coroutines.test.runTest {
        fix = null
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
    }

    @Test fun withoutTheChoiceNothingIsSent() = kotlinx.coroutines.test.runTest {
        choices.forget("a1")
        assertEquals(ArrivalStep.Stop, round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
    }

    @Test fun stoppingForgetsTheChoiceAndTellsTheServer() = kotlinx.coroutines.test.runTest {
        round.stopSharing("a1")
        assertEquals(null, choices.get("a1"))
        assertEquals(listOf("a1"), positions.stopped)
    }

    @Test fun stoppingWithoutAConnectionStillForgetsTheChoice() = kotlinx.coroutines.test.runTest {
        val offline = object : ArrivalPositions by positions {
            override suspend fun stopSharing(appointmentId: String): ArrivalReport = throw java.io.IOException("offline")
        }
        ArrivalRound(offline, choices) { fix }.stopSharing("a1")
        // The server stops showing it on its own after 2 min without positions.
        assertEquals(null, choices.get("a1"))
    }
}
