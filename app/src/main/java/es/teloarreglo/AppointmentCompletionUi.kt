package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * «Realizado» and the client's manual confirmation (#25), inside the card of a started or finished appointment.
 * Both operations are idempotent per appointment on the server, so a lost answer is retried with the same call and
 * never delivers twice. Nothing is shown as done or completed until the server answers; the deadline shown is the
 * server's, counted from the mark, never the phone's clock. The money effect is shown before confirming.
 */
@Composable
internal fun CompletionSection(
    appointment: Appointment, accountId: String, appointments: Appointments, scope: CoroutineScope, onChanged: () -> Unit,
) {
    val format = dateTimeFormat()
    var askMark by remember { mutableStateOf(false) }
    var askConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AppointmentFailure?>(null) }

    fun run(action: suspend () -> Unit) {
        busy = true; failed = false
        scope.launch {
            try { action(); onChanged() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = (e as? AppointmentException)?.failure; failed = true
                // The other participant may have acted first: show the server state.
                if (failure != null) onChanged()
            } finally { busy = false }
        }
    }

    appointment.doneAt?.let {
        Text(stringResource(R.string.completion_done_at, format.format(it)), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("done_mark_${appointment.id}"))
    }
    val due = appointment.silentCloseAt
    if (due != null) Text(
        stringResource(if (accountId == appointment.clientId) R.string.completion_deadline_client else R.string.completion_deadline_pro,
            format.format(due)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("done_deadline_${appointment.id}"))
    appointment.completedAt?.let {
        Text(stringResource(when {
            appointment.completion == Completion.NO_ANSWER -> R.string.completion_completed_no_answer
            appointment.completion == Completion.ADMINISTRATION -> R.string.resolution_completed_at
            accountId == appointment.clientId -> R.string.completion_completed_by_me
            else -> R.string.completion_completed_by_client
        }, format.format(it)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("completed_mark_${appointment.id}"))
        appointment.payment?.takeIf { p -> p.mode == PaymentMode.INTEGRATED && p.deliveredCents > 0 }?.let { p ->
            Text(stringResource(R.string.completion_delivered, euros(p.deliveredCents)), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("delivered_${appointment.id}"))
        }
    }
    if (appointment.canMarkDone(accountId)) Button(onClick = { askMark = true }, enabled = !busy,
        modifier = Modifier.testTag("mark_done")) { Text(stringResource(R.string.completion_mark)) }
    if (appointment.canConfirmDone(accountId)) Button(onClick = { askConfirm = true }, enabled = !busy,
        modifier = Modifier.testTag("confirm_done")) { Text(stringResource(R.string.completion_confirm)) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(failureText(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("completion_error"))

    if (askMark) AlertDialog(onDismissRequest = { askMark = false },
        title = { Text(stringResource(R.string.completion_mark_title)) },
        text = { Text(stringResource(R.string.completion_mark_explain)) },
        dismissButton = { TextButton(onClick = { askMark = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { askMark = false; run { appointments.markDone(appointment.id) } },
                modifier = Modifier.testTag("mark_done_confirm")) { Text(stringResource(R.string.completion_mark)) }
        })
    if (askConfirm) AlertDialog(onDismissRequest = { askConfirm = false },
        title = { Text(stringResource(R.string.completion_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("completion_effect")) {
                Text(when (val effect = appointment.completionEffect) {
                    is CompletionEffect.Deliver -> stringResource(R.string.completion_effect_deliver, euros(effect.cents))
                    is CompletionEffect.DirectNotHandled -> stringResource(R.string.completion_effect_direct, euros(effect.agreedCents))
                    CompletionEffect.None -> stringResource(R.string.completion_effect_none)
                })
                Text(stringResource(R.string.completion_final))
            }
        },
        dismissButton = { TextButton(onClick = { askConfirm = false }) { Text(stringResource(R.string.completion_not_yet)) } },
        confirmButton = {
            TextButton(onClick = { askConfirm = false; run { appointments.confirmDone(appointment.id) } },
                modifier = Modifier.testTag("confirm_done_confirm")) { Text(stringResource(R.string.completion_confirm)) }
        })
}
