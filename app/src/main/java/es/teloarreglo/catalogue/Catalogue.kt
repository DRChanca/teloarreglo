package es.teloarreglo.catalogue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Coverage(val code: String, val name: String)

@Serializable
data class ReferencePrice(
    val service: String,
    val scope: String,
    val unit: String,
    @SerialName("amount_cents") val amountCents: Int,
    val includes: String = "",
    val excludes: String = "",
)

@Serializable
data class PublicProfile(
    @SerialName("profile_id") val id: String,
    val name: String,
    val description: String,
    val trades: List<String>,
    val services: List<String>,
    val coverage: List<Coverage>,
    val languages: List<String>,
    val phone: String,
    val images: List<String>,
    val prices: List<ReferencePrice>,
    val rating: Double? = null,
    @SerialName("review_count") val reviewCount: Int = 0,
    @SerialName("business_address") val businessAddress: String? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    /** Point of the approved commercial address (#17); null when there is none. */
    @SerialName("business_lat") val businessLat: Double? = null,
    @SerialName("business_lng") val businessLng: Double? = null,
)

/** Read-only approved public snapshot. Missing/unpublished details return null. */
interface Catalogue {
    suspend fun list(trade: String? = null): List<PublicProfile>
    suspend fun profile(id: String): PublicProfile?
    /** «Destacado» badge (#33): an approved profile with an active simulated subscription. */
    suspend fun featured(id: String): Boolean = false
}

class SupabaseCatalogue(private val client: SupabaseClient) : Catalogue {
    override suspend fun list(trade: String?): List<PublicProfile> =
        client.postgrest.rpc("catalogue", buildJsonObject {
            if (trade != null) put("p_trade", trade)
        }).decodeList()

    override suspend fun profile(id: String): PublicProfile? =
        client.postgrest.rpc("catalogue", buildJsonObject { put("p_id", id) })
            .decodeList<PublicProfile>().singleOrNull()

    override suspend fun featured(id: String): Boolean =
        client.postgrest.rpc("is_featured", buildJsonObject { put("p_profile_id", id) }).decodeAs<Boolean>()
}
