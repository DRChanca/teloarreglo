package es.teloarreglo.identity

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Account(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("is_administrator") val isAdministrator: Boolean = false,
    @SerialName("professional_profile_id") val professionalProfileId: String? = null,
    @SerialName("can_hire") val canHire: Boolean = true,
)

enum class AccessError {
    CREDENTIALS, UNVERIFIED, EXPIRED, NETWORK, SERVICE, CONFIGURATION, INPUT,
    /** Verification/recovery link expired, already used or opened on another device. */
    LINK_INVALID,
    /** Auth refused to send more emails or attempts for now. */
    RATE_LIMITED,
    /** Password rejected by Auth's policy (minimum length, strength). */
    WEAK_PASSWORD,
    /** The Google sign-in could not be completed (configuration or provider failure). */
    GOOGLE,
}

enum class AccessStage {
    CHECKING, SIGNED_OUT, CONNECTED, UNAVAILABLE,
    /** Account created or verification resent: waiting for the user to open the email. */
    AWAITING_VERIFICATION,
    /** A valid recovery link opened a session that may only be used to choose a new password. */
    RECOVERING_PASSWORD,
}

/** Informational outcomes that are not errors. */
enum class AccessNotice { VERIFICATION_SENT, RESET_SENT, PASSWORD_CHANGED }

data class AccessState(
    val stage: AccessStage = AccessStage.CHECKING,
    val account: Account? = null,
    val busy: Boolean = false,
    val error: AccessError? = null,
    /** Email waiting for verification; kept so the user can resend without retyping. */
    val pendingEmail: String? = null,
    val notice: AccessNotice? = null,
)

/** Shared identity boundary. Capabilities come from current_account, never user metadata. */
interface Identity {
    val state: StateFlow<AccessState>
    suspend fun restore()
    suspend fun signIn(email: String, password: String)
    suspend fun signOut()

    /** Creates an account; it cannot sign in until the email is verified. */
    suspend fun signUp(email: String, password: String)
    suspend fun resendVerification(email: String)

    /** Same visible result whether or not the account exists, so emails are not disclosed. */
    suspend fun requestPasswordReset(email: String)

    /**
     * Handles a link that returned to the app from an email (verification or recovery).
     * Returns false if [link] is not an authentication link for this app.
     */
    suspend fun handleAuthLink(link: String): Boolean
    suspend fun setNewPassword(password: String)

    /** Native Google sign-in finished in the UI layer: verify the resulting session on the server. */
    suspend fun completeGoogleSignIn()
    fun reportGoogleFailure()

    /** Back to the sign-in form from verification or recovery screens. */
    fun dismiss()
}
