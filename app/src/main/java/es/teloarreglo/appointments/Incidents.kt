package es.teloarreglo.appointments

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Incidents and private evidence (#26). An incident never moves money and never changes the appointment's stage: it
 * waits for administrative review (#31). Opened before the completion it blocks the delivery (the client's confirmation
 * and the clock); opened after the close it is a communication and reverts nothing. Evidence is a concrete chat message
 * or a note, contributed only after the visibility notice: both participants and the review see it, never the rest
 * of the chat.
 */
@Serializable enum class IncidentKind {
    /** Cancellation requested after the start: no automatic refund or delivery. */
    @SerialName("cancellation") CANCELLATION,
    /** The service happened but its quality is disputed. */
    @SerialName("quality") QUALITY,
    /** Dispute about whether the service happened at all. */
    @SerialName("not_performed") NOT_PERFORMED,
}

/** Whether the service is acknowledged or disputed; kept apart from the stage and the money. */
@Serializable enum class Performance { @SerialName("acknowledged") ACKNOWLEDGED, @SerialName("disputed") DISPUTED }
@Serializable enum class IncidentStatus { @SerialName("open") OPEN, @SerialName("resolved") RESOLVED }
@Serializable enum class EvidenceKind { @SerialName("message") MESSAGE, @SerialName("note") NOTE }

/** A piece of evidence, append-only. A message is the server's copy of what was contributed, never the whole chat. */
@Serializable
data class IncidentEvidence(
    val id: String,
    @SerialName("incident_id") val incidentId: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("request_id") val requestId: String,
    val kind: EvidenceKind,
    val note: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("message_author_id") val messageAuthorId: String? = null,
    @SerialName("message_body") val messageBody: String? = null,
    @SerialName("attachment_kind") val attachmentKind: String? = null,
    @SerialName("attachment_path") val attachmentPath: String? = null,
    @SerialName("created_at") @Serializable(ServerInstant::class) val createdAt: Instant,
)

/** The incident with the stage and the simulated balance as they were when it was opened. */
@Serializable
data class Incident(
    val id: String,
    @SerialName("appointment_id") val appointmentId: String,
    @SerialName("opened_by") val openedBy: String,
    @SerialName("request_id") val requestId: String,
    val kind: IncidentKind,
    val description: String,
    val performance: Performance,
    val status: IncidentStatus,
    @SerialName("appointment_stage") val appointmentStage: AppointmentStage,
    @SerialName("payment_mode") val paymentMode: PaymentMode? = null,
    @SerialName("pending_cents") val pendingCents: Int? = null,
    @SerialName("delivered_cents") val deliveredCents: Int? = null,
    /** True when opened before the completion: no delivery happens while it is open. */
    @SerialName("blocks_delivery") val blocksDelivery: Boolean,
    @SerialName("created_at") @Serializable(ServerInstant::class) val createdAt: Instant,
    @SerialName("incident_evidence") private val rawEvidence: List<IncidentEvidence> = emptyList(),
) {
    /** Oldest first; nothing is ever edited or removed. */
    val evidence get() = rawEvidence.sortedBy { it.createdAt }
    val isOpen get() = status == IncidentStatus.OPEN
}

sealed interface EvidenceDraft {
    /** A concrete message (text, photo or video) of this appointment's conversation. */
    data class Message(val messageId: String) : EvidenceDraft
    data class Note(val text: String) : EvidenceDraft
}

/** ALREADY_OPEN: the other participant opened one first; add evidence to it. CLOSED: no longer accepts evidence. */
enum class IncidentFailure { ACCESS, INVALID, STALE, ALREADY_OPEN, CLOSED, RETRY_CONFLICT }
class IncidentException(val failure: IncidentFailure, cause: Exception) : Exception(failure.name, cause)

/**
 * Both operations are sent only after the visibility notice was shown and accepted. Reuse the same requestId after an
 * uncertain result: the server returns the first result and never duplicates the incident or the evidence.
 */
interface Incidents {
    suspend fun open(appointmentId: String, requestId: String, kind: IncidentKind, description: String): Incident
    suspend fun addEvidence(incidentId: String, requestId: String, draft: EvidenceDraft): IncidentEvidence
}

class SupabaseIncidents(private val client: SupabaseClient) : Incidents {
    override suspend fun open(appointmentId: String, requestId: String, kind: IncidentKind, description: String) = mapped {
        client.postgrest.rpc("open_incident", buildJsonObject {
            put("p_appointment_id", appointmentId)
            put("p_request_id", requestId)
            put("p_kind", kind.wire)
            put("p_description", description)
            put("p_visibility_acknowledged", true)
        }).decodeAs<Incident>()
    }

    override suspend fun addEvidence(incidentId: String, requestId: String, draft: EvidenceDraft) = mapped {
        client.postgrest.rpc("add_incident_evidence", buildJsonObject {
            put("p_incident_id", incidentId)
            put("p_request_id", requestId)
            put("p_message_id", (draft as? EvidenceDraft.Message)?.messageId)
            put("p_note", (draft as? EvidenceDraft.Note)?.text)
            put("p_visibility_acknowledged", true)
        }).decodeAs<IncidentEvidence>()
    }

    private val IncidentKind.wire get() = when (this) {
        IncidentKind.CANCELLATION -> "cancellation"
        IncidentKind.QUALITY -> "quality"
        IncidentKind.NOT_PERFORMED -> "not_performed"
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val message = error.message.orEmpty()
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> IncidentFailure.ACCESS
            "incident_open" in message -> IncidentFailure.ALREADY_OPEN
            "incident_closed" in message -> IncidentFailure.CLOSED
            "incompatible_state" in message -> IncidentFailure.STALE
            "retry_conflict" in message -> IncidentFailure.RETRY_CONFLICT
            "invalid_incident" in message || "invalid_evidence" in message || "visibility_not_acknowledged" in message ->
                IncidentFailure.INVALID
            else -> null
        }
        throw if (failure != null) IncidentException(failure, error) else error
    }
}
