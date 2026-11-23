package es.teloarreglo.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.UploadStatus
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.uploadAsFlow
import io.ktor.http.ContentType
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes

/** Each kind is enabled server-side in `private.chat_attachment_limit`. */
enum class AttachmentKind(val wire: String) { PHOTO("photo"), VIDEO("video") }

/**
 * Definitive refusals. Anything else (network, 5xx) is uncertain: retry with the same request id.
 * TOO_LONG is only raised on the device (video over one minute), never by the server.
 */
enum class AttachmentFailure { ACCESS, TOO_LARGE, TOO_LONG, UNSUPPORTED, QUOTA, CONFLICT }

/** A new compressed copy ready to send; the picked original is only read, never written. */
interface PreparedAttachment {
    val kind: AttachmentKind
    val mime: String
    val bytes: ByteArray
}
class ChatAttachmentException(val failure: AttachmentFailure, cause: Exception? = null) : Exception(failure.name, cause)

sealed interface AttachmentProgress {
    data class Uploading(val sentBytes: Long, val totalBytes: Long) : AttachmentProgress
    data class Sent(val message: ChatMessage) : AttachmentProgress
}

/**
 * Private chat attachments. The returned flow is cold: cancelling its collector
 * cancels the upload, and a message exists only once [AttachmentProgress.Sent]
 * is emitted, so a failed or cancelled upload is never sent silently.
 */
interface ChatAttachments {
    fun send(conversationId: String, requestId: String, kind: AttachmentKind, mime: String, bytes: ByteArray):
        Flow<AttachmentProgress>
    suspend fun url(path: String): String
}

@Serializable
private data class Reservation(
    @SerialName("object_path") val objectPath: String,
    val status: String,
    val message: ChatMessage? = null,
)

internal fun attachmentFailure(error: RestException): AttachmentFailure? {
    val text = (error.error + " " + error.description.orEmpty()).lowercase()
    return when {
        "quota" in text -> AttachmentFailure.QUOTA
        "too large" in text || "too_large" in text || "exceeded the maximum" in text -> AttachmentFailure.TOO_LARGE
        "mime type" in text || "unsupported_attachment" in text || "invalid_mime" in text -> AttachmentFailure.UNSUPPORTED
        "retry_conflict" in text -> AttachmentFailure.CONFLICT
        error.statusCode == 401 || error.statusCode == 403 || "row-level security" in text ||
            "permission_denied" in text || "authentication_required" in text || "unauthorized" in text ->
            AttachmentFailure.ACCESS
        else -> null
    }
}

private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
    throw attachmentFailure(error)?.let { ChatAttachmentException(it, error) } ?: error
}

class SupabaseChatAttachments(private val client: SupabaseClient) : ChatAttachments {
    private val bucket get() = client.storage.from(BUCKET)

    override fun send(conversationId: String, requestId: String, kind: AttachmentKind, mime: String, bytes: ByteArray) =
        flow {
            require(bytes.isNotEmpty())
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val reservation = mapped {
                client.postgrest.rpc("request_chat_attachment", buildJsonObject {
                    put("p_conversation_id", conversationId); put("p_request_id", requestId)
                    put("p_kind", kind.wire); put("p_mime", mime); put("p_sha256", digest)
                }).decodeAs<Reservation>()
            }
            reservation.message?.let { emit(AttachmentProgress.Sent(it)); return@flow }
            val total = bytes.size.toLong()
            emit(AttachmentProgress.Uploading(0, total))
            try {
                bucket.uploadAsFlow(reservation.objectPath, bytes) {
                    contentType = ContentType.parse(mime)
                    upsert = false
                }.collect { if (it is UploadStatus.Progress) emit(AttachmentProgress.Uploading(it.totalBytesSend, total)) }
            } catch (error: RestException) {
                // A lost response after a stored upload: never overwrite; the
                // idempotent confirmation checks the immutable object instead.
                val text = (error.error + " " + error.description.orEmpty()).lowercase()
                if ("already exists" !in text && "duplicate" !in text) {
                    throw attachmentFailure(error)?.let { ChatAttachmentException(it, error) } ?: error
                }
            }
            val message = mapped {
                client.postgrest.rpc("submit_chat_attachment", buildJsonObject {
                    put("p_conversation_id", conversationId); put("p_request_id", requestId)
                }).decodeAs<ChatMessage>()
            }
            emit(AttachmentProgress.Sent(message))
        }

    override suspend fun url(path: String): String = bucket.createSignedUrl(path, 10.minutes)

    companion object { const val BUCKET = "chat-attachments" }
}
