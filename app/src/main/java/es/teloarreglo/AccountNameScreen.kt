package es.teloarreglo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import es.teloarreglo.identity.ACCOUNT_NAME_MAX
import es.teloarreglo.identity.AccountNameException
import es.teloarreglo.identity.AccountNameFailure
import es.teloarreglo.identity.AccountNameSession
import es.teloarreglo.identity.AccountNameStatus
import es.teloarreglo.identity.cleanAccountName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The "Nombre" field shared by signup, the one-time question and the account card (#40). */
@Composable
fun AccountNameField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val tooLong = value.trim().length > ACCOUNT_NAME_MAX
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(stringResource(R.string.account_name_label)) },
        supportingText = {
            Text(if (tooLong) stringResource(R.string.account_name_invalid) else "${value.trim().length}/$ACCOUNT_NAME_MAX")
        },
        isError = tooLong, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = modifier.fillMaxWidth().testTag("account_name"),
    )
}

/**
 * Saves [typed] through [session]. Returns the error text to show, or null when saved. What was
 * typed is kept by the caller on failure so the user can retry.
 */
private suspend fun saveName(session: AccountNameSession, typed: String): Int? {
    val cleaned = cleanAccountName(typed) ?: return R.string.account_name_invalid
    return try { session.save(cleaned); null } catch (error: Exception) {
        if (error is CancellationException) throw error
        when ((error as? AccountNameException)?.failure) {
            AccountNameFailure.INVALID -> R.string.account_name_invalid
            AccountNameFailure.ACCESS -> R.string.account_name_access
            null -> R.string.account_name_network
        }
    }
}

/**
 * Asked once after signing in when the server confirms the account has no name (accounts created
 * before #40, or Google without a name). Signing out is always possible.
 */
@Composable
fun AccountNameScreen(session: AccountNameSession, scope: CoroutineScope, onSignOut: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.account_name_title), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.account_name_intro))
                AccountNameField(typed, { typed = it; error = null }, enabled = !busy)
                Button(
                    enabled = !busy && cleanAccountName(typed) != null,
                    onClick = {
                        busy = true
                        scope.launch { try { error = saveName(session, typed) } finally { busy = false } }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("account_name_save"),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.account_name_save))
                }
                error?.let { NameError(it) }
                TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.testTag("account_name_sign_out")) {
                    Text(stringResource(R.string.sign_out))
                }
            }
        }
    }
}

/** The name in the account screen, changed in place. The email is never shown as the name. */
@Composable
fun AccountNameCard(session: AccountNameSession, scope: CoroutineScope) {
    val status by session.status.collectAsState()
    val current = (status as? AccountNameStatus.Present)?.name
    var editing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    Card(Modifier.fillMaxWidth().testTag("account_name_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.account_name_label), style = MaterialTheme.typography.titleMedium)
            if (editing) {
                AccountNameField(typed, { typed = it; error = null }, enabled = !busy)
                Button(
                    enabled = !busy && cleanAccountName(typed) != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            try { error = saveName(session, typed); if (error == null) editing = false } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("account_name_save"),
                ) { Text(stringResource(if (busy) R.string.working else R.string.account_name_save)) }
                TextButton(onClick = { editing = false; error = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                error?.let { NameError(it) }
            } else {
                Text(
                    current ?: stringResource(R.string.account_name_none),
                    Modifier.testTag("account_name_current"),
                    style = if (current == null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = { typed = current.orEmpty(); editing = true }, modifier = Modifier.testTag("account_name_change")) {
                    Text(stringResource(R.string.account_name_change))
                }
            }
        }
    }
}

@Composable
private fun NameError(message: Int) {
    Text(
        stringResource(message),
        Modifier.testTag("account_name_error").semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
    )
}
