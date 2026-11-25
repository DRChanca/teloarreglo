package es.teloarreglo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import es.teloarreglo.home.AddressAutocomplete
import es.teloarreglo.home.AddressParts
import es.teloarreglo.home.AddressLookup
import es.teloarreglo.home.AddressSuggestion
import es.teloarreglo.home.ChoiceResult
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeDraft
import es.teloarreglo.home.HomeException
import es.teloarreglo.home.HomeFailure
import es.teloarreglo.home.HomeGap
import es.teloarreglo.home.homeSaveGaps
import es.teloarreglo.home.HomeSession
import es.teloarreglo.home.HomeSource
import es.teloarreglo.home.HomeStatus
import es.teloarreglo.home.LookupResult
import es.teloarreglo.home.distanceMeters
import es.teloarreglo.search.ASTURIAS_CENTER
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.Located
import es.teloarreglo.search.isUncertain
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home address step (#37). The street field searches Google Places after 7 characters and is followed
 * by the usual Spanish fields (número, bloque, portal, escalera, piso, puerta, locality) (#41). Mandatory after signing in when the account has none (like Glovo), and
 * reachable later from the account. Google Places autocomplete when available; otherwise (no key,
 * no network, API not enabled) a pin on the map or one GPS fix, with the address typed by hand.
 * Nothing is shown as saved until the server answers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeAddressScreen(
    session: HomeSession,
    places: AddressAutocomplete?,
    pins: PinSource,
    scope: CoroutineScope,
    required: Boolean,
    onDone: () -> Unit,
    onSignOut: () -> Unit,
) {
    val lookup = remember(places) { AddressLookup(places) }
    val current = session.home
    val initial = current?.editableParts
    var placesUp by remember { mutableStateOf(lookup.available) }
    var suggestions by remember { mutableStateOf<LookupResult?>(null) }
    var resolving by remember { mutableStateOf(false) }
    // The street field is also the Places search (#41); [chosenStreet] is the text a suggestion filled in,
    // so it is not searched again until the person types something else.
    var street by rememberSaveable { mutableStateOf(initial?.street.orEmpty()) }
    var chosenStreet by rememberSaveable { mutableStateOf(initial?.street) }
    var number by rememberSaveable { mutableStateOf(initial?.number.orEmpty()) }
    var block by rememberSaveable { mutableStateOf(initial?.block.orEmpty()) }
    var portal by rememberSaveable { mutableStateOf(initial?.portal.orEmpty()) }
    var staircase by rememberSaveable { mutableStateOf(initial?.staircase.orEmpty()) }
    var floor by rememberSaveable { mutableStateOf(initial?.floor.orEmpty()) }
    var door by rememberSaveable { mutableStateOf(initial?.door.orEmpty()) }
    var locality by rememberSaveable { mutableStateOf(initial?.locality.orEmpty()) }
    var lat by rememberSaveable { mutableStateOf(current?.lat) }
    var lng by rememberSaveable { mutableStateOf(current?.lng) }
    var source by rememberSaveable { mutableStateOf(current?.source) }
    var placeId by rememberSaveable { mutableStateOf(current?.placeId) }
    var locating by remember { mutableStateOf(false) }
    var noLocation by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val point = lat?.let { a -> lng?.let { b -> GeoPoint(a, b) } }?.takeIf { it.valid }
    val parts = AddressParts(street, number, block, portal, staircase, floor, door, locality)
    val gaps = homeSaveGaps(parts, hasPoint = point != null)

    // Debounced: Places is billed per session, and every keystroke would be a request.
    LaunchedEffect(street, placesUp) {
        if (!placesUp || street.isBlank() || street == chosenStreet) { suggestions = null; return@LaunchedEffect }
        delay(350)
        val result = lookup.suggest(street)
        suggestions = result
        if (result == LookupResult.Unavailable) placesUp = false
    }

    fun choose(suggestion: AddressSuggestion) {
        resolving = true
        scope.launch {
            try {
                when (val chosen = lookup.choose(suggestion)) {
                    is ChoiceResult.Chosen -> {
                        val filled = AddressParts.from(chosen.place)
                        street = filled.street; chosenStreet = filled.street
                        if (filled.number.isNotEmpty()) number = filled.number
                        if (filled.locality.isNotEmpty()) locality = filled.locality
                        lat = chosen.place.point.lat; lng = chosen.place.point.lng
                        source = HomeSource.PLACES; placeId = chosen.place.placeId
                        suggestions = null
                    }
                    ChoiceResult.Unavailable -> placesUp = lookup.available
                }
            } finally { resolving = false }
        }
    }

    @Composable
    fun RowScope.Detail(value: String, onChange: (String) -> Unit, label: Int, tag: String) =
        OutlinedTextField(value, { if (it.length <= AddressParts.MAX_DETAIL) onChange(it) }, enabled = !busy, singleLine = true,
            label = { Text(stringResource(label)) }, modifier = Modifier.weight(1f).testTag(tag))

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState())
                .padding(TeloTheme.spacing.extraLarge).testTag("home_screen"),
            verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.medium),
        ) {
            if (!required) TextButton(onClick = onDone, enabled = !busy, modifier = Modifier.testTag("home_back")) {
                Text(stringResource(R.string.catalogue_back))
            }
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.home_intro))
            Text(stringResource(R.string.home_privacy), style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(street, { if (it.length <= AddressParts.MAX_STREET) street = it }, enabled = !busy && !resolving,
                singleLine = true, label = { Text(stringResource(R.string.home_street_label)) },
                supportingText = if (placesUp) { { Text(stringResource(R.string.home_street_hint)) } } else null,
                modifier = Modifier.fillMaxWidth().testTag("home_street"))
            when (val s = suggestions) {
                is LookupResult.Suggestions -> if (s.items.isEmpty()) {
                    Text(stringResource(R.string.home_no_suggestions), style = MaterialTheme.typography.bodySmall)
                } else s.items.forEach { item ->
                    Card(onClick = { choose(item) }, enabled = !resolving,
                        modifier = Modifier.fillMaxWidth().testTag("home_suggestion_${item.placeId}")) {
                        Column(Modifier.padding(TeloTheme.spacing.medium)) {
                            Text(item.primary, fontWeight = FontWeight.Bold)
                            if (item.secondary.isNotBlank()) Text(item.secondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                else -> Unit
            }
            if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!placesUp) Text(stringResource(R.string.home_places_unavailable), Modifier.testTag("home_places_unavailable")
                .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)

            // Usual Spanish fields after the street (#41), two per row so labels fit with large text (#47).
            Row(horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                Detail(number, { number = it }, R.string.home_number, "home_number")
                Detail(block, { block = it }, R.string.home_block, "home_block")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                Detail(portal, { portal = it }, R.string.home_portal, "home_portal")
                Detail(staircase, { staircase = it }, R.string.home_staircase, "home_staircase")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                Detail(floor, { floor = it }, R.string.home_floor, "home_floor")
                Detail(door, { door = it }, R.string.home_door, "home_door")
            }
            OutlinedTextField(locality, { if (it.length <= AddressParts.MAX_LOCALITY) locality = it }, enabled = !busy,
                singleLine = true, label = { Text(stringResource(R.string.home_locality)) },
                modifier = Modifier.fillMaxWidth().testTag("home_locality"))

            // Only a missing point is worth a line; its source is not shown (David 2026-11-24).
            if (point == null) Text(stringResource(R.string.home_point_missing),
                Modifier.testTag("home_point"), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                OutlinedButton(onClick = {
                    locating = true; noLocation = false
                    scope.launch {
                        try {
                            val position = if (pins.ensurePermission()) pins.locate() else Located.Unavailable
                            if (position is Located.Position && !position.isUncertain()) {
                                lat = position.lat; lng = position.lng; source = HomeSource.GPS; placeId = null
                            } else noLocation = true
                        } finally { locating = false }
                    }
                }, enabled = !busy && !locating, modifier = Modifier.testTag("home_locate")) {
                    Text(stringResource(if (locating) R.string.home_locating else R.string.home_use_location))
                }
                if (pins.mapsReady) OutlinedButton(onClick = { picking = true }, enabled = !busy,
                    modifier = Modifier.testTag("home_pick_on_map")) { Text(stringResource(R.string.home_pick_on_map)) }
            }
            if (noLocation) Text(stringResource(R.string.home_location_unavailable), style = MaterialTheme.typography.bodySmall)

            error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("home_error").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val chosen = point ?: return@Button
                    busy = true; error = null
                    // Saving the same home twice is harmless, so a retry after a lost answer is safe.
                    scope.launch {
                        try {
                            session.save(HomeDraft(parts, chosen, source ?: HomeSource.PIN, placeId))
                            onDone()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            error = when ((e as? HomeException)?.failure) {
                                HomeFailure.INVALID -> R.string.home_error_invalid
                                HomeFailure.ACCESS -> R.string.home_error_access
                                null -> R.string.home_error_network
                            }
                        } finally { busy = false }
                    }
                },
                enabled = !busy && gaps.isEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("home_save"),
            ) { Text(stringResource(if (busy) R.string.home_saving else R.string.home_save)) }
            // A disabled button says why (#47), so the mandatory step never blocks without explanation.
            if (!busy && gaps.isNotEmpty()) {
                val names = gaps.map { stringResource(when (it) {
                    HomeGap.STREET -> R.string.home_gap_street
                    HomeGap.TOO_LONG -> R.string.home_gap_too_long
                    HomeGap.LOCATION -> R.string.home_gap_location
                }) }
                Text(stringResource(R.string.home_save_missing, names.joinToString(", ")),
                    Modifier.testTag("home_save_reason").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (required) TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.testTag("home_sign_out")) {
                Text(stringResource(R.string.sign_out))
            }
        }
    }
    if (picking) HomePinDialog(point, onDismiss = { picking = false }) {
        lat = it.lat; lng = it.lng; source = HomeSource.PIN; placeId = null; picking = false
    }
}

