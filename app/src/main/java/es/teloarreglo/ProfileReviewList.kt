package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.teloarreglo.appointments.Review
import es.teloarreglo.appointments.Reviews
import kotlinx.coroutines.CancellationException

/**
 * Published reviews on the public profile (#28): stars, comment and date only, readable without a session. A failed
 * load says so instead of pretending there are no reviews.
 */
@Composable
internal fun ProfileReviewList(profileId: String, reviews: Reviews) {
    var list by remember(profileId) { mutableStateOf<List<Review>?>(null) }
    var failed by remember(profileId) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(profileId, retry) {
        failed = false
        try { list = reviews.forProfile(profileId) }
        catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("profile_reviews")) {
        Text(stringResource(R.string.review_section), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val shown = list
        when {
            failed -> Row {
                Text(stringResource(R.string.review_load_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
            }
            shown == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            shown.isEmpty() -> Text(stringResource(R.string.review_none))
            else -> shown.forEach { review ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("public_review_${review.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { ReviewBody(review) }
                }
            }
        }
    }
}
