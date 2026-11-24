package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Changes to a confirmed appointment (#23), and during the job once started (2026-11-23): then only price, scope,
 * duration and inclusions, never the start time or the address. The pending change is shown side by side with the conditions in force
 * and what accepting does with the money; only the other participant may accept or reject it. Nothing is shown
 * as applied until the server answers, and the conditions in force are always the accepted proposal.
 */
@Composable
internal fun ChangeSection(
    appointment: Appointment, accountId: String, appointments: Appointments, scope: CoroutineScope,
    onPropose: () -> Unit, onCorrection: () -> Unit, onChanged: () -> Unit,
) {
    val format = dateTimeFormat()
    val professional = appointment.professionalId == accountId
    val pending = appointment.pendingChange
    val base = appointment.confirmedProposal ?: return
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppointmentFailure?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The decision being confirmed: "accept", "accept:INTEGRATED", "accept:DIRECT" or "reject".
    var asking by remember { mutableStateOf<String?>(null) }

    fun decide(action: suspend () -> Unit) {
        busy = true; failed = false; failure = null
        scope.launch {
            // Deciding a given version is idempotent on the server: a lost answer is retried safely.
            try { action(); onChanged() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = (e as? AppointmentException)?.failure; failed = true
                if (failure.refreshesAgenda()) onChanged()
            } finally { busy = false }
        }
    }

    if (pending != null) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("change_pending")) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pending.kind == ChangeKind.ADDRESS_CORRECTION) {
                    Text(stringResource(R.string.change_correction_title, pending.version), style = MaterialTheme.typography.labelLarge)
                    pending.note?.let { Text(stringResource(R.string.change_note, it)) }
                } else {
                    Text(stringResource(R.string.change_title, pending.version), style = MaterialTheme.typography.labelLarge)
                    ChangeComparison(pending, base, format)
                    pending.note?.let { Text(stringResource(R.string.change_note, it), style = MaterialTheme.typography.bodySmall) }
                    Text(effectText(appointment.changeEffect(pending)), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("change_effect"))
                }
                Text(stringResource(R.string.change_in_force), style = MaterialTheme.typography.bodySmall)
            }
        }
        val mine = pending.authorId == accountId
        if (!mine && pending.kind == ChangeKind.CHANGE) {
            val effect = appointment.changeEffect(pending)
            if (effect is ChangeEffect.ChooseMode && !professional) {
                Button(onClick = { asking = "accept:INTEGRATED" }, enabled = !busy, modifier = Modifier.testTag("change_accept_integrated")) {
                    Text(stringResource(R.string.change_accept_integrated, euros(effect.cents)))
                }
                OutlinedButton(onClick = { asking = "accept:DIRECT" }, enabled = !busy, modifier = Modifier.testTag("change_accept_direct")) {
                    Text(stringResource(R.string.change_accept_direct, euros(effect.cents)))
                }
            } else if (effect !is ChangeEffect.ChooseMode) {
                Button(onClick = { asking = "accept" }, enabled = !busy, modifier = Modifier.testTag("change_accept")) {
                    Text(stringResource(R.string.change_accept))
                }
            }
        }
        if (!mine) OutlinedButton(onClick = { asking = "reject" }, enabled = !busy, modifier = Modifier.testTag("change_reject")) {
            Text(stringResource(if (pending.kind == ChangeKind.ADDRESS_CORRECTION) R.string.change_dismiss else R.string.change_reject))
        }
    }
    if (!appointment.startAndAddressMayChange) Text(stringResource(R.string.running_change_hint),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("running_change_hint"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPropose, enabled = !busy, modifier = Modifier.testTag("change_propose")) {
            Text(stringResource(if (!professional && pending?.kind == ChangeKind.ADDRESS_CORRECTION) R.string.change_correct_address
                else R.string.change_propose))
        }
    }
    if (appointment.canRequestAddressCorrection(accountId)) TextButton(onClick = onCorrection, enabled = !busy, modifier = Modifier.testTag("change_request_correction")) {
        Text(stringResource(R.string.change_request_correction))
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(changeFailureText(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("change_error"))
    val decided = appointment.changes.filter { it.status != ChangeStatus.PENDING }
    if (decided.isNotEmpty()) {
        Text(stringResource(R.string.change_history), style = MaterialTheme.typography.titleSmall)
        decided.asReversed().forEach { change ->
            Text(stringResource(if (change.kind == ChangeKind.ADDRESS_CORRECTION) R.string.change_correction_item
                else R.string.change_history_item, change.version, stringResource(when (change.status) {
                ChangeStatus.PENDING -> R.string.change_status_pending
                ChangeStatus.ACCEPTED -> R.string.change_status_accepted
                ChangeStatus.REJECTED -> R.string.change_status_rejected
                ChangeStatus.SUPERSEDED -> R.string.change_status_superseded
            })), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("change_history_${change.version}"))
        }
    }
    val change = pending ?: return
    asking?.let { action ->
        AlertDialog(onDismissRequest = { asking = null },
            text = { Text(stringResource(if (action == "reject") R.string.change_reject_confirm else R.string.change_accept_confirm,
                change.version)) },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    when (action) {
                        "reject" -> decide { appointments.rejectChange(change.id) }
                        else -> decide { appointments.acceptChange(change.id, action.substringAfter(':', "").takeIf { it.isNotEmpty() }
                            ?.let(PaymentMode::valueOf)) }
                    }
                }, modifier = Modifier.testTag("change_decision_confirm")) {
                    Text(stringResource(if (action == "reject") R.string.change_reject else R.string.change_accept))
                }
            })
    }
}

