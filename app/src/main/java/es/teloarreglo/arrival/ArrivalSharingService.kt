package es.teloarreglo.arrival

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import es.teloarreglo.MainActivity
import es.teloarreglo.R
import es.teloarreglo.TeloarregloApplication
import es.teloarreglo.appointments.ArrivalChoice
import es.teloarreglo.appointments.ArrivalChoices
import es.teloarreglo.appointments.ArrivalFix
import es.teloarreglo.appointments.ArrivalRound
import es.teloarreglo.appointments.ArrivalStep
import es.teloarreglo.appointments.firstArrivalStep
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.search.Located
import es.teloarreglo.search.currentLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** The professional's choices (#85), on this phone only; never backed up (allowBackup=false). */
class SharedArrivalChoices(context: Context) : ArrivalChoices {
    private val store = context.getSharedPreferences("arrival", Context.MODE_PRIVATE)
    override fun all() = store.all.keys.mapNotNull(::get)
    override fun get(appointmentId: String): ArrivalChoice? {
        val raw = store.getString(appointmentId, null) ?: return null
        val start = raw.substringBefore('|').toLongOrNull() ?: return null
        return ArrivalChoice(appointmentId, raw.substringAfter('|'), Instant.ofEpochSecond(start))
    }
    // Written synchronously: a service woken right after must already see the change.
    override fun save(choice: ArrivalChoice) {
        store.edit().putString(choice.appointmentId, "${choice.start.epochSecond}|${choice.clientName}").commit()
    }
    override fun forget(appointmentId: String) { store.edit().remove(appointmentId).commit() }
}

/**
 * Entry points of «compartir mi ubicación» (#85). Nothing is sent outside the ventana de llegada: before it, only an
 * alarm waits for the earliest opening, and from then on each answer of the server decides (see [ArrivalRound]).
 */
object ArrivalSharing {
    private const val ACTION_START = "es.teloarreglo.arrival.START"
    private const val ACTION_STOP = "es.teloarreglo.arrival.STOP"
    private const val EXTRA_APPOINTMENT = "appointment_id"

    internal val running = MutableStateFlow<Set<String>>(emptySet())
    /** Appointments whose position this phone is sending right now (the service is in the foreground). */
    val sharing: StateFlow<Set<String>> get() = running

    private fun app(context: Context) = context.applicationContext as TeloarregloApplication

    /** The professional accepted: keep the choice and start now or wait for the opening. */
    fun choose(context: Context, choice: ArrivalChoice) {
        app(context).arrivalChoices.save(choice)
        resume(context, choice.appointmentId)
    }

    /** Applies a kept choice again (card shown, alarm fired); does nothing without one. */
    fun resume(context: Context, appointmentId: String) {
        val application = app(context)
        val choice = application.arrivalChoices.get(appointmentId) ?: return
        if (appointmentId in running.value) return
        when (val step = firstArrivalStep(choice.start, Instant.now())) {
            is ArrivalStep.WaitUntil -> ArrivalAlarms.schedule(context, appointmentId, step.at)
            is ArrivalStep.SendAt -> startService(context, appointmentId)
            ArrivalStep.Stop -> application.arrivalChoices.forget(appointmentId)
        }
    }

    /** «Dejar de compartir», from the card or the notification: the client sees «sin compartir» at once. */
    fun stop(context: Context, appointmentId: String) {
        ArrivalAlarms.cancel(context, appointmentId)
        if (appointmentId in running.value) {
            context.startService(Intent(context, ArrivalSharingService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_APPOINTMENT, appointmentId))
        } else {
            val application = app(context)
            val round = application.arrivalRound ?: return application.arrivalChoices.forget(appointmentId)
            application.applicationScope.launch { round.stopSharing(appointmentId) }
        }
    }

