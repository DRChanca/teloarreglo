package es.teloarreglo.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface AttachmentSendState {
    data object Idle : AttachmentSendState
    /** Compressing on the device; [fraction] is null when the step cannot report progress. */
    data class Preparing(val kind: AttachmentKind, val fraction: Float? = null) : AttachmentSendState
    data class Uploading(val kind: AttachmentKind, val fraction: Float) : AttachmentSendState
    /** [failure] null means uncertain delivery (network): retrying reuses the same request. */
    data class Failed(val kind: AttachmentKind, val failure: AttachmentFailure?, val canRetry: Boolean) : AttachmentSendState
    data class Sent(val message: ChatMessage) : AttachmentSendState
}

/**
 * One photo or video send at a time per conversation (#18/#19). Activity-scoped so
 * rotation or a short trip out of the chat does not lose progress; the compressed
 * copy and its request id are kept until confirmed or cancelled, so a retry never
 * creates a duplicate. A preparation error is never replaced by the original file.
 */
class ChatAttachmentSender(private val attachments: ChatAttachments, private val conversationId: String) : ViewModel() {
    private val mutableState = MutableStateFlow<AttachmentSendState>(AttachmentSendState.Idle)
    val state: StateFlow<AttachmentSendState> = mutableState
    private var job: Job? = null
    private var pending: Pair<String, PreparedAttachment>? = null
    val retryable get() = pending != null && (mutableState.value as? AttachmentSendState.Failed)?.canRetry == true
    val busy get() = mutableState.value is AttachmentSendState.Preparing || mutableState.value is AttachmentSendState.Uploading

    /** [prepare] compresses the picked file and may report its progress (0..1). */
    fun pick(kind: AttachmentKind, prepare: suspend (onProgress: (Float) -> Unit) -> PreparedAttachment) {
        if (busy) return
        pending = null
        job = viewModelScope.launch {
            mutableState.value = AttachmentSendState.Preparing(kind)
            val prepared = try {
                prepare { fraction -> mutableState.value = AttachmentSendState.Preparing(kind, fraction.coerceIn(0f, 1f)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = AttachmentSendState.Failed(
                    kind, (error as? ChatAttachmentException)?.failure ?: AttachmentFailure.UNSUPPORTED, canRetry = false)
                return@launch
            }
            pending = UUID.randomUUID().toString() to prepared
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
        mutableState.value = AttachmentSendState.Idle
    }

    fun dismiss() {
        if (busy) return
        pending = null
        mutableState.value = AttachmentSendState.Idle
    }

    private suspend fun upload() {
        val (requestId, prepared) = pending ?: return
        val kind = prepared.kind
        mutableState.value = AttachmentSendState.Uploading(kind, 0f)
        try {
            attachments.send(conversationId, requestId, kind, prepared.mime, prepared.bytes).collect { event ->
                mutableState.value = when (event) {
                    is AttachmentProgress.Uploading -> AttachmentSendState.Uploading(kind,
                        if (event.totalBytes > 0) (event.sentBytes.toFloat() / event.totalBytes).coerceIn(0f, 1f) else 0f)
                    is AttachmentProgress.Sent -> { pending = null; AttachmentSendState.Sent(event.message) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val failure = (error as? ChatAttachmentException)?.failure
            mutableState.value = AttachmentSendState.Failed(kind, failure,
                canRetry = failure == null || failure == AttachmentFailure.QUOTA)
        }
    }
}
