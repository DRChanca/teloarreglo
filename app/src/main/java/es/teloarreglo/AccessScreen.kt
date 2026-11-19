package es.teloarreglo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.Identity
import kotlinx.coroutines.launch

private val Green = Color(0xFF246451)
private val Canvas = Color(0xFFF7F8F5)

@Composable
fun AccessScreen(identity: Identity, onLanguageChange: (String) -> Unit) {
    val state by identity.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val errorInView = remember { BringIntoViewRequester() }
    var email by rememberSaveable { mutableStateOf("") }
    // A password is never saved into the instance state or preferences.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Canvas)) {
        Surface(Modifier.fillMaxSize(), color = Canvas) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth().widthIn(max = 480.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("teloarreglo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Green)
                    LanguageMenu(onLanguageChange)
                }
                Spacer(Modifier.height(48.dp))
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                    Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.intro), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (state.stage) {
                                AccessStage.CHECKING -> {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                    Text(stringResource(R.string.checking_session))
                                }
                                AccessStage.CONNECTED -> {
                                    Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(
                                        stringResource(R.string.connected_as, state.account?.email.orEmpty()),
                                        Modifier.testTag("account"),
                                    )
                                    Text(stringResource(R.string.account_hint))
                                    OutlinedButton(
                                        enabled = !state.busy,
                                        onClick = { scope.launch { identity.signOut() } },
                                        modifier = Modifier.fillMaxWidth().testTag("signOut"),
                                    ) { Text(stringResource(if (state.busy) R.string.working else R.string.sign_out)) }
                                }
                                AccessStage.UNAVAILABLE -> {
                                    Button(
                                        onClick = { scope.launch { identity.restore() } },
                                        modifier = Modifier.fillMaxWidth().testTag("retry"),
                                    ) { Text(stringResource(R.string.retry)) }
                                }
                                AccessStage.SIGNED_OUT -> {
                                    Text(stringResource(R.string.sign_in_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.sign_in_hint))
                                    OutlinedTextField(
                                        value = email, onValueChange = { email = it },
                                        label = { Text(stringResource(R.string.email)) },
                                        enabled = !state.busy, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                        modifier = Modifier.fillMaxWidth().testTag("email"),
                                    )
                                    OutlinedTextField(
                                        value = password, onValueChange = { password = it },
                                        label = { Text(stringResource(R.string.password)) },
                                        enabled = !state.busy, singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth().testTag("password"),
                                    )
                                    TextButton(onClick = { showPassword = !showPassword }) {
                                        Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                                    }
                                    Button(
                                        enabled = !state.busy,
                                        onClick = {
                                            keyboard?.hide()
                                            val entered = password
                                            password = ""
                                            scope.launch { identity.signIn(email.trim(), entered) }
                                        },
                                        modifier = Modifier.fillMaxWidth().testTag("signIn"),
                                    ) {
                                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
                                        else Text(stringResource(R.string.sign_in))
                                    }
                                }
                            }
                            state.error?.let { error ->
                                LaunchedEffect(error) { errorInView.bringIntoView() }
                                Text(
                                    stringResource(error.message()),
                                    Modifier.testTag("error").bringIntoViewRequester(errorInView)
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(if (BuildConfig.FLAVOR == "pruebas") R.string.environment_test else R.string.environment_presentation),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun LanguageMenu(onLanguageChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("language")) {
            Text(stringResource(R.string.language))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("" to stringResource(R.string.language_system), "es" to "Español", "en" to "English", "fr" to "Français", "de" to "Deutsch")
                .forEach { (tag, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { expanded = false; onLanguageChange(tag) },
                        modifier = Modifier.testTag("language_$tag"),
                    )
                }
        }
    }
}

private fun AccessError.message(): Int = when (this) {
    AccessError.CREDENTIALS -> R.string.error_credentials
    AccessError.UNVERIFIED -> R.string.error_unverified
    AccessError.EXPIRED -> R.string.error_expired
    AccessError.NETWORK -> R.string.error_network
    AccessError.SERVICE -> R.string.error_service
    AccessError.CONFIGURATION -> R.string.error_configuration
    AccessError.INPUT -> R.string.error_input
}
