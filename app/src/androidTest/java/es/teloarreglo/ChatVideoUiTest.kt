package es.teloarreglo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import es.teloarreglo.chat.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test

class ChatVideoUiTest {
    @get:Rule val compose = createComposeRule()
    private val conversation = Conversation("conversation", "profile", "client", "pro", "Fontanería")
    private val video = ChatMessage("message", "conversation", "client", "key", "", "2026-11-22T10:00:00Z",
        "video", "conversation/key.mp4", "video/mp4", 5_242_880)

    @Test fun theProfessionalSeesTheClientVideoAndOpensItWithAFreshPrivateLink() {
        val requested = mutableListOf<String>()
        val chat = object : Chat {
            override suspend fun open(profileId: String) = conversation
            override suspend fun conversations() = listOf(conversation)
            override suspend fun messages(conversationId: String) = listOf(video)
            override suspend fun send(conversationId: String, requestId: String, body: String) = error("unused")
        }
        val attachments = object : ChatAttachments {
            override fun send(conversationId: String, requestId: String, kind: AttachmentKind, mime: String,
                bytes: ByteArray) = emptyFlow<AttachmentProgress>()
            override suspend fun url(path: String): String { requested.add(path); return "file:///missing.mp4" }
        }
        compose.setContent { ChatScreen(chat, "pro", conversation, {}, {}, attachments = attachments) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat_video").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("video_attach").assertIsDisplayed()
        check(requested.isEmpty()) { "no link is signed until the video is opened" }
        compose.onNodeWithTag("chat_video").performClick()
        compose.waitUntil(5_000) { requested.isNotEmpty() }
        compose.onNodeWithTag("chat_video_player").assertExists()
        check(requested.single() == "conversation/key.mp4")
    }
}
