package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Steps of «Distancia máxima» in km; null is «Sin límite», the default (#52). */
internal val distanceSteps = listOf(2, 5, 10, 25, 50, null)

/**
 * Search filters of the session (#52). Only the device applies them, over the list already ordered by the
 * server (ADR 0001); they remove profiles and never change the order. The trade lives apart: the server asks
 * for it, and the chips and the sheet share it.
 */
internal data class SearchFilters(val maxKm: Int? = null)

/**
 * The rule of #52: a row passes the distance limit when its band (the one the card shows) is within it; a
 * row without a band (no registered location) does not pass an active limit. Without a search point there
 * are no bands, so the limit does not apply. The input order is kept.
 */
internal fun List<SearchRow>.filteredBy(filters: SearchFilters, hasPoint: Boolean): List<SearchRow> = filter { row ->
    val limit = filters.maxKm.takeIf { hasPoint }
    limit == null || (row.bandM != null && row.bandM <= limit * 1000)
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
            Text(stringResource(R.string.filters_distance), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            val step = distanceSteps.indexOf(draft.maxKm)
            Text(distanceStepLabel(draft.maxKm), Modifier.testTag("filters_distance_value"))
            Slider(
                value = step.toFloat(), onValueChange = { draft = draft.copy(maxKm = distanceSteps[it.roundToInt()]) },
                valueRange = 0f..(distanceSteps.size - 1).toFloat(), steps = distanceSteps.size - 2,
                enabled = hasPoint, modifier = Modifier.fillMaxWidth().testTag("filters_distance"),
            )
            Button(onClick = { onApply(draft) }, Modifier.fillMaxWidth().testTag("filters_apply")) {
                Text(stringResource(R.string.filters_apply_unknown))
            }
        }
    }
}

/** Keeps the filters across a profile and back, and configuration changes; never in the account. */
internal val SearchFiltersSaver = listSaver<SearchFilters, Int>(
    save = { listOf(it.maxKm ?: 0) },
    restore = { SearchFilters(maxKm = it[0].takeIf { km -> km > 0 }) },
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
