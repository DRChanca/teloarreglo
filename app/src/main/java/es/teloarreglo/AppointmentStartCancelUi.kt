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
 * «Comenzar trabajo» and cancellation before the start (#24), inside the card of a confirmed appointment.
 * Both operations are idempotent per appointment on the server, so a lost answer is retried with the same call and
 * never refunds twice. The money effect of a cancellation is shown before confirming; after the start there is no
 * cancellation button at all, only the explanation that a problem goes through an incident.
 */
@Composable
internal fun StartCancelSection(
    appointment: Appointment, accountId: String, appointments: Appointments, scope: CoroutineScope, onChanged: () -> Unit,
) {
    val format = dateTimeFormat()
    var askStart by remember { mutableStateOf(false) }
    var askCancel by remember { mutableStateOf(false) }
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

    appointment.startedAt?.let {
        Text(stringResource(R.string.start_cancel_started_at, format.format(it)), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("started_mark_${appointment.id}"))
        if (appointment.stage == AppointmentStage.STARTED)
            Text(stringResource(R.string.start_cancel_after_start), style = MaterialTheme.typography.bodySmall)
    }
    appointment.cancelledAt?.let {
        Text(stringResource(if (appointment.cancelledBy == accountId) R.string.start_cancel_cancelled_by_me
            else R.string.start_cancel_cancelled_by_other, format.format(it)), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag("cancelled_mark_${appointment.id}"))
    }
    if (appointment.canStart(accountId)) Button(onClick = { askStart = true }, enabled = !busy,
        modifier = Modifier.testTag("start_work")) { Text(stringResource(R.string.start_cancel_start)) }
    if (appointment.canCancel(accountId)) OutlinedButton(onClick = { askCancel = true }, enabled = !busy,
        modifier = Modifier.testTag("cancel_appointment")) { Text(stringResource(R.string.start_cancel_cancel)) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(failureText(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("start_cancel_error"))

    if (askStart) AlertDialog(onDismissRequest = { askStart = false },
        title = { Text(stringResource(R.string.start_cancel_start_title)) },
        text = { Text(stringResource(R.string.start_cancel_start_explain)) },
        dismissButton = { TextButton(onClick = { askStart = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(onClick = { askStart = false; run { appointments.startWork(appointment.id) } },
                modifier = Modifier.testTag("start_work_confirm")) { Text(stringResource(R.string.start_cancel_start)) }
        })
    if (askCancel) DestructiveConfirmDialog(
        title = stringResource(R.string.start_cancel_cancel_title),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("cancel_effect")) {
                Text(when (val effect = appointment.cancellationEffect) {
                    is CancellationEffect.Refund -> stringResource(R.string.start_cancel_effect_refund, euros(effect.cents))
                    is CancellationEffect.DirectNotRefunded ->
                        stringResource(R.string.start_cancel_effect_direct, euros(effect.agreedCents))
                    CancellationEffect.None -> stringResource(R.string.start_cancel_effect_none)
                })
                Text(stringResource(R.string.start_cancel_release))
            }
        },
        confirmLabel = stringResource(R.string.start_cancel_cancel),
        onConfirm = { askCancel = false; run { appointments.cancel(appointment.id) } },
        onDismiss = { askCancel = false },
        dismissLabel = stringResource(R.string.start_cancel_keep),
        confirmTag = "cancel_appointment_confirm", dismissTag = "cancel_appointment_dismiss")
}
