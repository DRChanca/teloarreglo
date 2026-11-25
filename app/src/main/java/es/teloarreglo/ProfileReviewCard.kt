package es.teloarreglo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.teloarreglo.catalogue.ProfileReview
import es.teloarreglo.catalogue.ProfileReviews
import es.teloarreglo.catalogue.ReviewSummary
import es.teloarreglo.catalogue.summary
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Result of the administrative review, read from the server each time the account opens.
 * A failed read is shown as such: it never looks like "no decision" (#29).
 */
@Composable
fun ProfileReviewCard(reviews: ProfileReviews) {
    var attempt by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<ProfileReview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        loading = true; failed = false
        try { review = reviews.mine() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { failed = true }
        loading = false
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("profile_review"),
    ) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.profile_review_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val current = review
            when {
                loading -> CircularProgressIndicator(Modifier.size(24.dp))
                failed -> {
                    Text(stringResource(R.string.profile_review_error))
                    TextButton(onClick = { attempt++ }, Modifier.testTag("profile_review_retry")) { Text(stringResource(R.string.retry)) }
                }
                current != null -> {
                    Text(stringResource(current.summary().message()), Modifier.testTag("profile_review_status"))
                    current.decidedAt?.let { Text(stringResource(R.string.profile_review_decided_at, formatDate(it))) }
                    if (current.summary() in setOf(ReviewSummary.REJECTED_FIRST, ReviewSummary.REJECTED_CHANGE)) {
                        // The administrator's reason is content: shown as written, not translated.
                        current.reason?.let { Text(stringResource(R.string.profile_review_reason, it), Modifier.testTag("profile_review_reason")) }
                    }
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.profile_review_refresh)) }
                }
            }
        }
    }
}

private fun ReviewSummary.message(): Int = when (this) {
    ReviewSummary.NOT_SUBMITTED -> R.string.profile_review_not_submitted
    ReviewSummary.PENDING_FIRST -> R.string.profile_review_pending_first
    ReviewSummary.PENDING_CHANGE -> R.string.profile_review_pending_change
    ReviewSummary.PUBLISHED -> R.string.profile_review_published
    ReviewSummary.REJECTED_FIRST -> R.string.profile_review_rejected_first
    ReviewSummary.REJECTED_CHANGE -> R.string.profile_review_rejected_change
}

private fun formatDate(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(iso)
