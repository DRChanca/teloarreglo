package es.teloarreglo

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/**
 * Asks before an irreversible action that loses something (#47): cancelling, rejecting or opening an incident.
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
) = ConfirmDialog(title = title, text = text, confirmLabel = confirmLabel, onConfirm = onConfirm, onDismiss = onDismiss,
    modifier = modifier, dismissLabel = dismissLabel, confirmTag = confirmTag, dismissTag = dismissTag, busy = busy,
    confirmEnabled = confirmEnabled, destructive = true)

/**
 * The same irreversible confirmation as [DestructiveConfirmDialog] for a positive action, such as confirming that
 * the work is done (#65): the confirm button keeps the primary colour of the other positive actions.
 */
@Composable
fun PositiveConfirmDialog(
    title: String,
    text: @Composable () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.cancel),
    confirmTag: String = "positive_confirm",
    dismissTag: String = "positive_dismiss",
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
) = ConfirmDialog(title = title, text = text, confirmLabel = confirmLabel, onConfirm = onConfirm, onDismiss = onDismiss,
    modifier = modifier, dismissLabel = dismissLabel, confirmTag = confirmTag, dismissTag = dismissTag, busy = busy,
    confirmEnabled = confirmEnabled, destructive = false)

@Composable
private fun ConfirmDialog(
    title: String,
    text: @Composable () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
    dismissLabel: String,
    confirmTag: String,
    dismissTag: String,
    busy: Boolean,
    confirmEnabled: Boolean,
    destructive: Boolean,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = modifier,
        title = { Text(title) },
        text = text,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && confirmEnabled, modifier = Modifier.testTag(confirmTag),
                colors = ButtonDefaults.textButtonColors(contentColor = confirmActionColor(MaterialTheme.colorScheme, destructive))) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag(dismissTag)) { Text(dismissLabel) }
        },
    )
}

/** Red only for actions that lose something; a positive confirmation uses the primary colour (#65). */
internal fun confirmActionColor(scheme: ColorScheme, destructive: Boolean): Color =
    if (destructive) scheme.error else scheme.primary
