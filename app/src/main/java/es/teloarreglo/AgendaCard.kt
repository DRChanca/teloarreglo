package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.UUID

private val clock = Regex("""^([01]\d|2[0-3]):[0-5]\d$""")

/** One editable row per weekday; the server accepts several windows a day, the editor keeps it simple. */
internal data class DayRow(val open: Boolean, val from: String, val to: String)

/** What is wrong in the schedule editor: the duration, and the weekdays (1 = Monday) whose start or end is wrong. */
internal data class AgendaProblems(val duration: Boolean, val from: Set<Int>, val to: Set<Int>) {
    val none get() = !duration && from.isEmpty() && to.isEmpty()
    val days get() = (from + to).sorted()
}

/** An end that does not come after its start is the end's problem; closed days are not checked. */
internal fun agendaProblems(duration: String, rows: List<DayRow>): AgendaProblems {
    val minutes = duration.toIntOrNull()
    val open = rows.withIndex().filter { it.value.open }.map { (i, r) -> i + 1 to r }
    val badFrom = open.filter { (_, r) -> !clock.matches(r.from) }.map { it.first }.toSet()
    val badTo = open.filter { (day, r) -> day !in badFrom && (!clock.matches(r.to) || r.to <= r.from) }.map { it.first }.toSet()
    return AgendaProblems(minutes == null || minutes !in 15..1440, badFrom, badTo)
}

private fun rowsFrom(windows: List<AvailabilityWindow>) = (1..7).map { day ->
    windows.firstOrNull { it.weekday == day }?.let { DayRow(true, it.start, it.end) } ?: DayRow(false, "09:00", "14:00")
}

/**
 * The professional's single agenda: weekly availability (Madrid time), usual duration, blocks and
 * confirmed bookings. Nothing is shown as saved until the server answers; saving the schedule replaces
 * it whole, so retrying is safe, and a block keeps its request key until the server confirms it.
 */
@Composable
fun AgendaCard(agenda: Agenda, scope: CoroutineScope) {
    var state by remember { mutableStateOf<AgendaState?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(rowsFrom(emptyList())) }
    var duration by rememberSaveable { mutableStateOf("60") }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var problems by remember { mutableStateOf(AgendaProblems(false, emptySet(), emptySet())) }
    var failure by remember { mutableStateOf<AgendaFailure?>(null) }
    var errorShown by remember { mutableStateOf(false) }
    LaunchedEffect(agenda, reload) {
        try {
            val loaded = agenda.load()
            state = loaded; loadFailed = false
            rows = rowsFrom(loaded.windows)
            loaded.defaultDurationMinutes?.let { duration = it.toString() }
        } catch (e: Exception) { if (e is CancellationException) throw e; loadFailed = true }
    }
    val locale = LocalConfiguration.current.locales[0]
    val format = dateTimeFormat()
    fun submit(action: suspend () -> Unit) {
        busy = true; saved = false; errorShown = false; failure = null
        scope.launch {
            try { action(); saved = true; reload++ }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = (e as? AgendaException)?.failure; errorShown = true
            } finally { busy = false }
        }
    }
    Card(Modifier.fillMaxWidth().padding(vertical = TeloTheme.spacing.small).testTag("agenda_card")) {
        Column(Modifier.padding(TeloTheme.spacing.large), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
            Text(stringResource(R.string.agenda_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.agenda_hint), style = MaterialTheme.typography.bodySmall)
            // Server rule (20261123012610): no saved window means no hours limit, only bookings and blocks.
            if (state?.windows?.isEmpty() == true) Text(stringResource(R.string.agenda_no_hours),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("agenda_no_hours"))
            if (loadFailed) Text(stringResource(R.string.agenda_load_error), color = MaterialTheme.colorScheme.error)
            if (state == null && !loadFailed) LinearProgressIndicator(Modifier.fillMaxWidth())
            val editable = state != null && !busy
            OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(4) }, enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = problems.duration,
                supportingText = if (problems.duration) {
                    { Text(stringResource(R.string.agenda_error_duration), Modifier.testTag("agenda_duration_error")) }
                } else null,
                label = { Text(stringResource(R.string.agenda_duration)) }, modifier = Modifier.fillMaxWidth().testTag("agenda_duration"))
            rows.forEachIndexed { index, row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall)) {
                    Checkbox(row.open, { open -> rows = rows.toMutableList().also { it[index] = row.copy(open = open) } },
                        enabled = editable, modifier = Modifier.testTag("agenda_day_${index + 1}"))
                    Text(DayOfWeek.of(index + 1).getDisplayName(TextStyle.SHORT, locale), Modifier.width(48.dp))
                    OutlinedTextField(row.from, { v -> rows = rows.toMutableList().also { it[index] = row.copy(from = v.take(5)) } },
                        enabled = editable && row.open, singleLine = true, isError = index + 1 in problems.from, label = { Text(stringResource(R.string.appointment_from)) },
                        modifier = Modifier.weight(1f).testTag("agenda_from_${index + 1}"))
                    OutlinedTextField(row.to, { v -> rows = rows.toMutableList().also { it[index] = row.copy(to = v.take(5)) } },
                        enabled = editable && row.open, singleLine = true, isError = index + 1 in problems.to, label = { Text(stringResource(R.string.appointment_to)) },
                        modifier = Modifier.weight(1f).testTag("agenda_to_${index + 1}"))
                }
            }
            // Names the weekdays to fix; the wrong hour fields are marked in their row.
            if (problems.days.isNotEmpty()) Text(
                stringResource(R.string.agenda_error_days,
                    problems.days.joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, locale) }),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("agenda_days_error").semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(onClick = {
                problems = agendaProblems(duration, rows)
                if (!problems.none) return@Button
                val windows = rows.withIndex().filter { it.value.open }.map { (i, r) -> AvailabilityWindow(i + 1, r.from, r.to) }
                val minutes = duration.toInt()
                submit { agenda.save(minutes, windows) }
            }, enabled = editable, modifier = Modifier.testTag("agenda_save")) { Text(stringResource(R.string.agenda_save)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (saved) Text(stringResource(R.string.agenda_saved), modifier = Modifier.testTag("agenda_saved"))
            if (errorShown) Text(stringResource(when (failure) {
                AgendaFailure.ACCESS -> R.string.agenda_error_access
                AgendaFailure.INVALID -> R.string.agenda_error_invalid
                AgendaFailure.SLOT_UNAVAILABLE -> R.string.agenda_error_slot
                AgendaFailure.RETRY_CONFLICT -> R.string.agenda_error_conflict
                null -> R.string.agenda_error_network
            }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("agenda_error"))

            HorizontalDivider()
            Text(stringResource(R.string.agenda_bookings), style = MaterialTheme.typography.titleSmall)
            val bookings = state?.bookings.orEmpty()
            if (bookings.isEmpty()) Text(stringResource(R.string.agenda_none), style = MaterialTheme.typography.bodySmall)
            bookings.forEach {
                Text(stringResource(R.string.agenda_interval, format.format(it.startAt), format.format(it.endAt)),
                    modifier = Modifier.testTag("agenda_booking"))
            }
            Text(stringResource(R.string.agenda_blocks), style = MaterialTheme.typography.titleSmall)
            val blocks = state?.blocks.orEmpty()
            if (blocks.isEmpty()) Text(stringResource(R.string.agenda_none), style = MaterialTheme.typography.bodySmall)
            blocks.forEach { block ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agenda_interval, format.format(block.startAt), format.format(block.endAt)))
                        block.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = { submit { agenda.removeBlock(block.id) } }, enabled = editable,
                        modifier = Modifier.testTag("agenda_block_remove")) { Text(stringResource(R.string.agenda_block_remove)) }
                }
            }
            BlockForm(agenda, editable, ::submit)
        }
    }
}

