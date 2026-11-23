package es.teloarreglo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.chat.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.*
import java.util.UUID

class ChatUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSendKeepsTextAndRetriesWithoutChangingIdentity() {
        var firstKey: String? = null
        val chat = object : Chat {
            override suspend fun open(profileId: String) = conversation
            override suspend fun conversations() = listOf(conversation)
            override suspend fun messages(conversationId: String) = emptyList<ChatMessage>()
            override suspend fun send(conversationId: String, requestId: String, body: String): ChatMessage {
                if (firstKey == null) {
                    firstKey = requestId
                    throw java.io.IOException("offline")
                }
                check(firstKey == requestId)
                return ChatMessage("message", conversationId, "client", requestId, body, "2026-11-20T18:00:00Z")
            }
        }
        compose.setContent { ChatScreen(chat, "client", conversation, {}, {}) }
        compose.onNodeWithTag("chat_input").performTextInput("Fuga de agua")
        compose.onNodeWithTag("chat_send").performClick()
        compose.onNodeWithTag("chat_send_error").assertExists()
        compose.onNodeWithTag("chat_input").assertTextContains("Fuga de agua")
        compose.onNodeWithTag("chat_retry_send").performClick()
        compose.onNodeWithTag("message_message").assertExists()
        compose.onNodeWithText("Fuga de agua").assertIsDisplayed()
    }

    @Test fun professionalOpensInboxAndReadsPersistedContact() {
        val chat = object : Chat {
            override suspend fun open(profileId: String) = conversation
            override suspend fun conversations() = listOf(conversation)
            override suspend fun messages(conversationId: String) = listOf(
                ChatMessage("persisted", conversationId, "client", "request", "Necesito una reparación", "2026-11-20T18:00:00Z"))
            override suspend fun send(conversationId: String, requestId: String, body: String) = error("not used")
        }
        compose.setContent {
            var selected by remember { mutableStateOf<Conversation?>(null) }
            ChatScreen(chat, "pro", selected, { selected = it }, { selected = null })
        }
        compose.waitUntil { compose.onAllNodesWithTag("conversation_conversation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("conversation_conversation").performClick()
        compose.waitUntil { compose.onAllNodesWithText("Necesito una reparación").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Necesito una reparación").assertIsDisplayed()
    }

    @Test fun recoveredHistoryConfirmsUncertainSendWithoutManualRetry() {
        val current = conversation.copy(id = UUID.randomUUID().toString())
        var committed: ChatMessage? = null
        val chat = object : Chat {
            override suspend fun open(profileId: String) = current
            override suspend fun conversations() = listOf(current)
            override suspend fun messages(conversationId: String) = listOfNotNull(committed)
            override suspend fun send(conversationId: String, requestId: String, body: String): ChatMessage {
                committed = ChatMessage("synced", conversationId, "client", requestId, body, "2026-11-20T18:00:00Z")
                throw java.io.IOException("response lost after commit")
            }
        }
        compose.setContent { ChatScreen(chat, "client", current, {}, {}) }
        compose.waitUntil { runCatching { compose.onNodeWithTag("chat_input").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag("chat_input").performTextInput("Ya guardado")
        compose.onNodeWithTag("chat_send").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("message_synced").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("chat_input").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag("chat_retry_send").assertDoesNotExist()
    }

    @Test fun uncertainSendSurvivesLeavingAndReopeningChat() {
        val current = conversation.copy(id = UUID.randomUUID().toString())
        var firstKey: String? = null
        var visible by mutableStateOf(true)
        val chat = object : Chat {
            override suspend fun open(profileId: String) = current
            override suspend fun conversations() = listOf(current)
            override suspend fun messages(conversationId: String) = emptyList<ChatMessage>()
            override suspend fun send(conversationId: String, requestId: String, body: String): ChatMessage {
                if (firstKey == null) { firstKey = requestId; throw java.io.IOException("response lost") }
                check(firstKey == requestId)
                return ChatMessage("recovered", conversationId, "client", requestId, body, "2026-11-20T18:00:00Z")
            }
        }
        compose.setContent { if (visible) ChatScreen(chat, "client", current, {}, {}) }
        compose.waitUntil { runCatching { compose.onNodeWithTag("chat_input").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag("chat_input").performTextInput("Mensaje pendiente")
        compose.onNodeWithTag("chat_send").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("chat_send_error").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.waitUntil { compose.onAllNodesWithTag("chat_retry_send").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_input").assertTextContains("Mensaje pendiente")
        compose.onNodeWithTag("chat_retry_send").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("message_recovered").fetchSemanticsNodes().isNotEmpty() }
    }

    companion object {
        val conversation = Conversation("conversation", "profile", "client", "pro", "Fontanería")
    }
}
