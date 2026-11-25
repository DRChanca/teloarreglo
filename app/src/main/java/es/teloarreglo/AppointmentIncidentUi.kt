package es.teloarreglo

import androidx.compose.foundation.background
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** What the incident section needs besides the appointment: the operations and the private attachments to show. */
class IncidentTools(
    val incidents: Incidents,
    val attachments: ChatAttachments? = null,
)

/** An open incident of this conversation where the account can add chat messages, and what it already holds. */
data class EvidenceTarget(
    val conversationId: String, val incidentId: String, val problem: String, val contributed: Set<String>,
)

/**
 * Joins a chat and its appointments panel (#26): the panel publishes where messages can be added, and an incident card
 * asks the chat to start choosing them. The chat sends them; long operations run in [scope] so leaving does not cancel.
 */
@Stable
class ChatEvidenceLink(val incidents: Incidents, val scope: CoroutineScope) {
    var targets by mutableStateOf(emptyList<EvidenceTarget>())
    /** Incident whose card asked to choose messages; the chat consumes it and enters selection mode. */
    var requested by mutableStateOf<String?>(null)
}

val LocalChatEvidence = compositionLocalOf<ChatEvidenceLink?> { null }

/** The open incidents of [appointments] where [accountId] can contribute chat messages. */
internal fun evidenceTargets(conversationId: String, appointments: List<Appointment>, accountId: String) =
    appointments.filter { it.canAddEvidence(accountId) }.mapNotNull { appointment ->
        appointment.openIncident?.let { incident ->
            EvidenceTarget(conversationId, incident.id, appointment.problem, incident.evidence.mapNotNull { it.messageId }.toSet())
        }
    }

/**
 * Incidents of one appointment (#26), inside its card. Opening and contributing always show first who will see it
 * (the other participant and administration); nothing is sent without that notice accepted. An uncertain result keeps
 * its request key and locks the form, so a retry never opens a second incident or duplicates evidence. Nothing here
 * promises a refund: the review (#31) decides; delivered money is never reverted automatically.
 */
@Composable
internal fun IncidentSection(
    appointment: Appointment, accountId: String, tools: IncidentTools?, scope: CoroutineScope, onChanged: () -> Unit,
    /** Present when the chat is behind: closes the panel and lets the user select messages there. */
    onChooseMessages: ((incidentId: String) -> Unit)? = null,
) {
    appointment.incidents.forEach { IncidentCard(appointment, it, accountId, tools, scope, onChanged, onChooseMessages) }
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
internal fun incidentFailureText(failure: IncidentFailure?) = stringResource(when (failure) {
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
internal fun VisibilityNotice(accepted: Boolean, onAccepted: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(TeloTheme.spacing.small)) {
            Text(stringResource(R.string.incident_visibility), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("incident_visibility"))
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.selectable(accepted, role = Role.Checkbox, onClick = { onAccepted(!accepted) })) {
                Checkbox(accepted, onCheckedChange = null, modifier = Modifier.testTag("incident_visibility_accept"))
                Spacer(Modifier.width(TeloTheme.spacing.small))
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
    DestructiveConfirmDialog(
        title = stringResource(R.string.incident_open_title),
        confirmLabel = stringResource(if (key != null) R.string.incident_retry else R.string.incident_open_send),
        onConfirm = {
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
        },
        onDismiss = onDismiss,
        confirmTag = "incident_open_confirm",
        dismissTag = "incident_open_cancel",
        busy = busy,
        confirmEnabled = accepted && chosen != null && description.isNotBlank(),
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                kinds.forEach { option ->
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()
                        .selectable(chosen == option, enabled = key == null, role = Role.RadioButton, onClick = { kind = option })
                        .testTag("incident_kind_${option.name.lowercase()}")) {
                        RadioButton(chosen == option, onClick = null, enabled = key == null)
                        Spacer(Modifier.width(TeloTheme.spacing.small))
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
    )
}

@Composable
private fun IncidentCard(
    appointment: Appointment, incident: Incident, accountId: String, tools: IncidentTools?, scope: CoroutineScope,
    onChanged: () -> Unit, onChooseMessages: ((String) -> Unit)?,
) {
    val format = dateTimeFormat()
    var writing by rememberSaveable(incident.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = TeloTheme.spacing.extraSmall).testTag("incident_${incident.id}")) {
        Column(Modifier.padding(TeloTheme.spacing.small), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall)) {
            Text(kindText(incident.kind), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (incident.isOpen) R.string.incident_status_open else R.string.incident_status_resolved),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("incident_status_${incident.id}"))
            Text(stringResource(if (incident.openedBy == accountId) R.string.incident_opened_by_me
                else R.string.incident_opened_by_other, format.format(incident.createdAt)), style = MaterialTheme.typography.bodySmall)
            Text(incident.description)
            Text(stringResource(if (incident.performance == Performance.ACKNOWLEDGED) R.string.incident_performance_acknowledged
                else R.string.incident_performance_disputed), style = MaterialTheme.typography.bodySmall)
            // Once administration settled the money (or resolved it) the incident no longer holds anything back.
            if (!incident.blocksDelivery || (incident.isOpen && incident.settlement == null))
                Text(stringResource(if (incident.blocksDelivery) R.string.incident_blocks else R.string.incident_after_close),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("incident_effect_${incident.id}"))
            IncidentResolution(incident, format)
            Text(stringResource(R.string.incident_evidence_title, incident.evidence.size), style = MaterialTheme.typography.titleSmall)
            incident.evidence.forEach { EvidenceRow(it, accountId, tools?.attachments, format) }
            if (tools != null && incident.isOpen && appointment.canAddEvidence(accountId)) {
                // Messages are chosen inside the chat itself (long press, search); here only a written note is typed.
                if (onChooseMessages != null) {
                    Text(stringResource(R.string.incident_evidence_from_chat_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onChooseMessages(incident.id) }, modifier = Modifier.testTag("incident_add_evidence")) {
                        Text(stringResource(R.string.incident_evidence_from_chat))
                    }
                }
                OutlinedButton(onClick = { writing = true }, modifier = Modifier.testTag("incident_add_note")) {
                    Text(stringResource(R.string.incident_evidence_add_note))
                }
            }
        }
    }
    if (writing && tools != null) NoteDialog(incident, tools, scope, onDismiss = { writing = false },
        onDone = { writing = false; onChanged() })
}

/** One piece of evidence, saying plainly what it is: a chat message (who wrote it and when, quoted) or a note. */
@Composable
private fun EvidenceRow(evidence: IncidentEvidence, accountId: String, attachments: ChatAttachments?,
                        format: java.time.format.DateTimeFormatter) {
    val message = evidence.kind == EvidenceKind.MESSAGE
    Column(Modifier.fillMaxWidth().padding(start = TeloTheme.spacing.small, top = TeloTheme.spacing.extraSmall).testTag("evidence_${evidence.id}"),
        verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall)) {
        Text(stringResource(if (message) R.string.incident_evidence_kind_message else R.string.incident_evidence_kind_note),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("evidence_kind_${evidence.id}"))
        if (message) {
            val author = stringResource(if (evidence.messageAuthorId == accountId) R.string.incident_evidence_written_by_me
                else R.string.incident_evidence_written_by_other)
            Text(evidence.messageCreatedAt?.let { author + " · " + format.format(it) } ?: author,
                style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.height(IntrinsicSize.Min)) {
            // A quote bar, as in messaging apps: what follows is the content exactly as it was contributed.
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.padding(start = TeloTheme.spacing.small)) {
                when {
                    evidence.note != null -> Text(evidence.note)
                    evidence.attachmentPath != null && attachments != null ->
                        if (evidence.attachmentKind == "video") ChatVideoBubble(evidence.attachmentPath, null, attachments)
                        else ChatPhotoBubble(evidence.attachmentPath, attachments)
                    evidence.attachmentPath != null -> Text(stringResource(if (evidence.attachmentKind == "video")
                        R.string.incident_evidence_video else R.string.incident_evidence_photo))
                }
                if (message && !evidence.messageBody.isNullOrEmpty()) Text("«${evidence.messageBody}»")
            }
        }
        Text(stringResource(if (evidence.authorId == accountId) R.string.incident_evidence_by_me
            else R.string.incident_evidence_by_other, format.format(evidence.createdAt)), style = MaterialTheme.typography.labelSmall)
    }
}

