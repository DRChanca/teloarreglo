package es.teloarreglo.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.exceptions.RestException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Conversation(
    val id: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("professional_id") val professionalId: String,
    val name: String,
    // Inbox only (#39): the client's self-declared name, never the email, and the
    // caller's own unread count, both computed by the server.
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
)

/** The other participant: the client's name for the professional, the business for the client. */
fun Conversation.counterpartName(accountId: String): String? =
    if (professionalId == accountId) clientName else name

@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("request_id") val requestId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    // Private attachment (#18/#19); open it through ChatAttachments.url.
    @SerialName("attachment_kind") val attachmentKind: String? = null,
    @SerialName("attachment_path") val attachmentPath: String? = null,
    @SerialName("attachment_type") val attachmentType: String? = null,
    @SerialName("attachment_size") val attachmentSize: Long? = null,
)

enum class SendFailure { ACCESS, INVALID_TEXT, CONFLICT }
class ChatSendException(val failure: SendFailure, cause: Exception) : Exception(failure.name, cause)

/** All returned messages are server-confirmed. Reuse requestId after an uncertain result. */
interface Chat {
    suspend fun open(profileId: String): Conversation
    suspend fun conversations(): List<Conversation>
    suspend fun messages(conversationId: String): List<ChatMessage>
    suspend fun send(conversationId: String, requestId: String, body: String): ChatMessage
    /** Marks read up to the newest persisted message, only for the caller. */
    suspend fun markRead(conversationId: String) {}
}

class SupabaseChat(private val client: SupabaseClient) : Chat {
    override suspend fun open(profileId: String): Conversation =
        client.postgrest.rpc("open_conversation", buildJsonObject {
            put("p_profile_id", profileId)
        }).decodeAs()

    override suspend fun conversations(): List<Conversation> =
        client.postgrest.rpc("conversation_inbox").decodeAs()

    override suspend fun markRead(conversationId: String) {
        client.postgrest.rpc("mark_conversation_read", buildJsonObject {
            put("p_conversation_id", conversationId)
        })
    }

    // Explicit pages avoid PostgREST's row cap silently dropping older messages.
    override suspend fun messages(conversationId: String): List<ChatMessage> = buildList {
        var offset = 0L
        do {
            val page = client.postgrest["chat_messages"].select {
                filter { eq("conversation_id", conversationId) }
                order("created_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                range(offset, offset + 199)
            }.decodeList<ChatMessage>()
            addAll(page)
            offset += page.size
        } while (page.size == 200)
    }

    override suspend fun send(conversationId: String, requestId: String, body: String): ChatMessage = try {
        client.postgrest.rpc("send_text", buildJsonObject {
            put("p_conversation_id", conversationId)
            put("p_request_id", requestId)
            put("p_body", body)
        }).decodeAs()
    } catch (error: RestException) {
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> SendFailure.ACCESS
            error.statusCode == 400 && error.message.orEmpty().contains("invalid_message") -> SendFailure.INVALID_TEXT
            error.statusCode == 400 && error.message.orEmpty().contains("retry_conflict") -> SendFailure.CONFLICT
            else -> null
        }
        if (failure != null) throw ChatSendException(failure, error)
        throw error
    }
}
