package es.teloarreglo

import es.teloarreglo.chat.ChatMessage
import es.teloarreglo.chat.MessageSearch
import org.junit.Assert.*
import org.junit.Test

class MessageSearchTest {
    private fun message(id: String, body: String) = ChatMessage(id, "c", "a", "k$id", body, "2026-11-23T10:00:00Z")
    private val chat = listOf(message("m1", "Llego a las 10"), message("m2", "La caldera sigue sin funcionar"),
        message("m3", "Mañana reviso la CALDERA"), message("m4", ""))

    @Test fun findsMessagesIgnoringCaseAndAccentsInChatOrder() {
        assertEquals(listOf("m2", "m3"), MessageSearch(chat, "caldera").ids)
        assertEquals(listOf("m3"), MessageSearch(chat, "manana").ids)
        assertEquals(listOf("m1"), MessageSearch(chat, "LLEGÓ").ids)
    }

    @Test fun aBlankQueryFindsNothing() {
        assertTrue(MessageSearch(chat, "  ").ids.isEmpty())
    }

    @Test fun highlightsEveryOccurrenceInTheOriginalText() {
        assertEquals(listOf(1..3), MessageSearch(chat, "ana").ranges("Mañana"))
        assertEquals(listOf(0..1, 3..4), MessageSearch(chat, "la").ranges("La lavadora"))
    }

    @Test fun startsAtTheNewestMatchAndMovesWrappingAround() {
        val search = MessageSearch(chat, "caldera")
        assertEquals("m3", search.idAt(search.initial))
        assertEquals(0, search.step(search.initial, +1))
        assertEquals(1, search.step(0, -1))
    }
}
