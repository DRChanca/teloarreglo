package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.*
import es.teloarreglo.chat.ChatAttachments
import es.teloarreglo.chat.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.UUID

/** What the incident section needs besides the appointment: the operations and the conversation to pick from. */
class IncidentTools(
    val incidents: Incidents,
    /** The appointment's conversation, read with the participant's own access, to choose concrete messages. */
    val messages: suspend (conversationId: String) -> List<ChatMessage>,
    val attachments: ChatAttachments? = null,
)

/**
 * Incidents of one appointment (#26), inside its card. Opening and contributing always show first who will see it
 * (the other participant and administration); nothing is sent without that notice accepted. An uncertain result keeps
 * its request key and locks the form, so a retry never opens a second incident or duplicates evidence. Nothing here
 * promises a refund: the review (#31) decides; delivered money is never reverted automatically.
 */
@Composable
internal fun IncidentSection(
    appointment: Appointment, accountId: String, tools: IncidentTools?, scope: CoroutineScope, onChanged: () -> Unit,
) {
    appointment.incidents.forEach { IncidentCard(appointment, it, accountId, tools, scope, onChanged) }
    if (tools == null || !appointment.canOpenIncident(accountId)) return
    var opening by rememberSaveable(appointment.id) { mutableStateOf(false) }
    if (appointment.stage == AppointmentStage.STARTED)
        Text(stringResource(R.string.incident_started_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { opening = true }, modifier = Modifier.testTag("incident_open")) {
        Text(stringResource(R.string.incident_open))
    }
    if (opening) OpenIncidentDialog(appointment, tools.incidents, scope, onDismiss = { opening = false },
        onDone = { opening = false; onChanged() })
}

@Composable
private fun kindText(kind: IncidentKind) = stringResource(when (kind) {
    IncidentKind.CANCELLATION -> R.string.incident_kind_cancellation
    IncidentKind.QUALITY -> R.string.incident_kind_quality
    IncidentKind.NOT_PERFORMED -> R.string.incident_kind_not_performed
})

@Composable
private fun kindExplain(kind: IncidentKind) = stringResource(when (kind) {
    IncidentKind.CANCELLATION -> R.string.incident_kind_cancellation_explain
    IncidentKind.QUALITY -> R.string.incident_kind_quality_explain
    IncidentKind.NOT_PERFORMED -> R.string.incident_kind_not_performed_explain
})

@Composable
private fun incidentFailureText(failure: IncidentFailure?) = stringResource(when (failure) {
    IncidentFailure.ACCESS -> R.string.appointment_error_access
    IncidentFailure.INVALID -> R.string.incident_error_invalid
    IncidentFailure.STALE -> R.string.appointment_error_stale
    IncidentFailure.ALREADY_OPEN -> R.string.incident_error_already_open
    IncidentFailure.CLOSED -> R.string.incident_error_closed
    IncidentFailure.RETRY_CONFLICT -> R.string.appointment_error_conflict
    null -> R.string.appointment_error_network
})

/** Who will see what is sent, and the explicit acceptance required before sending it. */
@Composable
private fun VisibilityNotice(accepted: Boolean, onAccepted: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(8.dp)) {
            Text(stringResource(R.string.incident_visibility), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("incident_visibility"))
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.selectable(accepted, role = Role.Checkbox, onClick = { onAccepted(!accepted) })) {
                Checkbox(accepted, onCheckedChange = null, modifier = Modifier.testTag("incident_visibility_accept"))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.incident_visibility_accept), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OpenIncidentDialog(
    appointment: Appointment, incidents: Incidents, scope: CoroutineScope, onDismiss: () -> Unit, onDone: () -> Unit,
) {
    val kinds = appointment.incidentKinds
    var kind by rememberSaveable { mutableStateOf<IncidentKind?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    // While a result is uncertain the key is kept and the content locked: a retry returns the same incident.
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<IncidentFailure?>(null) }
    val chosen = kind
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.incident_open_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                kinds.forEach { option ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()
                        .selectable(chosen == option, enabled = key == null, role = Role.RadioButton, onClick = { kind = option })
                        .testTag("incident_kind_${option.name.lowercase()}")) {
                        RadioButton(chosen == option, onClick = null, enabled = key == null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(kindText(option), style = MaterialTheme.typography.bodyMedium)
                            Text(kindExplain(option), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(description, { if (it.length <= 2000) description = it }, enabled = key == null,
                    label = { Text(stringResource(R.string.incident_description)) }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("incident_description"))
                Text(stringResource(if (appointment.stage == AppointmentStage.COMPLETED) R.string.incident_effect_after_close
                    else R.string.incident_effect_blocks), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("incident_effect"))
                VisibilityNotice(accepted) { accepted = it }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(incidentFailureText(failure), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("incident_error"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = !busy && accepted && chosen != null && description.isNotBlank(), onClick = {
                val sent = key ?: UUID.randomUUID().toString().also { key = it }
                busy = true; failed = false
                scope.launch {
                    try { incidents.open(appointment.id, sent, chosen!!, description.trim()); onDone() }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failure = (e as? IncidentException)?.failure; failed = true
                        // A definitive answer frees the key; uncertainty keeps it so a retry cannot open twice.
                        if (failure != null) key = null
                    } finally { busy = false }
                }
            }, modifier = Modifier.testTag("incident_open_confirm")) {
                Text(stringResource(if (key != null) R.string.incident_retry else R.string.incident_open_send))
            }
        })
}

@Composable
private fun IncidentCard(
    appointment: Appointment, incident: Incident, accountId: String, tools: IncidentTools?, scope: CoroutineScope,
    onChanged: () -> Unit,
) {
    val format = dateTimeFormat()
    var adding by rememberSaveable(incident.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("incident_${incident.id}")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kindText(incident.kind), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (incident.isOpen) R.string.incident_status_open else R.string.incident_status_resolved),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("incident_status_${incident.id}"))
            Text(stringResource(if (incident.openedBy == accountId) R.string.incident_opened_by_me
                else R.string.incident_opened_by_other, format.format(incident.createdAt)), style = MaterialTheme.typography.bodySmall)
            Text(incident.description)
            Text(stringResource(if (incident.performance == Performance.ACKNOWLEDGED) R.string.incident_performance_acknowledged
                else R.string.incident_performance_disputed), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (incident.blocksDelivery) R.string.incident_blocks else R.string.incident_after_close),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("incident_effect_${incident.id}"))
            Text(stringResource(R.string.incident_evidence_title, incident.evidence.size), style = MaterialTheme.typography.titleSmall)
            incident.evidence.forEach { EvidenceRow(it, accountId, tools?.attachments, format) }
            if (tools != null && incident.isOpen && appointment.canAddEvidence(accountId))
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag("incident_add_evidence")) {
                    Text(stringResource(R.string.incident_add_evidence))
                }
        }
    }
    if (adding && tools != null) EvidenceDialog(appointment, incident, tools, scope, onDismiss = { adding = false },
        onDone = { adding = false; onChanged() })
}

