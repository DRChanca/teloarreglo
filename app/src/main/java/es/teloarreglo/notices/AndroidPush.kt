package es.teloarreglo.notices

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import es.teloarreglo.BuildConfig
import es.teloarreglo.MainActivity
import es.teloarreglo.R
import es.teloarreglo.TeloarregloApplication
import es.teloarreglo.chat.OpenConversation
import es.teloarreglo.chat.showsNotification
import es.teloarreglo.identity.AccessStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Intent extra set when the person opens the app from a notice. */
const val EXTRA_OPEN_NOTICES = "es.teloarreglo.OPEN_NOTICES"
private const val CHANNEL = "notices"

/**
 * Firebase is configured from public client identifiers (no google-services.json, no Firebase plugin), and only
 * when all of them are present. FCM is used as a free transport only: no Firebase data, files or sign-in.
 */
class FirebasePushTokens(context: Context) : PushTokenSource {
    private val app: FirebaseApp? = run {
        val options = listOf(BuildConfig.FCM_PROJECT_ID, BuildConfig.FCM_API_KEY, BuildConfig.FCM_SENDER_ID,
            BuildConfig.FCM_APP_ID).takeIf { values -> values.all { it.isNotEmpty() } }?.let {
            FirebaseOptions.Builder().setProjectId(BuildConfig.FCM_PROJECT_ID).setApiKey(BuildConfig.FCM_API_KEY)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID).setApplicationId(BuildConfig.FCM_APP_ID).build()
        }
        options?.let { FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context, it) }
    }
    override val available: Boolean get() = app != null
    override suspend fun token(): String? = if (app == null) null else FirebaseMessaging.getInstance().token.await()
}

class SharedPushPreferences(context: Context) : PushPreferences {
    private val store = context.getSharedPreferences("push", Context.MODE_PRIVATE)
    override val installationId: String = store.getString("installation_id", null)
        ?: UUID.randomUUID().toString().also { store.edit().putString("installation_id", it).apply() }
    override var enabled: Boolean
        get() = store.getBoolean("enabled", true)
        set(value) { store.edit().putBoolean("enabled", value).apply() }
    override var signedInUser: String?
        get() = store.getString("signed_in_user", null)
        // Written synchronously: after signing out, a push that wakes a new process must already see null.
        set(value) { store.edit().putString("signed_in_user", value).commit() }
}

fun notificationPermissionGranted(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Localized title for each topic; also used by the in-app list. Never includes message text or addresses. */
fun topicText(topic: NoticeTopic): Int = when (topic) {
    NoticeTopic.MESSAGE -> R.string.notices_topic_message
    NoticeTopic.REQUEST -> R.string.notices_topic_request
    NoticeTopic.PROPOSAL -> R.string.notices_topic_proposal
    NoticeTopic.REJECTED -> R.string.notices_topic_rejected
    NoticeTopic.CONFIRMED -> R.string.notices_topic_confirmed
    NoticeTopic.CHANGE -> R.string.notices_topic_change
    NoticeTopic.CHANGE_DECIDED -> R.string.notices_topic_change_decided
    NoticeTopic.PROFESSIONAL_NEAR -> R.string.notices_topic_professional_near
    NoticeTopic.STARTED -> R.string.notices_topic_started
    NoticeTopic.CANCELLED -> R.string.notices_topic_cancelled
    NoticeTopic.DONE -> R.string.notices_topic_done
    NoticeTopic.REMINDER -> R.string.notices_topic_reminder
    NoticeTopic.COMPLETED -> R.string.notices_topic_completed
    NoticeTopic.INCIDENT -> R.string.notices_topic_incident
    NoticeTopic.REVIEW -> R.string.review_notice
    NoticeTopic.APPOINTMENT_OTHER -> R.string.notices_topic_appointment
    NoticeTopic.PROFILE_APPROVED -> R.string.notices_topic_profile_approved
    NoticeTopic.PROFILE_REJECTED -> R.string.notices_topic_profile_rejected
    NoticeTopic.OTHER -> R.string.notices_topic_other
}

/** Receives FCM data messages even with the app closed and shows them only to the account signed in here. */
class TeloMessagingService : FirebaseMessagingService() {
    private val app get() = application as TeloarregloApplication

    override fun onNewToken(token: String) {
        val push = app.push ?: return
        app.applicationScope.launch { push.sync(app.pushPreferences.signedInUser) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (app.push?.accepts(data["recipient_id"]) != true) return
        val conversation = data["conversation_id"]?.takeIf { data["kind"] == "message" && it.isNotEmpty() }
        // The chat on screen for this conversation refreshes at once instead of showing a notification.
        conversation?.let { OpenConversation.pushes.tryEmit(it) }
        if (showsNotification(data["kind"], conversation, OpenConversation.id) && notificationPermissionGranted(this)) {
            notify(data, conversation)
        }
        // The message reached this phone: the sender sees two ticks. Runs on FCM's worker thread, bounded in time.
        if (conversation != null) runBlocking { withTimeoutOrNull(10_000) { markDelivered(conversation) } }
    }

    private suspend fun markDelivered(conversation: String) {
        val chat = app.chat ?: return
        try {
            if (app.identity.state.value.stage != AccessStage.CONNECTED) app.identity.restore()
            if (app.identity.state.value.stage == AccessStage.CONNECTED) chat.markDelivered(conversation)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }

    private fun notify(data: Map<String, String>, conversation: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notices_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_NOTICES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notice)
            .setContentTitle(getString(topicText(topicOf(data["kind"].orEmpty(), data["event"]?.ifEmpty { null }))))
            .setContentText(getString(R.string.notices_open_app))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        // Tag = conversation or notice: a retried push replaces the same notification instead of adding another.
        val tag = conversation ?: data["notice_id"]
        try { manager.notify(tag, 0, notification) } catch (_: SecurityException) { }
    }
}