/** Only the fields that change, as "before → proposed", so nothing is accepted unseen. */
@Composable
private fun ChangeComparison(change: AppointmentChange, base: AppointmentProposal, format: DateTimeFormatter) {
    @Composable fun row(label: Int, before: String, after: String, tag: String) {
        Column(Modifier.testTag("change_field_$tag")) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.change_before_after, before, after), style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable fun includes(materials: Boolean?, travel: Boolean?) =
        stringResource(if (materials == true) R.string.appointment_materials_included else R.string.appointment_materials_excluded) +
            " · " + stringResource(if (travel == true) R.string.appointment_travel_included else R.string.appointment_travel_excluded)
    @Composable fun when_(start: java.time.Instant?, minutes: Int?) =
        stringResource(R.string.appointment_proposal_when, start?.let(format::format).orEmpty(), minutes ?: 0)
    @Composable fun amount(cents: Int?, fee: Int?) = euros(cents ?: 0) +
        (fee?.let { " · " + stringResource(R.string.appointment_visit_fee, euros(it)) } ?: "")
    if (change.scope != base.scope) row(R.string.change_field_scope, base.scope, change.scope.orEmpty(), "scope")
    if (change.startAt != base.startAt || change.durationMinutes != base.durationMinutes)
        row(R.string.change_field_when, when_(base.startAt, base.durationMinutes), when_(change.startAt, change.durationMinutes), "when")
    if (change.amountCents != base.amountCents || change.visitFeeCents != base.visitFeeCents)
        row(R.string.change_field_amount, amount(base.amountCents, base.visitFeeCents), amount(change.amountCents, change.visitFeeCents), "amount")
    if (change.materialsIncluded != base.materialsIncluded || change.travelIncluded != base.travelIncluded)
        row(R.string.change_field_includes, includes(base.materialsIncluded, base.travelIncluded),
            includes(change.materialsIncluded, change.travelIncluded), "includes")
    if (change.address != null) row(R.string.change_field_address, change.previousAddress.orEmpty(), change.address, "address")
}

