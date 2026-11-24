package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.EvidenceBatchFailure
import es.teloarreglo.appointments.addMessages
import es.teloarreglo.chat.MessageSelection
import kotlinx.coroutines.launch

/*
 * Chat side of incident evidence (#26, redesign 2026-11-23): messages are selected in the chat itself, like in
 * WhatsApp (long press, then taps), helped by a text search; the incident card only starts that mode or takes a note.
 */

internal val MessageSelectionSaver = listSaver<MessageSelection, String>(
    save = { listOf(it.active.toString()) + it.ids },
    restore = { MessageSelection(it.first().toBoolean(), it.drop(1)) },
)

/** Search row under the top bar: the query, then where the shown match is and how to move between matches. */
@Composable
internal fun ChatSearchBar(
    query: String, onQuery: (String) -> Unit, position: Int, total: Int,
    onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().testTag("chat_search_bar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { if (it.length <= 100) onQuery(it) }, singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_search)) },
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("chat_search_input"))
            IconButton(onClick = onClose, modifier = Modifier.testTag("chat_search_close")) {
                Icon(Icons.Default.Close, stringResource(R.string.chat_search_close))
            }
        }
        if (query.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (total == 0) stringResource(R.string.chat_search_none)
                else stringResource(R.string.chat_search_count, position + 1, total),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).testTag("chat_search_count"))
            IconButton(onClick = onPrevious, enabled = total > 1, modifier = Modifier.testTag("chat_search_previous")) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.chat_search_previous))
            }
            IconButton(onClick = onNext, enabled = total > 1, modifier = Modifier.testTag("chat_search_next")) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.chat_search_next))
            }
        }
    }
}

/** The message text with every match marked; the match being shown stands out more. */
@Composable
internal fun highlighted(text: String, ranges: List<IntRange>, current: Boolean): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(text)
    val colors = MaterialTheme.colorScheme
    val style = if (current) SpanStyle(background = colors.tertiary, color = colors.onTertiary)
        else SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/**
 * Confirms adding the selected messages: which incident (only asked when there are several), who will see them, and
 * then one call per message with its own stable key, so a retry after a lost answer never duplicates anything.
 * [onFinished] gets what was added and whether everything was; on a failure the dialog stays with the reason.
 */
@Composable
internal fun AddToIncidentDialog(
    link: ChatEvidenceLink, targets: List<EvidenceTarget>, preferred: String?, ids: List<String>,
    onFinished: (added: List<String>, complete: Boolean) -> Unit, onDismiss: () -> Unit,
) {
    var chosen by rememberSaveable {
        mutableStateOf((targets.firstOrNull { it.incidentId == preferred } ?: targets.singleOrNull())?.incidentId)
    }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<EvidenceBatchFailure?>(null) }
    var addedBefore by remember { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.chat_select_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.chat_select_confirm_text, ids.size), modifier = Modifier.testTag("chat_select_confirm_text"))
                if (targets.size > 1) targets.forEach { target ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .selectable(chosen == target.incidentId, enabled = !busy, role = Role.RadioButton,
                            onClick = { chosen = target.incidentId })
                        .testTag("chat_select_target_${target.incidentId}")) {
                        RadioButton(chosen == target.incidentId, onClick = null, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_select_target, target.problem), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                VisibilityNotice(accepted) { accepted = it }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                failed?.let {
                    Text(incidentFailureText(it.failure), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("chat_select_error"))
                    if (addedBefore > 0) Text(stringResource(R.string.chat_select_partial, addedBefore),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            val incidentId = chosen
            TextButton(enabled = !busy && accepted && incidentId != null && ids.isNotEmpty(), onClick = {
                busy = true; failed = null
                val sending = ids
                // Application scope: leaving the chat does not cancel calls already decided.
                link.scope.launch {
                    try {
                        val result = link.incidents.addMessages(incidentId!!, sending)
                        addedBefore += result.added.size
                        failed = result.failed
                        onFinished(result.added, result.failed == null)
                    } finally { busy = false }
                }
            }, modifier = Modifier.testTag("chat_select_confirm")) {
                Text(stringResource(if (failed != null) R.string.incident_retry else R.string.chat_select_send))
            }
        })
}
