package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
internal data class SearchFilters(val maxKm: Int? = null, val minRating: Int? = null)

/**
 * The rule of #52, input order kept:
 * - distance: the band (the one the card shows) within the limit; a row without a band (no registered
 *   location) does not pass an active limit; without a search point there are no bands and no limit;
 * - rating: the review average at least the minimum; a profile without reviews does not pass an active minimum.
 */
internal fun List<SearchRow>.filteredBy(filters: SearchFilters, hasPoint: Boolean): List<SearchRow> = filter { row ->
    val limit = filters.maxKm.takeIf { hasPoint }
    val rating = row.profile.rating.takeIf { row.profile.reviewCount > 0 }
    (limit == null || (row.bandM != null && row.bandM <= limit * 1000)) &&
        (filters.minRating == null || (rating != null && rating >= filters.minRating))
}

/** «Filtros» bottom sheet: changes are provisional until «Ver N profesionales». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FiltersSheet(
    filters: SearchFilters,
    hasPoint: Boolean,
    onApply: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetSection(R.string.filters_distance)
            val km = draft.maxKm.takeIf { hasPoint }
            Text(distanceStepLabel(km), Modifier.testTag("filters_distance_value"))
            Slider(
                value = distanceSteps.indexOf(km).toFloat(),
                onValueChange = { draft = draft.copy(maxKm = distanceSteps[it.roundToInt()]) },
                valueRange = 0f..(distanceSteps.size - 1).toFloat(), steps = distanceSteps.size - 2,
                enabled = hasPoint, modifier = Modifier.fillMaxWidth().testTag("filters_distance"),
            )
            if (!hasPoint) Text(stringResource(R.string.filters_distance_needs_point),
                Modifier.testTag("filters_distance_needs_point"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SheetSection(R.string.filters_rating)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ratingSteps.forEach { stars ->
                    FilterChip(selected = draft.minRating == stars, onClick = { draft = draft.copy(minRating = stars) },
                        modifier = Modifier.testTag("filters_rating_${stars ?: "any"}"),
                        label = { Text(if (stars == null) stringResource(R.string.filters_rating_any)
                            else stringResource(R.string.filters_rating_min, stars)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = SearchFilters() }, Modifier.testTag("filters_reset")) {
                    Text(stringResource(R.string.filters_reset))
                }
                Button(onClick = { onApply(draft) }, Modifier.weight(1f).testTag("filters_apply")) {
                    Text(stringResource(R.string.filters_apply_unknown))
                }
            }
        }
    }
}

@Composable
private fun SheetSection(label: Int) {
    Text(stringResource(label), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** Keeps the filters across a profile and back, and configuration changes; never in the account. */
internal val SearchFiltersSaver = listSaver<SearchFilters, Int>(
    save = { listOf(it.maxKm ?: 0, it.minRating ?: 0) },
    restore = { SearchFilters(maxKm = it[0].takeIf { km -> km > 0 }, minRating = it[1].takeIf { s -> s > 0 }) },
)

/** «Filtros», or «Filtros · N» with the number of filters away from their default (the trade counts). */
@Composable
internal fun FiltersButton(filters: SearchFilters, trade: String?, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.testTag("filters_open")) {
        Text(stringResource(R.string.filters_button))
    }
}

@Composable
private fun distanceStepLabel(km: Int?): String =
    if (km == null) stringResource(R.string.filters_distance_none) else stringResource(R.string.filters_distance_km, km)
