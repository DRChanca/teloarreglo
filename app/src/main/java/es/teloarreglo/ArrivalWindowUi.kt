package es.teloarreglo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import es.teloarreglo.appointments.Appointment
import es.teloarreglo.appointments.AppointmentLocation
import es.teloarreglo.appointments.AppointmentStage
import es.teloarreglo.appointments.ArrivalDistance
import es.teloarreglo.appointments.ArrivalMarker
import es.teloarreglo.appointments.ArrivalReading
import es.teloarreglo.appointments.ArrivalView
import es.teloarreglo.appointments.Arrivals
import es.teloarreglo.appointments.arrivalView
import es.teloarreglo.search.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/** How often the open card asks «leer llegada» again (the professional reports every ~30 s). */
private const val ARRIVAL_POLL_MS = 30_000L

/**
 * The ventana de llegada on the client's confirmed appointment (#84, ADR 0002): where the professional is and
 * when that was updated. Asks again every ~30 s only while it is on screen; the professional never sees it.
 * Closed window (not open yet, started, cancelled, or an hour after the start): nothing at all.
 */
@Composable
internal fun ArrivalWindowSection(appointment: Appointment, professional: Boolean, arrivals: Arrivals, mapsReady: Boolean) {
    if (professional || appointment.stage != AppointmentStage.CONFIRMED) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reading by remember(appointment.id) { mutableStateOf<ArrivalReading>(ArrivalReading.Closed) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(arrivals, appointment.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { reading = arrivals.read(appointment.id) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { /* keep the last answer: arrivalView turns it into «sin compartir» after 2 min */ }
                now = Instant.now()
                delay(ARRIVAL_POLL_MS)
            }
        }
    }
    val home = (appointment.location as? AppointmentLocation.Exact)?.point
    when (val view = arrivalView(reading, home, now)) {
        ArrivalView.Hidden -> Unit
        ArrivalView.NotSharing -> Text(stringResource(R.string.arrival_not_sharing), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("arrival_not_sharing_${appointment.id}").semantics { liveRegion = LiveRegionMode.Polite })
        is ArrivalView.Sharing -> Column(Modifier.fillMaxWidth().testTag("arrival_${appointment.id}"),
            verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall)) {
            Text(stringResource(R.string.arrival_title), style = MaterialTheme.typography.titleSmall)
            if (mapsReady) ArrivalMap(view.marker, view.home)
            else Text(stringResource(when (view.distance) {
                ArrivalDistance.FAR -> R.string.arrival_far
                ArrivalDistance.NEAR -> R.string.arrival_near
                ArrivalDistance.ARRIVING -> R.string.arrival_arriving
            }), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("arrival_distance_${appointment.id}")
                .semantics { liveRegion = LiveRegionMode.Polite })
            Text(if (view.updatedMinutesAgo < 1) stringResource(R.string.arrival_updated_now)
                else pluralStringResource(R.plurals.arrival_updated, view.updatedMinutesAgo.toInt(), view.updatedMinutesAgo.toInt()),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("arrival_updated_${appointment.id}"))
        }
    }
}

/** Same map style as the approximate zone and the search: the address of the appointment, and the professional as a circle or a point. */
@Composable
private fun ArrivalMap(marker: ArrivalMarker, home: GeoPoint?) {
    var loaded by remember { mutableStateOf(false) }
    val professional = when (marker) { is ArrivalMarker.Area -> marker.center; is ArrivalMarker.Exact -> marker.point }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(professional.latLng(), 13f) }
    LaunchedEffect(loaded, marker, home) {
        if (!loaded) return@LaunchedEffect
        val bounds = LatLngBounds.builder().include(professional.latLng()).apply { home?.let { include(it.latLng()) } }.build()
        runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 120)) }
    }
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(240.dp).testTag("arrival_map")) {
        GoogleMap(
            cameraPositionState = camera,
            uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false),
            mapColorScheme = mapColorScheme(),
            onMapLoaded = { loaded = true },
        ) {
            if (home != null) Marker(state = rememberUpdatedMarkerState(position = home.latLng()),
                title = stringResource(R.string.arrival_map_home),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            when (marker) {
                is ArrivalMarker.Area -> Circle(center = marker.center.latLng(), radius = marker.radiusMeters.toDouble(),
                    strokeColor = color, strokeWidth = 4f, fillColor = color.copy(alpha = 0.18f))
                is ArrivalMarker.Exact -> Marker(state = rememberUpdatedMarkerState(position = marker.point.latLng()),
                    title = stringResource(R.string.arrival_map_professional))
            }
        }
        Box(Modifier.align(Alignment.TopCenter)) { LoadWatch(loaded) }
    }
}