@Composable
private fun effectText(effect: ChangeEffect): String = when (effect) {
    ChangeEffect.None -> stringResource(R.string.change_effect_none)
    is ChangeEffect.PayDifference -> stringResource(R.string.change_effect_pay, euros(effect.cents))
    is ChangeEffect.Refund -> stringResource(R.string.change_effect_refund, euros(effect.cents))
    is ChangeEffect.DirectAgreement -> stringResource(R.string.change_effect_direct, euros(effect.agreedCents))
    is ChangeEffect.ChooseMode -> stringResource(R.string.change_effect_choose, euros(effect.cents))
    is ChangeEffect.NewPayment -> stringResource(if (effect.mode == PaymentMode.INTEGRATED) R.string.change_effect_new_integrated
        else R.string.change_effect_new_direct, euros(effect.cents))
}

/**
 * New conditions, prefilled with those in force. Only the client sees the address fields; the professional may
 * only ask for a correction. A cost added to a free appointment by the client carries its payment mode.
 */
@Composable
internal fun ChangeForm(
    appointments: Appointments, appointment: Appointment, accountId: String, scope: CoroutineScope,
    known: List<Appointment>, mapsReady: Boolean, onDone: () -> Unit,
) {
    val base = appointment.confirmedProposal ?: return
    val client = appointment.clientId == accountId
    val zone = ZoneId.systemDefault()
    val exact = appointment.location as? AppointmentLocation.Exact
    var scopeText by rememberSaveable(appointment.id) { mutableStateOf(base.scope) }
    var dateText by rememberSaveable(appointment.id) { mutableStateOf<String?>(base.startAt.atZone(zone).toLocalDate().toString()) }
    var start by rememberSaveable(appointment.id) { mutableStateOf(base.startAt.atZone(zone).toLocalTime().toString().take(5)) }
    var minutes by rememberSaveable(appointment.id) { mutableStateOf(base.durationMinutes.toString()) }
    var amount by rememberSaveable(appointment.id) { mutableStateOf(centsText(base.amountCents)) }
    var visitFee by rememberSaveable(appointment.id) { mutableStateOf(base.visitFeeCents?.let(::centsText).orEmpty()) }
    var materials by rememberSaveable(appointment.id) { mutableStateOf(base.materialsIncluded) }
    var travel by rememberSaveable(appointment.id) { mutableStateOf(base.travelIncluded) }
    var changeAddress by rememberSaveable(appointment.id) {
        mutableStateOf(client && appointment.pendingChange?.kind == ChangeKind.ADDRESS_CORRECTION)
    }
    var address by rememberSaveable(appointment.id) { mutableStateOf(exact?.address.orEmpty()) }
    var lat by rememberSaveable(appointment.id) { mutableStateOf(exact?.let { "%.6f".format(Locale.ROOT, it.point.lat) }.orEmpty()) }
    var lng by rememberSaveable(appointment.id) { mutableStateOf(exact?.let { "%.6f".format(Locale.ROOT, it.point.lng) }.orEmpty()) }
    var note by rememberSaveable(appointment.id) { mutableStateOf("") }
    var mode by rememberSaveable(appointment.id) { mutableStateOf<String?>(null) }
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
        if (key != null && !busy && known.any { a -> a.id == appointment.id && a.changes.any { it.requestId == key } }) {
            key = null; onDone()
        }
    }
    val editable = key == null && !busy
    // During the job (2026-11-23) the start and the address stay as they are; only the rest can change.
    val running = !appointment.startAndAddressMayChange
    Text(appointment.problem, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(if (running) R.string.running_change_intro else R.string.change_form_intro),
        style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(scopeText, { if (it.length <= 2000) scopeText = it }, enabled = editable, minLines = 2,
        label = { Text(stringResource(R.string.appointment_scope)) }, modifier = Modifier.fillMaxWidth().testTag("change_scope"))
    if (running) Text(stringResource(R.string.running_change_start_fixed, dateTimeFormat().format(base.startAt)),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("change_start_fixed"))
    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.appointment_date))
        DateField(date, editable) { dateText = it.toString() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!running) OutlinedTextField(start, { start = it.take(5) }, enabled = editable, singleLine = true,
            label = { Text(stringResource(R.string.appointment_start)) }, modifier = Modifier.weight(1f).testTag("change_start"))
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, enabled = editable, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text(stringResource(R.string.appointment_duration)) }, modifier = Modifier.weight(1f).testTag("change_minutes"))
    }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    OutlinedTextField(amount, { amount = it.take(10) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
        label = { Text(stringResource(R.string.appointment_amount_input)) }, modifier = Modifier.fillMaxWidth().testTag("change_amount"))
    OutlinedTextField(visitFee, { visitFee = it.take(10) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
        label = { Text(stringResource(R.string.appointment_visit_fee_input)) }, modifier = Modifier.fillMaxWidth().testTag("change_visit_fee"))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(materials, { materials = it }, enabled = editable, modifier = Modifier.testTag("change_materials"))
        Text(stringResource(R.string.appointment_materials_included))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(travel, { travel = it }, enabled = editable, modifier = Modifier.testTag("change_travel"))
        Text(stringResource(R.string.appointment_travel_included))
    }
    if (client && !running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(changeAddress, { changeAddress = it }, enabled = editable, modifier = Modifier.testTag("change_address_toggle"))
            Text(stringResource(R.string.change_form_change_address))
        }
        if (changeAddress) {
            Text(stringResource(R.string.change_form_address_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(address, { if (it.length <= 300) address = it }, enabled = editable,
                label = { Text(stringResource(R.string.appointment_address)) }, modifier = Modifier.fillMaxWidth().testTag("change_address"))
            var picking by remember { mutableStateOf(false) }
            if (mapsReady) OutlinedButton(onClick = { picking = true }, enabled = editable) { Text(stringResource(R.string.map_pick_on_map)) }
            if (picking) MapPickDialog(
                initial = lat.toDoubleOrNull()?.let { a -> lng.toDoubleOrNull()?.let { b -> es.teloarreglo.search.GeoPoint(a, b) } }
                    ?.takeIf { it.valid },
                onDismiss = { picking = false },
            ) { point -> lat = "%.6f".format(Locale.ROOT, point.lat); lng = "%.6f".format(Locale.ROOT, point.lng); picking = false }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it.take(12) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
                    label = { Text(stringResource(R.string.appointment_latitude)) }, modifier = Modifier.weight(1f).testTag("change_lat"))
                OutlinedTextField(lng, { lng = it.take(12) }, enabled = editable, singleLine = true, keyboardOptions = decimal,
                    label = { Text(stringResource(R.string.appointment_longitude)) }, modifier = Modifier.weight(1f).testTag("change_lng"))
            }
        }
    }
    val cents = parseEuroCents(amount)
    val fee = if (visitFee.isBlank()) null else parseEuroCents(visitFee)
    // A client adding a cost to a free appointment says now how it will be paid.
    val needsMode = client && appointment.payment == null && maxOf(cents ?: 0, fee ?: 0) > 0
    if (needsMode) {
        Text(stringResource(R.string.change_form_mode), style = MaterialTheme.typography.bodyMedium)
        PaymentMode.entries.forEach { option ->
            Row(Modifier.selectable(selected = mode == option.name, enabled = editable, onClick = { mode = option.name })
                .testTag("change_mode_${option.name}"), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option.name, onClick = null, enabled = editable)
                Text(stringResource(if (option == PaymentMode.INTEGRATED) R.string.change_mode_integrated else R.string.change_mode_direct))
            }
        }
    }
    OutlinedTextField(note, { if (it.length <= 500) note = it }, enabled = editable,
        label = { Text(stringResource(R.string.change_form_note)) }, modifier = Modifier.fillMaxWidth().testTag("change_note"))
    if (invalid) Text(stringResource(R.string.appointment_form_invalid), color = MaterialTheme.colorScheme.error)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (shownError) Text(if (failure == AppointmentFailure.ALREADY_STARTED) changeFailureText(failure)
        else sendFailureText(failure, professional = !client), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("change_form_error"))
    Button(onClick = {
        // Once started, the start in force is sent unchanged: the server refuses any other.
        val startAt = if (running) base.startAt else localInstant(date, start)
        val duration = minutes.toIntOrNull()
        val latitude = lat.replace(',', '.').toDoubleOrNull()
        val longitude = lng.replace(',', '.').toDoubleOrNull()
        invalid = scopeText.isBlank() || startAt == null || duration == null || duration !in 15..1440 || cents == null ||
            (visitFee.isNotBlank() && fee == null) || (needsMode && mode == null) ||
            (!running && changeAddress && (address.isBlank() || latitude == null || longitude == null))
        if (invalid) return@Button
        val requestKey = key ?: UUID.randomUUID().toString().also { key = it }
        busy = true; shownError = false; failure = null
        scope.launch {
            try {
                appointments.proposeChange(appointment.id, expected, requestKey, ChangeDraft(scopeText.trim(), startAt!!, duration!!,
                    cents!!, fee, materials, travel,
                    address = if (client && !running && changeAddress) ChangedAddress(address.trim(), latitude!!, longitude!!) else null,
                    note = note.trim().ifEmpty { null }, mode = if (needsMode) mode?.let(PaymentMode::valueOf) else null))
                key = null; onDone()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = (e as? AppointmentException)?.failure
                // A definitive rejection frees the form for correction; uncertainty keeps the key.
                if (failure != null) key = null
                if (failure == AppointmentFailure.STALE) latestKnown.firstOrNull { it.id == appointment.id }?.let { expected = it.version }
                shownError = true
            } finally { busy = false }
        }
    }, enabled = !busy, modifier = Modifier.testTag("change_send")) {
        Text(stringResource(if (key != null) R.string.appointment_retry_send else R.string.change_send))
    }
}

