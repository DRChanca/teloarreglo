package es.teloarreglo

import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.AccessState
import es.teloarreglo.identity.Account
import es.teloarreglo.identity.Identity
import kotlinx.coroutines.flow.MutableStateFlow

class TestIdentity : Identity {
    override val state = MutableStateFlow(AccessState())
    var savedAccount: Account? = null
    var failure: AccessError? = AccessError.CREDENTIALS
    override suspend fun restore() {
        state.value = savedAccount?.let { AccessState(AccessStage.CONNECTED, it) }
            ?: AccessState(AccessStage.SIGNED_OUT)
    }
    override suspend fun signIn(email: String, password: String) {
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
}
