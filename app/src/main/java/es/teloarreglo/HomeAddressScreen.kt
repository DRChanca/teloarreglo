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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import es.teloarreglo.home.AddressAutocomplete
import es.teloarreglo.home.AddressLookup
import es.teloarreglo.home.AddressSuggestion
import es.teloarreglo.home.ChoiceResult
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeDraft
import es.teloarreglo.home.HomeException
import es.teloarreglo.home.HomeFailure
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
 * Home address step (#37). Mandatory after signing in when the account has none (like Glovo), and
 * reachable later from the account. Google Places autocomplete when available; otherwise (no key,
 * no network, API not enabled) a pin on the map or one GPS fix, with the address typed by hand.
 * Nothing is shown as saved until the server answers.
 */
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
    var placesUp by remember { mutableStateOf(lookup.available) }
    var manual by rememberSaveable { mutableStateOf(!lookup.available) }
    var query by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<LookupResult?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf(current?.address.orEmpty()) }
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
    val showPlaces = placesUp && !manual

    // Debounced: Places is billed per session, and every keystroke would be a request.
    LaunchedEffect(query, showPlaces) {
        if (!showPlaces || query.isBlank()) { suggestions = null; return@LaunchedEffect }
        delay(350)
        val result = lookup.suggest(query)
        suggestions = result
        if (result == LookupResult.Unavailable) { placesUp = false; manual = true }
    }

    fun choose(suggestion: AddressSuggestion) {
        resolving = true
        scope.launch {
            try {
                when (val chosen = lookup.choose(suggestion)) {
                    is ChoiceResult.Chosen -> {
                        address = chosen.place.address; lat = chosen.place.point.lat; lng = chosen.place.point.lng
                        source = HomeSource.PLACES; placeId = chosen.place.placeId
                        query = ""; suggestions = null
                    }
                    ChoiceResult.Unavailable -> { placesUp = lookup.available; manual = true }
                }
            } finally { resolving = false }
        }
    }

    TeloTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F8F5)) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState())
                    .padding(24.dp).testTag("home_screen"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!required) TextButton(onClick = onDone, enabled = !busy, modifier = Modifier.testTag("home_back")) {
                    Text(stringResource(R.string.catalogue_back))
                }
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.home_intro))
                Text(stringResource(R.string.home_privacy), style = MaterialTheme.typography.bodySmall)

                if (showPlaces) {
                    OutlinedTextField(query, { if (it.length <= 120) query = it }, enabled = !busy && !resolving, singleLine = true,
                        label = { Text(stringResource(R.string.home_search_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("home_query"))
                    when (val s = suggestions) {
                        is LookupResult.Suggestions -> if (s.items.isEmpty()) {
                            Text(stringResource(R.string.home_no_suggestions), style = MaterialTheme.typography.bodySmall)
                        } else s.items.forEach { item ->
                            Card(onClick = { choose(item) }, enabled = !resolving,
                                modifier = Modifier.fillMaxWidth().testTag("home_suggestion_${item.placeId}")) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(item.primary, fontWeight = FontWeight.Bold)
                                    if (item.secondary.isNotBlank()) Text(item.secondary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        else -> Unit
                    }
                    if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { manual = true }, modifier = Modifier.testTag("home_manual")) {
                        Text(stringResource(R.string.home_manual))
                    }
                } else {
                    if (!placesUp) Text(stringResource(R.string.home_places_unavailable), Modifier.testTag("home_places_unavailable")
                        .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (placesUp) TextButton(onClick = { manual = false }) { Text(stringResource(R.string.home_use_places)) }
                }

                OutlinedTextField(address, { if (it.length <= 300) address = it }, enabled = !busy, minLines = 2,
                    label = { Text(stringResource(R.string.home_address_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("home_address"))
                Text(
                    point?.let { stringResource(R.string.home_point, "%.5f".format(Locale.ROOT, it.lat), "%.5f".format(Locale.ROOT, it.lng)) }
                        ?: stringResource(R.string.home_point_missing),
                    Modifier.testTag("home_point"), style = MaterialTheme.typography.bodySmall,
                )
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
                                session.save(HomeDraft(address, chosen, source ?: HomeSource.PIN, placeId))
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
                    enabled = !busy && address.isNotBlank() && point != null,
                    modifier = Modifier.fillMaxWidth().testTag("home_save"),
                ) { Text(stringResource(if (busy) R.string.home_saving else R.string.home_save)) }
                if (required) TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.testTag("home_sign_out")) {
                    Text(stringResource(R.string.sign_out))
                }
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
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.home_pick_on_map), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.home_pin_hint), style = MaterialTheme.typography.bodySmall)
                MapPointPicker(initial ?: ASTURIAS_CENTER, pin, onPin = { pin = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
