package es.teloarreglo.catalogue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes

@Serializable
enum class CoverStatus {
    @SerialName("uploading") UPLOADING,
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
}

@Serializable
data class CoverRequest(
    val id: String,
    val status: CoverStatus,
    @SerialName("object_path") val objectPath: String? = null,
    val reason: String? = null,
)

/** Owner-only editing; signed reads are authorized by Storage, never by a hidden button. */
interface ProfessionalCovers {
    suspend fun state(profileId: String): CoverRequest?
    suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray): CoverRequest
    suspend fun generic(profileId: String, requestId: String): CoverRequest
    suspend fun imageUrl(path: String): String
}

class SupabaseProfessionalCovers(private val client: SupabaseClient) : ProfessionalCovers {
    override suspend fun state(profileId: String): CoverRequest? {
        val result = client.postgrest.rpc("professional_cover", buildJsonObject { put("p_profile", profileId) })
        // decodeAs is bounded by Any, so a nullable return type cannot make its
        // inferred serializer nullable. SQL JSON null means no submission yet.
        return if (result.data.trim() == "null") null else result.decodeAs<CoverRequest>()
    }

    override suspend fun generic(profileId: String, requestId: String): CoverRequest =
        client.postgrest.rpc("request_professional_cover", buildJsonObject {
            put("p_profile", profileId); put("p_request", requestId)
        }).decodeAs()

    override suspend fun photo(profileId: String, requestId: String, jpeg: ByteArray): CoverRequest {
        require(jpeg.isNotEmpty() && jpeg.size <= 2 * 1024 * 1024)
        val digest = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
        val request = client.postgrest.rpc("request_professional_cover", buildJsonObject {
            put("p_profile", profileId); put("p_request", requestId); put("p_sha256", digest)
        }).decodeAs<CoverRequest>()
        if (request.status != CoverStatus.UPLOADING) return request
        try {
            client.storage.from("professional-covers").upload(checkNotNull(request.objectPath), jpeg) {
                contentType = ContentType.Image.JPEG
                upsert = false
            }
        } catch (error: RestException) {
            // A response may have been lost after upload. Never overwrite; the
            // idempotent submission RPC verifies the immutable object exists.
            if (error.statusCode !in listOf(400, 409)) throw error
        }
        return client.postgrest.rpc("submit_professional_cover", buildJsonObject {
            put("p_request", requestId)
        }).decodeAs()
    }

    override suspend fun imageUrl(path: String): String =
        client.storage.from("professional-covers").createSignedUrl(path, 15.minutes)
}
