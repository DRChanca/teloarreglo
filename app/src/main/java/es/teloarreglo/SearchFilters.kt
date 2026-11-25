package es.teloarreglo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Steps of «Distancia máxima» in km; null is «Sin límite», the default (#52). */
internal val distanceSteps = listOf(2, 5, 10, 25, 50, null)

/** Options of «Valoración mínima» in stars; null is «Cualquiera», the default (#52). */
internal val ratingSteps = listOf(null, 3, 4)

/**
 * Search filters of the session (#52). Only the device applies them, over the list already ordered by the
 * server (ADR 0001); they remove profiles and never change the order. The trade lives apart: the server asks
 * for it, and the chips and the sheet share it.
 */
internal data class SearchFilters(
    /** «Distancia máxima» in km, one of [distanceSteps]; null is «Sin límite». */
    val maxDistanceKm: Int? = null,
    /** «Valoración mínima» in stars, one of [ratingSteps]; null is «Cualquiera». */
    val minRating: Int? = null,
)

/**
 * The rule of #52, input order kept:
 * - distance: the band (the one the card shows) within the limit; a row without a band (no registered
 *   location) does not pass an active limit; without a search point there are no bands and no limit;
 * - rating: the review average at least the minimum; a profile without reviews does not pass an active minimum.
 */
internal fun List<SearchRow>.filteredBy(filters: SearchFilters, hasPoint: Boolean): List<SearchRow> = filter { row ->
    val maxDistanceM = filters.maxDistanceKm.takeIf { hasPoint }?.let { it * 1000 }
    val rating = row.profile.rating.takeIf { row.profile.reviewCount > 0 }
    (maxDistanceM == null || (row.bandM != null && row.bandM <= maxDistanceM)) &&
        (filters.minRating == null || (rating != null && rating >= filters.minRating))
}

/** Filters away from their default, the trade included; a distance limit counts only with a search point. */
internal fun SearchFilters.activeCount(trade: String?, hasPoint: Boolean): Int =
    listOfNotNull(trade, maxDistanceKm.takeIf { hasPoint }, minRating).size

/**
 * «Filtros» bottom sheet: changes are provisional until «Ver N profesionales». [countFor] gives N for a trade
 * and filters, or null when it cannot be known on the device (another trade than the one the list holds).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FiltersSheet(
    trade: String?,
    filters: SearchFilters,
    hasPoint: Boolean,
    countFor: (trade: String?, filters: SearchFilters) -> Int?,
    onApply: (trade: String?, filters: SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftTrade by remember(trade) { mutableStateOf(trade) }
    var draft by remember(filters) { mutableStateOf(filters) }
    val spacing = TeloTheme.spacing
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = spacing.extraLarge)
                .padding(bottom = spacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            SheetSection(R.string.filters_trade)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                FilterChip(selected = draftTrade == null, onClick = { draftTrade = null },
                    modifier = Modifier.testTag("filters_trade_all"), label = { Text(stringResource(R.string.trade_all)) })
                catalogueTrades.forEach { key ->
                    FilterChip(selected = draftTrade == key, onClick = { draftTrade = key },
                        modifier = Modifier.testTag("filters_trade_$key"), label = { Text(stringResource(tradeLabel(key))) })
                }
            }
            SheetSection(R.string.filters_distance)
            val maxDistanceKm = draft.maxDistanceKm.takeIf { hasPoint }
            Text(distanceStepLabel(maxDistanceKm), Modifier.testTag("filters_distance_value"))
            Slider(
                value = distanceSteps.indexOf(maxDistanceKm).toFloat(),
                onValueChange = { draft = draft.copy(maxDistanceKm = distanceSteps[it.roundToInt()]) },
                valueRange = 0f..(distanceSteps.size - 1).toFloat(), steps = distanceSteps.size - 2,
                enabled = hasPoint, modifier = Modifier.fillMaxWidth().testTag("filters_distance"),
            )
            if (!hasPoint) Text(stringResource(R.string.filters_distance_needs_point),
                Modifier.testTag("filters_distance_needs_point"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SheetSection(R.string.filters_rating)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                ratingSteps.forEach { stars ->
                    FilterChip(selected = draft.minRating == stars, onClick = { draft = draft.copy(minRating = stars) },
                        modifier = Modifier.testTag("filters_rating_${stars ?: "any"}"),
                        label = { Text(if (stars == null) stringResource(R.string.filters_rating_any)
                            else stringResource(R.string.filters_rating_min, stars)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draftTrade = null; draft = SearchFilters() }, Modifier.testTag("filters_reset")) {
                    Text(stringResource(R.string.filters_reset))
                }
                val count = countFor(draftTrade, draft)
                Button(onClick = { onApply(draftTrade, draft) }, Modifier.weight(1f).testTag("filters_apply")) {
                    Text(if (count == null) stringResource(R.string.filters_apply_unknown)
                        else pluralStringResource(R.plurals.filters_apply, count, count))
                }
            }
        }
    }
}

@Composable
private fun SheetSection(label: Int) {
    Text(stringResource(label), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** Keeps the filters across a profile and back, and configuration changes; never in the account. Null stays null. */
internal val SearchFiltersSaver = listSaver<SearchFilters, Int?>(
    save = { listOf(it.maxDistanceKm, it.minRating) },
    restore = { SearchFilters(maxDistanceKm = it[0], minRating = it[1]) },
)

/** «Filtros», or «Filtros · N» with the number of filters away from their default (the trade counts). */
@Composable
internal fun FiltersButton(active: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag("filters_open")) {
        Text(if (active == 0) stringResource(R.string.filters_button) else stringResource(R.string.filters_button_active, active))
    }
}

