package es.teloarreglo.appointments

import es.teloarreglo.home.distanceMeters
import es.teloarreglo.search.GeoPoint
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/** Where the professional is, as «leer llegada» lets the client see it (ADR 0002). */
sealed interface ArrivalMarker {
    /** More than 2 km from the address of the appointment: a circle whose centre the server displaced; it contains the real position. */
    data class Area(val center: GeoPoint, val radiusMeters: Int) : ArrivalMarker
    /** 2 km or less from the address of the appointment: the exact position and its accuracy. */
    data class Exact(val point: GeoPoint, val accuracyMeters: Double?) : ArrivalMarker
}

/** Within this distance of the address of the appointment the exact point reads «Llegando» (the same 100 m as the proximity notice). */
const val ARRIVING_M = 100.0

/** The answer of «leer llegada» (`appointment_arrival`, #82), for the client of a confirmed appointment. */
sealed interface ArrivalReading {
    /** Outside the ventana de llegada (also «not yet open»), or a state this app does not know. */
    data object Closed : ArrivalReading
    /** Open window, but no recent position: the professional does not share it. */
    data object NotSharing : ArrivalReading
    data class Sharing(val marker: ArrivalMarker, val updatedAt: Instant) : ArrivalReading
}

@Serializable
private data class ArrivalResponse(
    val state: String,
    val precision: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("radius_m") val radiusMeters: Int? = null,
    @SerialName("accuracy_m") val accuracyMeters: Double? = null,
    @SerialName("updated_at") @Serializable(ServerInstant::class) val updatedAt: Instant? = null,
)

private val arrivalJson = Json { ignoreUnknownKeys = true }

/** Decodes the jsonb of `appointment_arrival`. An incomplete or unknown answer reads as closed. */
fun parseArrival(json: String): ArrivalReading {
    val response = arrivalJson.decodeFromString<ArrivalResponse>(json)
    return when (response.state) {
        "not_sharing" -> ArrivalReading.NotSharing
        "sharing" -> response.sharing() ?: ArrivalReading.Closed
        else -> ArrivalReading.Closed
    }
}

private fun ArrivalResponse.sharing(): ArrivalReading? {
    val point = GeoPoint(latitude ?: return null, longitude ?: return null)
    val updated = updatedAt ?: return null
    val marker = when (precision) {
        "area" -> ArrivalMarker.Area(point, radiusMeters ?: return null)
        "exact" -> ArrivalMarker.Exact(point, accuracyMeters)
        else -> return null
    }
    return ArrivalReading.Sharing(marker, updated)
}

/** How far the professional is, in words (the only thing shown when there is no map). */
enum class ArrivalDistance {
    /** «A más de 2 km»: the approximate circle. */
    FAR,
    /** «A menos de 2 km»: the exact point. */
    NEAR,
    /** «Llegando»: the exact point at 100 m or less from the address of the appointment. */
    ARRIVING,
}

/** What the client's confirmed appointment shows of the ventana de llegada. */
sealed interface ArrivalView {
    /** Window closed: no map and no text. */
    data object Hidden : ArrivalView
    /** «El profesional no comparte su ubicación», never an old position. */
    data object NotSharing : ArrivalView
    /** The professional's position next to the address of the appointment ([home]), and how long ago it was updated. */
    data class Sharing(val marker: ArrivalMarker, val home: GeoPoint?, val distance: ArrivalDistance,
        val updatedMinutesAgo: Long) : ArrivalView
}

/** Like the server, a position more than 2 min old no longer counts as shared. */
val ARRIVAL_STALE_AFTER: Duration = Duration.ofMinutes(2)

/**
 * [now] is the phone's clock. A reading kept while reads fail turns into «sin compartir» once its position is more
 * than [ARRIVAL_STALE_AFTER] old, so an old position is never shown as current. A clock behind the server's counts
 * as «just now».
 */
fun arrivalView(reading: ArrivalReading, home: GeoPoint?, now: Instant): ArrivalView = when (reading) {
    ArrivalReading.Closed -> ArrivalView.Hidden
    ArrivalReading.NotSharing -> ArrivalView.NotSharing
    is ArrivalReading.Sharing -> {
        val age = Duration.between(reading.updatedAt, now).let { if (it.isNegative) Duration.ZERO else it }
        if (age > ARRIVAL_STALE_AFTER) ArrivalView.NotSharing
        else ArrivalView.Sharing(reading.marker, home, distanceOf(reading.marker, home), age.toMinutes())
    }
}

private fun distanceOf(marker: ArrivalMarker, home: GeoPoint?): ArrivalDistance = when (marker) {
    is ArrivalMarker.Area -> ArrivalDistance.FAR
    is ArrivalMarker.Exact ->
        if (home != null && distanceMeters(marker.point, home) <= ARRIVING_M) ArrivalDistance.ARRIVING else ArrivalDistance.NEAR
}

/** «Leer llegada»: only the client of the appointment may read it; the server refuses anyone else. */
interface Arrivals {
    suspend fun read(appointmentId: String): ArrivalReading
}

class SupabaseArrivals(private val client: SupabaseClient) : Arrivals {
    override suspend fun read(appointmentId: String): ArrivalReading = parseArrival(
        client.postgrest.rpc("appointment_arrival", buildJsonObject { put("p_appointment_id", appointmentId) }).data)
}