    internal fun startService(context: Context, appointmentId: String) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ArrivalSharingService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_APPOINTMENT, appointmentId))
        } catch (_: RuntimeException) {
            // Started from the background without being allowed (no exact alarm, no background location): the
            // choice stays and the card starts it as soon as the professional opens the appointment.
        }
    }

    internal fun stopIntent(context: Context, appointmentId: String?): PendingIntent = PendingIntent.getService(context,
        appointmentId.hashCode(), Intent(context, ArrivalSharingService::class.java).setAction(ACTION_STOP)
            .putExtra(EXTRA_APPOINTMENT, appointmentId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    internal fun isStart(intent: Intent?) = intent?.action == ACTION_START
    internal fun isStop(intent: Intent?) = intent?.action == ACTION_STOP
    internal fun appointmentOf(intent: Intent?) = intent?.getStringExtra(EXTRA_APPOINTMENT)
}

/** Wakes the phone at the opening of the window; exact when allowed, so the service may start from the background. */
object ArrivalAlarms {
    private fun pending(context: Context, appointmentId: String, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, appointmentId.hashCode(),
        Intent(context, ArrivalAlarmReceiver::class.java).putExtra("appointment_id", appointmentId),
        PendingIntent.FLAG_IMMUTABLE or flags)

    fun schedule(context: Context, appointmentId: String, at: Instant) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pending(context, appointmentId, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        try {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    fun cancel(context: Context, appointmentId: String) {
        val intent = pending(context, appointmentId, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(intent)
        intent.cancel()
    }
}

class ArrivalAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("appointment_id")?.let { ArrivalSharing.resume(context, it) }
    }
}

/**
 * Foreground service of type location (#85): while the ventana de llegada is open it reports the position about every
 * 30 s, with the fixed notification «Compartiendo tu ubicación con {cliente}» and its «Dejar de compartir» action.
 * It ends on its own when the server says the window is closed (or not yet open), or refuses the position.
 */
class ArrivalSharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val app get() = application as TeloarregloApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appointmentId = ArrivalSharing.appointmentOf(intent)
        when {
            ArrivalSharing.isStart(intent) && appointmentId != null -> start(appointmentId)
            ArrivalSharing.isStop(intent) -> stop(appointmentId)
            else -> refresh()
        }
        return START_NOT_STICKY
    }

    private fun start(appointmentId: String) {
        // Shown before anything is sent: the professional always sees when the client can see them. Also called
        // first when there turns out to be nothing to send, since a started foreground service must show it.
        val shown = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(names(appointmentId)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            true
        } catch (_: RuntimeException) { false } // No location permission any more, or not allowed from the background.
        val round = app.arrivalRound
        if (!shown || round == null || app.arrivalChoices.get(appointmentId) == null) return refresh()
        if (jobs[appointmentId]?.isActive == true) return
        jobs[appointmentId] = scope.launch {
            try { share(appointmentId, round) } finally { jobs.remove(appointmentId); refresh() }
        }
        refresh()
    }

    private suspend fun share(appointmentId: String, round: ArrivalRound) {
        ensureSession()
        while (true) {
            when (val step = round.run(appointmentId, Instant.now())) {
                is ArrivalStep.SendAt -> delay(Duration.between(Instant.now(), step.at).toMillis().coerceAtLeast(0))
                is ArrivalStep.WaitUntil -> { ArrivalAlarms.schedule(this, appointmentId, step.at); return }
                ArrivalStep.Stop -> return
            }
        }
    }

    /** Woken by the alarm the app may have no session in memory yet; the stored one is restored. */
    private suspend fun ensureSession() {
        try { if (app.identity.state.value.stage != AccessStage.CONNECTED) app.identity.restore() }
        catch (error: Exception) { if (error is CancellationException) throw error }
    }

    /** Stops [appointmentId], or every appointment from the notification. The job ends before the server is told. */
    private fun stop(appointmentId: String?) {
        val ids = appointmentId?.let(::listOf) ?: jobs.keys.toList()
        val round = app.arrivalRound
        for (id in ids) {
            jobs.remove(id)?.cancel()
            ArrivalAlarms.cancel(this, id)
            if (round != null) app.applicationScope.launch { round.stopSharing(id) } else app.arrivalChoices.forget(id)
        }
        refresh()
    }

    private fun names(extra: String? = null): String =
        (jobs.keys + listOfNotNull(extra)).mapNotNull { app.arrivalChoices.get(it)?.clientName }.distinct()
            .joinToString(", ").ifEmpty { getString(R.string.arrival_client_fallback) }

    private fun refresh() {
        ArrivalSharing.running.value = jobs.keys.toSet()
        if (jobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(names()))
        }
    }

    private fun notification(clients: String): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.arrival_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle(getString(R.string.arrival_sharing_with, clients))
            .setContentText(getString(R.string.arrival_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.arrival_stop), ArrivalSharing.stopIntent(this, null))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        ArrivalSharing.running.value = emptySet()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "arrival"
        const val NOTIFICATION_ID = 8500
    }
}

/** One fix for the round; null when there is no permission, no provider or no fix in time. */
internal suspend fun arrivalFix(context: Context): ArrivalFix? =
    when (val located = currentLocation(context, timeoutMs = 20_000)) {
        is Located.Position -> ArrivalFix(located.lat, located.lng, located.accuracyM?.toDouble())
        Located.Unavailable -> null
    }