/** Filters left nobody: say so, with an illustration and «Restablecer filtros», never a bare empty list. */
@Composable
internal fun FiltersEmpty(onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(TeloTheme.spacing.small), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.medium)) {
        NoMatchesArtwork(Modifier.size(160.dp))
        Text(stringResource(R.string.filters_empty), Modifier.testTag("filters_empty"),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Button(onClick = onReset, Modifier.testTag("filters_empty_reset")) { Text(stringResource(R.string.filters_empty_reset)) }
    }
}

/**
 * A magnifier over an empty card, drawn like the trade illustrations (ProfileCover): no photo, no network.
 * Its colours are theme roles, so it follows dark mode.
 */
@Composable
private fun NoMatchesArtwork(modifier: Modifier) {
    val description = stringResource(R.string.filters_empty_artwork)
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    val card = TeloTheme.extended.card
    Canvas(modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.secondaryContainer)
        .semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val unit = minOf(w, h)
        drawCircle(accent.copy(alpha = .12f), unit * .46f, Offset(w * .56f, h * .48f))
        drawCircle(card.copy(alpha = .65f), unit * .26f, Offset(w * .30f, h * .26f))
        drawRoundRect(accent.copy(alpha = .16f), Offset(w * .16f, h * .80f), Size(w * .68f, h * .07f), CornerRadius(unit * .04f))
        // An empty result card behind the lens.
        drawRoundRect(card, Offset(w * .20f, h * .30f), Size(w * .46f, h * .36f), CornerRadius(unit * .04f))
        drawLine(accent.copy(alpha = .35f), Offset(w * .27f, h * .42f), Offset(w * .56f, h * .42f), unit * .035f)
        drawLine(accent.copy(alpha = .25f), Offset(w * .27f, h * .52f), Offset(w * .48f, h * .52f), unit * .035f)
        // The magnifier.
        drawLine(ink, Offset(w * .66f, h * .64f), Offset(w * .80f, h * .78f), unit * .07f)
        drawCircle(card.copy(alpha = .55f), unit * .15f, Offset(w * .58f, h * .54f))
        drawCircle(ink, unit * .15f, Offset(w * .58f, h * .54f), style = Stroke(unit * .045f))
    }
}

@Composable
private fun distanceStepLabel(maxDistanceKm: Int?): String =
    if (maxDistanceKm == null) stringResource(R.string.filters_distance_none)
    else stringResource(R.string.filters_distance_km, maxDistanceKm)
