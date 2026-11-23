package es.teloarreglo

import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessNotice
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.AccessState
import es.teloarreglo.identity.Account
import es.teloarreglo.identity.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

class TestIdentity : Identity {
    override val state = MutableStateFlow(AccessState(AccessStage.SIGNED_OUT))
    var savedAccount: Account? = null
    var failure: AccessError? = AccessError.CREDENTIALS
    var signInResponse: CompletableDeferred<Unit>? = null
    var signInDelayMillis: Long = 0
    override suspend fun restore() {
        state.value = savedAccount?.let { AccessState(AccessStage.CONNECTED, it) }
            ?: AccessState(AccessStage.SIGNED_OUT)
    }
    override suspend fun signIn(email: String, password: String) {
        state.value = AccessState(AccessStage.SIGNED_OUT, busy = true)
        signInResponse?.await()
        delay(signInDelayMillis)
        val error = failure
        if (error != null) {
            state.value = AccessState(AccessStage.SIGNED_OUT, error = error)
        } else {
            savedAccount = Account("account-12", email)
            restore()
        }
    }
    override suspend fun signOut() {
        savedAccount = null
        state.value = AccessState(AccessStage.SIGNED_OUT)
    }
    var signedUpName: String? = null
    override suspend fun signUp(email: String, password: String, name: String) {
        signedUpName = name
        state.value = AccessState(
            AccessStage.AWAITING_VERIFICATION, pendingEmail = email, notice = AccessNotice.VERIFICATION_SENT,
        )
    }
    override suspend fun resendVerification(email: String) = signUp(email, "", "")
    override suspend fun requestPasswordReset(email: String) {
        state.value = AccessState(AccessStage.SIGNED_OUT, notice = AccessNotice.RESET_SENT)
    }
    override suspend fun handleAuthLink(link: String) = false
    override suspend fun setNewPassword(password: String) = Unit
    override suspend fun completeGoogleSignIn() = Unit
    override fun reportGoogleFailure() {
        state.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.GOOGLE)
    }
    override fun dismiss() {
        state.value = AccessState(AccessStage.SIGNED_OUT)
    }
}
