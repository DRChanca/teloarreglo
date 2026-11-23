package es.teloarreglo.search

import es.teloarreglo.catalogue.PublicProfile
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Map and manual location (#17). The map only shows what is already public: the approved
 * commercial point of a profile and, on the user's own device, the point they search from.
 * Professionals' search locations never leave the server, so the map never has them.
 */
data class GeoPoint(val lat: Double, val lng: Double) {
    val valid: Boolean get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

/** Rough centre of Asturias, for an empty picker. */
val ASTURIAS_CENTER = GeoPoint(43.36, -5.85)

/** A concejo with a point guaranteed inside it (server `concejo_anchors`). */
@Serializable
data class ConcejoAnchor(@SerialName("ine_code") val ineCode: String, val name: String, val lat: Double, val lng: Double) {
    val point: GeoPoint get() = GeoPoint(lat, lng)
}

/** Concejos to choose by hand when GPS is denied, imprecise or the job is elsewhere. */
interface ConcejoDirectory {
    suspend fun anchors(): List<ConcejoAnchor>
}

class SupabaseConcejoDirectory(private val client: SupabaseClient) : ConcejoDirectory {
    override suspend fun anchors(): List<ConcejoAnchor> = client.postgrest.rpc("concejo_anchors").decodeList()
}

/** The pin if the user placed one on the map; otherwise the concejo's own point (no map available). */
fun searchPoint(concejo: ConcejoAnchor, pin: GeoPoint?): GeoPoint = pin ?: concejo.point

/** Approved commercial point, only together with its approved commercial address. */
fun PublicProfile.publicLocation(): GeoPoint? {
    if (businessAddress.isNullOrBlank()) return null
    return GeoPoint(businessLat ?: return null, businessLng ?: return null).takeIf { it.valid }
}

data class MapMarker(val profileId: String, val name: String, val point: GeoPoint, val distanceBandM: Int)

/** Same candidates and order as the list; [listOnly] counts those without a public point. */
data class MapView(val markers: List<MapMarker>, val listOnly: Int)

fun mapView(rows: List<Pair<PublicProfile, Int>>): MapView {
    val markers = rows.mapNotNull { (profile, band) -> profile.publicLocation()?.let { MapMarker(profile.id, profile.name, it, band) } }
    return MapView(markers, rows.size - markers.size)
}

enum class MapAvailability { READY, NO_KEY, UNAVAILABLE }

fun mapAvailability(apiKey: String, playServicesReady: Boolean): MapAvailability = when {
    apiKey.isBlank() -> MapAvailability.NO_KEY
    !playServicesReady -> MapAvailability.UNAVAILABLE
    else -> MapAvailability.READY
}

/**
 * Google Maps directions to a public or legitimately shared destination. No origin: Maps uses the
 * device's own position, which Teloarreglo does not send anywhere. Null for impossible points.
 */
fun externalMapsUri(destination: GeoPoint): String? {
    if (!destination.valid) return null
    val coordinates = String.format(Locale.ROOT, "%.6f,%.6f", destination.lat, destination.lng)
    return "https://www.google.com/maps/dir/?api=1&destination=" + URLEncoder.encode(coordinates, "UTF-8")
}
