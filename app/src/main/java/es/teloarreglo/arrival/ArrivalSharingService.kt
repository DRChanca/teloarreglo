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
import es.teloarreglo.appointments.decodeArrivalChoice
import es.teloarreglo.appointments.encodeArrivalChoice
import es.teloarreglo.appointments.firstArrivalStep
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.search.Located
import es.teloarreglo.search.arrivalLocation
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
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/** The professional's choices (#85), on this phone only; never backed up (allowBackup=false). */
class SharedArrivalChoices(context: Context) : ArrivalChoices {
    private val store = context.getSharedPreferences("arrival", Context.MODE_PRIVATE)
    override fun all() = store.all.keys.mapNotNull(::get)
    override fun get(appointmentId: String): ArrivalChoice? =
        store.getString(appointmentId, null)?.let { decodeArrivalChoice(appointmentId, it) }
    // Written synchronously: a service woken right after must already see the change.
    override fun save(choice: ArrivalChoice) {
        store.edit().putString(choice.appointmentId, encodeArrivalChoice(choice)).commit()
    }
    override fun forget(appointmentId: String) { store.edit().remove(appointmentId).commit() }
}

/**
 * Entry points of «compartir mi ubicación» (#85). Nothing is sent outside the ventana de llegada: before it, only an
 * alarm waits for the earliest opening; from then on each round asks the server the window state without any
 * position and sends one only while it is open (see [ArrivalRound]).
 */
object ArrivalSharing {
    private const val ACTION_START = "es.teloarreglo.arrival.START"
    private const val ACTION_STOP = "es.teloarreglo.arrival.STOP"
    internal const val EXTRA_APPOINTMENT = "appointment_id"
    /**
     * The window is asked before starting the service, within the few seconds an exact alarm allows an app to start a
     * foreground service from the background; without an answer by then the service starts and asks itself.
     */
    private const val CHECK_BEFORE_START_MS = 5_000L

    internal val running = MutableStateFlow<Set<String>>(emptySet())
    /** Appointments whose position this phone is sending right now (the service is in the foreground). */
    val sharing: StateFlow<Set<String>> get() = running

    private fun app(context: Context) = context.applicationContext as TeloarregloApplication

    /**
     * «Compartir mi ubicación» chosen (once the location permission is granted): a new choice, which the first round
     * tells the server before any position (it clears a previous «Dejar de compartir»); then start now or wait.
     */
    fun choose(context: Context, choice: ArrivalChoice) {
        app(context).arrivalChoices.save(choice.copy(announced = false))
        resume(context, choice.appointmentId)
    }

    /** The card shows a kept choice: the name and the start (moved by an accepted change) are updated and it is applied again. */
    fun keep(context: Context, appointmentId: String, clientName: String, start: Instant) {
        val choices = app(context).arrivalChoices
        val kept = choices.get(appointmentId) ?: return
        if (kept.clientName != clientName || kept.start != start) choices.save(kept.copy(clientName = clientName, start = start))
        resume(context, appointmentId)
    }

    /** Applies a kept choice again (card shown, alarm fired, phone restarted); does nothing without one. [done] runs at the end. */
    fun resume(context: Context, appointmentId: String, done: () -> Unit = {}) {
        val application = app(context)
        val choice = application.arrivalChoices.get(appointmentId)
        if (choice == null || appointmentId in running.value) return done()
        when (val step = firstArrivalStep(choice.start, Instant.now())) {
            is ArrivalStep.WaitUntil -> { ArrivalAlarms.schedule(context, appointmentId, step.at); done() }
            is ArrivalStep.SendAt -> startWhenOpen(context, appointmentId, done)
            ArrivalStep.Stop -> { application.arrivalChoices.forget(appointmentId); done() }
        }
    }

    /**
     * Inside the possible window: asks the server first (no position), so the fixed notification only appears when
     * there is something to send. «Aún no abierta» (another appointment started) waits for its opening with an alarm.
     */
    private fun startWhenOpen(context: Context, appointmentId: String, done: () -> Unit) {
        val application = app(context)
        val round = application.arrivalRound ?: return done()
        application.applicationScope.launch {
            try {
                val step = withTimeoutOrNull(CHECK_BEFORE_START_MS) {
                    ensureSession(application)
                    round.check(appointmentId, Instant.now())
                } ?: ArrivalStep.SendAt(Instant.now())
                when (step) {
                    is ArrivalStep.WaitUntil -> ArrivalAlarms.schedule(context, appointmentId, step.at)
                    is ArrivalStep.SendAt -> if (appointmentId !in running.value) startService(context, appointmentId)
                    ArrivalStep.Stop -> ArrivalAlarms.cancel(context, appointmentId)
                }
            } finally { done() }
        }
    }

