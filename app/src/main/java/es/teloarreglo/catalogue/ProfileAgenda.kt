package es.teloarreglo.catalogue

import es.teloarreglo.appointments.AvailabilityWindow
import kotlinx.serialization.Serializable

/**
 * Weekly hours and usual duration sent with a profile presentation (#41), so the professional fills the
 * agenda when asking for review instead of after approval. The server saves it with `save_agenda` in the
 * same transaction; it is never part of the reviewed version and changing it later needs no review.
 */
data class ProfileAgenda(val defaultDurationMinutes: Int, val windows: List<AvailabilityWindow>)

/** One editable weekday: open or closed, "HH:MM" bounds in Madrid time. */
@Serializable
data class AgendaDay(val open: Boolean, val from: String = "09:00", val to: String = "14:00")

private val CLOCK = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

/** The editor's agenda: usual duration as typed and seven days, Monday first. */
@Serializable
data class AgendaDraft(val duration: String = "60", val days: List<AgendaDay> = List(7) { AgendaDay(open = false) }) {
    /**
     * The agenda to send, or null while it cannot be: a duration of 15–1440 minutes, at least one open
     * day, and each open day ending after it starts.
     */
    fun toAgenda(): ProfileAgenda? {
        val minutes = duration.trim().toIntOrNull()?.takeIf { it in 15..1440 } ?: return null
        val open = days.withIndex().filter { it.value.open }
        if (open.isEmpty()) return null
        if (open.any { (_, d) -> !CLOCK.matches(d.from) || !CLOCK.matches(d.to) || d.to <= d.from }) return null
        return ProfileAgenda(minutes, open.map { (i, d) -> AvailabilityWindow(i + 1, d.from, d.to) })
    }

    companion object {
        /** Starts from the saved agenda (first window of each day), or all days closed. */
        fun from(durationMinutes: Int?, windows: List<AvailabilityWindow>) = AgendaDraft(
            duration = (durationMinutes ?: 60).toString(),
            days = (1..7).map { day ->
                windows.firstOrNull { it.weekday == day }?.let { AgendaDay(true, it.start, it.end) } ?: AgendaDay(false)
            },
        )
    }
}