@Composable
private fun EvidenceRow(evidence: IncidentEvidence, accountId: String, attachments: ChatAttachments?,
                        format: java.time.format.DateTimeFormatter) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp).testTag("evidence_${evidence.id}")) {
        Text(stringResource(if (evidence.authorId == accountId) R.string.incident_evidence_by_me
            else R.string.incident_evidence_by_other, format.format(evidence.createdAt)), style = MaterialTheme.typography.labelSmall)
        when {
            evidence.note != null -> Text(evidence.note)
            evidence.attachmentPath != null && attachments != null ->
                if (evidence.attachmentKind == "video") ChatVideoBubble(evidence.attachmentPath, null, attachments)
                else ChatPhotoBubble(evidence.attachmentPath, attachments)
            evidence.attachmentPath != null -> Text(stringResource(if (evidence.attachmentKind == "video")
                R.string.incident_evidence_video else R.string.incident_evidence_photo))
            else -> Text(stringResource(R.string.incident_evidence_message, evidence.messageBody.orEmpty()))
        }
    }
}

@Composable
private fun EvidenceDialog(
    appointment: Appointment, incident: Incident, tools: IncidentTools, scope: CoroutineScope,
    onDismiss: () -> Unit, onDone: () -> Unit,
) {
    var messages by remember { mutableStateOf<List<ChatMessage>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(appointment.conversationId, reload) {
        loadFailed = false
        try { messages = tools.messages(appointment.conversationId) }
        catch (e: Exception) { if (e is CancellationException) throw e; loadFailed = true }
    }
    var noteMode by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<IncidentFailure?>(null) }
    val contributed = incident.evidence.mapNotNull { it.messageId }.toSet()
    val format = dateTimeFormat()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.incident_add_evidence)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!noteMode, onClick = { if (key == null) noteMode = false },
                        label = { Text(stringResource(R.string.incident_evidence_choose_message)) },
                        modifier = Modifier.testTag("evidence_mode_message"))
                    FilterChip(noteMode, onClick = { if (key == null) noteMode = true },
                        label = { Text(stringResource(R.string.incident_evidence_choose_note)) },
                        modifier = Modifier.testTag("evidence_mode_note"))
                }
                if (noteMode) OutlinedTextField(note, { if (it.length <= 2000) note = it }, enabled = key == null,
                    label = { Text(stringResource(R.string.incident_evidence_note)) }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("evidence_note"))
                else {
                    Text(stringResource(R.string.incident_evidence_pick_explain), style = MaterialTheme.typography.bodySmall)
                    if (loadFailed) TextButton(onClick = { reload++ }) { Text(stringResource(R.string.incident_evidence_load_error)) }
                    else if (messages == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (messages!!.isEmpty()) Text(stringResource(R.string.incident_evidence_no_messages))
                    messages.orEmpty().asReversed().forEach { message ->
                        val already = message.id in contributed
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .selectable(chosen == message.id, enabled = !already && key == null, role = Role.RadioButton,
                                onClick = { chosen = message.id })
                            .testTag("evidence_pick_${message.id}")) {
                            RadioButton(chosen == message.id, onClick = null, enabled = !already && key == null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(when (message.attachmentKind) {
                                    "photo" -> stringResource(R.string.incident_evidence_photo)
                                    "video" -> stringResource(R.string.incident_evidence_video)
                                    else -> message.body
                                }, maxLines = 3)
                                Text((runCatching { format.format(OffsetDateTime.parse(message.createdAt)) }.getOrNull() ?: "") +
                                    if (already) " · " + stringResource(R.string.incident_evidence_already) else "",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                VisibilityNotice(accepted) { accepted = it }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(incidentFailureText(failure), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("evidence_error"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            val draft = if (noteMode) note.trim().takeIf { it.isNotEmpty() }?.let { EvidenceDraft.Note(it) }
                else chosen?.let { EvidenceDraft.Message(it) }
            TextButton(enabled = !busy && accepted && draft != null, onClick = {
                val sent = key ?: UUID.randomUUID().toString().also { key = it }
                busy = true; failed = false
                scope.launch {
                    try { tools.incidents.addEvidence(incident.id, sent, draft!!); onDone() }
                    catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failure = (e as? IncidentException)?.failure; failed = true
                        if (failure != null) key = null
                    } finally { busy = false }
                }
            }, modifier = Modifier.testTag("evidence_send")) {
                Text(stringResource(if (key != null) R.string.incident_retry else R.string.incident_evidence_send))
            }
        })
}
