package es.teloarreglo.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface PhotoSendState {
    data object Idle : PhotoSendState
    data object Preparing : PhotoSendState
    data class Uploading(val fraction: Float) : PhotoSendState
    /** [failure] null means uncertain delivery (network): retrying reuses the same request. */
    data class Failed(val failure: AttachmentFailure?, val canRetry: Boolean) : PhotoSendState
    data class Sent(val message: ChatMessage) : PhotoSendState
}

/**
 * One photo send per conversation. Activity-scoped so rotation or a short trip
 * out of the chat does not lose progress; the compressed copy and its request id
 * are kept until confirmed or cancelled, so a retry never creates a duplicate.
 */
class ChatPhotoSender(private val attachments: ChatAttachments, private val conversationId: String) : ViewModel() {
    private val mutableState = MutableStateFlow<PhotoSendState>(PhotoSendState.Idle)
    val state: StateFlow<PhotoSendState> = mutableState
    private var job: Job? = null
    private var pending: Pair<String, PreparedPhoto>? = null
    val retryable get() = pending != null && (mutableState.value as? PhotoSendState.Failed)?.canRetry == true
    val busy get() = mutableState.value is PhotoSendState.Preparing || mutableState.value is PhotoSendState.Uploading

    fun pick(prepare: suspend () -> PreparedPhoto) {
        if (busy) return
        pending = null
        job = viewModelScope.launch {
            mutableState.value = PhotoSendState.Preparing
            val photo = try { prepare() } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = PhotoSendState.Failed(
                    (error as? ChatAttachmentException)?.failure ?: AttachmentFailure.UNSUPPORTED, canRetry = false)
                return@launch
            }
            pending = UUID.randomUUID().toString() to photo
            upload()
        }
    }

    fun retry() {
        if (!retryable) return
        job = viewModelScope.launch { upload() }
    }

    fun cancel() {
        job?.cancel()
        job = null
        pending = null
        mutableState.value = PhotoSendState.Idle
    }

    fun dismiss() {
        if (busy) return
        pending = null
        mutableState.value = PhotoSendState.Idle
    }

    private suspend fun upload() {
        val (requestId, photo) = pending ?: return
        mutableState.value = PhotoSendState.Uploading(0f)
        try {
            attachments.send(conversationId, requestId, AttachmentKind.PHOTO, photo.mime, photo.bytes).collect { event ->
                mutableState.value = when (event) {
                    is AttachmentProgress.Uploading -> PhotoSendState.Uploading(
                        if (event.totalBytes > 0) (event.sentBytes.toFloat() / event.totalBytes).coerceIn(0f, 1f) else 0f)
                    is AttachmentProgress.Sent -> { pending = null; PhotoSendState.Sent(event.message) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val failure = (error as? ChatAttachmentException)?.failure
            mutableState.value = PhotoSendState.Failed(failure, canRetry = failure == null || failure == AttachmentFailure.QUOTA)
        }
    }
}