@Composable
private fun BlockForm(agenda: Agenda, enabled: Boolean, submit: (suspend () -> Unit) -> Unit) {
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var from by rememberSaveable { mutableStateOf("14:00") }
    var to by rememberSaveable { mutableStateOf("15:00") }
    var reason by rememberSaveable { mutableStateOf("") }
    // Kept until the server confirms, so a retry after a lost answer never creates a second block.
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val badFrom = invalid && !clock.matches(from)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
        Text(stringResource(R.string.appointment_date))
        DateField(LocalDate.parse(dateText), enabled && key == null) { dateText = it.toString() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
        OutlinedTextField(from, { from = it.take(5) }, enabled = enabled && key == null, singleLine = true, isError = badFrom,
            label = { Text(stringResource(R.string.appointment_from)) }, modifier = Modifier.weight(1f).testTag("agenda_block_from"))
        OutlinedTextField(to, { to = it.take(5) }, enabled = enabled && key == null, singleLine = true, isError = invalid && !badFrom,
            label = { Text(stringResource(R.string.appointment_to)) }, modifier = Modifier.weight(1f).testTag("agenda_block_to"))
    }
    OutlinedTextField(reason, { reason = it.take(200) }, enabled = enabled && key == null, singleLine = true,
        label = { Text(stringResource(R.string.agenda_block_reason)) }, modifier = Modifier.fillMaxWidth().testTag("agenda_block_reason"))
    if (invalid) Text(stringResource(R.string.agenda_error_invalid), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    OutlinedButton(onClick = {
        val date = LocalDate.parse(dateText)
        val start = localInstant(date, from)
        val end = localInstant(date, to)
        invalid = start == null || end == null || !end.isAfter(start)
        if (invalid) return@OutlinedButton
        val requestKey = key ?: UUID.randomUUID().toString().also { key = it }
        submit {
            try {
                agenda.addBlock(requestKey, start!!, end!!, reason.trim().ifEmpty { null })
                key = null; reason = ""
            } catch (e: AgendaException) { key = null; throw e }  // A definitive answer frees the form.
        }
    }, enabled = enabled, modifier = Modifier.testTag("agenda_block_add")) {
        Text(stringResource(if (key != null) R.string.appointment_retry_send else R.string.agenda_block_add))
    }
}
