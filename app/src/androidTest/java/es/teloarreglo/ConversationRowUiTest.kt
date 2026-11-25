package es.teloarreglo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.chat.*
import org.junit.Rule
import org.junit.Test

class ConversationRowUiTest {
    @get:Rule val compose = createComposeRule()
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val conversation = Conversation("c1", "profile", "client-0000abcd", "pro", "Fontanería Prueba",
        clientName = "Lucía Prueba", unreadCount = 3)

    @Test fun professionalSeesClientNameAndAccessibleUnreadCount() {
        compose.setContent { TeloTheme { ConversationRow(conversation, "pro") {} } }
        compose.onNodeWithText("Lucía Prueba").assertIsDisplayed()
        compose.onNodeWithTag("unread_c1", useUnmergedTree = true)
            .assertTextEquals("3")
            .assertContentDescriptionEquals(resources.getQuantityString(R.plurals.chat_unread, 3, 3))
    }

    @Test fun noIndicatorWithoutUnreadMessages() {
        compose.setContent { TeloTheme { ConversationRow(conversation.copy(unreadCount = 0), "pro") {} } }
        compose.onNodeWithTag("unread_c1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun clientSeesBusinessNameAndOwnIndicator() {
        compose.setContent { TeloTheme { ConversationRow(conversation.copy(unreadCount = 1), "client-0000abcd") {} } }
        compose.onNodeWithText("Fontanería Prueba").assertIsDisplayed()
        compose.onNodeWithText("Lucía Prueba").assertDoesNotExist()
        compose.onNodeWithTag("unread_c1", useUnmergedTree = true).assertTextEquals("1")
    }

    @Test fun clientWithoutDeclaredNameFallsBackToShortIdentifier() {
        compose.setContent { TeloTheme { ConversationRow(conversation.copy(clientName = null), "pro") {} } }
        compose.onNodeWithText(resources.getString(R.string.chat_client, "0000abcd")).assertIsDisplayed()
    }

    @Test fun largeCountsAreCappedVisuallyButAnnouncedExactly() {
        compose.setContent { TeloTheme { ConversationRow(conversation.copy(unreadCount = 120), "pro") {} } }
        compose.onNodeWithTag("unread_c1", useUnmergedTree = true)
            .assertTextEquals("99+")
            .assertContentDescriptionEquals(resources.getQuantityString(R.plurals.chat_unread, 120, 120))
    }

    @Test fun openingConversationMarksItRead() {
        val read = mutableListOf<String>()
        val chat = object : Chat {
            override suspend fun open(profileId: String) = conversation
            override suspend fun conversations() = listOf(conversation)
            override suspend fun messages(conversationId: String) =
                listOf(ChatMessage("m1", conversationId, "client-0000abcd", "k", "Hola", "2026-11-23T10:00:00Z"))
            override suspend fun send(conversationId: String, requestId: String, body: String) = error("unused")
            override suspend fun markRead(conversationId: String) { read += conversationId }
        }
        compose.setContent { ChatScreen(chat, "pro", conversation, {}, {}) }
        compose.waitUntil(5_000) { read.isNotEmpty() }
        compose.onNodeWithText("Lucía Prueba").assertIsDisplayed()
    }

    /** #47: an empty inbox offers the way to find a professional instead of a bare sentence. */
    @Test fun emptyInboxOffersTheCatalogue() {
        var browsed = false
        val chat = object : Chat {
            override suspend fun open(profileId: String) = conversation
            override suspend fun conversations() = emptyList<Conversation>()
            override suspend fun messages(conversationId: String) = emptyList<ChatMessage>()
            override suspend fun send(conversationId: String, requestId: String, body: String) = error("unused")
            override suspend fun markRead(conversationId: String) {}
        }
        compose.setContent { TeloTheme { ChatScreen(chat, "pro", null, {}, {}, onBrowse = { browsed = true }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_empty_browse").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(resources.getString(R.string.chat_empty)).assertIsDisplayed()
        compose.onNodeWithTag("chat_empty_browse").performClick()
        assert(browsed)
    }
}
