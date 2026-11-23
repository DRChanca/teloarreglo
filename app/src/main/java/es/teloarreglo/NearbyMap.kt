package es.teloarreglo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.MapAvailability
import es.teloarreglo.search.MapView
import es.teloarreglo.search.externalMapsUri
import es.teloarreglo.search.mapAvailability
import kotlinx.coroutines.delay

/** Google Maps (SDK without map ID) for #17. Only composed when [deviceMapAvailability] is READY. */
fun deviceMapAvailability(context: Context): MapAvailability = mapAvailability(
    BuildConfig.MAPS_API_KEY,
    runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }
        .getOrDefault(false),
)

/** Opens Google Maps (app or web) with directions to [destination]. False when nothing could open it. */
fun openExternalMaps(context: Context, destination: GeoPoint): Boolean {
    val uri = Uri.parse(externalMapsUri(destination) ?: return false)
    val app = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    return listOf(app, Intent(Intent.ACTION_VIEW, uri)).any { intent ->
        try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
    }
}

private fun GeoPoint.latLng() = LatLng(lat, lng)

/** Text shown instead of the map, so the list never looks like a broken map. */
@Composable
fun MapUnavailableNotice(availability: MapAvailability, modifier: Modifier = Modifier) {
    if (availability == MapAvailability.READY) return
    Text(
        stringResource(if (availability == MapAvailability.NO_KEY) R.string.map_unavailable_key else R.string.map_unavailable_services),
        modifier.testTag("map_unavailable").semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Warns (without hiding the list) when tiles never load: bad key, no network, blocked API. */
@Composable
private fun LoadWatch(loaded: Boolean) {
    var late by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(12_000); late = true }
    if (late && !loaded) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(8.dp)) {
        Text(stringResource(R.string.map_not_loaded), Modifier.padding(8.dp).testTag("map_not_loaded"))
    }
}

/** Results on the map: same candidates and order as the list; the info window opens the same profile. */
@Composable
fun ResultsMap(view: MapView, from: GeoPoint, onOpenProfile: (String) -> Unit) {
    var loaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(from.latLng(), 12f) }
    LaunchedEffect(loaded, view) {
        if (!loaded || view.markers.isEmpty()) return@LaunchedEffect
        val bounds = LatLngBounds.builder().include(from.latLng()).apply { view.markers.forEach { include(it.point.latLng()) } }.build()
        runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 96)) }
    }
    Box(Modifier.fillMaxWidth().height(320.dp).testTag("map_results")) {
        GoogleMap(
            cameraPositionState = camera,
            uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
            onMapLoaded = { loaded = true },
        ) {
            Marker(
                state = rememberUpdatedMarkerState(position = from.latLng()),
                title = stringResource(R.string.map_search_point),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
            )
            val hint = stringResource(R.string.map_open_profile)
            view.markers.forEach { marker ->
                key(marker.profileId) {
                    Marker(
                        state = rememberUpdatedMarkerState(position = marker.point.latLng()),
                        title = marker.name,
                        snippet = hint,
                        onInfoWindowClick = { onOpenProfile(marker.profileId) },
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter)) { LoadWatch(loaded) }
    }
}

/** Tap to place the job's point. The pin never replaces the written street address. */
@Composable
fun MapPointPicker(center: GeoPoint, pin: GeoPoint?, onPin: (GeoPoint) -> Unit, modifier: Modifier = Modifier) {
    var loaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom((pin ?: center).latLng(), 12f) }
    LaunchedEffect(center) { runCatching { camera.animate(CameraUpdateFactory.newLatLngZoom(center.latLng(), 12f)) } }
    Box(modifier.fillMaxWidth().height(300.dp).testTag("map_picker")) {
        GoogleMap(
            cameraPositionState = camera,
            uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
            onMapLoaded = { loaded = true },
            onMapClick = { onPin(GeoPoint(it.latitude, it.longitude)) },
        ) {
            if (pin != null) Marker(state = rememberUpdatedMarkerState(position = pin.latLng()))
        }
        Box(Modifier.align(Alignment.TopCenter)) { LoadWatch(loaded) }
    }
}

/** The approximate area of a pending appointment (#38): only the circle, no marker, since the centre is not the home. */
@Composable
fun ApproximateAreaMap(center: GeoPoint, radiusMeters: Int, modifier: Modifier = Modifier) {
    var loaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(center.latLng(), 14f) }
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(220.dp).testTag("area_map")) {
        GoogleMap(
            cameraPositionState = camera,
            uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
            onMapLoaded = { loaded = true },
        ) {
            Circle(center = center.latLng(), radius = radiusMeters.toDouble(), strokeColor = color,
                strokeWidth = 4f, fillColor = color.copy(alpha = 0.18f))
        }
        Box(Modifier.align(Alignment.TopCenter)) { LoadWatch(loaded) }
    }
}
