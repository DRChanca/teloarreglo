package es.teloarreglo.appointments

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Reviews of performed appointments (#28). Only the client publishes, once per appointment, and never automatically:
 * the server decides whether the service is recorded as performed (confirmation, silent close, a quality complaint that
 * acknowledges it, or the administrative decision on performance), apart from what happened to the money. The public
 * part is stars, comment and date: never the appointment, the author, the address or the chat.
 */
@Serializable enum class ReviewState {
    @SerialName("enabled") ENABLED,
    @SerialName("published") PUBLISHED,
    /** Not started, cancelled, or only started: nothing performed yet. */
    @SerialName("not_done") NOT_DONE,
    /** Marked done: the client confirms it (or acknowledges it in a quality complaint) first. */
    @SerialName("awaiting_confirmation") AWAITING_CONFIRMATION,
    /** Whether the service happened is disputed and waits for the administrative decision. */
    @SerialName("performance_disputed") PERFORMANCE_DISPUTED,
    /** Administration recorded that the service did not happen. */
    @SerialName("not_performed") NOT_PERFORMED,
}

/** A published review. [appointmentId] is present only for the participants of that appointment. */
@Serializable
data class Review(
    val id: String,
    @SerialName("profile_id") val profileId: String,
    val stars: Int,
    val comment: String,
    @SerialName("created_at") @Serializable(ServerInstant::class) val createdAt: Instant,
    @SerialName("appointment_id") val appointmentId: String? = null,
)

@Serializable
data class AppointmentReviewState(
    @SerialName("appointment_id") val appointmentId: String,
    val state: ReviewState,
    val review: Review? = null,
) {
    val canPublish get() = state == ReviewState.ENABLED
}

/** NOT_ENABLED: not performed yet or still awaiting the client's confirmation. */
enum class ReviewFailure { ACCESS, INVALID, RETRY_CONFLICT, ALREADY_REVIEWED, NOT_ENABLED, PERFORMANCE_DISPUTED, NOT_PERFORMED }
class ReviewException(val failure: ReviewFailure, cause: Exception) : Exception(failure.name, cause)

interface Reviews {
    /** Per appointment of the conversation this account takes part in. */
    suspend fun states(conversationId: String): Map<String, AppointmentReviewState>
    /**
     * Sent only after the notice about private data in public content was accepted. Reuse [requestId] after an
     * uncertain result: the server returns the first review and never publishes a second one.
     */
    suspend fun publish(appointmentId: String, requestId: String, stars: Int, comment: String): Review
    /** Public reviews of a profile, newest first; readable without a session. */
    suspend fun forProfile(profileId: String): List<Review>
}

class SupabaseReviews(private val client: SupabaseClient) : Reviews {
    override suspend fun states(conversationId: String) = mapped {
        client.postgrest.rpc("review_states", buildJsonObject { put("p_conversation_id", conversationId) })
            .decodeList<AppointmentReviewState>().associateBy { it.appointmentId }
    }

    override suspend fun publish(appointmentId: String, requestId: String, stars: Int, comment: String) = mapped {
        client.postgrest.rpc("publish_review", buildJsonObject {
            put("p_appointment_id", appointmentId)
            put("p_request_id", requestId)
            put("p_stars", stars)
            put("p_comment", comment)
            put("p_privacy_acknowledged", true)
        }).decodeAs<Review>()
    }

    override suspend fun forProfile(profileId: String) = mapped {
        client.postgrest["reviews"].select {
            filter { eq("profile_id", profileId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Review>()
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val message = error.message.orEmpty()
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> ReviewFailure.ACCESS
            "review_exists" in message -> ReviewFailure.ALREADY_REVIEWED
            "performance_disputed" in message -> ReviewFailure.PERFORMANCE_DISPUTED
            "not_performed" in message -> ReviewFailure.NOT_PERFORMED
            "awaiting_confirmation" in message || "not_done" in message -> ReviewFailure.NOT_ENABLED
            "retry_conflict" in message -> ReviewFailure.RETRY_CONFLICT
            "invalid_review" in message || "privacy_not_acknowledged" in message -> ReviewFailure.INVALID
            else -> null
        }
        throw if (failure != null) ReviewException(failure, error) else error
    }
}
