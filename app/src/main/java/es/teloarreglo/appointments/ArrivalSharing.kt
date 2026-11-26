package es.teloarreglo.appointments

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** State of the ventana de llegada as the server computes it (ADR 0002); the app never decides it alone. */
@Serializable enum class ArrivalWindow {
    @SerialName("open") OPEN, @SerialName("not_yet_open") NOT_YET_OPEN, @SerialName("closed") CLOSED,
}

/**
 * Answer of «informar posición», «dejar de compartir», «estado de la ventana» and «volver a compartir»
 * (docs/citas-contrato.md, #82, #85). «Estado de la ventana» carries only the window, its times and the agreed start
 * ([startAt]), so [accepted] is false there. [stopped]: the professional's «Dejar de compartir» holds on the server, so
 * no position counts until they choose to share again.
 */
@Serializable
data class ArrivalReport(
    val window: ArrivalWindow,
    val accepted: Boolean = false,
    @SerialName("opens_at") @Serializable(ServerInstant::class) val opensAt: Instant? = null,
    @SerialName("closes_at") @Serializable(ServerInstant::class) val closesAt: Instant? = null,
    @SerialName("reported_at") @Serializable(ServerInstant::class) val reportedAt: Instant? = null,
    @SerialName("start_at") @Serializable(ServerInstant::class) val startAt: Instant? = null,
    val stopped: Boolean = false,
)

/** What came of the last round: the window state, and the report if a position was sent. */
sealed interface ReportOutcome {
    data class Answered(val report: ArrivalReport) : ReportOutcome
    /** The server refused it for good (not this professional's appointment, or no longer reachable by this account). */
    data object Rejected : ReportOutcome
    /** No answer (network) or no position fix: nothing is known, so the next round asks again. */
    data object Failed : ReportOutcome
}

/** What the professional's phone does next for one appointment. */
sealed interface ArrivalStep {
    /** Keep the foreground service and send the next position at [at]. */
    data class SendAt(val at: Instant) : ArrivalStep
    /** Nothing more to send for this appointment: the service ends and the choice is forgotten. */
    data object Stop : ArrivalStep
    /** Send nothing now; ask again at [at] (the opening the server gave, which may still move). */
    data class WaitUntil(val at: Instant) : ArrivalStep
}

/** About every 30 s while the window is open. */
const val ARRIVAL_REPORT_INTERVAL_S = 30L

/**
 * The single decision of the professional's phone after each attempt (#85). [sharing] is the professional's choice;
 * without it nothing is ever sent. The window itself is always the server's answer, never computed here.
 */
fun nextArrivalStep(sharing: Boolean, outcome: ReportOutcome, now: Instant): ArrivalStep = if (!sharing) ArrivalStep.Stop
else when (outcome) {
    ReportOutcome.Rejected -> ArrivalStep.Stop
    ReportOutcome.Failed -> ArrivalStep.SendAt(now.plusSeconds(ARRIVAL_REPORT_INTERVAL_S))
    is ReportOutcome.Answered -> if (outcome.report.stopped) ArrivalStep.Stop else when (outcome.report.window) {
        ArrivalWindow.CLOSED -> ArrivalStep.Stop
        ArrivalWindow.NOT_YET_OPEN -> outcome.report.opensAt?.let(ArrivalStep::WaitUntil) ?: ArrivalStep.Stop
        else -> ArrivalStep.SendAt(now.plusSeconds(ARRIVAL_REPORT_INTERVAL_S))
    }
}

/** The longest window: 60 min before the start (ADR 0002). With another appointment started the server says -30. */
const val ARRIVAL_EARLIEST_OPENING_MIN = 60L
/** The window closes at the latest an hour after the start. */
const val ARRIVAL_LATEST_CLOSING_MIN = 60L

/**
 * Right after the professional chooses to share (or the phone wakes up with that choice), before asking the server:
 * nothing happens until the earliest moment the window can be open. [ArrivalStep.SendAt] here only starts the rounds,
 * and each round asks «estado de la ventana» (no position) before sending one, so the server's answer decides.
 */
