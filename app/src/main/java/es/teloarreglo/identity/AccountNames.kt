package es.teloarreglo.identity

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The person's own name (#40), not the business name of a professional profile. Given at email
 * signup, taken from Google without asking, and the single source of `client_name` in the chat
 * inbox (#39). Only its owner writes it; other participants only see it, never the email.
 */
const val ACCOUNT_NAME_MAX = 80

/** The name as stored: trimmed, 1–80 characters; null if it cannot be accepted. */
fun cleanAccountName(raw: String): String? = raw.trim().takeIf { it.length in 1..ACCOUNT_NAME_MAX }

/** Server rejections, kept apart from network uncertainty (rethrown as is). */
enum class AccountNameFailure { ACCESS, INVALID }
class AccountNameException(val failure: AccountNameFailure, cause: Exception) : Exception(failure.name, cause)

interface AccountNames {
    /** The signed-in account's name, or null if it never gave one. */
    suspend fun current(): String?
    /** Creates or replaces the name and returns it as stored. */
    suspend fun save(name: String): String
}

class SupabaseAccountNames(private val client: SupabaseClient) : AccountNames {
    override suspend fun current(): String? = mapped {
        // A text RPC answers a JSON string, or JSON null when the account has no name.
        when (val value = Json.parseToJsonElement(client.postgrest.rpc("my_account_name").data)) {
            JsonNull -> null
            else -> value.jsonPrimitive.content
        }
    }

    override suspend fun save(name: String): String = mapped {
        client.postgrest.rpc("set_account_name", buildJsonObject { put("p_name", name.trim()) }).decodeAs<String>()
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> AccountNameFailure.ACCESS
            "invalid_account_name" in error.message.orEmpty() -> AccountNameFailure.INVALID
            else -> null
        }
        throw if (failure != null) AccountNameException(failure, error) else error
    }
}

sealed interface AccountNameStatus {
    /** Signed out, or not checked yet. */
    data object Unknown : AccountNameStatus
    /** The server confirmed this account has no name: the app asks for it once. */
    data object Missing : AccountNameStatus
    data class Present(val name: String) : AccountNameStatus
    /** Could not check (network): nothing is blocked; checked again on the next start. */
    data object Failed : AccountNameStatus
}

/** The signed-in account's name for this session. Only a confirmed [AccountNameStatus.Missing] asks. */
class AccountNameSession(private val names: AccountNames) {
    private val mutable = MutableStateFlow<AccountNameStatus>(AccountNameStatus.Unknown)
    val status: StateFlow<AccountNameStatus> = mutable.asStateFlow()
    private var owner: String? = null

    val required: Boolean get() = status.value == AccountNameStatus.Missing

    /** Loads the name of [userId] (null when signed out). */
    suspend fun load(userId: String?) {
        if (userId != owner) { owner = userId; mutable.value = AccountNameStatus.Unknown }
        if (userId == null) return
        val result = try {
            names.current()?.let(AccountNameStatus::Present) ?: AccountNameStatus.Missing
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            AccountNameStatus.Failed
        }
        // Ignore an answer that arrives after the account changed.
        if (owner == userId && (result != AccountNameStatus.Failed || mutable.value !is AccountNameStatus.Present)) {
            mutable.value = result
        }
    }

    /** Saves the name; failures propagate so the screen can show them and keep what was typed. */
    suspend fun save(name: String): String {
        val expected = owner
        return names.save(name).also { if (owner == expected) mutable.value = AccountNameStatus.Present(it) }
    }
}
