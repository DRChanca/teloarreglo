package es.teloarreglo

import es.teloarreglo.chat.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAttachmentSenderTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val photo = PreparedPhoto(byteArrayOf(1, 2, 3), 1600, 1200)
    private val video = PreparedVideo(byteArrayOf(4, 5, 6, 7), durationMs = 42_000, width = 1280, height = 720)
    private val PHOTO = AttachmentKind.PHOTO
    private val VIDEO = AttachmentKind.VIDEO
    private fun message(requestId: String, kind: String = "photo") = ChatMessage("m-$requestId", "conversation",
        "client", requestId, "", "2026-11-22T10:00:00Z", kind, "conversation/$requestId", "image/jpeg", 3)

    private class FakeAttachments(val behaviour: suspend kotlinx.coroutines.flow.FlowCollector<AttachmentProgress>.(String) -> Unit)
        : ChatAttachments {
        val requests = mutableListOf<String>()
        val uploads = mutableListOf<Triple<AttachmentKind, String, ByteArray>>()
        override fun send(conversationId: String, requestId: String, kind: AttachmentKind, mime: String, bytes: ByteArray):
            Flow<AttachmentProgress> = flow {
            requests.add(requestId); uploads.add(Triple(kind, mime, bytes))
            behaviour(requestId)
        }
        override suspend fun url(path: String) = "https://signed/$path"
    }

    @Test fun progressIsShownAndTheConfirmedMessageIsHandedToTheChat() = runTest {
        // StateFlow is conflated, so observe the state at each step of the upload.
        val seen = mutableListOf<AttachmentSendState>()
        lateinit var sender: ChatAttachmentSender
        val attachments = FakeAttachments { id ->
            emit(AttachmentProgress.Uploading(1, 4)); seen.add(sender.state.value)
            emit(AttachmentProgress.Uploading(4, 4)); seen.add(sender.state.value)
            emit(AttachmentProgress.Sent(message(id)))
        }
        sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(PHOTO) { seen.add(sender.state.value); photo }
        val sent = sender.state.value as AttachmentSendState.Sent
        assertEquals("photo", sent.message.attachmentKind)
        assertEquals(listOf(AttachmentSendState.Preparing(PHOTO), AttachmentSendState.Uploading(PHOTO, 0.25f),
            AttachmentSendState.Uploading(PHOTO, 1f)), seen)
        assertArrayEquals(photo.bytes, attachments.uploads.single().third)
        sender.dismiss()
        assertEquals(AttachmentSendState.Idle, sender.state.value)
    }

    @Test fun aVideoShowsCompressionProgressThenUploadsTheCompressedMp4AsAVideo() = runTest {
        val seen = mutableListOf<AttachmentSendState>()
        lateinit var sender: ChatAttachmentSender
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id, "video"))) }
        sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(VIDEO) { progress ->
            seen.add(sender.state.value)
            progress(0.5f); seen.add(sender.state.value)
            video
        }
        assertEquals(listOf(AttachmentSendState.Preparing(VIDEO), AttachmentSendState.Preparing(VIDEO, 0.5f)), seen)
        val (kind, mime, bytes) = attachments.uploads.single()
        assertEquals(VIDEO, kind)
        assertEquals("video/mp4", mime)
        assertArrayEquals(video.bytes, bytes)
        assertEquals("video", (sender.state.value as AttachmentSendState.Sent).message.attachmentKind)
    }

    @Test fun aTooLongVideoIsExplainedWithoutUploadingAnything() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(VIDEO) { throw ChatAttachmentException(AttachmentFailure.TOO_LONG) }
        assertEquals(AttachmentSendState.Failed(VIDEO, AttachmentFailure.TOO_LONG, canRetry = false), sender.state.value)
        assertFalse(sender.retryable)
        assertTrue(attachments.requests.isEmpty())
    }

    @Test fun anUnexpectedCompressionErrorIsNeverReplacedByTheOriginal() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(VIDEO) { throw IllegalStateException("encoder failed") }
        assertEquals(AttachmentSendState.Failed(VIDEO, AttachmentFailure.UNSUPPORTED, canRetry = false), sender.state.value)
        assertTrue(attachments.requests.isEmpty())
    }

    @Test fun oneAttachmentAtATimeSoAPhotoCannotInterruptAVideo() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(VIDEO) { awaitCancellation() }
        sender.pick(PHOTO) { photo }
        assertEquals(AttachmentSendState.Preparing(VIDEO), sender.state.value)
        assertTrue(attachments.requests.isEmpty())
    }

    @Test fun retryAfterAnUncertainFailureReusesTheSameRequestSoNoDuplicateIsCreated() = runTest {
        var attempt = 0
        val attachments = FakeAttachments { id ->
            if (attempt++ == 0) throw java.io.IOException("connection lost")
            emit(AttachmentProgress.Sent(message(id)))
        }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(VIDEO) { video }
        val failed = sender.state.value as AttachmentSendState.Failed
        assertNull(failed.failure)
        assertTrue(failed.canRetry)
        sender.retry()
        assertTrue(sender.state.value is AttachmentSendState.Sent)
        assertEquals(2, attachments.requests.size)
        assertEquals(attachments.requests[0], attachments.requests[1])
        assertSame(attachments.uploads[0].third, attachments.uploads[1].third)
    }

    @Test fun aNewAttachmentGetsANewRequest() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(PHOTO) { photo }; sender.dismiss()
        sender.pick(PHOTO) { photo }
        assertNotEquals(attachments.requests[0], attachments.requests[1])
    }

    @Test fun cancellingDuringUploadStopsItAndNothingIsSent() = runTest {
        val started = CompletableDeferred<Unit>()
        val attachments = FakeAttachments { emit(AttachmentProgress.Uploading(0, 3)); started.complete(Unit); awaitCancellation() }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(VIDEO) { video }
        started.await()
        assertTrue(sender.state.value is AttachmentSendState.Uploading)
        sender.cancel()
        assertEquals(AttachmentSendState.Idle, sender.state.value)
        assertFalse(sender.retryable)
    }

    @Test fun cancellingDuringCompressionNeverStartsTheUpload() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(VIDEO) { progress -> progress(0.3f); awaitCancellation() }
        assertEquals(AttachmentSendState.Preparing(VIDEO, 0.3f), sender.state.value)
        sender.cancel()
        assertEquals(AttachmentSendState.Idle, sender.state.value)
        assertTrue(attachments.requests.isEmpty())
    }

    @Test fun anIncompatibleOrOversizedPhotoIsExplainedWithoutUploadingAnything() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(PHOTO) { throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED) }
        assertEquals(AttachmentSendState.Failed(PHOTO, AttachmentFailure.UNSUPPORTED, canRetry = false), sender.state.value)
        sender.pick(VIDEO) { throw ChatAttachmentException(AttachmentFailure.TOO_LARGE) }
        assertEquals(AttachmentSendState.Failed(VIDEO, AttachmentFailure.TOO_LARGE, canRetry = false), sender.state.value)
        assertTrue(attachments.requests.isEmpty())
    }

    @Test fun aQuotaRefusalCanBeRetriedLaterButAPermissionRefusalCannot() = runTest {
        var failure = AttachmentFailure.QUOTA
        val attachments = FakeAttachments { throw ChatAttachmentException(failure) }
        val sender = ChatAttachmentSender(attachments, "conversation")
        sender.pick(PHOTO) { photo }
        assertEquals(AttachmentSendState.Failed(PHOTO, AttachmentFailure.QUOTA, canRetry = true), sender.state.value)
        failure = AttachmentFailure.ACCESS
        sender.retry()
        assertEquals(AttachmentSendState.Failed(PHOTO, AttachmentFailure.ACCESS, canRetry = false), sender.state.value)
    }
}
