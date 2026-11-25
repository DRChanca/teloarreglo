package es.teloarreglo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.appointments.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** In-memory agenda with the server's observable rules: whole-schedule save, block keys, overlap refusal. */
private class FakeAgenda : Agenda {
    var state = AgendaState()
    val blockKeys = mutableListOf<String>()
    var overlap = false

    override suspend fun load() = state
    override suspend fun save(defaultDurationMinutes: Int, windows: List<AvailabilityWindow>): AgendaState {
        state = state.copy(defaultDurationMinutes = defaultDurationMinutes, windows = windows)
        return state
    }
    override suspend fun addBlock(requestId: String, start: Instant, end: Instant, reason: String?) {
        blockKeys += requestId
        if (overlap) throw AgendaException(AgendaFailure.SLOT_UNAVAILABLE, Exception())
        state = state.copy(blocks = state.blocks + AgendaBlock("b${state.blocks.size + 1}", start, end, reason))
    }
    override suspend fun removeBlock(blockId: String) {
        state = state.copy(blocks = state.blocks.filterNot { it.id == blockId })
    }
}

class AgendaUiTest {
    @get:Rule val compose = createComposeRule()

    /** Hosts the card in a scrolling column, as the profile editor does, so off-screen controls can be scrolled to. */
    private fun show(fake: FakeAgenda) = compose.setContent {
        Column(Modifier.verticalScroll(rememberScrollState())) { AgendaCard(fake, rememberCoroutineScope()) }
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun professionalSavesWeeklyAvailabilityAndDuration() {
        val fake = FakeAgenda()
        show(fake)
        compose.waitUntil(5_000) { compose.onNodeWithTag("agenda_save").fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Disabled) { null } == null }
        compose.onNodeWithTag("agenda_duration").performTextReplacement("45")
        compose.onNodeWithTag("agenda_day_2").performClick()
        compose.onNodeWithTag("agenda_save").performScrollTo().performClick()
        waitForTag("agenda_saved")
        assertEquals(45, fake.state.defaultDurationMinutes)
        assertEquals(listOf(AvailabilityWindow(2, "09:00", "14:00")), fake.state.windows)
    }

    @Test fun anOverlappingBlockIsRefusedAndNotShownAsSaved() {
        val fake = FakeAgenda().apply { overlap = true }
        show(fake)
        compose.waitUntil(5_000) { compose.onNodeWithTag("agenda_block_add").fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Disabled) { null } == null }
        compose.onNodeWithTag("agenda_block_add").performScrollTo().performClick()
        waitForTag("agenda_error")
        compose.onAllNodesWithTag("agenda_saved").assertCountEquals(0)
        assertEquals(0, fake.state.blocks.size)
    }

    @Test fun aWrongHourMarksItsFieldAndNamesTheDayWithoutSaving() {
        val fake = FakeAgenda()
        show(fake)
        compose.waitUntil(5_000) { compose.onNodeWithTag("agenda_save").fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Disabled) { null } == null }
        compose.onNodeWithTag("agenda_day_3").performClick()
        compose.onNodeWithTag("agenda_to_3").performTextReplacement("08:00")
        compose.onNodeWithTag("agenda_save").performScrollTo().performClick()
        compose.onNodeWithTag("agenda_days_error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("agenda_to_3").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose.onNodeWithTag("agenda_from_3").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Error))
        compose.onAllNodesWithTag("agenda_saved").assertCountEquals(0)
        assertEquals(emptyList<AvailabilityWindow>(), fake.state.windows)
    }
}
