package es.teloarreglo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.chat.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test

class ChatPhotoUiTest {
    @get:Rule val compose = createComposeRule()
    private val conversation = Conversation("conversation", "profile", "client", "pro", "Fontanería")
    private val photo = ChatMessage("message", "conversation", "client", "key", "", "2026-11-22T10:00:00Z",
        "photo", "conversation/key.jpg", "image/jpeg", 1234)

    @Test fun theProfessionalSeesTheClientPhotoAndCanOpenItFullScreen() {
        val requested = mutableListOf<String>()
        val chat = object : Chat {
            override suspend fun open(profileId: String) = conversation
            override suspend fun conversations() = listOf(conversation)
            override suspend fun messages(conversationId: String) = listOf(photo)
            override suspend fun send(conversationId: String, requestId: String, body: String) = error("unused")
        }
        val attachments = object : ChatAttachments {
            override fun send(conversationId: String, requestId: String, kind: AttachmentKind, mime: String,
                bytes: ByteArray) = emptyFlow<AttachmentProgress>()
            override suspend fun url(path: String): String { requested.add(path); return "file:///missing.jpg" }
        }
        compose.setContent { ChatScreen(chat, "pro", conversation, {}, {}, attachments = attachments) }
        compose.waitUntil(5_000) { requested.isNotEmpty() }
        compose.onNodeWithTag("photo_attach").assertIsDisplayed()
        compose.onNodeWithTag("message_message").assertExists()
        compose.onNodeWithTag("chat_photo").assertExists()
        check(requested.single() == "conversation/key.jpg")
    }
}
