package es.teloarreglo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import es.teloarreglo.chat.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test

/** The composer only talks about limits when a video is refused, and then it names the limit (#51). */
class ChatAttachmentLimitsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val conversation = Conversation("conversation", "profile", "client", "pro", "Fontanería")
    private val chat = object : Chat {
        override suspend fun open(profileId: String) = conversation
        override suspend fun conversations() = listOf(conversation)
        override suspend fun messages(conversationId: String) = emptyList<ChatMessage>()
        override suspend fun send(conversationId: String, requestId: String, body: String) = error("unused")
    }
    private val attachments = object : ChatAttachments {
        override fun send(conversationId: String, requestId: String, kind: AttachmentKind, mime: String,
            bytes: ByteArray) = emptyFlow<AttachmentProgress>()
        override suspend fun url(path: String) = "file:///missing"
    }

    private fun show() {
        compose.setContent { ChatScreen(chat, "pro", conversation, {}, {}, attachments = attachments) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("video_attach").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Stands in for the gallery: the picked video is refused while being prepared. */
    private fun pickRefusedVideo(failure: AttachmentFailure) {
        val sender = ViewModelProvider(compose.activity)["chat-attachment/pro/conversation", ChatAttachmentSender::class.java]
        compose.runOnIdle { sender.pick(AttachmentKind.VIDEO) { throw ChatAttachmentException(failure) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("attachment_error").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun atRestThereIsNoTextAboutSizesOrCompression() {
        show()
        check(compose.onAllNodes(hasText("720p", substring = true)).fetchSemanticsNodes().isEmpty())
        check(compose.onAllNodes(hasText("1600 px", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aTooLongVideoIsRefusedNamingTheDurationLimit() {
        show()
        pickRefusedVideo(AttachmentFailure.TOO_LONG)
        compose.onNodeWithTag("attachment_error").assert(hasText("40", substring = true))
    }

    @Test fun aTooLargeVideoIsRefusedNamingTheSizeLimit() {
        show()
        pickRefusedVideo(AttachmentFailure.TOO_LARGE)
        compose.onNodeWithTag("attachment_error").assert(hasText("20", substring = true))
    }
}
