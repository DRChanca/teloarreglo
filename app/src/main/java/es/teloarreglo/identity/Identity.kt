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

enum class AccessError { CREDENTIALS, UNVERIFIED, EXPIRED, NETWORK, SERVICE, CONFIGURATION, INPUT }
enum class AccessStage { CHECKING, SIGNED_OUT, CONNECTED, UNAVAILABLE }

data class AccessState(
    val stage: AccessStage = AccessStage.CHECKING,
    val account: Account? = null,
    val busy: Boolean = false,
    val error: AccessError? = null,
)

/** Shared identity boundary. Capabilities come from current_account, never user metadata. */
interface Identity {
    val state: StateFlow<AccessState>
    suspend fun restore()
    suspend fun signIn(email: String, password: String)
    suspend fun signOut()
}
