package es.teloarreglo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.distanceMeters
import es.teloarreglo.search.ConcejoAnchor
import es.teloarreglo.search.ConcejoDirectory
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.Located
import es.teloarreglo.search.MapAvailability
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.isUncertain
import es.teloarreglo.search.mapView
import es.teloarreglo.search.searchPoint
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the single search (ADR 0001) needs to order by distance. [ensurePermission] asks for location
 * permission (true if granted); [locate] returns one fix. Without GPS the user chooses a concejo from
 * [concejos] and, when [map] is READY, a pin (#17). With a [home] (#37) the order starts from it without
 * asking for GPS; a silent fix more than 5 km away ([askIfAway], once per session) shows a banner.
 */
class ProximitySource(
    val search: ProfessionalSearch,
    val ensurePermission: suspend () -> Boolean,
    val locate: suspend () -> Located,
    val concejos: ConcejoDirectory? = null,
    val map: MapAvailability = MapAvailability.NO_KEY,
    val home: HomeAddress? = null,
    val askIfAway: (GeoPoint) -> Boolean = { false },
    val onChangeHome: (() -> Unit)? = null,
)

/**
 * The distance order of the search: one request at a time (a new one cancels the previous), and «Reintentar»
 * or a trade change repeat the last origin (#47) instead of asking for GPS again.
 */
internal class Proximity(var source: ProximitySource, catalogue: Catalogue, private val scope: CoroutineScope) {
    private val session = NearbySearchSession(source.search, catalogue)
    var state: NearbyState by mutableStateOf(NearbyState.Idle)
        private set
    var origin: SearchOrigin? by mutableStateOf(null)
        private set
    /** Silent fix far from home, waiting for the banner's answer; never stored. */
    var away: GeoPoint? by mutableStateOf(null)
    /** GPS and the manual choice, folded unless the user asks for them or there is no origin. */
    var otherWays by mutableStateOf(false)
    var manual by mutableStateOf(false)
    private var trade: String? = null
    private var running: Job? = null

    val busy get() = state == NearbyState.Locating || state == NearbyState.Searching
    val results get() = state as? NearbyState.Results

    private fun launch(block: suspend () -> Unit) {
        running?.cancel()
        running = scope.launch { block() }
    }

    private suspend fun show(next: suspend () -> NearbyState?) {
        state = NearbyState.Searching
        state = next() ?: NearbyState.Idle
        origin = session.last?.origin
        if (state is NearbyState.Results) { otherWays = false; manual = false }
    }

    private fun run(query: NearbyQuery) = launch { show { session.search(query, trade) } }

    fun fromHome() { source.home?.let { run(NearbyQuery(SearchOrigin.Home, it.point, it.ineCode)) } }
    fun fromHere(point: GeoPoint) { away = null; run(NearbyQuery(SearchOrigin.Gps, point, null)) }
    fun fromManual(anchor: ConcejoAnchor, point: GeoPoint) = run(NearbyQuery(SearchOrigin.Manual(anchor.name), point, anchor.ineCode))
    fun repeat() { if (session.last != null) launch { show { session.repeat(trade) } } }
    fun confirm(ineCode: String) = launch { show { session.confirm(ineCode, trade) } }

    fun changeTrade(next: String?) {
        if (next == trade) return
        trade = next
        repeat()
    }

    /** «Usar mi ubicación»: the user asked, so the fix is used even far from home. */
    fun useLocation() = launch {
        if (!source.ensurePermission()) { state = NearbyState.PermissionDenied; manual = true; return@launch }
        state = NearbyState.Locating
        when (val located = source.locate()) {
            Located.Unavailable -> { state = NearbyState.LocationUnavailable; manual = true }
            is Located.Position -> if (located.isUncertain()) {
                state = NearbyState.Uncertain; manual = true
            } else {
                away = null
                show { session.search(NearbyQuery(SearchOrigin.Gps, GeoPoint(located.lat, located.lng), null), trade) }
            }
        }
    }

    /** In the background, without a permission prompt: only notices being far from home. */
    suspend fun noticeAway() {
        val here = source.locate()
        if (here is Located.Position && !here.isUncertain()) {
            val point = GeoPoint(here.lat, here.lng)
            if (source.askIfAway(point)) away = point
        }
    }
}

/** Null without a search service: the search then keeps the catalogue order. */
@Composable
internal fun rememberProximity(source: ProximitySource?, catalogue: Catalogue, trade: String?): Proximity? {
    if (source == null) return null
    val scope = rememberCoroutineScope()
    val proximity = remember(source.search, catalogue) { Proximity(source, catalogue, scope) }
    proximity.source = source
    // Trade first, so that the first search from home already uses a restored trade.
    LaunchedEffect(proximity, trade) { proximity.changeTrade(trade) }
    LaunchedEffect(proximity, source.home) {
        val home = source.home ?: return@LaunchedEffect
        if (proximity.origin == null || proximity.origin == SearchOrigin.Home) proximity.fromHome()
        proximity.noticeAway()
    }
    return proximity
}

/**
 * Top of the search list: the far-from-home banner, «Cerca de: … · Cambiar», the other ways to place the
 * search when unfolded, what went wrong, and the list/map switch once there is a distance order.
 */
@Composable
internal fun ProximityHeader(p: Proximity, showMap: Boolean, onShowMap: (Boolean) -> Unit) {
    val home = p.source.home
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
        val here = p.away
        if (here != null && home != null) AwayBanner(distanceMeters(home.point, here),
            onSearchHere = { p.fromHere(here) }, onDismiss = { p.away = null })
        OriginLine(p)
        if (p.otherWays) OtherWays(p)
        when (val s = p.state) {
            NearbyState.PermissionDenied -> Notice("nearby_denied", R.string.nearby_denied)
            NearbyState.LocationUnavailable -> Notice("nearby_unavailable", R.string.nearby_unavailable)
            NearbyState.Uncertain -> Notice("nearby_uncertain", R.string.nearby_uncertain)
            NearbyState.OutsideArea -> Notice("nearby_outside", R.string.nearby_outside)
            is NearbyState.Confirm -> Column(Modifier.testTag("nearby_confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nearby_confirm), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                s.options.forEach { option ->
                    OutlinedButton(onClick = { p.confirm(option.ineCode) },
                        modifier = Modifier.fillMaxWidth().testTag("nearby_confirm_${option.ineCode}")) { Text(option.name) }
                }
            }
            is NearbyState.Failed -> Row(Modifier.fillMaxWidth().testTag("nearby_error"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (s.reason == SearchError.NETWORK) R.string.search_order_failed_network else R.string.search_order_failed),
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { p.repeat() }, modifier = Modifier.testTag("nearby_retry")) { Text(stringResource(R.string.retry)) }
            }
            is NearbyState.Results -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showMap, onClick = { onShowMap(false) },
                    modifier = Modifier.testTag("map_toggle_list"), label = { Text(stringResource(R.string.map_toggle_list)) })
                FilterChip(selected = showMap, onClick = { onShowMap(true) },
                    modifier = Modifier.testTag("map_toggle_map"), label = { Text(stringResource(R.string.map_toggle_map)) })
            }
            NearbyState.Idle, NearbyState.Locating, NearbyState.Searching -> Unit
        }
    }
}