fun firstArrivalStep(start: Instant, now: Instant): ArrivalStep {
    val earliest = start.minusSeconds(ARRIVAL_EARLIEST_OPENING_MIN * 60)
    return when {
        !now.isBefore(start.plusSeconds(ARRIVAL_LATEST_CLOSING_MIN * 60)) -> ArrivalStep.Stop
        now.isBefore(earliest) -> ArrivalStep.WaitUntil(earliest)
        else -> ArrivalStep.SendAt(now)
    }
}

/** «Estado de la ventana», «Informar posición», «Dejar de compartir» and «Volver a compartir» (#82, #85), for the professional. */
interface ArrivalPositions {
    /** The window of the appointment without sending any position. */
    suspend fun window(appointmentId: String): ArrivalReport
    suspend fun report(appointmentId: String, fix: ArrivalFix): ArrivalReport
    /**
     * Deletes the position at once and leaves the stop mark: the client sees «El profesional no comparte su ubicación»
     * and a report that arrives later is refused.
     */
    suspend fun stopSharing(appointmentId: String): ArrivalReport
    /** The professional chose to share: clears the stop mark. Sends no position; answers like [window]. */
    suspend fun startSharing(appointmentId: String): ArrivalReport
}

/**
 * The professional chose to share for this appointment; [clientName] goes in the fixed notification. [announced]: the
 * server already knows of this choice («volver a compartir» answered), so its stop mark, if any, came later.
 */
data class ArrivalChoice(val appointmentId: String, val clientName: String, val start: Instant,
    val announced: Boolean = false)

/** The professional's choices, kept on this phone only. */
interface ArrivalChoices {
    fun all(): List<ArrivalChoice>
    fun get(appointmentId: String): ArrivalChoice?
    fun save(choice: ArrivalChoice)
    fun forget(appointmentId: String)
}

/** One position fix of the professional's phone. */
data class ArrivalFix(val latitude: Double, val longitude: Double, val accuracyM: Double?)

/**
 * One round for one appointment: ask the window, and only while it is open take a fix and report it; then decide
 * what follows. Outside the window no position ever leaves the phone. The choice is forgotten when there is nothing
 * left to share (window closed, rejected, or stopped on the server), so a later wake-up never sends again.
 *
 * A new choice is first told to the server («volver a compartir», which clears the stop mark of an earlier «Dejar de
 * compartir»). That call and «dejar de compartir» go one after the other, never crossed, so a quick stop and share
 * again on this phone reach the server in that order.
 */
