package es.teloarreglo.catalogue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReviewStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
}

/** Latest administrative decision on the owner's profile, as recorded by the server. */
@Serializable
data class ProfileReview(
    @SerialName("profile_id") val profileId: String,
    /** null: no submission yet (e.g. a prepared, already approved profile). */
    val status: ReviewStatus? = null,
    val reason: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    /** Whether an approved version is public, independently of a pending or rejected change. */
    val published: Boolean = false,
)

/** What the owner must understand: is anything public, and is a decision pending or negative. */
enum class ReviewSummary { NOT_SUBMITTED, PENDING_FIRST, PENDING_CHANGE, PUBLISHED, REJECTED_FIRST, REJECTED_CHANGE }

fun ProfileReview.summary(): ReviewSummary = when (status) {
    ReviewStatus.PENDING -> if (published) ReviewSummary.PENDING_CHANGE else ReviewSummary.PENDING_FIRST
    ReviewStatus.REJECTED -> if (published) ReviewSummary.REJECTED_CHANGE else ReviewSummary.REJECTED_FIRST
    ReviewStatus.APPROVED, null -> if (published) ReviewSummary.PUBLISHED else ReviewSummary.NOT_SUBMITTED
}

/** Owner-only read of the review result decided in the private panel (#29). */
interface ProfileReviews {
    /** null when the account has no professional profile; failures propagate. */
    suspend fun mine(): ProfileReview?
}

class SupabaseProfileReviews(private val client: SupabaseClient) : ProfileReviews {
    override suspend fun mine(): ProfileReview? {
        val result = client.postgrest.rpc("my_profile_review")
        return if (result.data.trim() == "null") null else result.decodeAs<ProfileReview>()
    }
}
