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
class ChatPhotoSenderTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val photo = PreparedPhoto(byteArrayOf(1, 2, 3), 1600, 1200)
    private fun message(requestId: String) = ChatMessage("m-$requestId", "conversation", "client", requestId, "",
        "2026-11-22T10:00:00Z", "photo", "conversation/$requestId.jpg", "image/jpeg", 3)

    private class FakeAttachments(val behaviour: suspend kotlinx.coroutines.flow.FlowCollector<AttachmentProgress>.(String) -> Unit)
        : ChatAttachments {
        val requests = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()
        override fun send(conversationId: String, requestId: String, kind: AttachmentKind, mime: String, bytes: ByteArray):
            Flow<AttachmentProgress> = flow {
            requests.add(requestId); uploads.add(bytes)
            behaviour(requestId)
        }
        override suspend fun url(path: String) = "https://signed/$path"
    }

    @Test fun progressIsShownAndTheConfirmedMessageIsHandedToTheChat() = runTest {
        // StateFlow is conflated, so observe the state at each step of the upload.
        val seen = mutableListOf<PhotoSendState>()
        lateinit var sender: ChatPhotoSender
        val attachments = FakeAttachments { id ->
            emit(AttachmentProgress.Uploading(1, 4)); seen.add(sender.state.value)
            emit(AttachmentProgress.Uploading(4, 4)); seen.add(sender.state.value)
            emit(AttachmentProgress.Sent(message(id)))
        }
        sender = ChatPhotoSender(attachments, "conversation")
        sender.pick { seen.add(sender.state.value); photo }
        val sent = sender.state.value as PhotoSendState.Sent
        assertEquals("photo", sent.message.attachmentKind)
        assertEquals(listOf(PhotoSendState.Preparing, PhotoSendState.Uploading(0.25f), PhotoSendState.Uploading(1f)), seen)
        assertArrayEquals(photo.bytes, attachments.uploads.single())
        sender.dismiss()
        assertEquals(PhotoSendState.Idle, sender.state.value)
    }

    @Test fun retryAfterAnUncertainFailureReusesTheSameRequestSoNoDuplicateIsCreated() = runTest {
        var attempt = 0
        val attachments = FakeAttachments { id ->
            if (attempt++ == 0) throw java.io.IOException("connection lost")
            emit(AttachmentProgress.Sent(message(id)))
        }
        val sender = ChatPhotoSender(attachments, "conversation")
        sender.pick { photo }
        val failed = sender.state.value as PhotoSendState.Failed
        assertNull(failed.failure)
        assertTrue(failed.canRetry)
        sender.retry()
        assertTrue(sender.state.value is PhotoSendState.Sent)
        assertEquals(2, attachments.requests.size)
        assertEquals(attachments.requests[0], attachments.requests[1])
    }

    @Test fun aNewPhotoGetsANewRequest() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatPhotoSender(attachments, "conversation")
        sender.pick { photo }; sender.dismiss()
        sender.pick { photo }
        assertNotEquals(attachments.requests[0], attachments.requests[1])
    }

    @Test fun cancellingDuringUploadStopsItAndNothingIsSent() = runTest {
        val started = CompletableDeferred<Unit>()
        val attachments = FakeAttachments { emit(AttachmentProgress.Uploading(0, 3)); started.complete(Unit); awaitCancellation() }
        val sender = ChatPhotoSender(attachments, "conversation")
        sender.pick { photo }
        started.await()
        assertTrue(sender.state.value is PhotoSendState.Uploading)
        sender.cancel()
        assertEquals(PhotoSendState.Idle, sender.state.value)
        assertFalse(sender.retryable)
    }

    @Test fun cancellingDuringCompressionNeverStartsTheUpload() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatPhotoSender(attachments, "conversation")
        sender.pick { awaitCancellation() }
        assertEquals(PhotoSendState.Preparing, sender.state.value)
        sender.cancel()
        assertEquals(PhotoSendState.Idle, sender.state.value)
        assertTrue(attachments.requests.isEmpty())
    }

    @Test fun anIncompatibleOrOversizedPhotoIsExplainedWithoutUploadingAnything() = runTest {
        val attachments = FakeAttachments { id -> emit(AttachmentProgress.Sent(message(id))) }
        val sender = ChatPhotoSender(attachments, "conversation")
        sender.pick { throw ChatAttachmentException(AttachmentFailure.UNSUPPORTED) }
        assertEquals(PhotoSendState.Failed(AttachmentFailure.UNSUPPORTED, canRetry = false), sender.state.value)
        sender.pick { throw ChatAttachmentException(AttachmentFailure.TOO_LARGE) }
        assertEquals(PhotoSendState.Failed(AttachmentFailure.TOO_LARGE, canRetry = false), sender.state.value)
        assertTrue(attachments.requests.isEmpty())
    }

    @Test fun aQuotaRefusalCanBeRetriedLaterButAPermissionRefusalCannot() = runTest {
        var failure = AttachmentFailure.QUOTA
        val attachments = FakeAttachments { throw ChatAttachmentException(failure) }
        val sender = ChatPhotoSender(attachments, "conversation")
        sender.pick { photo }
        assertEquals(PhotoSendState.Failed(AttachmentFailure.QUOTA, canRetry = true), sender.state.value)
        failure = AttachmentFailure.ACCESS
        sender.retry()
        assertEquals(PhotoSendState.Failed(AttachmentFailure.ACCESS, canRetry = false), sender.state.value)
    }
}
