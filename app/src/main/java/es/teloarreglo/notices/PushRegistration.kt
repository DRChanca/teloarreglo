package es.teloarreglo.notices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** FCM token of this installation; [available] is false when the build has no Firebase configuration. */
interface PushTokenSource {
    val available: Boolean
    suspend fun token(): String?
}

/** Small per-installation state kept on the device (no secrets): its id, the person's choice and who is signed in. */
interface PushPreferences {
    val installationId: String
    var enabled: Boolean
    /** Account whose pushes this device may show; cleared before signing out. */
    var signedInUser: String?
}

enum class PushStatus {
    /** Build without Firebase: notices are only in the app. */
    NOT_CONFIGURED,
    /** Android notification permission missing (Android 13+) or blocked in system settings. */
    PERMISSION_NEEDED,
    /** Turned off by the person on this device. */
    OFF,
    ACTIVE,
    /** Registration could not reach the server; retried on the next start or token change. */
    FAILED,
    SIGNED_OUT,
}

/**
 * Keeps this installation registered for the signed-in account (#32). Push is best effort: any failure here is
 * reported as a status, never thrown into the caller, and never affects notices, appointments or payments.
 */
class PushRegistration(
    private val devices: PushDevices,
    private val tokens: PushTokenSource,
    private val preferences: PushPreferences,
    private val permissionGranted: () -> Boolean,
) {
    private val _status = MutableStateFlow(if (tokens.available) PushStatus.SIGNED_OUT else PushStatus.NOT_CONFIGURED)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /** Registers for [userId] (null: nobody signed in). Safe to call on every start, sign-in or token refresh. */
    suspend fun sync(userId: String?) {
        if (userId == null) { preferences.signedInUser = null; _status.value = idle(); return }
        preferences.signedInUser = userId
        if (!tokens.available) { _status.value = PushStatus.NOT_CONFIGURED; return }
        val granted = permissionGranted()
        val wanted = preferences.enabled && granted
        _status.value = try {
            val token = tokens.token() ?: error("no_token")
            devices.register(preferences.installationId, token, wanted)
            when { !preferences.enabled -> PushStatus.OFF; !granted -> PushStatus.PERMISSION_NEEDED; else -> PushStatus.ACTIVE }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            PushStatus.FAILED
        }
    }

    suspend fun setEnabled(on: Boolean) {
        preferences.enabled = on
        sync(preferences.signedInUser)
    }

    /** Called before the session ends: the device stops showing this account's pushes even if offline. */
    suspend fun beforeSignOut() {
        preferences.signedInUser = null
        _status.value = idle()
        if (!tokens.available) return
        try {
            devices.unregister(preferences.installationId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // The server row is moved by the next sign-in on this device; meanwhile accepts() discards its pushes.
        }
    }

    /** Whether a received push addressed to [recipientId] belongs to the account signed in here. */
    fun accepts(recipientId: String?): Boolean = recipientId != null && recipientId == preferences.signedInUser

    private fun idle() = if (tokens.available) PushStatus.SIGNED_OUT else PushStatus.NOT_CONFIGURED
}
