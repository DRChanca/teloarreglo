package es.teloarreglo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

private const val MAX_COMMENT = 2000

/**
 * The review of a performed appointment (#28), inside its card. Whether it can be written is the server's answer
 * ([state]); the app only explains it. Nothing is shown as published until the server answers, and an uncertain
 * result keeps its key with the content locked so a retry never publishes a second review.
 */
@Composable
internal fun ReviewSection(
    appointment: Appointment, accountId: String, state: AppointmentReviewState?, reviews: Reviews?, scope: CoroutineScope,
    onChanged: () -> Unit,
) {
    if (reviews == null || state == null) return
    val client = accountId == appointment.clientId
    var writing by remember { mutableStateOf(false) }
    val review = state.review
    when {
        review != null -> Column(Modifier.testTag("review_${appointment.id}")) {
            Text(stringResource(if (client) R.string.review_published_mine else R.string.review_published_client),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            ReviewBody(review)
        }
        !client -> Unit
        state.canPublish -> Button(onClick = { writing = true }, modifier = Modifier.testTag("review_write")) {
            Text(stringResource(R.string.review_publish))
        }
        else -> reasonText(state.state)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("review_reason_${appointment.id}"))
        }
    }
    if (writing) ReviewDialog(appointment, reviews, scope, onDismiss = { writing = false }, onDone = { writing = false; onChanged() })
}

@Composable
private fun reasonText(state: ReviewState): String? = when (state) {
    ReviewState.AWAITING_CONFIRMATION -> stringResource(R.string.review_awaiting_confirmation)
    ReviewState.PERFORMANCE_DISPUTED -> stringResource(R.string.review_disputed)
    ReviewState.NOT_PERFORMED -> stringResource(R.string.review_not_performed)
    else -> null
}

/** Stars, comment and date: what the public profile shows too. The comment keeps its original language. */
@Composable
internal fun ReviewBody(review: Review) {
    val stars = stringResource(R.string.review_stars, review.stars)
    RatingStarRow(RatingStars(full = review.stars.coerceIn(0, 5), half = false), MaterialTheme.colorScheme.primary,
        Modifier.semantics(mergeDescendants = true) { contentDescription = stars })
    Text(review.comment, style = MaterialTheme.typography.bodyMedium)
    Text(dateTimeFormat().format(review.createdAt), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun reviewFailureText(failure: ReviewFailure?) = when (failure) {
    ReviewFailure.ACCESS -> R.string.appointment_error_access
    ReviewFailure.INVALID -> R.string.review_error_invalid
    ReviewFailure.RETRY_CONFLICT -> R.string.appointment_error_conflict
    ReviewFailure.ALREADY_REVIEWED -> R.string.review_error_exists
    ReviewFailure.NOT_ENABLED -> R.string.review_awaiting_confirmation
    ReviewFailure.PERFORMANCE_DISPUTED -> R.string.review_disputed
    ReviewFailure.NOT_PERFORMED -> R.string.review_not_performed
    null -> R.string.review_error_network
}

@Composable
private fun ReviewDialog(appointment: Appointment, reviews: Reviews, scope: CoroutineScope, onDismiss: () -> Unit, onDone: () -> Unit) {
    var stars by rememberSaveable { mutableIntStateOf(0) }
    var comment by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var key by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ReviewFailure?>(null) }
    val locked = key != null || busy
    val valid = stars in 1..5 && comment.isNotBlank() && comment.length <= MAX_COMMENT && accepted
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Equal-width slots: the stars keep the same distance however narrow the dialog is (#66).
                Row(Modifier.fillMaxWidth()) {
                    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (locked) 0.38f else 1f)
                    for (n in 1..5) {
                        val label = stringResource(R.string.review_stars, n)
                        Box(Modifier.weight(1f).heightIn(min = 48.dp)
                            .clickable(enabled = !locked, role = Role.Button) { stars = n }
                            .testTag("review_star_$n").semantics { contentDescription = label },
                            contentAlignment = Alignment.Center) {
                            Star(if (n <= stars) StarFill.FULL else StarFill.EMPTY, color, 28.dp)
                        }
                    }
                }
                OutlinedTextField(comment, { if (it.length <= MAX_COMMENT) comment = it }, enabled = !locked,
                    label = { Text(stringResource(R.string.review_comment)) }, minLines = 3,
                    supportingText = { Text("${comment.length}/$MAX_COMMENT") },
                    modifier = Modifier.fillMaxWidth().testTag("review_comment"))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(8.dp)) {
                        Text(stringResource(R.string.review_privacy), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("review_privacy"))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectable(accepted,
                            enabled = !locked, role = Role.Checkbox, onClick = { accepted = !accepted })) {
                            Checkbox(accepted, onCheckedChange = null, enabled = !locked,
                                modifier = Modifier.testTag("review_privacy_accept"))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.review_privacy_accept), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(reviewFailureText(failure)), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("review_error"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = valid && !busy, modifier = Modifier.testTag("review_send"), onClick = {
                val requestKey = key ?: UUID.randomUUID().toString().also { key = it }
                busy = true; failed = false
                scope.launch {
                    try {
                        reviews.publish(appointment.id, requestKey, stars, comment.trim())
                        key = null; onDone()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failure = (e as? ReviewException)?.failure; failed = true
                        // A refusal is final for this key; only an uncertain result keeps it for the retry.
                        if (failure != null) key = null
                    } finally { busy = false }
                }
            }) { Text(stringResource(if (key != null) R.string.review_retry else R.string.review_send)) }
        })
}
