package es.teloarreglo.catalogue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public fields of the owner's profile (#30). Name, description, trades, coverage, languages, phone
 * and business address are presented for review. Services and reference prices are published
 * directly with [ProfessionalProfiles.saveOffer] (David, 2026-11-23); the server replaces them in a
 * presented version. Photos travel back as received; the private address is never part of it.
 */
@Serializable
data class ProfileContent(
    val name: String = "",
    val description: String = "",
    val trades: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val coverage: List<Coverage> = emptyList(),
    val languages: List<String> = listOf("es"),
    val phone: String = "",
    val images: List<String> = emptyList(),
    val prices: List<ReferencePrice> = emptyList(),
    /** Published only if the owner chooses to; a home-based professional can leave it empty. */
    @SerialName("business_address") val businessAddress: String? = null,
)

enum class ProfileField { NAME, TRADES, COVERAGE, LANGUAGES, PHONE }

private val PHONE = Regex("^\\+?[0-9][0-9 ]{5,19}$")

/** Required fields still empty or malformed; the server checks the same and has the last word. */
fun ProfileContent.missingFields(): Set<ProfileField> = buildSet {
    if (name.isBlank()) add(ProfileField.NAME)
    if (trades.isEmpty()) add(ProfileField.TRADES)
    if (coverage.isEmpty()) add(ProfileField.COVERAGE)
    if (languages.isEmpty()) add(ProfileField.LANGUAGES)
    if (!PHONE.matches(phone.trim())) add(ProfileField.PHONE)
}

/** Owner's own profile: what visitors see now, what to edit next and the latest review. */
@Serializable
data class OwnProfile(
    @SerialName("profile_id") val profileId: String,
    @SerialName("private_address") val privateAddress: String? = null,
    /** Last approved version; null while nothing has been approved. */
    val public: ProfileContent? = null,
    /** Latest presented version (to correct or keep changing), else the approved one. */
    val editable: ProfileContent,
    val review: ProfileReview,
)

enum class ProfileEditFailure { INVALID, CONFLICT, NO_PROFILE, ACCESS }
class ProfileEditException(val failure: ProfileEditFailure, cause: Exception) : Exception(failure.name, cause)

/**
 * Only the owner presents and changes their profile; the server derives the profile from the
 * session. A refusal is a [ProfileEditException]; anything else (network, unavailable) is rethrown
 * as is, so the caller retries with the same request id instead of assuming nothing was sent.
 */
interface ProfessionalProfiles {
    suspend fun mine(): OwnProfile?
    suspend fun start(): OwnProfile
    suspend fun submit(requestId: String, content: ProfileContent): ProfileReview
    suspend fun savePrivateAddress(address: String?): OwnProfile
    /** Publishes services and reference prices at once, without review. Existing appointments keep their amounts. */
    suspend fun saveOffer(services: List<String>, prices: List<ReferencePrice>): OwnProfile
}

private val contentJson = Json { encodeDefaults = true }

class SupabaseProfessionalProfiles(private val client: SupabaseClient) : ProfessionalProfiles {
    override suspend fun mine(): OwnProfile? = mapped {
        val result = client.postgrest.rpc("my_professional_profile")
        if (result.data.trim() == "null") null else result.decodeAs<OwnProfile>()
    }

    override suspend fun start(): OwnProfile = mapped {
        client.postgrest.rpc("start_professional_profile").decodeAs<OwnProfile>()
    }

    override suspend fun submit(requestId: String, content: ProfileContent): ProfileReview = mapped {
        client.postgrest.rpc("submit_professional_profile", buildJsonObject {
            put("p_submission", requestId)
            put("p_content", contentJson.encodeToJsonElement(ProfileContent.serializer(), content))
        }).decodeAs<ProfileReview>()
    }

    override suspend fun savePrivateAddress(address: String?): OwnProfile = mapped {
        client.postgrest.rpc("save_professional_private_address", buildJsonObject { put("p_address", address) })
            .decodeAs<OwnProfile>()
    }

    override suspend fun saveOffer(services: List<String>, prices: List<ReferencePrice>): OwnProfile = mapped {
        client.postgrest.rpc("save_professional_offer", buildJsonObject {
            put("p_services", contentJson.encodeToJsonElement(ListSerializer(String.serializer()), services))
            put("p_prices", contentJson.encodeToJsonElement(ListSerializer(ReferencePrice.serializer()), prices))
        }).decodeAs<OwnProfile>()
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val message = error.message.orEmpty()
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> ProfileEditFailure.ACCESS
            "invalid_profile_content" in message || "invalid_profile_offer" in message ||
                "invalid_private_address" in message -> ProfileEditFailure.INVALID
            "request_conflict" in message -> ProfileEditFailure.CONFLICT
            "profile_not_found" in message -> ProfileEditFailure.NO_PROFILE
            else -> null
        }
        throw if (failure != null) ProfileEditException(failure, error) else error
    }
}