/** Map of the same rows as the list (#17): only profiles with a public location get a marker. */
@Composable
internal fun ProximityMap(p: Proximity, results: NearbyState.Results, onOpenProfile: (String) -> Unit) {
    val located = results.rows.mapNotNull { row -> row.bandM?.let { row.profile to it } }
    val view = mapView(located, results.featured)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p.source.map == MapAvailability.READY) ResultsMap(view, results.from, onOpenProfile) else MapUnavailableNotice(p.source.map)
        Text(
            if (view.markers.isEmpty()) stringResource(R.string.map_none_public)
            else stringResource(R.string.map_list_only, view.listOnly, results.rows.size),
            Modifier.testTag("map_coverage"), style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** «Cerca de: tu casa (Oviedo) · Cambiar», or «Ordenar por cercanía» while nothing places the search. */
@Composable
private fun OriginLine(p: Proximity) {
    val home = p.source.home
    val origin = p.origin ?: if (home != null) SearchOrigin.Home else null
    if (origin == null) {
        OutlinedButton(onClick = { p.otherWays = !p.otherWays }, enabled = !p.busy,
            modifier = Modifier.fillMaxWidth().testTag("search_sort_nearby")) { Text(stringResource(R.string.search_sort_nearby)) }
        return
    }
    val concejo = p.results?.concejo?.name ?: home?.concejoName.takeIf { origin == SearchOrigin.Home }
    val text = when (origin) {
        SearchOrigin.Home -> if (concejo != null) stringResource(R.string.search_near_home_in, concejo) else stringResource(R.string.search_near_home)
        SearchOrigin.Gps -> if (concejo != null) stringResource(R.string.search_near_here_in, concejo) else stringResource(R.string.search_near_here)
        is SearchOrigin.Manual -> stringResource(R.string.search_near_manual, origin.concejo)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).testTag("nearby_origin_text"), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { p.otherWays = !p.otherWays }, modifier = Modifier.testTag("nearby_other_ways")) {
            Text(stringResource(if (p.otherWays) R.string.search_change_close else R.string.search_change))
        }
    }
}

