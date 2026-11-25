package es.teloarreglo.catalogue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Concejo of an appointment address, as the server resolves it. */
@Serializable
data class ZoneConcejo(@SerialName("ine_code") val ineCode: String, val name: String)

/** Whether an appointment's address lies in the professional's work zone (ADR 0001: it only informs). */
@Serializable
data class AppointmentZone(@SerialName("in_zone") val inZone: Boolean, val concejo: ZoneConcejo)

enum class WorkZoneFailure { NOT_PUBLISHED, EMPTY, UNKNOWN_CONCEJO, ACCESS }
class WorkZoneException(val failure: WorkZoneFailure, cause: Exception) : Exception(failure.name, cause)

/**
 * The professional's work zone (ADR 0001): it informs, it never restricts. In a published profile the
 * owner changes it at once, without review; it always keeps at least one concejo. A refusal is a
 * [WorkZoneException]; anything else (network) is rethrown as is.
 */
interface WorkZones {
    /** Null when the caller is not a participant or the appointment does not exist. */
    suspend fun appointmentZone(appointmentId: String): AppointmentZone?
    /** Replaces the zone of the caller's published profile; returns the zone now public. */
    suspend fun setMine(codes: List<String>): List<Coverage>
    /** Concejo containing (or nearest within 250 m of) a point; null outside Asturias. */
    suspend fun concejoAt(lat: Double, lng: Double): ZoneConcejo?
    /** Public zone of an approved profile; null when it is not published. */
    suspend fun publicZone(profileId: String): List<Coverage>?
}

@Serializable
private data class ResolvedConcejo(@SerialName("ine_code") val ineCode: String, val name: String,
    @SerialName("distance_m") val distanceM: Double = 0.0)

class SupabaseWorkZones(private val client: SupabaseClient) : WorkZones {
    override suspend fun appointmentZone(appointmentId: String): AppointmentZone? = mapped {
        val result = client.postgrest.rpc("appointment_zone", buildJsonObject { put("p_appointment_id", appointmentId) })
        if (result.data.trim() == "null") null else result.decodeAs<AppointmentZone>()
    }

    override suspend fun setMine(codes: List<String>): List<Coverage> = mapped {
        client.postgrest.rpc("set_my_work_zone", buildJsonObject {
            putJsonArray("p_codes") { codes.forEach { add(it) } }
        }).decodeList<Coverage>()
    }

    override suspend fun concejoAt(lat: Double, lng: Double): ZoneConcejo? = mapped {
        client.postgrest.rpc("resolve_concejo", buildJsonObject { put("p_lat", lat); put("p_lng", lng) })
            .decodeList<ResolvedConcejo>().minByOrNull { it.distanceM }?.let { ZoneConcejo(it.ineCode, it.name) }
    }

    override suspend fun publicZone(profileId: String): List<Coverage>? = mapped {
        client.postgrest.rpc("catalogue", buildJsonObject { put("p_id", profileId) })
            .decodeList<PublicProfile>().singleOrNull()?.coverage
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val message = error.message.orEmpty()
        val failure = when {
            "not_published" in message -> WorkZoneFailure.NOT_PUBLISHED
            "zone_empty" in message -> WorkZoneFailure.EMPTY
            "unknown_concejo" in message -> WorkZoneFailure.UNKNOWN_CONCEJO
            error.statusCode == 401 || error.statusCode == 403 -> WorkZoneFailure.ACCESS
            else -> null
        }
        throw if (failure != null) WorkZoneException(failure, error) else error
    }
}

/** The zone with [concejo] added, as INE codes for [WorkZones.setMine]; unchanged if already there. */
fun List<Coverage>.withConcejo(concejo: ZoneConcejo): List<String> =
    map { it.code }.let { codes -> if (concejo.ineCode in codes) codes else codes + concejo.ineCode }

/** The notice is for appointments still under way; a cancelled or completed one needs no zone advice. */
fun showsZoneNotice(stage: es.teloarreglo.appointments.AppointmentStage, zone: AppointmentZone?): Boolean =
    zone != null && !zone.inZone && stage != es.teloarreglo.appointments.AppointmentStage.CANCELLED &&
        stage != es.teloarreglo.appointments.AppointmentStage.COMPLETED

/** True only when both are known and the concejo is not in the zone: unknown never warns. */
fun outsideZone(zone: List<Coverage>?, concejo: ZoneConcejo?): Boolean =
    zone != null && concejo != null && zone.none { it.code == concejo.ineCode }
