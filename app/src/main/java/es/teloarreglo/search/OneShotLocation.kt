package es.teloarreglo.search

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A single position for one search. No tracking, no background updates, nothing stored. */
sealed interface Located {
    data class Position(val lat: Double, val lng: Double, val accuracyM: Float?) : Located
    /** Location services off, no fix in time or no provider. */
    data object Unavailable : Located
}

/** Beyond this the position is too uncertain to choose a concejo; the manual alternative applies. */
const val MAX_ACCURACY_M = 1000f

fun Located.Position.isUncertain(): Boolean = accuracyM == null || accuracyM > MAX_ACCURACY_M

fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/** Requests one current fix (GPS if enabled, otherwise network), giving up after [timeoutMs]. */
@SuppressLint("MissingPermission") // Callers check hasLocationPermission first.
suspend fun currentLocation(context: Context, timeoutMs: Long = 20_000): Located {
    if (!hasLocationPermission(context)) return Located.Unavailable
    val manager = context.getSystemService(LocationManager::class.java) ?: return Located.Unavailable
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return Located.Unavailable
    val location = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<android.location.Location?> { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager, provider, cancel, ContextCompat.getMainExecutor(context),
            ) { location -> if (continuation.isActive) continuation.resume(location) }
        }
    } ?: return Located.Unavailable
    return Located.Position(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null)
}