/** GPS once, a concejo (and pin) by hand, or back to the home when the search left it. */
@Composable
private fun OtherWays(p: Proximity) {
    val home = p.source.home
    Card(Modifier.fillMaxWidth().testTag("nearby_origin"), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(TeloTheme.spacing.large), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
            if (home != null && p.origin != null && p.origin != SearchOrigin.Home) {
                OutlinedButton(onClick = { p.fromHome() }, enabled = !p.busy,
                    modifier = Modifier.fillMaxWidth().testTag("nearby_from_home")) { Text(stringResource(R.string.home_search_from_home)) }
            }
            if (home != null) p.source.onChangeHome?.let { change ->
                TextButton(onClick = change, modifier = Modifier.testTag("search_change_home")) { Text(stringResource(R.string.search_change_home)) }
            }
            Button(onClick = { p.useLocation() }, enabled = !p.busy, modifier = Modifier.fillMaxWidth().testTag("nearby_locate")) {
                Text(stringResource(R.string.nearby_use_location))
            }
            Text(stringResource(R.string.nearby_privacy), style = MaterialTheme.typography.bodySmall)
            p.source.concejos?.let { concejos ->
                if (!p.manual) OutlinedButton(onClick = { p.manual = true }, enabled = !p.busy,
                    modifier = Modifier.fillMaxWidth().testTag("map_manual")) { Text(stringResource(R.string.map_manual)) }
                else ManualLocation(concejos, p.source.map, p.busy, onCancel = { p.manual = false }) { anchor, point ->
                    p.fromManual(anchor, point)
                }
            }
        }
    }
}

/** Non-blocking: the list keeps showing results from home while the banner asks (replaces the old dialog). */
@Composable
private fun AwayBanner(meters: Double, onSearchHere: () -> Unit, onDismiss: () -> Unit) {
    val dismiss = stringResource(R.string.search_away_dismiss)
    val km = String.format(Locale.getDefault(), "%.0f", meters / 1000)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("search_away")) {
        Row(Modifier.padding(start = TeloTheme.spacing.large), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_away_km, km), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSearchHere, modifier = Modifier.testTag("search_away_here")) { Text(stringResource(R.string.home_far_here)) }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("search_away_dismiss").semantics { contentDescription = dismiss }) {
                Text("✕")
            }
        }
    }
}

/** Placeholder card with the shape of a result while the distance order arrives: nothing jumps later. */
@Composable
internal fun SkeletonCard() {
    val block = MaterialTheme.colorScheme.surfaceVariant
    Card(Modifier.fillMaxWidth().testTag("search_skeleton"), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = TeloTheme.extended.card)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(CoverCrop.LIST_WIDTH).height(CoverCrop.LIST_HEIGHT).background(block, RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(18.dp).background(block, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(0.9f).height(14.dp).background(block, RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(0.4f).height(14.dp).background(block, RoundedCornerShape(6.dp)))
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
                            if (pin != null) Text(stringResource(R.string.map_pin_placed),
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
internal fun distanceText(bandM: Int): String =
    if (bandM <= 1000) stringResource(R.string.nearby_distance_under_1km)
    else stringResource(R.string.nearby_distance_about, String.format(Locale.getDefault(), "%.1f", bandM / 1000.0))
