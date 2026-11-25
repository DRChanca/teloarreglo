package es.teloarreglo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.teloarreglo.subscription.Subscription
import es.teloarreglo.subscription.SubscriptionException
import es.teloarreglo.subscription.SubscriptionFailure
import es.teloarreglo.subscription.Subscriptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Optional 5 €/month subscription as a proposal (#33). The professional can try a simulated
 * state, but nothing is bought, renewed or invoiced and no payment data is ever requested.
 * The scene states what the free profile keeps, so the subscription never looks mandatory
 * or like a purchase of verification, and «Destacado» is shown labelled and bounded to the
 * approved coverage returned by the server.
 */
@Composable
fun SubscriptionCard(subscriptions: Subscriptions, operationScope: CoroutineScope = rememberCoroutineScope()) {
    var open by rememberSaveable { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<Subscription?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SubscriptionFailure?>(null) }
    LaunchedEffect(attempt) {
        loading = true; loadFailed = false
        try { current = subscriptions.mine() } catch (e: CancellationException) { throw e } catch (_: Exception) { loadFailed = true }
        loading = false
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = TeloTheme.extended.subscriptionContainer, contentColor = TeloTheme.extended.onSubscriptionContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("subscription"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.subscription_title), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.subscription_price, formatEuros(current?.priceCents ?: 500)),
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.subscription_proposal), style = MaterialTheme.typography.bodySmall)
            if (!open) {
                TextButton(onClick = { open = true }, Modifier.testTag("subscription_open")) {
                    Text(stringResource(R.string.subscription_open))
                }
                return@Column
            }
            Section(R.string.subscription_benefits_title, "subscription_benefits", listOf(
                R.string.subscription_benefit_stats, R.string.subscription_benefit_export,
                R.string.subscription_benefit_templates, R.string.subscription_benefit_featured))
            HorizontalDivider()
            FeaturedPreview(current)
            HorizontalDivider()
            Section(R.string.subscription_free_title, "subscription_free_keeps", listOf(
                R.string.subscription_free_search, R.string.subscription_free_contact, R.string.subscription_free_chat,
                R.string.subscription_free_agenda, R.string.subscription_free_payments, R.string.subscription_free_reviews))
            Text(stringResource(R.string.subscription_no_verification), fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            val value = current
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    loadFailed || value == null -> {
                        Text(stringResource(R.string.subscription_load_error))
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                    }
                    else -> {
                        Text(stringResource(if (value.simulated) R.string.subscription_state_simulated else R.string.subscription_state_free),
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("subscription_state"))
                        failure?.let {
                            Text(stringResource(if (it == SubscriptionFailure.ACCESS) R.string.subscription_error_access
                                else R.string.subscription_error_unconfirmed),
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("subscription_error"))
                        }
                        val toggle: () -> Unit = {
                            saving = true; failure = null
                            operationScope.launch {
                                try { current = subscriptions.simulate(!value.simulated) }
                                catch (e: CancellationException) { throw e }
                                catch (e: SubscriptionException) { failure = e.failure }
                                catch (_: Exception) { failure = SubscriptionFailure.UNCONFIRMED }
                                finally { saving = false }
                            }
                        }
                        val label = stringResource(when {
                            saving -> R.string.working
                            value.simulated -> R.string.subscription_cancel
                            else -> R.string.subscription_try
                        })
                        if (value.simulated) {
                            OutlinedButton(onClick = toggle, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("subscription_toggle")) { Text(label) }
                        } else {
                            Button(onClick = toggle, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("subscription_toggle")) { Text(label) }
                        }
                        Text(stringResource(R.string.subscription_no_charge), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int, tag: String, items: List<Int>) {
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), fontWeight = FontWeight.SemiBold)
        items.forEach { Text("• " + stringResource(it)) }
    }
}

/** The visible «Destacado» label: same look in the preview, the search results and the public profile. */
@Composable
internal fun FeaturedBadge(modifier: Modifier = Modifier, label: Int = R.string.subscription_featured_badge) {
    Text(stringResource(label),
        color = TeloTheme.extended.onFeaturedBadge, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = modifier.background(TeloTheme.extended.featuredBadge, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A labelled sample, never a real ranking: zones come from the approved coverage only. */
@Composable
private fun FeaturedPreview(subscription: Subscription?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeaturedBadge()
            Text(stringResource(R.string.subscription_featured_title), fontWeight = FontWeight.SemiBold)
        }
        val zones = subscription?.featuredZones.orEmpty()
        if (subscription != null && zones.isEmpty()) {
            Text(stringResource(R.string.subscription_no_zones), Modifier.testTag("subscription_no_zones"))
        } else if (zones.isNotEmpty()) {
            Text(stringResource(R.string.subscription_featured_zones, zones.joinToString(", ") { it.name }),
                Modifier.testTag("subscription_featured_zones"))
        }
        Text(stringResource(R.string.subscription_featured_rules), style = MaterialTheme.typography.bodySmall)
    }
}

/** 500 → "5 €" / "€5"; integer cents, no floating point in the amount itself. */
private fun formatEuros(cents: Int): String {
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        currency = Currency.getInstance("EUR")
        if (cents % 100 == 0) maximumFractionDigits = 0 else minimumFractionDigits = 2
    }
    return format.format(java.math.BigDecimal(cents).movePointLeft(2))
}
