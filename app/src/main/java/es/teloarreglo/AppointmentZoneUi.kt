package es.teloarreglo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import es.teloarreglo.appointments.Appointment
import es.teloarreglo.catalogue.AppointmentZone
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.WorkZoneException
import es.teloarreglo.catalogue.WorkZoneFailure
import es.teloarreglo.catalogue.WorkZones
import es.teloarreglo.catalogue.ZoneConcejo
import es.teloarreglo.catalogue.outsideZone
import es.teloarreglo.catalogue.showsZoneNotice
import es.teloarreglo.catalogue.withConcejo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The work zone only informs (ADR 0001). On the appointment card, when the address is outside the
 * professional's zone, the professional reads it and can add that concejo in one tap (applied at once,
 * without review); the client reads a neutral line. Nothing here ever blocks another action: if the
 * zone cannot be read, nothing is shown.
 */
@Composable
internal fun AppointmentZoneNotice(
    appointment: Appointment, professional: Boolean, professionalName: String, profileId: String,
    zones: WorkZones, publicZone: List<Coverage>?, scope: CoroutineScope, onZoneChanged: (List<Coverage>) -> Unit,
) {
    var zone by remember(appointment.id) { mutableStateOf<AppointmentZone?>(null) }
    var busy by remember(appointment.id) { mutableStateOf(false) }
    var failure by remember(appointment.id) { mutableStateOf<WorkZoneFailure?>(null) }
    var failed by remember(appointment.id) { mutableStateOf(false) }
    var added by remember(appointment.id) { mutableStateOf<String?>(null) }
    // Once per version (an address change bumps it) and again when the zone itself changes.
    LaunchedEffect(zones, appointment.id, appointment.version, publicZone) {
        zone = try { zones.appointmentZone(appointment.id) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { zone }
    }
    added?.takeIf { professional }?.let { name ->
        Text(stringResource(R.string.zone_added, name), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("appointment_zone_added_${appointment.id}").semantics { liveRegion = LiveRegionMode.Polite })
    }
    val shown = zone?.takeIf { showsZoneNotice(appointment.stage, it) } ?: return
    val concejo = shown.concejo
    if (!professional) {
        Text(stringResource(R.string.zone_appointment_outside_client, professionalName, concejo.name),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("appointment_zone_${appointment.id}"))
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("appointment_zone_${appointment.id}")) {
        Column(Modifier.padding(TeloTheme.spacing.medium), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
            Text(stringResource(R.string.zone_appointment_outside_professional, concejo.name), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(enabled = !busy, modifier = Modifier.testTag("appointment_zone_add_${appointment.id}"), onClick = {
                busy = true; failed = false; failure = null
                scope.launch {
                    try {
                        val current = publicZone ?: zones.publicZone(profileId) ?: throw IllegalStateException("zone_unavailable")
                        val updated = zones.setMine(current.withConcejo(concejo))
                        // The notice disappears at once; the next read of appointment_zone confirms it.
                        zone = shown.copy(inZone = true); added = concejo.name
                        onZoneChanged(updated)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = (e as? WorkZoneException)?.failure; failed = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.zone_add_concejo, concejo.name)) }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(if (failure == WorkZoneFailure.NOT_PUBLISHED) R.string.zone_error_not_published
                    else R.string.zone_add_error, concejo.name), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("appointment_zone_error_${appointment.id}"))
            }
        }
    }
}

/**
 * Before a request is sent: the concejo of the chosen point, compared with the profile's public zone.
 * Informative only, no extra step; unknown point or zone shows nothing.
 */
@Composable
internal fun RequestZoneLine(zones: WorkZones, publicZone: List<Coverage>?, lat: Double?, lng: Double?) {
    var concejo by remember { mutableStateOf<ZoneConcejo?>(null) }
    LaunchedEffect(zones, lat, lng) {
        concejo = null
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) return@LaunchedEffect
        delay(400) // typed coordinates: wait until the person stops typing
        concejo = try { zones.concejoAt(lat, lng) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    val outside = concejo?.takeIf { outsideZone(publicZone, it) } ?: return
    Text(stringResource(R.string.zone_request_outside, outside.name), style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("appointment_request_zone").semantics { liveRegion = LiveRegionMode.Polite })
}
