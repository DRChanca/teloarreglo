package es.teloarreglo.subscription

import es.teloarreglo.catalogue.Coverage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Optional 5 €/month subscription as a simulated scene (#33). The server never charges and
 * stores no payment data; `featuredZones` is the approved coverage where a «Destacado» could
 * appear, so promotion never reaches an unreviewed profile or a concejo outside coverage.
 */
@Serializable
data class Subscription(
    @SerialName("profile_id") val profileId: String,
    @SerialName("price_cents") val priceCents: Int,
    val currency: String,
    val period: String,
    val simulated: Boolean,
    val charged: Boolean = false,
    @SerialName("featured_zones") val featuredZones: List<Coverage> = emptyList(),
)

/** ACCESS: session or role problem. UNCONFIRMED: the change may not have been saved. */
enum class SubscriptionFailure { ACCESS, UNCONFIRMED }
class SubscriptionException(val failure: SubscriptionFailure, cause: Exception) : Exception(failure.name, cause)

interface Subscriptions {
    /** null when the account has no professional profile. */
    suspend fun mine(): Subscription?
    /** Turns the simulation on or off. Idempotent; never charges. */
    suspend fun simulate(active: Boolean): Subscription
}

class SupabaseSubscriptions(private val client: SupabaseClient) : Subscriptions {
    override suspend fun mine(): Subscription? = mapped {
        val result = client.postgrest.rpc("my_subscription")
        if (result.data.trim() == "null") null else result.decodeAs<Subscription>()
    }

    override suspend fun simulate(active: Boolean): Subscription = mapped {
        client.postgrest.rpc("simulate_subscription", buildJsonObject { put("p_active", active) }).decodeAs<Subscription>()
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val failure = if (error.statusCode == 401 || error.statusCode == 403) SubscriptionFailure.ACCESS
            else SubscriptionFailure.UNCONFIRMED
        throw SubscriptionException(failure, error)
    }
}