@Composable
private fun HomePinDialog(initial: GeoPoint?, onDismiss: () -> Unit, onPick: (GeoPoint) -> Unit) {
    var pin by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(TeloTheme.spacing.large), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.medium)) {
                Text(stringResource(R.string.home_pick_on_map), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.home_pin_hint), style = MaterialTheme.typography.bodySmall)
                MapPointPicker(initial ?: ASTURIAS_CENTER, pin, onPin = { pin = it })
                Row(horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                    Button(onClick = { pin?.let(onPick) }, enabled = pin != null,
                        modifier = Modifier.testTag("home_pin_confirm")) { Text(stringResource(R.string.map_pick_confirm)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/** Home summary on the account screen, with the way to change it. */
@Composable
fun HomeSummaryCard(session: HomeSession, onChange: () -> Unit) {
    val status by session.status.collectAsState()
    Card(Modifier.fillMaxWidth().testTag("home_summary")) {
        Column(Modifier.padding(TeloTheme.spacing.large), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
            Text(stringResource(R.string.home_account_title), style = MaterialTheme.typography.titleMedium)
            when (val s = status) {
                is HomeStatus.Present -> {
                    Text(s.home.address, Modifier.testTag("home_summary_address"))
                    HomeConcejoLine(s.home)
                }
                else -> Text(stringResource(R.string.home_account_none), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onChange, modifier = Modifier.testTag("home_change")) { Text(stringResource(R.string.home_change)) }
        }
    }
}

@Composable
private fun HomeConcejoLine(home: HomeAddress) {
    Text(
        home.concejoName?.let { stringResource(R.string.home_concejo, it) } ?: stringResource(R.string.home_outside_area),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * "You are far from home": asked at most once per session, only on the device. Change the home,
 * search from here, or keep searching from home.
 */
@Composable
fun AwayFromHomeDialog(home: GeoPoint, here: GeoPoint, onChangeHome: () -> Unit, onSearchHere: () -> Unit, onKeepHome: () -> Unit) {
    val km = String.format(Locale.getDefault(), "%.0f", distanceMeters(home, here) / 1000)
    AlertDialog(
        onDismissRequest = onKeepHome,
        modifier = Modifier.testTag("home_far"),
        title = { Text(stringResource(R.string.home_far_title)) },
        text = { Text(stringResource(R.string.home_far_text, km)) },
        confirmButton = {
            TextButton(onClick = onSearchHere, modifier = Modifier.testTag("home_far_here")) { Text(stringResource(R.string.home_far_here)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onChangeHome, modifier = Modifier.testTag("home_far_change")) { Text(stringResource(R.string.home_change)) }
                TextButton(onClick = onKeepHome, modifier = Modifier.testTag("home_far_keep")) { Text(stringResource(R.string.home_far_keep)) }
            }
        },
    )
}
