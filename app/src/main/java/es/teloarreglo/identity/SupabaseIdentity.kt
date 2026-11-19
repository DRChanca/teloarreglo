package es.teloarreglo.identity

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

class SupabaseIdentity(private val client: SupabaseClient, scope: CoroutineScope) : Identity {
    private val mutableState = MutableStateFlow(AccessState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    init {
        scope.launch {
            client.auth.sessionStatus.collect { status ->
                mutex.withLock {
                    // An SDK event may have waited behind a successful sign-in/restore.
                    if (client.auth.sessionStatus.value == status &&
                        mutableState.value.stage == AccessStage.CONNECTED
                    ) {
                        when (status) {
                            is SessionStatus.RefreshFailure -> {
                                val reason = when (status.cause) {
                                    is RefreshFailureCause.NetworkError -> AccessError.NETWORK
                                    is RefreshFailureCause.InternalServerError -> AccessError.SERVICE
                                }
                                mutableState.value = AccessState(AccessStage.UNAVAILABLE, error = reason)
                            }
                            is SessionStatus.NotAuthenticated ->
                                mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.EXPIRED)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    override suspend fun restore() = mutex.withLock {
        mutableState.value = AccessState(AccessStage.CHECKING)
        try {
            client.auth.awaitInitialization()
            val found = client.auth.currentSessionOrNull() != null || client.auth.loadFromStorage(autoRefresh = false)
            if (!found) {
                mutableState.value = AccessState(AccessStage.SIGNED_OUT)
            } else {
                // A saved token is not proof of a live session. Refresh and ask the server.
                client.auth.refreshCurrentSession()
                connect()
                client.auth.startAutoRefreshForCurrentSession()
            }
        } catch (error: Exception) {
            handleFailure(error, restoring = true)
        }
    }

    override suspend fun signIn(email: String, password: String) = mutex.withLock {
        if (email.isBlank() || password.isEmpty()) {
            mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.INPUT)
            return@withLock
        }
        mutableState.value = AccessState(AccessStage.SIGNED_OUT, busy = true)
        try {
            client.auth.signInWith(Email) { this.email = email; this.password = password }
            connect()
        } catch (error: Exception) {
            handleFailure(error, restoring = false)
        }
    }

    override suspend fun signOut() = mutex.withLock {
        val previous = mutableState.value
        mutableState.value = previous.copy(busy = true, error = null)
        try {
            client.auth.signOut()
            mutableState.value = AccessState(AccessStage.SIGNED_OUT)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Do not claim logout succeeded if the remote request failed.
            mutableState.value = previous.copy(busy = false, error = classify(error))
        }
    }

    private suspend fun connect() {
        val account = client.postgrest.rpc("current_account").decodeAs<Account>()
        check(account.userId == client.auth.currentUserOrNull()?.id)
        mutableState.value = AccessState(AccessStage.CONNECTED, account)
    }

    private suspend fun handleFailure(error: Exception, restoring: Boolean) {
        if (error is CancellationException) throw error
        val reason = classify(error)
        val invalidStorage = error is GeneralSecurityException || error is SerializationException
        if (reason == AccessError.EXPIRED || invalidStorage) {
            client.auth.clearSession()
            mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.EXPIRED)
        } else {
            mutableState.value = AccessState(
                if (restoring || client.auth.currentSessionOrNull() != null) AccessStage.UNAVAILABLE else AccessStage.SIGNED_OUT,
                error = reason,
            )
        }
    }

    private fun classify(error: Exception): AccessError {
        if (error is AuthRestException) {
            return when (error.error) {
                "invalid_credentials" -> AccessError.CREDENTIALS
                "email_not_confirmed" -> AccessError.UNVERIFIED
                "refresh_token_not_found", "refresh_token_already_used", "session_not_found",
                "session_expired", "bad_jwt", "user_not_found" -> AccessError.EXPIRED
                else -> if (error.statusCode == 401) AccessError.EXPIRED else AccessError.SERVICE
            }
        }
        if (error is RestException) {
            return if (error.statusCode == 401) AccessError.EXPIRED else AccessError.SERVICE
        }
        return if (error is IOException || error is io.ktor.client.plugins.HttpRequestTimeoutException ||
            error is io.github.jan.supabase.exceptions.HttpRequestException
        ) AccessError.NETWORK else AccessError.SERVICE
    }
}
