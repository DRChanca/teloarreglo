package es.teloarreglo.home

import es.teloarreglo.search.GeoPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Home address (#37): asked once after signing in, like Glovo, so proximity search and appointment
 * requests start from home without asking for GPS every time. Private: only its owner reads or
 * writes it (RLS + definer RPC); it never reaches search, catalogue or other accounts. The server
 * resolves the concejo from the point, so the client can never claim a different one.
 */
@Serializable
enum class HomeSource {
    /** Chosen from Google Places autocomplete. */
    @SerialName("places") PLACES,
    /** Pin placed on the map, address typed by hand. */
    @SerialName("pin") PIN,
    /** One GPS fix, address typed by hand. */
    @SerialName("gps") GPS,
}

@Serializable
data class HomeAddress(
    val address: String,
    val lat: Double,
    val lng: Double,
    /** Concejo containing (or within 250 m of) the point; null outside Asturias. */
    @SerialName("ine_code") val ineCode: String? = null,
    @SerialName("concejo_name") val concejoName: String? = null,
    val source: HomeSource,
    @SerialName("place_id") val placeId: String? = null,
) {
    val point: GeoPoint get() = GeoPoint(lat, lng)
}

data class HomeDraft(val address: String, val point: GeoPoint, val source: HomeSource, val placeId: String? = null)

/** Server rejections, kept apart from network uncertainty (rethrown as is). */
enum class HomeFailure { ACCESS, INVALID }
class HomeException(val failure: HomeFailure, cause: Exception) : Exception(failure.name, cause)

interface HomeAddresses {
    /** The signed-in account's home, or null if it has none yet. */
    suspend fun current(): HomeAddress?
    /** Creates or replaces the home. Saving the same draft twice is harmless. */
    suspend fun save(draft: HomeDraft): HomeAddress
}

class SupabaseHomeAddresses(private val client: SupabaseClient) : HomeAddresses {
    override suspend fun current(): HomeAddress? = mapped {
        client.postgrest.rpc("home_address").decodeList<HomeAddress>().firstOrNull()
    }

    override suspend fun save(draft: HomeDraft): HomeAddress = mapped {
        client.postgrest.rpc("set_home_address", buildJsonObject {
            put("p_address", draft.address.trim())
            put("p_lat", draft.point.lat)
            put("p_lng", draft.point.lng)
            put("p_source", when (draft.source) { HomeSource.PLACES -> "places"; HomeSource.PIN -> "pin"; HomeSource.GPS -> "gps" })
            put("p_place_id", draft.placeId)
        }).decodeList<HomeAddress>().single()
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> HomeFailure.ACCESS
            "invalid_home_address" in error.message.orEmpty() -> HomeFailure.INVALID
            else -> null
        }
        throw if (failure != null) HomeException(failure, error) else error
    }
}

/** Beyond this distance from home the user is asked whether to change it or search from here. */
const val AWAY_FROM_HOME_M = 5_000.0

/** Great-circle distance (haversine, mean Earth radius). Straight line, not by road. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLng / 2).let { it * it }
    return 2 * 6_371_008.8 * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
}

/**
 * "You are far from home" is asked at most once per session (app process and signed-in account).
 * The position is only compared on the device; it is never stored or sent.
 */
class AwayFromHome {
    private var asked = false

    /** True the first time in this session that [here] is more than 5 km from [home]. */
    fun shouldAsk(home: GeoPoint?, here: GeoPoint): Boolean {
        if (asked || home == null || !home.valid || !here.valid) return false
        if (distanceMeters(home, here) <= AWAY_FROM_HOME_M) return false
        asked = true
        return true
    }

    /** A new session (other account or sign-out) may ask again. */
    fun reset() { asked = false }
}