/** The professional's request to correct the address: a note only, never a new address. */
@Composable
internal fun CorrectionForm(
    appointments: Appointments, appointment: Appointment, scope: CoroutineScope, known: List<Appointment>, onDone: () -> Unit,
) {
    var note by rememberSaveable(appointment.id) { mutableStateOf("") }
    var key by rememberSaveable(appointment.id) { mutableStateOf<String?>(null) }
    var expected by rememberSaveable(appointment.id) { mutableIntStateOf(appointment.version) }
    val latestKnown by rememberUpdatedState(known)
    var busy by remember { mutableStateOf(false) }
    var shownError by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppointmentFailure?>(null) }
    LaunchedEffect(known, key) {
        if (key != null && !busy && known.any { a -> a.id == appointment.id && a.changes.any { it.requestId == key } }) {
            key = null; onDone()
        }
    }
    Text(appointment.problem, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.change_correction_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(note, { if (it.length <= 500) note = it }, enabled = key == null && !busy, minLines = 2,
        label = { Text(stringResource(R.string.change_correction_note)) }, modifier = Modifier.fillMaxWidth().testTag("correction_note"))
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (shownError) Text(failureText(failure), color = MaterialTheme.colorScheme.error)
    Button(onClick = {
        if (note.isBlank()) return@Button
        val requestKey = key ?: UUID.randomUUID().toString().also { key = it }
        busy = true; shownError = false; failure = null
        scope.launch {
            try { appointments.requestAddressCorrection(appointment.id, expected, requestKey, note.trim()); key = null; onDone() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = (e as? AppointmentException)?.failure
                if (failure != null) key = null
                if (failure == AppointmentFailure.STALE) latestKnown.firstOrNull { it.id == appointment.id }?.let { expected = it.version }
                shownError = true
            } finally { busy = false }
        }
    }, enabled = !busy && note.isNotBlank(), modifier = Modifier.testTag("correction_send")) {
        Text(stringResource(if (key != null) R.string.appointment_retry_send else R.string.change_correction_send))
    }
}

/** A change refused because the work started names the start time and the address, not a cancellation. */
@Composable
private fun changeFailureText(failure: AppointmentFailure?): String =
    if (failure == AppointmentFailure.ALREADY_STARTED) stringResource(R.string.running_change_error_started) else failureText(failure)

/** 8550 → "85,50" in the form's input format (parseEuroCents accepts both separators). */
private fun centsText(cents: Int) = "%d,%02d".format(Locale.ROOT, cents / 100, cents % 100)
