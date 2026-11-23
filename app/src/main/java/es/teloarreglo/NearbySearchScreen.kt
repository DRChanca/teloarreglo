package es.teloarreglo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.ConcejoAnchor
import es.teloarreglo.search.ConcejoDirectory
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.MapAvailability
import es.teloarreglo.search.mapView
import es.teloarreglo.search.searchPoint
import es.teloarreglo.search.Located
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import es.teloarreglo.search.isUncertain
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the nearby search shows. Positions are kept only for the current search. */
private sealed interface NearbyState {
    data object Idle : NearbyState
    data object Locating : NearbyState
    data object Searching : NearbyState
    data object PermissionDenied : NearbyState
    data object LocationUnavailable : NearbyState
    data object Uncertain : NearbyState
    data object OutsideArea : NearbyState
    data class Confirm(val options: List<Concejo>) : NearbyState
    data class Results(val concejo: Concejo, val rows: List<Pair<PublicProfile, Int>>, val from: GeoPoint) : NearbyState
    data class Failed(val reason: SearchError) : NearbyState
}

/**
 * Search by the job's location: one GPS fix, coverage of that concejo first, then proximity.
 * [ensurePermission] asks for location permission (true if granted); [locate] returns one fix.
 * Without GPS the user chooses a concejo from [concejos] and, when [map] is READY, a pin (#17).
 * The map and the list show the same server results; the map is optional and never required.
 */
