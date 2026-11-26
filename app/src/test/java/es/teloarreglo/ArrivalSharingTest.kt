package es.teloarreglo

import es.teloarreglo.appointments.*
import kotlinx.coroutines.launch
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
    /** A choice the server already knows of (a kept one); [fresh] is one just made on this phone. */
    private val choice = ArrivalChoice("a1", "Lucía", now.plusSeconds(1800), announced = true)
    private val fresh = choice.copy(announced = false)
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
            stopped += appointmentId; calls += "stop"; return ArrivalReport(ArrivalWindow.OPEN, accepted = false, stopped = true)
        }
        val calls = mutableListOf<String>()
        var start: suspend () -> ArrivalReport = { window() }
        override suspend fun startSharing(appointmentId: String): ArrivalReport { calls += "start"; return start() }
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
    @Test fun aNewChoiceIsToldToTheServerBeforeAnyPosition() = kotlinx.coroutines.test.runTest {
        choices.save(fresh)
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), round.run("a1", now))
        assertEquals(listOf("start"), positions.calls)
        assertEquals(1, positions.reported.size)
        assertEquals(choice, choices.get("a1"))
        // Later rounds only ask the window.
        round.run("a1", now)
        assertEquals(listOf("start"), positions.calls)
    }

    @Test fun aNewChoiceThatCannotReachTheServerSendsNothingAndTriesAgain() = kotlinx.coroutines.test.runTest {
        choices.save(fresh)
        positions.start = { throw java.io.IOException("offline") }
        assertEquals(ArrivalStep.SendAt(now.plusSeconds(30)), round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
        assertEquals(fresh, choices.get("a1"))
    }

    @Test fun aStopOnTheServerEndsAKeptChoiceWithoutSending() = kotlinx.coroutines.test.runTest {
        // «Dejar de compartir» from another phone, or before signing out: the stop mark holds.
        positions.window = { open.copy(accepted = false, stopped = true) }
        assertEquals(ArrivalStep.Stop, round.run("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
        assertEquals(null, choices.get("a1"))
    }

    @Test fun aReportRefusedBecauseOfAStopEnds() = kotlinx.coroutines.test.runTest {
        positions.answer = { open.copy(accepted = false, stopped = true) }
        positions.window = { open.copy(accepted = false) }
        assertEquals(ArrivalStep.Stop, round.run("a1", now))
        assertEquals(null, choices.get("a1"))
    }

    @Test fun stopAndShareAgainReachTheServerInThatOrder() = kotlinx.coroutines.test.runTest {
        val startReached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        choices.save(fresh)
        positions.start = { startReached.complete(Unit); release.await(); open.copy(accepted = false) }
        val sharing = launch { round.run("a1", now) }
        startReached.await()
        // A stop while «volver a compartir» is on its way waits for it, so the server ends stopped.
        val stopping = launch { round.stopSharing("a1") }
        testScheduler.runCurrent()
        assertEquals(listOf("start"), positions.calls)
        release.complete(Unit)
        sharing.join(); stopping.join()
        assertEquals(listOf("start", "stop"), positions.calls)
        assertEquals(null, choices.get("a1"))
    }

    @Test fun anAcceptedChangeMovesTheKeptStart() = kotlinx.coroutines.test.runTest {
        val earlier = now.plusSeconds(600)
        positions.window = { ArrivalReport(ArrivalWindow.OPEN, startAt = earlier) }
        round.check("a1", now)
        assertEquals(earlier, choices.get("a1")?.start)
    }

    @Test fun checkingNeverSendsAPosition() = kotlinx.coroutines.test.runTest {
        assertEquals(ArrivalStep.SendAt(now), round.check("a1", now))
        val opens = now.plusSeconds(600)
        positions.window = { ArrivalReport(ArrivalWindow.NOT_YET_OPEN, opensAt = opens) }
        assertEquals(ArrivalStep.WaitUntil(opens), round.check("a1", now))
        positions.window = { ArrivalReport(ArrivalWindow.CLOSED) }
        assertEquals(ArrivalStep.Stop, round.check("a1", now))
        assertEquals(emptyList<Any>(), positions.reported)
        assertEquals(null, choices.get("a1"))
    }

    @Test fun checkingWithoutAnAnswerStartsAndLetsTheRoundsAsk() = kotlinx.coroutines.test.runTest {
        positions.window = { throw java.io.IOException("offline") }
        assertEquals(ArrivalStep.SendAt(now), round.check("a1", now))
        assertEquals(choice, choices.get("a1"))
    }
}
