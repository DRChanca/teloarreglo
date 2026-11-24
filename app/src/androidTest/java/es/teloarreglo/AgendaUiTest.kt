package es.teloarreglo

import androidx.compose.runtime.rememberCoroutineScope
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

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun professionalSavesWeeklyAvailabilityAndDuration() {
        val fake = FakeAgenda()
        compose.setContent { AgendaCard(fake, rememberCoroutineScope()) }
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
        compose.setContent { AgendaCard(fake, rememberCoroutineScope()) }
        compose.waitUntil(5_000) { compose.onNodeWithTag("agenda_block_add").fetchSemanticsNode().config
            .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Disabled) { null } == null }
        compose.onNodeWithTag("agenda_block_add").performScrollTo().performClick()
        waitForTag("agenda_error")
        compose.onAllNodesWithTag("agenda_saved").assertCountEquals(0)
        assertEquals(0, fake.state.blocks.size)
    }
}