@Composable
fun NearbySearchScreen(
    search: ProfessionalSearch,
    catalogue: Catalogue,
    ensurePermission: suspend () -> Boolean,
    locate: suspend () -> Located,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    concejos: ConcejoDirectory? = null,
    map: MapAvailability = MapAvailability.NO_KEY,
) {
    val scope = rememberCoroutineScope()
    var trade by rememberSaveable { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<NearbyState>(NearbyState.Idle) }
    // Last position, only to resend with a confirmed concejo; never shown nor saved.
    var lastPosition by remember { mutableStateOf<GeoPoint?>(null) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    // Denied permission, no fix or an imprecise one: offer the manual choice right away.
    LaunchedEffect(state) {
        if (state == NearbyState.PermissionDenied || state == NearbyState.LocationUnavailable ||
            state == NearbyState.Uncertain) manual = true
    }

    suspend fun runSearch(position: GeoPoint, ineCode: String?) {
        state = NearbyState.Searching
        state = when (val outcome = search.search(position.lat, position.lng, trade, ineCode)) {
            is SearchOutcome.Found -> try {
                val byId = catalogue.list(trade).associateBy { it.id }
                NearbyState.Results(outcome.concejo, outcome.results.mapNotNull { r -> byId[r.profileId]?.let { it to r.distanceBandM } }, position)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                NearbyState.Failed(SearchError.SERVICE)
            }
            is SearchOutcome.ConfirmConcejo -> NearbyState.Confirm(outcome.options)
            SearchOutcome.OutsideArea -> NearbyState.OutsideArea
            is SearchOutcome.Failed -> NearbyState.Failed(outcome.reason)
        }
    }

    fun start() = scope.launch {
        if (!ensurePermission()) { state = NearbyState.PermissionDenied; return@launch }
        state = NearbyState.Locating
        when (val located = locate()) {
            Located.Unavailable -> state = NearbyState.LocationUnavailable
            is Located.Position -> if (located.isUncertain()) {
                state = NearbyState.Uncertain
            } else {
                val point = GeoPoint(located.lat, located.lng)
                lastPosition = point
                runSearch(point, null)
            }
        }
    }

    TeloTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F8F5)) {
            // One scrollable list for header and results: on small screens results stay reachable.
            LazyColumn(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).testTag("nearby_screen"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { TextButton(onClick = onBack, modifier = Modifier.testTag("nearby_back")) { Text(stringResource(R.string.nearby_back)) } }
                item { Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                item { Text(stringResource(R.string.nearby_hint)) }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = trade == null, onClick = { trade = null }, label = { Text(stringResource(R.string.trade_all)) })
                        catalogueTrades.forEach { key ->
                            FilterChip(selected = trade == key, onClick = { trade = key },
                                modifier = Modifier.testTag("nearby_trade_$key"), label = { Text(stringResource(tradeLabel(key))) })
                        }
                    }
                }
                item {
                    val busy = state == NearbyState.Locating || state == NearbyState.Searching
                    Button(onClick = { start() }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("nearby_locate")) {
                        Text(stringResource(R.string.nearby_use_location))
                    }
                }
                item { Text(stringResource(R.string.nearby_privacy), style = MaterialTheme.typography.bodySmall) }
                if (concejos != null) item {
                    val busy = state == NearbyState.Locating || state == NearbyState.Searching
                    if (!manual) OutlinedButton(onClick = { manual = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("map_manual")) { Text(stringResource(R.string.map_manual)) }
                    else ManualLocation(concejos, map, busy, onCancel = { manual = false }) { anchor, point ->
                        lastPosition = point
                        scope.launch { runSearch(point, anchor.ineCode) }
                    }
                }

                when (val s = state) {
                    NearbyState.Idle -> Unit
                    NearbyState.Locating, NearbyState.Searching -> item {
                        Row(Modifier.testTag("nearby_loading"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(if (s == NearbyState.Locating) R.string.nearby_locating else R.string.nearby_searching))
                        }
                    }
                    NearbyState.PermissionDenied -> item { Notice("nearby_denied", R.string.nearby_denied) }
                    NearbyState.LocationUnavailable -> item { Notice("nearby_unavailable", R.string.nearby_unavailable) }
                    NearbyState.Uncertain -> item { Notice("nearby_uncertain", R.string.nearby_uncertain) }
                    NearbyState.OutsideArea -> item { Notice("nearby_outside", R.string.nearby_outside) }
                    is NearbyState.Confirm -> item {
                        Column(Modifier.testTag("nearby_confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.nearby_confirm), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            s.options.forEach { option ->
                                OutlinedButton(
                                    onClick = { lastPosition?.let { p -> scope.launch { runSearch(p, option.ineCode) } } },
                                    modifier = Modifier.fillMaxWidth().testTag("nearby_confirm_${option.ineCode}"),
                                ) { Text(option.name) }
                            }
                        }
                    }
                    is NearbyState.Failed -> item {
                        Column(Modifier.testTag("nearby_error"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(when (s.reason) {
                                SearchError.NETWORK -> R.string.nearby_error_network
                                else -> R.string.nearby_error_service
                            }), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Button(onClick = { start() }, modifier = Modifier.testTag("nearby_retry")) { Text(stringResource(R.string.retry)) }
                        }
                    }
                    is NearbyState.Results -> if (s.rows.isEmpty()) {
                        item { Notice("nearby_empty", R.string.nearby_empty, s.concejo.name) }
                    } else {
                        item {
                            Text(stringResource(R.string.nearby_results, s.concejo.name), Modifier.testTag("nearby_concejo"),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !showMap, onClick = { showMap = false },
                                    modifier = Modifier.testTag("map_toggle_list"), label = { Text(stringResource(R.string.map_toggle_list)) })
                                FilterChip(selected = showMap, onClick = { showMap = true },
                                    modifier = Modifier.testTag("map_toggle_map"), label = { Text(stringResource(R.string.map_toggle_map)) })
                            }
                        }
                        if (showMap) item {
                            val view = mapView(s.rows)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (map == MapAvailability.READY) ResultsMap(view, s.from, onOpenProfile) else MapUnavailableNotice(map)
                                Text(
                                    if (view.markers.isEmpty()) stringResource(R.string.map_none_public)
                                    else stringResource(R.string.map_list_only, view.listOnly, s.rows.size),
                                    Modifier.testTag("map_coverage"), style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        items(s.rows, key = { it.first.id }) { (profile, band) ->
                            Card(onClick = { onOpenProfile(profile.id) }, modifier = Modifier.fillMaxWidth().testTag("nearby_${profile.id}")) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(profile.name, fontWeight = FontWeight.Bold)
                                    Text(distanceText(band), Modifier.testTag("nearby_distance_${profile.id}"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Manual job location: a concejo (always) and a pin when the map is available. Without a map the
 * concejo's own point is used and the user is told so. The server still applies coverage and order.
 */
@Composable
private fun ManualLocation(
    directory: ConcejoDirectory, map: MapAvailability, busy: Boolean,
    onCancel: () -> Unit, onSearch: (ConcejoAnchor, GeoPoint) -> Unit,
) {
    var anchors by remember { mutableStateOf<List<ConcejoAnchor>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pinLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var pinLng by rememberSaveable { mutableStateOf<Double?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        failed = false
        anchors = try { directory.anchors() } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true; null
        }
    }
    val chosen = anchors?.firstOrNull { it.ineCode == selected }
    val pin = pinLat?.let { lat -> pinLng?.let { lng -> GeoPoint(lat, lng) } }
    Card(Modifier.fillMaxWidth().testTag("map_manual_panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.map_manual_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.map_manual_hint), style = MaterialTheme.typography.bodySmall)
            val list = anchors
            when {
                failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.map_concejos_error), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("map_concejos_error").semantics { liveRegion = LiveRegionMode.Polite })
                    OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                }
                list == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text(stringResource(R.string.map_concejos_loading))
                }
                list.isEmpty() -> Text(stringResource(R.string.map_concejos_empty), Modifier.testTag("map_concejos_empty"))
                else -> {
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("map_concejo")) {
                            Text(chosen?.name ?: stringResource(R.string.map_concejo_choose))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            list.forEach { anchor ->
                                DropdownMenuItem(text = { Text(anchor.name) }, modifier = Modifier.testTag("map_concejo_${anchor.ineCode}"),
                                    onClick = { selected = anchor.ineCode; pinLat = null; pinLng = null; expanded = false })
                            }
                        }
                    }
                    if (chosen != null) {
                        if (map == MapAvailability.READY) {
                            Text(stringResource(R.string.map_pin_hint), style = MaterialTheme.typography.bodySmall)
                            MapPointPicker(chosen.point, pin, onPin = { pinLat = it.lat; pinLng = it.lng })
                            if (pin != null) Text(stringResource(R.string.map_pin_placed,
                                String.format(Locale.ROOT, "%.5f", pin.lat), String.format(Locale.ROOT, "%.5f", pin.lng)),
                                Modifier.testTag("map_pin_placed"), style = MaterialTheme.typography.bodySmall)
                        } else {
                            MapUnavailableNotice(map)
                            Text(stringResource(R.string.map_no_map_anchor), Modifier.testTag("map_no_map_anchor"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { chosen?.let { onSearch(it, searchPoint(it, pin)) } }, enabled = chosen != null && !busy,
                    modifier = Modifier.testTag("map_search_here")) { Text(stringResource(R.string.map_search_here)) }
                TextButton(onClick = onCancel, modifier = Modifier.testTag("map_manual_cancel")) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun Notice(tag: String, text: Int, arg: String? = null) {
    Text(
        if (arg == null) stringResource(text) else stringResource(text, arg),
        Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Rounded band from the server: "less than 1 km" or "about N km". Straight line, not by road. */
@Composable
private fun distanceText(bandM: Int): String =
    if (bandM <= 1000) stringResource(R.string.nearby_distance_under_1km)
    else stringResource(R.string.nearby_distance_about, String.format(Locale.getDefault(), "%.1f", bandM / 1000.0))
