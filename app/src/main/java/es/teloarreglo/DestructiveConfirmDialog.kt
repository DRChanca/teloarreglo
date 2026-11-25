package es.teloarreglo

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/**
 * Asks before an irreversible action (#47): cancelling, finishing or opening an incident.
 * The confirm button is in the error colour and names the action; the cancel button keeps things as they are.
 * While [busy] neither button nor a tap outside does anything, so the action is never sent twice.
 */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    text: @Composable () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.cancel),
    confirmTag: String = "destructive_confirm",
    dismissTag: String = "destructive_dismiss",
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = modifier,
        title = { Text(title) },
        text = text,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && confirmEnabled, modifier = Modifier.testTag(confirmTag),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag(dismissTag)) { Text(dismissLabel) }
        },
    )
}
