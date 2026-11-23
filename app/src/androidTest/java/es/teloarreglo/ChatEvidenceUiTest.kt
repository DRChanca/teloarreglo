package es.teloarreglo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.appointments.*
import es.teloarreglo.chat.*
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** #26 redesign: messages are selected inside the chat (long press, taps, search) and added one by one. */
class ChatEvidenceUiTest {
    @get:Rule val compose = createComposeRule()
    private val conversation = Conversation("conversation", "profile", "client", "pro", "Fontanería")
    private val history = listOf(
        ChatMessage("m1", "conversation", "pro", "k1", "Llego a las 10", "2030-01-15T09:00:00Z"),
        ChatMessage("m2", "conversation", "client", "k2", "La caldera sigue rota", "2030-01-15T12:00:00Z"),
        ChatMessage("m3", "conversation", "pro", "k3", "Ya está arreglada", "2030-01-15T13:00:00Z"),
    )
    private val chat = object : Chat {
        override suspend fun open(profileId: String) = conversation
        override suspend fun conversations() = listOf(conversation)
        override suspend fun messages(conversationId: String) = history
        override suspend fun send(conversationId: String, requestId: String, body: String) = error("not used")
    }

    private class RecordingIncidents : Incidents {
        val added = mutableListOf<Pair<String, String>>()
        override suspend fun open(appointmentId: String, requestId: String, kind: IncidentKind, description: String) =
            error("not used")
        override suspend fun addEvidence(incidentId: String, requestId: String, draft: EvidenceDraft): IncidentEvidence {
            val id = (draft as EvidenceDraft.Message).messageId
            added += id to requestId
            return IncidentEvidence("e$id", incidentId, "client", requestId, EvidenceKind.MESSAGE, messageId = id,
                createdAt = Instant.now())
        }
    }

    @Test fun longPressSelectsMessagesAndAddsEachToTheIncident() {
        val incidents = RecordingIncidents()
        val link = ChatEvidenceLink(incidents, MainScope())
        link.targets = listOf(EvidenceTarget("conversation", "inc", "Caldera", contributed = setOf("m1")))
        compose.setContent { ChatScreen(chat, "client", conversation, {}, {}, evidence = link) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("message_m2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message_in_incident_m1").assertExists()
        compose.onNodeWithTag("message_row_m2").performTouchInput { longClick() }
        compose.onNodeWithTag("message_row_m3").performClick()
        compose.onNodeWithTag("message_row_m1").performClick() // already in the incident: not selectable
        compose.onNodeWithTag("chat_selection_count").assertTextEquals("Seleccionados: 2")
        compose.onNodeWithTag("chat_selection_add").performClick()
        compose.onNodeWithTag("chat_select_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("incident_visibility_accept").performClick()
        compose.onNodeWithTag("chat_select_confirm").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("chat_selection_done").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("m2", "m3"), incidents.added.map { it.first })
        assertEquals(messageEvidenceKey("inc", "m2"), incidents.added.first().second)
        compose.onAllNodesWithTag("chat_selection_bar").assertCountEquals(0)
    }

    @Test fun searchFindsMessagesAndTheIncidentCardStartsSelection() {
        val link = ChatEvidenceLink(RecordingIncidents(), MainScope())
        link.targets = listOf(EvidenceTarget("conversation", "inc", "Caldera", emptySet()))
        compose.setContent { ChatScreen(chat, "client", conversation, {}, {}, evidence = link) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("message_m2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_search_open").performClick()
        compose.onNodeWithTag("chat_search_input").performTextInput("CALDERA")
        compose.onNodeWithTag("chat_search_count").assertTextEquals("1 de 1")
        compose.runOnIdle { link.requested = "inc" }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("chat_selection_bar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_selection_add").assertIsNotEnabled()
        compose.onNodeWithTag("message_row_m2").performClick()
        compose.onNodeWithTag("chat_selection_add").assertIsEnabled()
    }
}
