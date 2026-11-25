package es.teloarreglo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import es.teloarreglo.appointments.*
import es.teloarreglo.arrival.ArrivalSharing
import es.teloarreglo.search.hasLocationPermission
import java.time.Instant

private fun backgroundLocationGranted(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
    PackageManager.PERMISSION_GRANTED

/**
 * «Compartir mi ubicación» in the professional's confirmed appointment (#85). Voluntary: the explanation comes first,
 * then the location, background location and notification permissions; refusing any of them blocks nothing.
 */
@Composable
internal fun ArrivalSharingSection(appointment: Appointment, accountId: String, clientName: String?) {
    val context = LocalContext.current
    val app = context.applicationContext as? TeloarregloApplication ?: return
    val start = appointment.confirmedProposal?.startAt ?: return
    if (app.arrivalRound == null || appointment.professionalId != accountId ||
        appointment.stage != AppointmentStage.CONFIRMED || firstArrivalStep(start, Instant.now()) == ArrivalStep.Stop) return
    val name = clientName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.arrival_client_fallback)
    var chosen by remember(appointment.id) { mutableStateOf(app.arrivalChoices.get(appointment.id) != null) }
    val sharing by ArrivalSharing.sharing.collectAsState()
    var explain by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(backgroundLocationGranted(context)) }

    // A kept choice is applied again whenever the card is shown (also with a new start after an accepted change).
    LaunchedEffect(appointment.id, chosen, start) {
        if (chosen) ArrivalSharing.choose(context, ArrivalChoice(appointment.id, name, start))
    }
    fun finish() {
        background = backgroundLocationGranted(context)
        chosen = true
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !es.teloarreglo.notices.notificationPermissionGranted(context))
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else finish()
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askNotifications() }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.none { it }) { denied = true; return@rememberLauncherForActivityResult }
        if (!backgroundLocationGranted(context)) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else askNotifications()
    }
    fun accept() {
        denied = false
        if (!hasLocationPermission(context)) foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
        else if (!backgroundLocationGranted(context)) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else askNotifications()
    }

    Column(Modifier.fillMaxWidth().testTag("arrival_sharing_${appointment.id}")) {
        if (!chosen) {
            Text(stringResource(R.string.arrival_offer), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { explain = true }, modifier = Modifier.testTag("arrival_share")) {
                Text(stringResource(R.string.arrival_share))
            }
            if (denied) Text(stringResource(R.string.arrival_denied), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("arrival_denied"))
        } else {
            Text(stringResource(if (appointment.id in sharing) R.string.arrival_sharing_with else R.string.arrival_will_share, name),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("arrival_state"))
            if (!background) Text(stringResource(R.string.arrival_no_background), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { ArrivalSharing.stop(context, appointment.id); chosen = false },
                modifier = Modifier.testTag("arrival_stop")) { Text(stringResource(R.string.arrival_stop)) }
        }
    }

    if (explain) AlertDialog(onDismissRequest = { explain = false },
        title = { Text(stringResource(R.string.arrival_explain_title)) },
        text = { Text(stringResource(R.string.arrival_explain)) },
        dismissButton = { TextButton(onClick = { explain = false }, modifier = Modifier.testTag("arrival_explain_dismiss")) {
            Text(stringResource(R.string.arrival_not_now))
        } },
        confirmButton = { TextButton(onClick = { explain = false; accept() }, modifier = Modifier.testTag("arrival_explain_accept")) {
            Text(stringResource(R.string.arrival_continue))
        } })
}
