package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import es.teloarreglo.appointments.*
import es.teloarreglo.chat.Conversation
import es.teloarreglo.search.Located
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale
import java.util.UUID

/** Location helpers from the activity; the pin is taken once, on demand, and only sent with a request. */
class PinSource(
    val ensurePermission: suspend () -> Boolean,
    val locate: suspend () -> Located,
    /** True when Google Maps can be shown (#17): the point can then be chosen on the map. */
    val mapsReady: Boolean = false,
)

/**
 * Appointment requests and proposals inside a conversation. Polls only while visible, like the chat.
 * Nothing is shown as sent until the server answers; an uncertain result keeps its request key and
 * locks the form so a retry can never create a second request or proposal.
 */
@Composable
fun AppointmentsPanel(
    appointments: Appointments, accountId: String, conversation: Conversation,
    scope: CoroutineScope, pins: PinSource,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var list by remember(conversation.id) { mutableStateOf<List<Appointment>?>(null) }
    var loadFailed by remember(conversation.id) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    // "list", "request" or "propose:<appointment id>"; null keeps the chat in front.
    var sheet by rememberSaveable(conversation.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(appointments, conversation.id, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { list = appointments.list(conversation.id); loadFailed = false }
                catch (error: Exception) { if (error is CancellationException) throw error; loadFailed = true }
                delay(2500)
            }
        }
    }
    val isClient = conversation.clientId == accountId
    val current = list.orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("appointments_summary")) {
        if (loadFailed) Text(stringResource(R.string.appointment_load_error), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        val latest = current.firstOrNull()
        if (latest == null) {
            if (list != null) Text(stringResource(R.string.appointment_none), style = MaterialTheme.typography.bodySmall)
            if (isClient) OutlinedButton(onClick = { sheet = "request" }, modifier = Modifier.testTag("appointment_request")) {
                Text(stringResource(R.string.appointment_request))
            }
        } else {
            ElevatedCard(onClick = { sheet = "list" }, modifier = Modifier.fillMaxWidth().testTag("appointment_card")) {
                Column(Modifier.padding(12.dp)) {
                    Text(noticeText(latest, accountId), style = MaterialTheme.typography.bodyMedium)
                    ReservationLine(latest)
                    Text(stringResource(R.string.appointment_view_all, current.size), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    val shown = sheet ?: return
    Dialog(onDismissRequest = { sheet = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TeloTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp)
                    .verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { sheet = if (shown == "list") null else "list" }) {
                            Text(stringResource(if (shown == "list") R.string.appointment_close else R.string.catalogue_back))
                        }
                        Text(stringResource(R.string.appointment_title), style = MaterialTheme.typography.titleLarge)
                    }
                    when {
                        shown == "request" -> RequestForm(appointments, conversation, scope, pins, current,
                            onDone = { refresh++; sheet = "list" })
                        shown.startsWith("propose:") -> current.firstOrNull { it.id == shown.removePrefix("propose:") }
                            ?.let { ProposalForm(appointments, it, scope, current, onDone = { refresh++; sheet = "list" }) }
                        else -> {
                            if (isClient) Button(onClick = { sheet = "request" }, modifier = Modifier.testTag("appointment_new_request")) {
                                Text(stringResource(R.string.appointment_new_request))
                            }
                            current.forEach { appointment ->
                                AppointmentDetails(appointment, accountId, appointments, scope,
                                    onPropose = { sheet = "propose:${appointment.id}" }, onChanged = { refresh++ })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun noticeText(appointment: Appointment, accountId: String): String {
    val version = appointment.currentProposal?.version ?: 0
    return when (appointment.notice(accountId)) {
        AppointmentNotice.AWAITING_PROPOSAL -> stringResource(R.string.appointment_notice_awaiting_proposal)
        AppointmentNotice.SEND_PROPOSAL -> stringResource(R.string.appointment_notice_send_proposal)
        AppointmentNotice.DECIDE_PROPOSAL -> stringResource(R.string.appointment_notice_decide, version)
        AppointmentNotice.AWAITING_CLIENT -> stringResource(R.string.appointment_notice_awaiting_client, version)
        AppointmentNotice.AWAITING_NEW_PROPOSAL -> stringResource(R.string.appointment_notice_awaiting_new)
        AppointmentNotice.SEND_NEW_PROPOSAL -> stringResource(R.string.appointment_notice_send_new)
        AppointmentNotice.CONFIRMED -> stringResource(R.string.appointment_notice_confirmed)
    }
}

/** States plainly whether the interval is reserved: only a confirmed appointment occupies the agenda. */
@Composable
private fun ReservationLine(appointment: Appointment) {
    val confirmed = appointment.confirmedProposal
    if (appointment.stage == AppointmentStage.CONFIRMED && confirmed != null) {
        Text(stringResource(R.string.appointment_confirmed_reserved, dateTimeFormat().format(confirmed.startAt),
            confirmed.durationMinutes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("appointment_confirmed_${appointment.id}"))
    } else Text(stringResource(R.string.appointment_unreserved), style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun dateTimeFormat(): DateTimeFormatter {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
    }
}

@Composable
private fun euros(cents: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("EUR") }
        .format(BigDecimal.valueOf(cents.toLong(), 2))
}

@Composable
private fun failureText(failure: AppointmentFailure?): String = stringResource(when (failure) {
    AppointmentFailure.ACCESS -> R.string.appointment_error_access
    AppointmentFailure.INVALID -> R.string.appointment_error_invalid
    AppointmentFailure.OUT_OF_COVERAGE -> R.string.appointment_error_coverage
    AppointmentFailure.STALE -> R.string.appointment_error_stale
    AppointmentFailure.RETRY_CONFLICT -> R.string.appointment_error_conflict
    AppointmentFailure.SLOT_UNAVAILABLE -> R.string.appointment_error_slot
    null -> R.string.appointment_error_network
})

@Composable
private fun AppointmentDetails(
    appointment: Appointment, accountId: String, appointments: Appointments, scope: CoroutineScope,
    onPropose: () -> Unit, onChanged: () -> Unit,
) {
    val format = dateTimeFormat()
    val professional = appointment.professionalId == accountId
    var confirmReject by remember { mutableStateOf<AppointmentProposal?>(null) }
    var confirmAccept by remember { mutableStateOf<AppointmentProposal?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<AppointmentFailure?>(null) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("appointment_${appointment.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(appointment.problem, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.appointment_requested_window,
                format.format(appointment.requestedStart), format.format(appointment.requestedEnd)))
            Text(stringResource(R.string.appointment_address_label, appointment.address), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.appointment_pin, "%.5f".format(Locale.ROOT, appointment.latitude),
                "%.5f".format(Locale.ROOT, appointment.longitude)), style = MaterialTheme.typography.bodySmall)
            // Shared by the client with this appointment only: both participants may navigate to it.
            val context = androidx.compose.ui.platform.LocalContext.current
            var noMapsApp by remember { mutableStateOf(false) }
            TextButton(onClick = {
                noMapsApp = !openExternalMaps(context, es.teloarreglo.search.GeoPoint(appointment.latitude, appointment.longitude))
            }, modifier = Modifier.testTag("map_open_appointment")) { Text(stringResource(R.string.map_open_appointment)) }
            if (noMapsApp) Text(stringResource(R.string.map_no_app), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("map_no_app"))
            Text(noticeText(appointment, accountId), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("appointment_notice_${appointment.id}"))
            ReservationLine(appointment)
            if (appointment.proposals.isNotEmpty()) {
                Text(stringResource(R.string.appointment_history), style = MaterialTheme.typography.titleSmall)
                appointment.proposals.asReversed().forEach { ProposalCard(it, format) }
            }
            val pending = appointment.currentProposal
            val confirmed = appointment.stage == AppointmentStage.CONFIRMED
            if (professional && !confirmed) Button(onClick = onPropose, modifier = Modifier.testTag("appointment_propose")) {
                Text(stringResource(if (appointment.proposals.isEmpty()) R.string.appointment_propose else R.string.appointment_propose_new))
            }
            if (!professional && pending != null) {
                if (pending.isFree) Button(onClick = { confirmAccept = pending }, enabled = !busy,
                    modifier = Modifier.testTag("appointment_accept")) { Text(stringResource(R.string.appointment_accept)) }
                else Text(stringResource(R.string.appointment_paid_later), style = MaterialTheme.typography.bodySmall)
            }
            if (!professional && pending != null) OutlinedButton(onClick = { confirmReject = pending }, enabled = !busy,
                modifier = Modifier.testTag("appointment_reject")) { Text(stringResource(R.string.appointment_reject)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) Text(failureText(failure), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("appointment_decision_error"))
        }
    }
    confirmAccept?.let { proposal ->
        AlertDialog(onDismissRequest = { confirmAccept = null },
            text = { Text(stringResource(R.string.appointment_accept_confirm, proposal.version)) },
            dismissButton = { TextButton(onClick = { confirmAccept = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmAccept = null
                    busy = true; error = null
                    // Accepting a given version is idempotent on the server: a lost answer is retried safely.
                    scope.launch {
                        try { appointments.accept(proposal.id); onChanged() }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            failure = (e as? AppointmentException)?.failure; error = e.message.orEmpty()
                            if (failure == AppointmentFailure.STALE || failure == AppointmentFailure.SLOT_UNAVAILABLE) onChanged()
                        } finally { busy = false }
                    }
                }, modifier = Modifier.testTag("appointment_accept_confirm")) { Text(stringResource(R.string.appointment_accept)) }
            })
    }
    confirmReject?.let { proposal ->
        AlertDialog(onDismissRequest = { confirmReject = null },
            text = { Text(stringResource(R.string.appointment_reject_confirm, proposal.version)) },
            dismissButton = { TextButton(onClick = { confirmReject = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmReject = null
                    busy = true; error = null
                    // Rejecting a given version is idempotent on the server: retrying is always safe.
                    scope.launch {
                        try { appointments.reject(proposal.id); onChanged() }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            failure = (e as? AppointmentException)?.failure; error = e.message.orEmpty()
                            if (failure == AppointmentFailure.STALE) onChanged()
                        } finally { busy = false }
                    }
                }, modifier = Modifier.testTag("appointment_reject_confirm")) { Text(stringResource(R.string.appointment_reject)) }
            })
    }
}

@Composable
private fun ProposalCard(proposal: AppointmentProposal, format: DateTimeFormatter) {
    val current = proposal.status == ProposalStatus.PENDING || proposal.status == ProposalStatus.ACCEPTED
    Surface(color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("proposal_${proposal.version}")) {
        Column(Modifier.padding(10.dp)) {
            Text(stringResource(R.string.appointment_proposal_version, proposal.version) + " · " + stringResource(when (proposal.status) {
                ProposalStatus.PENDING -> R.string.appointment_status_pending
                ProposalStatus.REJECTED -> R.string.appointment_status_rejected
                ProposalStatus.SUPERSEDED -> R.string.appointment_status_superseded
                ProposalStatus.ACCEPTED -> R.string.appointment_status_accepted
            }), style = MaterialTheme.typography.labelLarge)
            Text(proposal.scope)
            Text(stringResource(R.string.appointment_proposal_when, format.format(proposal.startAt), proposal.durationMinutes))
            Text(stringResource(R.string.appointment_amount, euros(proposal.amountCents)), style = MaterialTheme.typography.titleSmall)
            Text(proposal.visitFeeCents?.let { stringResource(R.string.appointment_visit_fee, euros(it)) }
                ?: stringResource(R.string.appointment_no_visit_fee), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (proposal.materialsIncluded) R.string.appointment_materials_included else R.string.appointment_materials_excluded) +
                " · " + stringResource(if (proposal.travelIncluded) R.string.appointment_travel_included else R.string.appointment_travel_excluded),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun localInstant(date: LocalDate?, time: String): Instant? = try {
    date?.let { LocalDateTime.of(it, LocalTime.parse(time.trim())).atZone(ZoneId.systemDefault()).toInstant() }
} catch (_: Exception) { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(date: LocalDate?, enabled: Boolean, onDate: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.testTag("appointment_date")) {
        Text(date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            ?: stringResource(R.string.appointment_pick_date))
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                picking = false
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } }) {
            DatePicker(state)
        }
    }
}

@Composable
private fun SubmitArea(busy: Boolean, keyPending: Boolean, failure: AppointmentFailure?, shownError: Boolean,
                       label: Int, tag: String, onSubmit: () -> Unit) {
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.appointment_sending))
    }
    if (shownError) Text(failureText(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("appointment_error"))
    Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.testTag(tag)) {
        Text(stringResource(if (keyPending) R.string.appointment_retry_send else label))
    }
}

@Composable
private fun RequestForm(
    appointments: Appointments, conversation: Conversation, scope: CoroutineScope, pins: PinSource,
    known: List<Appointment>, onDone: () -> Unit,
) {
    var problem by rememberSaveable(conversation.id) { mutableStateOf("") }
    var dateText by rememberSaveable(conversation.id) { mutableStateOf<String?>(LocalDate.now().plusDays(1).toString()) }
    var from by rememberSaveable(conversation.id) { mutableStateOf("09:00") }
    var to by rememberSaveable(conversation.id) { mutableStateOf("11:00") }
    var address by rememberSaveable(conversation.id) { mutableStateOf("") }
    var lat by rememberSaveable(conversation.id) { mutableStateOf("") }
    var lng by rememberSaveable(conversation.id) { mutableStateOf("") }
    var key by rememberSaveable(conversation.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var noLocation by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var shownError by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppointmentFailure?>(null) }
    val date = dateText?.let(LocalDate::parse)
    // A request whose response was lost is recognised in the history by its key.
    LaunchedEffect(known, key) { if (key != null && !busy && known.any { it.requestId == key }) { key = null; onDone() } }
    val editable = key == null && !busy
    val scrollSafe = Modifier.fillMaxWidth()
    Text(stringResource(R.string.appointment_price_later), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(problem, { if (it.length <= 2000) problem = it }, enabled = editable, minLines = 2,
        label = { Text(stringResource(R.string.appointment_problem)) }, modifier = scrollSafe.testTag("appointment_problem"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.appointment_date))
        DateField(date, editable) { dateText = it.toString() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(from, { from = it.take(5) }, enabled = editable, singleLine = true,
            label = { Text(stringResource(R.string.appointment_from)) }, modifier = Modifier.weight(1f).testTag("appointment_from"))
        OutlinedTextField(to, { to = it.take(5) }, enabled = editable, singleLine = true,
            label = { Text(stringResource(R.string.appointment_to)) }, modifier = Modifier.weight(1f).testTag("appointment_to"))
    }
    OutlinedTextField(address, { if (it.length <= 300) address = it }, enabled = editable,
        label = { Text(stringResource(R.string.appointment_address)) }, modifier = scrollSafe.testTag("appointment_address"))
    Text(stringResource(R.string.appointment_address_privacy), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        locating = true; noLocation = false
        scope.launch {
            try {
                val position = if (pins.ensurePermission()) pins.locate() else Located.Unavailable
                if (position is Located.Position) {
                    lat = "%.6f".format(Locale.ROOT, position.lat); lng = "%.6f".format(Locale.ROOT, position.lng)
                } else noLocation = true
            } finally { locating = false }
        }
    }, enabled = editable && !locating, modifier = Modifier.testTag("appointment_locate")) {
        Text(stringResource(if (locating) R.string.appointment_locating else R.string.appointment_use_location))
    }
    if (noLocation) Text(stringResource(R.string.appointment_location_unavailable), style = MaterialTheme.typography.bodySmall)
    var picking by remember { mutableStateOf(false) }
    if (pins.mapsReady) OutlinedButton(onClick = { picking = true }, enabled = editable,
        modifier = Modifier.testTag("map_pick_on_map")) { Text(stringResource(R.string.map_pick_on_map)) }
    Text(stringResource(R.string.map_pin_not_address), style = MaterialTheme.typography.bodySmall)
    if (picking) MapPickDialog(
        initial = lat.replace(',', '.').toDoubleOrNull()?.let { a -> lng.replace(',', '.').toDoubleOrNull()?.let { b ->
            es.teloarreglo.search.GeoPoint(a, b) } }?.takeIf { it.valid },
        onDismiss = { picking = false },
    ) { point ->
        lat = "%.6f".format(Locale.ROOT, point.lat); lng = "%.6f".format(Locale.ROOT, point.lng); picking = false
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        OutlinedTextField(lat, { lat = it.take(12) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
            label = { Text(stringResource(R.string.appointment_latitude)) }, modifier = Modifier.weight(1f).testTag("appointment_lat"))
        OutlinedTextField(lng, { lng = it.take(12) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
            label = { Text(stringResource(R.string.appointment_longitude)) }, modifier = Modifier.weight(1f).testTag("appointment_lng"))
    }
    if (invalid) Text(stringResource(R.string.appointment_form_invalid), color = MaterialTheme.colorScheme.error)
    SubmitArea(busy, key != null, failure, shownError, R.string.appointment_send_request, "appointment_send_request") {
        val start = localInstant(date, from)
        val end = localInstant(date, to)
        val latitude = lat.replace(',', '.').toDoubleOrNull()
        val longitude = lng.replace(',', '.').toDoubleOrNull()
        invalid = problem.isBlank() || address.isBlank() || start == null || end == null || !end.isAfter(start) ||
            latitude == null || longitude == null
        if (invalid) return@SubmitArea
        val requestKey = key ?: UUID.randomUUID().toString().also { key = it }
        busy = true; shownError = false; failure = null
        scope.launch {
            try {
                appointments.request(conversation.id, requestKey,
                    AppointmentRequest(problem.trim(), start!!, end!!, address.trim(), latitude!!, longitude!!))
                key = null; problem = ""; address = ""; onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = (e as? AppointmentException)?.failure
                // A definitive rejection frees the form for correction; uncertainty keeps the key.
                if (failure != null) key = null
                shownError = true
            } finally { busy = false }
        }
    }
}

@Composable
private fun ProposalForm(
    appointments: Appointments, appointment: Appointment, scope: CoroutineScope,
    known: List<Appointment>, onDone: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val requested = appointment.requestedStart.atZone(zone)
    val previous = appointment.proposals.lastOrNull()
    var scopeText by rememberSaveable(appointment.id) { mutableStateOf(previous?.scope ?: "") }
    var dateText by rememberSaveable(appointment.id) { mutableStateOf<String?>(requested.toLocalDate().toString()) }
    var start by rememberSaveable(appointment.id) { mutableStateOf(requested.toLocalTime().toString().take(5)) }
    var minutes by rememberSaveable(appointment.id) {
        mutableStateOf(Duration.between(appointment.requestedStart, appointment.requestedEnd).toMinutes().coerceIn(15, 1440).toString())
    }
    var amount by rememberSaveable(appointment.id) { mutableStateOf("") }
    var visitFee by rememberSaveable(appointment.id) { mutableStateOf("") }
    var materials by rememberSaveable(appointment.id) { mutableStateOf(previous?.materialsIncluded ?: false) }
    var travel by rememberSaveable(appointment.id) { mutableStateOf(previous?.travelIncluded ?: true) }
    var key by rememberSaveable(appointment.id) { mutableStateOf<String?>(null) }
    // The version this draft is based on; a newer server version makes it stale instead of silently overriding.
    var expected by rememberSaveable(appointment.id) { mutableIntStateOf(appointment.version) }
    val latestKnown by rememberUpdatedState(known)
    var busy by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var shownError by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppointmentFailure?>(null) }
    val date = dateText?.let(LocalDate::parse)
    LaunchedEffect(known, key) {
        if (key != null && !busy && known.any { a -> a.id == appointment.id && a.proposals.any { it.requestId == key } }) {
            key = null; onDone()
        }
    }
    val editable = key == null && !busy
    Text(appointment.problem, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.appointment_unreserved), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(scopeText, { if (it.length <= 2000) scopeText = it }, enabled = editable, minLines = 2,
        label = { Text(stringResource(R.string.appointment_scope)) }, modifier = Modifier.fillMaxWidth().testTag("proposal_scope"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.appointment_date))
        DateField(date, editable) { dateText = it.toString() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(start, { start = it.take(5) }, enabled = editable, singleLine = true,
            label = { Text(stringResource(R.string.appointment_start)) }, modifier = Modifier.weight(1f).testTag("proposal_start"))
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, enabled = editable, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(stringResource(R.string.appointment_duration)) }, modifier = Modifier.weight(1f).testTag("proposal_minutes"))
    }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    OutlinedTextField(amount, { amount = it.take(10) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
        label = { Text(stringResource(R.string.appointment_amount_input)) }, modifier = Modifier.fillMaxWidth().testTag("proposal_amount"))
    OutlinedTextField(visitFee, { visitFee = it.take(10) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
        label = { Text(stringResource(R.string.appointment_visit_fee_input)) }, modifier = Modifier.fillMaxWidth().testTag("proposal_visit_fee"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(materials, { materials = it }, enabled = editable, modifier = Modifier.testTag("proposal_materials"))
        Text(stringResource(R.string.appointment_materials_included))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(travel, { travel = it }, enabled = editable, modifier = Modifier.testTag("proposal_travel"))
        Text(stringResource(R.string.appointment_travel_included))
    }
    if (invalid) Text(stringResource(R.string.appointment_form_invalid), color = MaterialTheme.colorScheme.error)
    SubmitArea(busy, key != null, failure, shownError,
        if (previous == null) R.string.appointment_propose else R.string.appointment_propose_new, "proposal_send") {
        val startAt = localInstant(date, start)
        val duration = minutes.toIntOrNull()
        val cents = parseEuroCents(amount)
        val fee = if (visitFee.isBlank()) null else parseEuroCents(visitFee)
        invalid = scopeText.isBlank() || startAt == null || duration == null || duration !in 15..1440 || cents == null ||
            (visitFee.isNotBlank() && fee == null)
        if (invalid) return@SubmitArea
        val requestKey = key ?: UUID.randomUUID().toString().also { key = it }
        busy = true; shownError = false; failure = null
        scope.launch {
            try {
                appointments.propose(appointment.id, expected, requestKey,
                    ProposalDraft(scopeText.trim(), startAt!!, duration!!, cents!!, fee, materials, travel))
                key = null; onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = (e as? AppointmentException)?.failure
                if (failure != null) key = null
                // Rebase only after telling the user; the next send is an explicit new decision.
                if (failure == AppointmentFailure.STALE) latestKnown.firstOrNull { it.id == appointment.id }?.let { expected = it.version }
                shownError = true
            } finally { busy = false }
        }
    }
}

/** Full-screen map to place the appointment point; the typed address stays the reference. */
@Composable
private fun MapPickDialog(
    initial: es.teloarreglo.search.GeoPoint?, onDismiss: () -> Unit, onPick: (es.teloarreglo.search.GeoPoint) -> Unit,
) {
    var pin by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp).safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.map_pick_on_map), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.map_pin_hint), style = MaterialTheme.typography.bodySmall)
                MapPointPicker(initial ?: es.teloarreglo.search.ASTURIAS_CENTER, pin, onPin = { pin = it })
                Text(stringResource(R.string.map_pin_not_address), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pin?.let(onPick) }, enabled = pin != null,
                        modifier = Modifier.testTag("map_pick_confirm")) { Text(stringResource(R.string.map_pick_confirm)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}
