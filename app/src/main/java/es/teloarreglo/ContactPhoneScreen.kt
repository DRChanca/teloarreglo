package es.teloarreglo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import es.teloarreglo.identity.ContactPhoneException
import es.teloarreglo.identity.ContactPhoneFailure
import es.teloarreglo.identity.ContactPhoneSession
import es.teloarreglo.identity.ContactPhoneStatus
import es.teloarreglo.identity.normalizeContactPhone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The «Teléfono de contacto» field shared by the mandatory step and Ajustes › Cuenta (#78). Shows
 * [error] under the field; otherwise how a number may be written.
 */
@Composable
fun ContactPhoneField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, error: Int? = null,
                      modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(stringResource(R.string.contact_phone_label)) },
        supportingText = {
            if (error != null) Text(stringResource(error),
                Modifier.testTag("contact_phone_error").semantics { liveRegion = LiveRegionMode.Polite })
            else Text(stringResource(R.string.contact_phone_hint))
        },
        isError = error != null, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = modifier.fillMaxWidth().testTag("contact_phone"),
    )
}

/**
 * Saves [typed] through [session]. Returns the error text to show, or null when saved. A badly
 * written number is rejected before anything is sent; the caller keeps what was typed on failure.
 */
suspend fun saveContactPhone(session: ContactPhoneSession, typed: String): Int? {
    if (normalizeContactPhone(typed) == null) return R.string.contact_phone_invalid
    return try { session.save(typed); null } catch (error: Exception) {
        if (error is CancellationException) throw error
        when ((error as? ContactPhoneException)?.failure) {
            ContactPhoneFailure.INVALID -> R.string.contact_phone_invalid
            ContactPhoneFailure.ACCESS -> R.string.contact_phone_access
            null -> R.string.contact_phone_network
        }
    }
}

/**
 * Third mandatory step after signing in (#74), after the name and the home, when the server
 * confirms the account has no contact phone. It cannot be skipped; signing out is always possible.
 */
@Composable
fun ContactPhoneScreen(session: ContactPhoneSession, scope: CoroutineScope, onSignOut: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(TeloTheme.spacing.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.large)) {
                Text(stringResource(R.string.contact_phone_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.contact_phone_intro), Modifier.testTag("contact_phone_intro"))
                ContactPhoneField(typed, { typed = it; error = null }, enabled = !busy, error = error)
                BusyButton(busy, R.string.contact_phone_save, "contact_phone_save", enabled = typed.isNotBlank()) {
                    busy = true
                    scope.launch { try { error = saveContactPhone(session, typed) } finally { busy = false } }
                }
                TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.testTag("contact_phone_sign_out")) {
                    Text(stringResource(R.string.sign_out))
                }
            }
        }
    }
}

/**
 * Editing state of the contact phone in Ajustes › Cuenta (#78). Same rule and RPC as the mandatory
 * step; saving updates [session], so the step is not asked again, and confirmed appointments get the
 * new number because it is always read at the moment.
 */
class ContactPhoneEditor(private val session: ContactPhoneSession) {
    var editing by mutableStateOf(false); private set
    var typed by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<Int?>(null); private set

    /** Opens the field with the current number, if any. */
    fun start() { typed = session.phone.orEmpty(); error = null; editing = true }
    fun type(text: String) { typed = text; error = null }
    fun cancel() { editing = false; error = null }

    /** Saves what was typed; true when saved. On failure the error is shown and the text kept. */
    suspend fun save(): Boolean {
        busy = true
        try { error = saveContactPhone(session, typed) } finally { busy = false }
        if (error == null) editing = false
        return error == null
    }

    companion object {
        /** Keeps an open edit and its text across configuration changes, like the name card. */
        fun saver(session: ContactPhoneSession) = Saver<ContactPhoneEditor, List<Any>>(
            save = { listOf(it.editing, it.typed) },
            restore = { ContactPhoneEditor(session).apply { editing = it[0] as Boolean; typed = it[1] as String } },
        )
    }
}

/** The contact phone in the account screen (#78), changed in place like the name. */
@Composable
fun ContactPhoneCard(session: ContactPhoneSession, scope: CoroutineScope) {
    val status by session.status.collectAsState()
    val editor = rememberSaveable(session, saver = ContactPhoneEditor.saver(session)) { ContactPhoneEditor(session) }
    Card(Modifier.fillMaxWidth().testTag("contact_phone_card")) {
        Column(Modifier.padding(TeloTheme.spacing.large), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
            Text(stringResource(R.string.contact_phone_label), style = MaterialTheme.typography.titleMedium)
            if (editor.editing) {
                Text(stringResource(R.string.contact_phone_intro), style = MaterialTheme.typography.bodySmall)
                ContactPhoneField(editor.typed, editor::type, enabled = !editor.busy, error = editor.error)
                BusyButton(editor.busy, R.string.contact_phone_save, "contact_phone_save", enabled = editor.typed.isNotBlank()) {
                    scope.launch { editor.save() }
                }
                TextButton(onClick = editor::cancel, enabled = !editor.busy) { Text(stringResource(R.string.cancel)) }
            } else {
                val current = session.phone
                Text(
                    current ?: stringResource(
                        if (status == ContactPhoneStatus.Missing) R.string.contact_phone_none else R.string.contact_phone_unknown,
                    ),
                    Modifier.testTag("contact_phone_current"),
                    style = if (current == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = editor::start, modifier = Modifier.testTag("contact_phone_change")) {
                    Text(stringResource(R.string.contact_phone_change))
                }
            }
        }
    }
}
