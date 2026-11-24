package es.teloarreglo

import es.teloarreglo.chat.MessageSelection
import org.junit.Assert.*
import org.junit.Test

class MessageSelectionTest {
    @Test fun longPressStartsSelectionWithThatMessageAndTapsToggleOthers() {
        val selection = MessageSelection().longPress("m1", unavailable = emptySet())
            .tap("m2", emptySet()).tap("m3", emptySet()).tap("m2", emptySet())
        assertTrue(selection.active)
        assertEquals(listOf("m1", "m3"), selection.ids)
    }

    @Test fun tapOutsideSelectionModeSelectsNothing() {
        val selection = MessageSelection().tap("m1", emptySet())
        assertFalse(selection.active)
        assertEquals(emptyList<String>(), selection.ids)
    }

    @Test fun messagesAlreadyInTheIncidentCannotBeSelected() {
        val selection = MessageSelection().longPress("m1", unavailable = setOf("m1"))
        assertTrue(selection.active)
        assertEquals(emptyList<String>(), selection.tap("m1", setOf("m1")).ids)
    }

    @Test fun startingFromTheIncidentOpensAnEmptySelection() {
        val selection = MessageSelection().start()
        assertTrue(selection.active)
        assertTrue(selection.ids.isEmpty())
        assertFalse(selection.cancel().active)
    }

    @Test fun addedMessagesLeaveTheSelectionAndItEndsWhenNothingRemains() {
        val selection = MessageSelection().longPress("m1", emptySet()).tap("m2", emptySet())
        val partial = selection.withoutAdded(listOf("m1"))
        assertEquals(listOf("m2"), partial.ids)
        assertTrue(partial.active)
        assertFalse(partial.withoutAdded(listOf("m2")).active)
    }
}
