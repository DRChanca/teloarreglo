package es.teloarreglo.identity

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * @param authLink Base of the links that return from emails to this app, e.g.
 *   `es.teloarreglo.pruebas://auth`. Without it, email links are not handled.
 *   Requires Auth configured with the PKCE flow so links carry a one-time code.
 */
class SupabaseIdentity(
    private val client: SupabaseClient,
    scope: CoroutineScope,
    private val authLink: String? = null,
) : Identity {
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
        // A recovery session must end in a new password, not silently become a normal sign-in.
        if (mutableState.value.stage == AccessStage.RECOVERING_PASSWORD) return@withLock
        // Keep the "check your email" screen when the app returns from the mail client.
        if (mutableState.value.stage == AccessStage.AWAITING_VERIFICATION) return@withLock
        // An invalid email link handled just before must stay visible after the restore.
        val linkError = mutableState.value.error.takeIf { it == AccessError.LINK_INVALID }
        mutableState.value = AccessState(AccessStage.CHECKING)
        try {
            client.auth.awaitInitialization()
            val found = client.auth.currentSessionOrNull() != null || client.auth.loadFromStorage(autoRefresh = false)
            if (!found) {
                mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = linkError)
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
            // Offer to resend the verification email without retyping the address.
            if (mutableState.value.error == AccessError.UNVERIFIED) {
                mutableState.value = mutableState.value.copy(pendingEmail = email)
            }
        }
    }

    override suspend fun signOut() = mutex.withLock {
        val previous = mutableState.value
        mutableState.value = previous.copy(busy = true, error = null, notice = null)
        try {
            client.auth.signOut()
            mutableState.value = AccessState(AccessStage.SIGNED_OUT)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Do not claim logout succeeded if the remote request failed.
            mutableState.value = previous.copy(busy = false, error = classify(error))
        }
    }

    override suspend fun signUp(email: String, password: String) = mutex.withLock {
        if (email.isBlank() || password.isEmpty()) {
            mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.INPUT)
            return@withLock
        }
        mutableState.value = AccessState(AccessStage.SIGNED_OUT, busy = true)
        try {
            // Roles are never sent here: capabilities come only from current_account.
            client.auth.signUpWith(Email, redirectUrl = linkFor(FLOW_SIGNUP)) {
                this.email = email
                this.password = password
            }
            if (client.auth.currentSessionOrNull() != null) {
                // Only possible if Auth stops requiring confirmation; still verify on the server.
                connect()
            } else {
                // Auth answers the same for an address that already exists: no disclosure here either.
                mutableState.value = AccessState(
                    AccessStage.AWAITING_VERIFICATION, pendingEmail = email, notice = AccessNotice.VERIFICATION_SENT,
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = classify(error))
        }
    }

    override suspend fun resendVerification(email: String) = mutex.withLock {
        val previous = mutableState.value
        if (email.isBlank()) {
            mutableState.value = previous.copy(error = AccessError.INPUT, notice = null)
            return@withLock
        }
        mutableState.value = previous.copy(busy = true, error = null, notice = null)
        try {
            client.auth.resendEmail(OtpType.Email.SIGNUP, email)
            mutableState.value = AccessState(
                AccessStage.AWAITING_VERIFICATION, pendingEmail = email, notice = AccessNotice.VERIFICATION_SENT,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = previous.copy(busy = false, error = classify(error), notice = null)
        }
    }

    override suspend fun requestPasswordReset(email: String) = mutex.withLock {
        if (email.isBlank()) {
            mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.INPUT)
            return@withLock
        }
        mutableState.value = AccessState(AccessStage.SIGNED_OUT, busy = true)
        try {
            client.auth.resetPasswordForEmail(email, redirectUrl = linkFor(FLOW_RECOVERY))
            mutableState.value = AccessState(AccessStage.SIGNED_OUT, notice = AccessNotice.RESET_SENT)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = classify(error))
        }
    }

    override suspend fun handleAuthLink(link: String): Boolean {
        val parsed = parseAuthLink(link) ?: return false
        mutex.withLock {
            val code = parsed["code"]
            if (parsed.containsKey("error") || parsed.containsKey("error_code") || code.isNullOrBlank()) {
                // Expired, already used or otherwise rejected by Auth before reaching the app.
                mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.LINK_INVALID)
                return@withLock
            }
            mutableState.value = AccessState(AccessStage.CHECKING, busy = true)
            try {
                client.auth.exchangeCodeForSession(code)
                val account = fetchAccount()
                mutableState.value = if (parsed["flow"] == FLOW_RECOVERY) {
                    AccessState(AccessStage.RECOVERING_PASSWORD, account)
                } else {
                    AccessState(AccessStage.CONNECTED, account)
                }
                client.auth.startAutoRefreshForCurrentSession()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val reason = if (error is IllegalArgumentException || error.isLinkFailure()) {
                    // No code verifier on this device (opened elsewhere) or a stale/used code.
                    AccessError.LINK_INVALID
                } else {
                    classify(error)
                }
                mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = reason)
            }
        }
        return true
    }

    override suspend fun setNewPassword(password: String) = mutex.withLock {
        val previous = mutableState.value
        if (previous.stage != AccessStage.RECOVERING_PASSWORD) return@withLock
        if (password.isEmpty()) {
            mutableState.value = previous.copy(error = AccessError.INPUT)
            return@withLock
        }
        mutableState.value = previous.copy(busy = true, error = null)
        try {
            client.auth.updateUser(redirectUrl = null) { this.password = password }
            connect()
            mutableState.value = mutableState.value.copy(notice = AccessNotice.PASSWORD_CHANGED)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Stay in recovery so the user can choose another password.
            mutableState.value = previous.copy(busy = false, error = classify(error))
        }
    }

    override suspend fun completeGoogleSignIn() = mutex.withLock {
        mutableState.value = AccessState(AccessStage.SIGNED_OUT, busy = true)
        try {
            connect()
            client.auth.startAutoRefreshForCurrentSession()
        } catch (error: Exception) {
            handleFailure(error, restoring = false)
        }
    }

    override fun reportGoogleFailure() {
        mutableState.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.GOOGLE)
    }

    override fun dismiss() {
        val current = mutableState.value
        if (current.stage == AccessStage.AWAITING_VERIFICATION || current.stage == AccessStage.SIGNED_OUT) {
            mutableState.value = AccessState(AccessStage.SIGNED_OUT)
        }
    }

    private fun linkFor(flow: String): String? = authLink?.let { "$it?flow=$flow" }

    /** Query and fragment parameters of a link for this app, or null if it is not one. */
    private fun parseAuthLink(link: String): Map<String, String>? {
        val base = authLink ?: return null
        val uri = try { URI(link) } catch (_: Exception) { return null }
        val expected = URI(base)
        if (!uri.scheme.equals(expected.scheme, ignoreCase = true) ||
            !uri.host.equals(expected.host, ignoreCase = true)
        ) return null
        return listOfNotNull(uri.rawQuery, uri.rawFragment)
            .flatMap { it.split('&') }
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
    }

    private suspend fun fetchAccount(): Account {
        val account = client.postgrest.rpc("current_account").decodeAs<Account>()
        check(account.userId == client.auth.currentUserOrNull()?.id)
        return account
    }

    private suspend fun connect() {
        mutableState.value = AccessState(AccessStage.CONNECTED, fetchAccount())
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

    private fun Exception.isLinkFailure() = this is AuthRestException && error in LINK_FAILURES

    private fun classify(error: Exception): AccessError {
        if (error is AuthRestException) {
            return when (error.error) {
                "invalid_credentials" -> AccessError.CREDENTIALS
                "email_not_confirmed" -> AccessError.UNVERIFIED
                "weak_password", "same_password" -> AccessError.WEAK_PASSWORD
                "over_email_send_rate_limit", "over_request_rate_limit" -> AccessError.RATE_LIMITED
                "validation_failed", "email_address_invalid" -> AccessError.INPUT
                in LINK_FAILURES -> AccessError.LINK_INVALID
                "refresh_token_not_found", "refresh_token_already_used", "session_not_found",
                "session_expired", "bad_jwt", "user_not_found" -> AccessError.EXPIRED
                else -> if (error.statusCode == 401) AccessError.EXPIRED else AccessError.SERVICE
            }
        }
        if (error is RestException) {
            return when (error.statusCode) {
                401 -> AccessError.EXPIRED
                429 -> AccessError.RATE_LIMITED
                else -> AccessError.SERVICE
            }
        }
        return if (error is IOException || error is io.ktor.client.plugins.HttpRequestTimeoutException ||
            error is io.github.jan.supabase.exceptions.HttpRequestException
        ) AccessError.NETWORK else AccessError.SERVICE
    }

    companion object {
        const val FLOW_SIGNUP = "signup"
        const val FLOW_RECOVERY = "recovery"
        private val LINK_FAILURES = setOf(
            "flow_state_not_found", "flow_state_expired", "bad_code_verifier", "otp_expired",
        )
    }
}