class ArrivalRound(
    private val positions: ArrivalPositions,
    private val choices: ArrivalChoices,
    private val locate: suspend () -> ArrivalFix?,
) {
    private val serverOrder = Mutex()

    suspend fun run(appointmentId: String, now: Instant): ArrivalStep = step(appointmentId, now, send = true)

    /**
     * The same as a round but never sends a position: whether the service must start now ([ArrivalStep.SendAt] at
     * [now]), wait for the opening, or stop. Also brings the kept start up to date (an accepted change moved it).
     */
    suspend fun check(appointmentId: String, now: Instant): ArrivalStep = step(appointmentId, now, send = false)

    private suspend fun step(appointmentId: String, now: Instant, send: Boolean): ArrivalStep {
        val sharing = choices.get(appointmentId) != null
        val outcome = if (!sharing) ReportOutcome.Failed else try {
            val window = window(appointmentId)
            if (window == null) ReportOutcome.Failed
            else if (window.window != ArrivalWindow.OPEN || window.stopped || !send) ReportOutcome.Answered(window)
            else locate()?.let { ReportOutcome.Answered(positions.report(appointmentId, it)) } ?: ReportOutcome.Failed
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Only a refusal of access is final; an impossible fix or a lost answer waits for the next round.
            if ((error as? AppointmentException)?.failure == AppointmentFailure.ACCESS) ReportOutcome.Rejected
            else ReportOutcome.Failed
        }
        val next = nextArrivalStep(choices.get(appointmentId) != null, outcome, now)
        if (next == ArrivalStep.Stop) choices.forget(appointmentId)
        return if (!send && next is ArrivalStep.SendAt) ArrivalStep.SendAt(now) else next
    }

    /** The window of a kept choice, telling the server of the choice first if it does not know it yet. */
    private suspend fun window(appointmentId: String): ArrivalReport? {
        val choice = choices.get(appointmentId) ?: return null
        val report = if (choice.announced) positions.window(appointmentId) else serverOrder.withLock {
            val current = choices.get(appointmentId) ?: return null
            positions.startSharing(appointmentId).also { choices.save(current.copy(announced = true)) }
        }
        val start = report.startAt
        if (start != null) choices.get(appointmentId)?.takeIf { it.start != start }?.let { choices.save(it.copy(start = start)) }
        return report
    }

    /**
     * «Dejar de compartir»: the choice is forgotten first, so nothing else is sent, then the server deletes the
     * position and refuses any report still on its way. Without a connection the client still sees «sin compartir»
     * once 2 min pass without positions.
     */
    suspend fun stopSharing(appointmentId: String) = serverOrder.withLock {
        choices.forget(appointmentId)
        try { positions.stopSharing(appointmentId) } catch (error: Exception) { if (error is CancellationException) throw error }
        Unit
    }
}

class SupabaseArrivalPositions(private val client: SupabaseClient) : ArrivalPositions {
    override suspend fun window(appointmentId: String) = mapped {
        client.postgrest.rpc("arrival_sharing_window", buildJsonObject { put("p_appointment_id", appointmentId) })
            .decodeAs<ArrivalReport>()
    }

    override suspend fun report(appointmentId: String, fix: ArrivalFix) = mapped {
        client.postgrest.rpc("report_arrival_position", buildJsonObject {
            put("p_appointment_id", appointmentId)
            put("p_latitude", fix.latitude)
            put("p_longitude", fix.longitude)
            put("p_accuracy_m", fix.accuracyM)
        }).decodeAs<ArrivalReport>()
    }

    override suspend fun stopSharing(appointmentId: String) = mapped {
        client.postgrest.rpc("stop_sharing_arrival", buildJsonObject { put("p_appointment_id", appointmentId) })
            .decodeAs<ArrivalReport>()
    }

    override suspend fun startSharing(appointmentId: String) = mapped {
        client.postgrest.rpc("start_sharing_arrival", buildJsonObject { put("p_appointment_id", appointmentId) })
            .decodeAs<ArrivalReport>()
    }

    /** Stable errors of #82: 42501 permission_denied (final) and 22023 invalid_position (this fix only). */
    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val message = error.message.orEmpty()
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 || "permission_denied" in message -> AppointmentFailure.ACCESS
            "invalid_position" in message || error.statusCode == 400 -> AppointmentFailure.INVALID
            else -> null
        }
        throw if (failure != null) AppointmentException(failure, error) else error
    }
}

/** How a choice is kept on the phone: «start|announced|name», announced being 1 or 0. */
fun encodeArrivalChoice(choice: ArrivalChoice): String =
    "${choice.start.epochSecond}|${if (choice.announced) 1 else 0}|${choice.clientName}"

/** Reads [encodeArrivalChoice]; a choice kept before [ArrivalChoice.announced] existed («start|name») reads as not announced. */
fun decodeArrivalChoice(appointmentId: String, raw: String): ArrivalChoice? {
    val start = Instant.ofEpochSecond(raw.substringBefore('|').toLongOrNull() ?: return null)
    val rest = raw.substringAfter('|', "")
    val flag = rest.substringBefore('|', "")
    return if (flag == "0" || flag == "1") ArrivalChoice(appointmentId, rest.substringAfter('|'), start, flag == "1")
    else ArrivalChoice(appointmentId, rest, start)
}
