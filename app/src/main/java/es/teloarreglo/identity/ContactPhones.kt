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
 * Teléfono de contacto (#74, spec #71): private and mandatory number of the account. Only its owner
 * reads and writes it; the professional of a confirmed appointment will get it from the appointment
 * (#79), never from search, chat or requests. Changed from Ajustes › Cuenta (#78).
 */

private val SEPARATORS = Regex("[\\s().-]")
private val SPANISH = Regex("[6-9][0-9]{8}")
private val INTERNATIONAL = Regex("\\+[1-9][0-9]{7,14}")

/**
 * The phone in E.164, or null if it cannot be accepted. Spaces, dashes, dots and parentheses are
 * ignored; a Spanish number of 9 digits (6, 7, 8 or 9 first) gets +34; anything else needs «+» and
 * its country code (8 to 15 digits). Same rule as `private.normalize_contact_phone` on the server.
 */
fun normalizeContactPhone(raw: String): String? {
    val compact = raw.replace(SEPARATORS, "")
    return when {
        SPANISH.matches(compact) -> "+34$compact"
        INTERNATIONAL.matches(compact) -> compact
        else -> null
    }
}

/** Server or local rejections, kept apart from network uncertainty (rethrown as is). */
enum class ContactPhoneFailure { ACCESS, INVALID }
class ContactPhoneException(val failure: ContactPhoneFailure, cause: Exception? = null) : Exception(failure.name, cause)

interface ContactPhones {
    /** The signed-in account's phone in E.164, or null if it has none. */
    suspend fun current(): String?
    /** Creates or replaces the phone and returns it as stored (E.164). */
    suspend fun save(phone: String): String
}

class SupabaseContactPhones(private val client: SupabaseClient) : ContactPhones {
    override suspend fun current(): String? = mapped {
        // A text RPC answers a JSON string, or JSON null when the account has no phone.
        when (val value = Json.parseToJsonElement(client.postgrest.rpc("my_contact_phone").data)) {
            JsonNull -> null
            else -> value.jsonPrimitive.content
        }
    }

    override suspend fun save(phone: String): String {
        val normalized = normalizeContactPhone(phone) ?: throw ContactPhoneException(ContactPhoneFailure.INVALID)
        return mapped {
            client.postgrest.rpc("set_contact_phone", buildJsonObject { put("p_phone", normalized) }).decodeAs<String>()
        }
    }

    private inline fun <T> mapped(block: () -> T): T = try { block() } catch (error: RestException) {
        val failure = when {
            error.statusCode == 401 || error.statusCode == 403 -> ContactPhoneFailure.ACCESS
            "invalid_contact_phone" in error.message.orEmpty() -> ContactPhoneFailure.INVALID
            else -> null
        }
        throw if (failure != null) ContactPhoneException(failure, error) else error
    }
}

sealed interface ContactPhoneStatus {
    /** Signed out, or not checked yet. */
    data object Unknown : ContactPhoneStatus
    /** The server confirmed this account has no phone: the app asks for it before continuing. */
    data object Missing : ContactPhoneStatus
    data class Present(val phone: String) : ContactPhoneStatus
    /** Could not check (network): nothing is blocked; checked again on the next start. */
    data object Failed : ContactPhoneStatus
}

/**
 * The signed-in account's contact phone for this session, shared by the mandatory step and the
 * account settings. Only a confirmed [ContactPhoneStatus.Missing] makes the step mandatory.
 */
class ContactPhoneSession(private val phones: ContactPhones) {
    private val mutable = MutableStateFlow<ContactPhoneStatus>(ContactPhoneStatus.Unknown)
    val status: StateFlow<ContactPhoneStatus> = mutable.asStateFlow()
    private var owner: String? = null

    val required: Boolean get() = status.value == ContactPhoneStatus.Missing
    val phone: String? get() = (status.value as? ContactPhoneStatus.Present)?.phone

    /** Loads the phone of [userId] (null when signed out). */
    suspend fun load(userId: String?) {
        if (userId != owner) { owner = userId; mutable.value = ContactPhoneStatus.Unknown }
        if (userId == null) return
        val result = try {
            phones.current()?.let(ContactPhoneStatus::Present) ?: ContactPhoneStatus.Missing
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ContactPhoneStatus.Failed
        }
        // Ignore an answer that arrives after the account changed.
        if (owner == userId && (result != ContactPhoneStatus.Failed || mutable.value !is ContactPhoneStatus.Present)) {
            mutable.value = result
        }
    }

    /** Saves the phone; failures propagate so the screen can show them and keep what was typed. */
    suspend fun save(phone: String): String {
        val expected = owner
        return phones.save(phone).also { if (owner == expected) mutable.value = ContactPhoneStatus.Present(it) }
    }
}
