package es.teloarreglo.appointments

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** Weekly window in Madrid local time; ISO weekday (1 = Monday), "HH:MM" bounds, end after start. */
@Serializable data class AvailabilityWindow(val weekday: Int, val start: String, val end: String)

@Serializable
data class AgendaBlock(
    val id: String,
    @SerialName("start_at") @Serializable(ServerInstant::class) val startAt: Instant,
    @SerialName("end_at") @Serializable(ServerInstant::class) val endAt: Instant,
    val reason: String? = null,
)

/** A confirmed appointment occupying [startAt, endAt). */
@Serializable
data class AgendaBooking(
    @SerialName("appointment_id") val appointmentId: String,
    @SerialName("start_at") @Serializable(ServerInstant::class) val startAt: Instant,
    @SerialName("end_at") @Serializable(ServerInstant::class) val endAt: Instant,
)

/** The professional's single agenda. Blocks and bookings are future ones only. */
@Serializable
data class AgendaState(
    @SerialName("default_duration_minutes") val defaultDurationMinutes: Int? = null,
    val windows: List<AvailabilityWindow> = emptyList(),
    val blocks: List<AgendaBlock> = emptyList(),
    val bookings: List<AgendaBooking> = emptyList(),
)

enum class AgendaFailure { ACCESS, INVALID, SLOT_UNAVAILABLE, RETRY_CONFLICT }
class AgendaException(val failure: AgendaFailure, cause: Exception) : Exception(failure.name, cause)

/**
 * Availability, usual duration and blocks of the caller's own professional profile. Confirmed
 * appointments and blocks share one exclusion constraint on the server, so a block can never cover
 * a booking and vice versa. Network uncertainty is rethrown as is; retrying a block reuses its key.
 */
interface Agenda {
    suspend fun load(): AgendaState
    suspend fun save(defaultDurationMinutes: Int, windows: List<AvailabilityWindow>): AgendaState
    suspend fun addBlock(requestId: String, start: Instant, end: Instant, reason: String?)
    suspend fun removeBlock(blockId: String)
}

class SupabaseAgenda(private val client: SupabaseClient) : Agenda {
    override suspend fun load(): AgendaState = mapped {
        client.postgrest.rpc("my_agenda").decodeAs<AgendaState>()
    }

    override suspend fun save(defaultDurationMinutes: Int, windows: List<AvailabilityWindow>) = mapped {
        client.postgrest.rpc("save_agenda", buildJsonObject {
            put("p_default_duration_minutes", defaultDurationMinutes)
            putJsonArray("p_windows") {
                windows.forEach { window ->
                    addJsonObject { put("weekday", window.weekday); put("start", window.start); put("end", window.end) }
                }
            }
        }).decodeAs<AgendaState>()
    }

    override suspend fun addBlock(requestId: String, start: Instant, end: Instant, reason: String?) = mapped {
        client.postgrest.rpc("add_agenda_block", buildJsonObject {
            put("p_request_id", requestId)
            put("p_start", start.toString())
            put("p_end", end.toString())
            put("p_reason", reason)
        })
        Unit
    }

    override suspend fun removeBlock(blockId: String) = mapped {
        client.postgrest.rpc("remove_agenda_block", buildJsonObject { put("p_block_id", blockId) })
        Unit
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val message = error.message.orEmpty()
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> AgendaFailure.ACCESS
            "slot_unavailable" in message -> AgendaFailure.SLOT_UNAVAILABLE
            "retry_conflict" in message -> AgendaFailure.RETRY_CONFLICT
            "invalid_agenda" in message || "invalid_block" in message -> AgendaFailure.INVALID
            else -> null
        }
        throw if (failure != null) AgendaException(failure, error) else error
    }
}
