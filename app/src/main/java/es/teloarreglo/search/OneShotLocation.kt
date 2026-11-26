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
suspend fun currentLocation(context: Context, timeoutMs: Long = 20_000): Located {
    if (!hasLocationPermission(context)) return Located.Unavailable
    val manager = context.getSystemService(LocationManager::class.java) ?: return Located.Unavailable
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .firstOrNull { manager.enabled(it) } ?: return Located.Unavailable
    return manager.fix(context, provider, timeoutMs) ?: Located.Unavailable
}

/** The system's fused provider (GPS, Wi-Fi and cells together), from Android 12. */
internal const val FUSED_PROVIDER = "fused"

/**
 * Which providers a fix for the ventana de llegada tries, in order, and how long each (#72). Indoors GPS alone gives
 * nothing: the fused provider (Android 12+) decides by itself; without it GPS gets half the time and the network the
 * rest, so a phone in a building still reports a position.
 */
internal fun arrivalProviders(sdkInt: Int, enabled: (String) -> Boolean, timeoutMs: Long): List<Pair<String, Long>> {
    if (sdkInt >= 31 && enabled(FUSED_PROVIDER)) return listOf(FUSED_PROVIDER to timeoutMs)
    val gps = enabled(LocationManager.GPS_PROVIDER)
    val network = enabled(LocationManager.NETWORK_PROVIDER)
    return when {
        gps && network -> listOf(LocationManager.GPS_PROVIDER to timeoutMs / 2, LocationManager.NETWORK_PROVIDER to timeoutMs - timeoutMs / 2)
        gps -> listOf(LocationManager.GPS_PROVIDER to timeoutMs)
        network -> listOf(LocationManager.NETWORK_PROVIDER to timeoutMs)
        else -> emptyList()
    }
}

/** One current fix for the ventana de llegada, trying [arrivalProviders] in turn within [timeoutMs] in all. */
suspend fun arrivalLocation(context: Context, timeoutMs: Long = 20_000): Located {
    if (!hasLocationPermission(context)) return Located.Unavailable
    val manager = context.getSystemService(LocationManager::class.java) ?: return Located.Unavailable
    for ((provider, budget) in arrivalProviders(android.os.Build.VERSION.SDK_INT, { manager.enabled(it) }, timeoutMs)) {
        manager.fix(context, provider, budget)?.let { return it }
    }
    return Located.Unavailable
}

private fun LocationManager.enabled(provider: String) = runCatching { isProviderEnabled(provider) }.getOrDefault(false)

@SuppressLint("MissingPermission") // Callers check hasLocationPermission first.
private suspend fun LocationManager.fix(context: Context, provider: String, timeoutMs: Long): Located.Position? {
    val location = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<android.location.Location?> { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            val deliver = { location: android.location.Location? -> if (continuation.isActive) continuation.resume(location) }
            try {
                if (provider == FUSED_PROVIDER && android.os.Build.VERSION.SDK_INT >= 31) {
                    // High accuracy: the fused provider then combines GPS with Wi-Fi and cells, instead of the
                    // balanced default that may wait for the network alone.
                    getCurrentLocation(provider, android.location.LocationRequest.Builder(0L)
                        .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setDurationMillis(timeoutMs).build(), cancel, ContextCompat.getMainExecutor(context)) { deliver(it) }
                } else {
                    LocationManagerCompat.getCurrentLocation(
                        this@fix, provider, cancel, ContextCompat.getMainExecutor(context),
                    ) { deliver(it) }
                }
            } catch (_: RuntimeException) {
                // Unknown provider or permission withdrawn meanwhile: no fix from this one.
                if (continuation.isActive) continuation.resume(null)
            }
        }
    } ?: return null
    return Located.Position(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null)
}