/** A written note for the incident; chat messages are selected in the chat, never retyped here. */
@Composable
private fun NoteDialog(
    incident: Incident, tools: IncidentTools, scope: CoroutineScope, onDismiss: () -> Unit, onDone: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<IncidentFailure?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.incident_evidence_add_note)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                OutlinedTextField(note, { if (it.length <= 2000) note = it }, enabled = key == null,
                    label = { Text(stringResource(R.string.incident_evidence_note)) }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("evidence_note"))
                VisibilityNotice(accepted) { accepted = it }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(incidentFailureText(failure), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("evidence_error"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            val draft = note.trim().takeIf { it.isNotEmpty() }?.let { EvidenceDraft.Note(it) }
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

/** What administration decided (#31): the money and the performance, apart, each with its reason and date. */
@Composable
private fun IncidentResolution(incident: Incident, format: java.time.format.DateTimeFormatter) {
    val settlement = incident.settlement
    val decision = incident.performanceDecision
    if (settlement != null) Column(Modifier.testTag("incident_settlement_${incident.id}")) {
        val refunded = incident.settlementRefundedCents ?: 0
        val delivered = incident.settlementDeliveredCents ?: 0
        Text(when (settlement) {
            Settlement.DELIVER -> stringResource(R.string.resolution_settlement_deliver, euros(delivered))
            Settlement.REFUND -> stringResource(R.string.resolution_settlement_refund, euros(refunded))
            Settlement.SPLIT -> stringResource(R.string.resolution_settlement_split, euros(refunded), euros(delivered))
        }, style = MaterialTheme.typography.bodyMedium)
        incident.settlementReason?.let { Text(stringResource(R.string.resolution_reason, it), style = MaterialTheme.typography.bodySmall) }
        incident.settledAt?.let { Text(stringResource(R.string.resolution_decided_at, format.format(it)), style = MaterialTheme.typography.labelSmall) }
    }
    if (decision != null) Column(Modifier.testTag("incident_performance_${incident.id}")) {
        Text(stringResource(when (decision) {
            PerformanceDecision.PERFORMED -> R.string.resolution_performance_performed
            PerformanceDecision.NOT_PERFORMED -> R.string.resolution_performance_not_performed
            PerformanceDecision.MISSING_EVIDENCE -> R.string.resolution_performance_missing_evidence
        }), style = MaterialTheme.typography.bodyMedium)
        incident.performanceReason?.let { Text(stringResource(R.string.resolution_reason, it), style = MaterialTheme.typography.bodySmall) }
        incident.performanceDecidedAt?.let { Text(stringResource(R.string.resolution_decided_at, format.format(it)), style = MaterialTheme.typography.labelSmall) }
    }
    if (incident.isOpen && (settlement != null || decision != null))
        Text(stringResource(R.string.resolution_partial), style = MaterialTheme.typography.bodySmall)
}
