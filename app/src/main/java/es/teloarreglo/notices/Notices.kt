package es.teloarreglo.notices

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * A persisted notice (#32): the server writes one for every confirmed message, appointment event or administrative
 * decision. It is the source of truth; push only announces it. Payloads carry identifiers and kinds, never message
 * bodies or addresses. Messages of one conversation arrive grouped, with [count] unread ones.
 */
@Serializable
data class Notice(
    val id: String,
    val kind: String,
    val payload: JsonObject,
    @SerialName("created_at") val createdAt: String,
    val read: Boolean = false,
    val count: Int = 1,
) {
    val conversationId: String? get() = payload["conversation_id"]?.jsonPrimitive?.contentOrNull
    val event: String? get() = (payload["event"] ?: payload["status"])?.jsonPrimitive?.contentOrNull
}

/** What a notice is about, independent of language. Unknown future events keep a generic topic. */
enum class NoticeTopic {
    MESSAGE, REQUEST, PROPOSAL, REJECTED, CONFIRMED, CHANGE, CHANGE_DECIDED, PROFESSIONAL_NEAR, STARTED, CANCELLED, DONE,
    REMINDER, COMPLETED, INCIDENT, REVIEW, APPOINTMENT_OTHER, PROFILE_APPROVED, PROFILE_REJECTED, OTHER,
}

fun topicOf(kind: String, event: String?): NoticeTopic = when (kind) {
    "message" -> NoticeTopic.MESSAGE
    "profile_review" -> if (event == "approved") NoticeTopic.PROFILE_APPROVED else NoticeTopic.PROFILE_REJECTED
    "appointment" -> when {
        event == "requested" -> NoticeTopic.REQUEST
        event == "proposed" -> NoticeTopic.PROPOSAL
        event == "rejected" -> NoticeTopic.REJECTED
        event == "accepted" -> NoticeTopic.CONFIRMED
        event == "change_proposed" || event == "address_correction_requested" -> NoticeTopic.CHANGE
        event == "change_accepted" || event == "change_rejected" -> NoticeTopic.CHANGE_DECIDED
        event == "professional_near" -> NoticeTopic.PROFESSIONAL_NEAR
        event == "started" -> NoticeTopic.STARTED
        event == "cancelled" -> NoticeTopic.CANCELLED
        event == "done" -> NoticeTopic.DONE
        event == "reminder" -> NoticeTopic.REMINDER
        event == "completed" -> NoticeTopic.COMPLETED
        event?.startsWith("incident") == true -> NoticeTopic.INCIDENT
        event == "review_published" -> NoticeTopic.REVIEW
        else -> NoticeTopic.APPOINTMENT_OTHER
    }
    else -> NoticeTopic.OTHER
}

fun Notice.topic(): NoticeTopic = topicOf(kind, event)

/** Where tapping a notice leads (#47): its conversation, the account for a profile review, or nowhere. */
sealed interface NoticeDestination {
    data class Conversation(val id: String) : NoticeDestination
    data object Account : NoticeDestination
}

fun Notice.destination(): NoticeDestination? =
    conversationId?.let { NoticeDestination.Conversation(it) } ?: if (kind == "profile_review") NoticeDestination.Account else null

/** What came of opening a notice's conversation (#47): a missing conversation and an unreachable service read differently. */
enum class NoticeOpening { OPENED, NOT_FOUND, UNREACHABLE }

/**
 * Looks the notice's conversation up among the account's conversations and opens it. [load] returns null when there
 * is no chat service; a failure to load is a connection problem, not a missing conversation.
 */
suspend fun <T> openNoticeConversation(
    id: String, load: suspend () -> List<T>?, idOf: (T) -> String, open: (T) -> Unit,
): NoticeOpening {
    val conversations = try { load() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        ?: return NoticeOpening.UNREACHABLE
    val conversation = conversations.find { idOf(it) == id } ?: return NoticeOpening.NOT_FOUND
    open(conversation)
    return NoticeOpening.OPENED
}

/** Unread notices that lead nowhere: seeing them on the list is reading them. */
fun List<Notice>.readOnSight(): List<String> = filter { !it.read && it.destination() == null }.map { it.id }

interface Notices {
    suspend fun list(): List<Notice>
    suspend fun markRead(ids: List<String>)
}

/** This installation's push registration, owned by the signed-in account. */
interface PushDevices {
    suspend fun register(installationId: String, token: String, enabled: Boolean)
    suspend fun unregister(installationId: String)
}

class SupabaseNotices(private val client: SupabaseClient) : Notices, PushDevices {
    override suspend fun list(): List<Notice> = client.postgrest.rpc("my_notices").decodeList()

    override suspend fun markRead(ids: List<String>) {
        client.postgrest.rpc("mark_notices_read", buildJsonObject { putJsonArray("p_ids") { ids.forEach { add(it) } } })
    }

    override suspend fun register(installationId: String, token: String, enabled: Boolean) {
        client.postgrest.rpc("register_push_device", buildJsonObject {
            put("p_installation_id", installationId); put("p_token", token); put("p_enabled", enabled)
        })
    }

    override suspend fun unregister(installationId: String) {
        client.postgrest.rpc("unregister_push_device", buildJsonObject { put("p_installation_id", installationId) })
    }
}