    /**
     * Brings kept choices up to date with the server and applies them again ([ids], or all of them): a start moved by
     * an accepted change re-schedules the alarm, a closed window or a stop from elsewhere forgets the choice. Called
     * when the app comes to the foreground, when a push about one of these appointments arrives and after a restart.
     * Without an answer the kept start is used as it is.
     */
    suspend fun sync(context: Context, ids: Collection<String>? = null) {
        val application = app(context)
        val round = application.arrivalRound ?: return
        for (choice in application.arrivalChoices.all()) {
            val id = choice.appointmentId
            if (ids != null && id !in ids) continue
            if (id in running.value) continue // Its rounds ask the server every ~30 s.
            val step = withTimeoutOrNull(CHECK_BEFORE_START_MS) {
                ensureSession(application)
                round.check(id, Instant.now())
            }
            if (step == ArrivalStep.Stop) ArrivalAlarms.cancel(context, id) else resume(context, id)
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

    /**
     * Before signing out: every choice of this phone is stopped on the server (while the session still works),
     * forgotten, its alarm cancelled and the service ended, so nothing is sent for the account that leaves.
     */
    suspend fun stopAll(context: Context) {
        val application = app(context)
        val ids = (application.arrivalChoices.all().map { it.appointmentId } + running.value).distinct()
        for (id in ids) {
            ArrivalAlarms.cancel(context, id)
            application.arrivalChoices.forget(id)
            application.arrivalRound?.let { round -> withTimeoutOrNull(CHECK_BEFORE_START_MS) { round.stopSharing(id) } }
        }
        if (running.value.isNotEmpty()) {
            context.startService(Intent(context, ArrivalSharingService::class.java).setAction(ACTION_STOP))
        }
    }

    /** Woken by an alarm, a push or a restart the app may have no session in memory yet; the stored one is restored. */
    internal suspend fun ensureSession(application: TeloarregloApplication) {
        try { if (application.identity.state.value.stage != AccessStage.CONNECTED) application.identity.restore() }
        catch (error: Exception) { if (error is CancellationException) throw error }
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
        Intent(context, ArrivalAlarmReceiver::class.java).putExtra(ArrivalSharing.EXTRA_APPOINTMENT, appointmentId),
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
        val appointmentId = intent.getStringExtra(ArrivalSharing.EXTRA_APPOINTMENT) ?: return
        // Kept alive while the window is asked (a few seconds at most) before starting the service.
        val pending = goAsync()
        ArrivalSharing.resume(context, appointmentId) { pending.finish() }
    }
}

/** Alarms do not survive a restart (or an update of the app): every kept choice is brought up to date and re-scheduled. */
class ArrivalBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as TeloarregloApplication
        if (app.arrivalChoices.all().isEmpty()) return
        val pending = goAsync()
        app.applicationScope.launch { try { ArrivalSharing.sync(context) } finally { pending.finish() } }
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
        // Shown before anything is sent: the professional always sees when the client can see them. Android requires
        // it within seconds of startForegroundService, before the server could be asked, which is why the window is
        // asked before starting the service ([ArrivalSharing.resume]); it only shows briefly here if the window
        // changed in between or that answer did not arrive in time.
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
        ArrivalSharing.ensureSession(app)
        while (true) {
            when (val step = round.run(appointmentId, Instant.now())) {
                is ArrivalStep.SendAt -> delay(Duration.between(Instant.now(), step.at).toMillis().coerceAtLeast(0))
                is ArrivalStep.WaitUntil -> { ArrivalAlarms.schedule(this, appointmentId, step.at); return }
                ArrivalStep.Stop -> return
            }
        }
    }

    /**
     * Stops [appointmentId], or every appointment from the notification. A report already on its way may still reach
     * the server after «dejar de compartir»: the server refuses it (stop mark), so the client never sees it.
     */
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
    when (val located = arrivalLocation(context, timeoutMs = 20_000)) {
        is Located.Position -> ArrivalFix(located.lat, located.lng, located.accuracyM?.toDouble())
        Located.Unavailable -> null
    }
