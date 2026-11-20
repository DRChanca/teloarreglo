package es.teloarreglo.chat

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
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
)

@Serializable
data class ChatMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("request_id") val requestId: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

/** All returned messages are server-confirmed. Reuse requestId after an uncertain result. */
interface Chat {
    suspend fun open(profileId: String): Conversation
    suspend fun conversations(): List<Conversation>
    suspend fun messages(conversationId: String): List<ChatMessage>
    suspend fun send(conversationId: String, requestId: String, body: String): ChatMessage
}

class SupabaseChat(private val client: SupabaseClient) : Chat {
    override suspend fun open(profileId: String): Conversation =
        client.postgrest.rpc("open_conversation", buildJsonObject {
            put("p_profile_id", profileId)
        }).decodeAs()

    override suspend fun conversations(): List<Conversation> =
        client.postgrest["conversations"].select { order("created_at", Order.DESCENDING) }.decodeList()

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

    override suspend fun send(conversationId: String, requestId: String, body: String): ChatMessage =
        client.postgrest.rpc("send_text", buildJsonObject {
            put("p_conversation_id", conversationId)
            put("p_request_id", requestId)
            put("p_body", body)
        }).decodeAs()
}
