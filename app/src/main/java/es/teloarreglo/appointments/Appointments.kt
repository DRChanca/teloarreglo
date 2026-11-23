package es.teloarreglo.appointments

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import es.teloarreglo.search.GeoPoint
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime

/** Server timestamps arrive with an offset (`+00:00`); they are always handled as instants. */
object ServerInstant : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("ServerInstant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

@Serializable enum class AppointmentStage { @SerialName("pending") PENDING, @SerialName("confirmed") CONFIRMED }
@Serializable enum class ProposalStatus {
    @SerialName("pending") PENDING, @SerialName("rejected") REJECTED, @SerialName("superseded") SUPERSEDED,
    @SerialName("accepted") ACCEPTED,
}

@Serializable
data class AppointmentProposal(
    val id: String,
    @SerialName("appointment_id") val appointmentId: String,
    @SerialName("author_id") val authorId: String,
    val version: Int,
    @SerialName("request_id") val requestId: String,
    val scope: String,
    @SerialName("start_at") @Serializable(ServerInstant::class) val startAt: Instant,
    @SerialName("end_at") @Serializable(ServerInstant::class) val endAt: Instant,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("amount_cents") val amountCents: Int,
    @SerialName("visit_fee_cents") val visitFeeCents: Int? = null,
    @SerialName("materials_included") val materialsIncluded: Boolean,
    @SerialName("travel_included") val travelIncluded: Boolean,
    val status: ProposalStatus,
    @SerialName("created_at") @Serializable(ServerInstant::class) val createdAt: Instant,
) {
    /** Free visits are confirmed by acceptance alone; any cost goes through payment (#22). */
    val isFree get() = amountCents == 0 && (visitFeeCents ?: 0) == 0
    /** What confirming with payment charges (#22): the agreed price, or the visit fee when only that was proposed. */
    val payableCents get() = maxOf(amountCents, visitFeeCents ?: 0)
}

/** INTEGRATED: the client pays the total (simulated) to confirm. DIRECT: paid to the professional outside the app. */
@Serializable enum class PaymentMode { @SerialName("integrated") INTEGRATED, @SerialName("direct") DIRECT }

/**
 * Simulated money of one confirmed appointment, in euro cents. paid = pending + refunded + delivered.
 * A direct agreement records [agreedCents] only: the app neither charges nor certifies external money.
 */
@Serializable
data class AppointmentPayment(
    @SerialName("appointment_id") val appointmentId: String,
    @SerialName("proposal_id") val proposalId: String,
    @SerialName("request_id") val requestId: String,
    val mode: PaymentMode,
    @SerialName("agreed_cents") val agreedCents: Int,
    @SerialName("paid_cents") val paidCents: Int,
    @SerialName("pending_cents") val pendingCents: Int,
    @SerialName("refunded_cents") val refundedCents: Int,
    @SerialName("delivered_cents") val deliveredCents: Int,
    val simulated: Boolean = true,
)

/**
 * Where the job is, as the server lets this account see it (`appointment_places`, #38). The client always
 * gets the exact place; the professional only once the appointment is confirmed, and until then a circle
 * whose centre the server displaced at random (the home is inside it, not necessarily at its centre).
 */
@Serializable
data class AppointmentPlace(
    @SerialName("appointment_id") val appointmentId: String,
    val exact: Boolean,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("area_lat") val areaLat: Double,
    @SerialName("area_lng") val areaLng: Double,
    @SerialName("area_radius_m") val areaRadiusMeters: Int,
    @SerialName("concejo_name") val concejoName: String? = null,
) {
    val location: AppointmentLocation get() =
        if (exact && address != null && latitude != null && longitude != null)
            AppointmentLocation.Exact(address, GeoPoint(latitude, longitude))
        else AppointmentLocation.Approximate(GeoPoint(areaLat, areaLng), areaRadiusMeters, concejoName)
}

sealed interface AppointmentLocation {
    /** Address and pin: shown with "How to get there". */
    data class Exact(val address: String, val point: GeoPoint) : AppointmentLocation
    /** Only a circle of [radiusMeters] around [center] and the concejo; no directions. */
    data class Approximate(val center: GeoPoint, val radiusMeters: Int, val concejoName: String?) : AppointmentLocation
}

/** Readable only by its client and professional; the exact place comes apart, in [place]. */
@Serializable
data class Appointment(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("professional_id") val professionalId: String,
    @SerialName("request_id") val requestId: String,
    val problem: String,
    @SerialName("requested_start") @Serializable(ServerInstant::class) val requestedStart: Instant,
    @SerialName("requested_end") @Serializable(ServerInstant::class) val requestedEnd: Instant,
    val version: Int,
    val stage: AppointmentStage,
    @SerialName("created_at") @Serializable(ServerInstant::class) val createdAt: Instant,
    @SerialName("appointment_proposals") private val rawProposals: List<AppointmentProposal> = emptyList(),
    /** Present once a proposal with a cost is confirmed; free visits have none. */
    @SerialName("appointment_payments") val payment: AppointmentPayment? = null,
    /** Filled by [Appointments.list] from `appointment_places`; null until the server has answered. */
    @Transient val place: AppointmentPlace? = null,
) {
    val location: AppointmentLocation? get() = place?.location

    /** Whole history, oldest version first. Rejected and superseded versions are kept. */
    val proposals get() = rawProposals.sortedBy { it.version }
    /** The version the client may still decide on, if any. */
    val currentProposal get() = rawProposals.filter { it.status == ProposalStatus.PENDING }.maxByOrNull { it.version }
    /** The accepted version whose interval the agenda reserves, once confirmed. */
    val confirmedProposal get() = rawProposals.firstOrNull { it.status == ProposalStatus.ACCEPTED }

    /** What this account should know or do next. Only CONFIRMED means the interval is reserved. */
    fun notice(accountId: String): AppointmentNotice {
        val professional = accountId == professionalId
        return when {
            stage == AppointmentStage.CONFIRMED -> AppointmentNotice.CONFIRMED
            currentProposal != null -> if (professional) AppointmentNotice.AWAITING_CLIENT else AppointmentNotice.DECIDE_PROPOSAL
            rawProposals.isEmpty() -> if (professional) AppointmentNotice.SEND_PROPOSAL else AppointmentNotice.AWAITING_PROPOSAL
            else -> if (professional) AppointmentNotice.SEND_NEW_PROPOSAL else AppointmentNotice.AWAITING_NEW_PROPOSAL
        }
    }
}

enum class AppointmentNotice {
    AWAITING_PROPOSAL, SEND_PROPOSAL, DECIDE_PROPOSAL, AWAITING_CLIENT, AWAITING_NEW_PROPOSAL, SEND_NEW_PROPOSAL, CONFIRMED
}

data class AppointmentRequest(
    val problem: String, val start: Instant, val end: Instant,
    val address: String, val latitude: Double, val longitude: Double,
)

data class ProposalDraft(
    val scope: String, val start: Instant, val durationMinutes: Int, val amountCents: Int,
    val visitFeeCents: Int?, val materialsIncluded: Boolean, val travelIncluded: Boolean,
)

/** Server rejections, kept apart from network uncertainty (which is rethrown as is). */
/** SLOT_UNAVAILABLE: the interval overlaps the agenda or leaves its availability; nothing was confirmed. */
enum class AppointmentFailure { ACCESS, INVALID, OUT_OF_COVERAGE, STALE, RETRY_CONFLICT, SLOT_UNAVAILABLE }
class AppointmentException(val failure: AppointmentFailure, cause: Exception) : Exception(failure.name, cause)

/**
 * Requests and versioned proposals. Pending requests and proposals may overlap and reserve nothing;
 * only [accept] of a free proposal or [confirm] of one with a cost confirms and reserves, atomically on the server.
 * Reuse the same requestId after an uncertain result; accepting the same proposal again is safe.
 */
interface Appointments {
    suspend fun list(conversationId: String): List<Appointment>
    suspend fun request(conversationId: String, requestId: String, request: AppointmentRequest): Appointment
    suspend fun propose(appointmentId: String, expectedVersion: Int, requestId: String, draft: ProposalDraft): AppointmentProposal
    suspend fun reject(proposalId: String): AppointmentProposal
    suspend fun accept(proposalId: String): AppointmentProposal
    /**
     * Confirms a proposal with a cost: acceptance, reservation and the simulated payment happen together
     * or not at all. Reuse [requestId] after an uncertain result; it is bound to this proposal and mode.
     */
    suspend fun confirm(proposalId: String, requestId: String, mode: PaymentMode): AppointmentPayment
}

/** Every column the server lets app accounts read; `*`, address, latitude or longitude would be refused (#38). */
private const val READABLE_COLUMNS = "id,conversation_id,profile_id,client_id,professional_id,request_id,problem," +
    "requested_start,requested_end,version,stage,created_at"

class SupabaseAppointments(private val client: SupabaseClient) : Appointments {
    override suspend fun list(conversationId: String): List<Appointment> = mapped {
        val rows = client.postgrest["appointments"].select(Columns.raw("$READABLE_COLUMNS,appointment_proposals(*),appointment_payments(*)")) {
            filter { eq("conversation_id", conversationId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Appointment>()
        val places = client.postgrest.rpc("appointment_places", buildJsonObject { put("p_conversation_id", conversationId) })
            .decodeList<AppointmentPlace>().associateBy { it.appointmentId }
        rows.map { it.copy(place = places[it.id]) }
    }

    override suspend fun request(conversationId: String, requestId: String, request: AppointmentRequest) = mapped {
        client.postgrest.rpc("request_appointment", buildJsonObject {
            put("p_conversation_id", conversationId)
            put("p_request_id", requestId)
            put("p_problem", request.problem)
            put("p_start", request.start.toString())
            put("p_end", request.end.toString())
            put("p_address", request.address)
            put("p_lat", request.latitude)
            put("p_lng", request.longitude)
        }).decodeAs<Appointment>()
    }

    override suspend fun propose(appointmentId: String, expectedVersion: Int, requestId: String, draft: ProposalDraft) = mapped {
        client.postgrest.rpc("propose_appointment", buildJsonObject {
            put("p_appointment_id", appointmentId)
            put("p_expected_version", expectedVersion)
            put("p_request_id", requestId)
            put("p_scope", draft.scope)
            put("p_start", draft.start.toString())
            put("p_duration_minutes", draft.durationMinutes)
            put("p_amount_cents", draft.amountCents)
            put("p_visit_fee_cents", draft.visitFeeCents)
            put("p_materials_included", draft.materialsIncluded)
            put("p_travel_included", draft.travelIncluded)
        }).decodeAs<AppointmentProposal>()
    }

    override suspend fun reject(proposalId: String) = mapped {
        client.postgrest.rpc("reject_proposal", buildJsonObject { put("p_proposal_id", proposalId) })
            .decodeAs<AppointmentProposal>()
    }

    override suspend fun accept(proposalId: String) = mapped {
        client.postgrest.rpc("accept_proposal", buildJsonObject { put("p_proposal_id", proposalId) })
            .decodeAs<AppointmentProposal>()
    }

    override suspend fun confirm(proposalId: String, requestId: String, mode: PaymentMode) = mapped {
        client.postgrest.rpc("confirm_proposal", buildJsonObject {
            put("p_proposal_id", proposalId)
            put("p_request_id", requestId)
            put("p_mode", if (mode == PaymentMode.INTEGRATED) "integrated" else "direct")
        }).decodeAs<AppointmentPayment>()
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val message = error.message.orEmpty()
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> AppointmentFailure.ACCESS
            "slot_unavailable" in message -> AppointmentFailure.SLOT_UNAVAILABLE
            "stale_version" in message || "incompatible_state" in message || "proposal_expired" in message ->
                AppointmentFailure.STALE
            "out_of_coverage" in message -> AppointmentFailure.OUT_OF_COVERAGE
            "retry_conflict" in message -> AppointmentFailure.RETRY_CONFLICT
            "invalid_appointment" in message || "invalid_proposal" in message || "payment_required" in message ||
                "payment_not_required" in message || "invalid_payment" in message ->
                AppointmentFailure.INVALID
            else -> null
        }
        throw if (failure != null) AppointmentException(failure, error) else error
    }
}

private val euroInput = Regex("""^(\d{1,7})(?:[.,](\d{1,2}))?$""")

/** "85,5" → 8550. Exact decimal parsing, never floating point; null when not a valid amount. */
fun parseEuroCents(text: String): Int? {
    val match = euroInput.matchEntire(text.trim()) ?: return null
    val euros = match.groupValues[1].toInt()
    val cents = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toInt()
    return euros * 100 + cents
}
