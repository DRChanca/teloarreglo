package es.teloarreglo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessNotice
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.Identity
import io.github.jan.supabase.compose.auth.ComposeAuth
import io.github.jan.supabase.compose.auth.composable.NativeSignInResult
import io.github.jan.supabase.compose.auth.composable.rememberSignInWithGoogle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope


/** What the signed-out card shows. Not identity state: only which form the user chose. */
private enum class Form { SIGN_IN, SIGN_UP, RESET }

@Composable
fun AccessScreen(
    identity: Identity,
    onLanguageChange: (String) -> Unit,
    /** Leaves an invalid email link, the only state of this tab with its own «Volver» (#47). */
    onLeaveLink: (() -> Unit)? = null,
    onProfile: ((String) -> Unit)? = null,
    operationScope: CoroutineScope = rememberCoroutineScope(),
    googleAuth: ComposeAuth? = null,
    profileReviews: es.teloarreglo.catalogue.ProfileReviews? = null,
    subscriptions: es.teloarreglo.subscription.Subscriptions? = null,
    agenda: es.teloarreglo.appointments.Agenda? = null,
    /** Home address summary and its change action (#37), shown to a connected account. */
    homeSlot: (@Composable () -> Unit)? = null,
    /** The person's name and its change action (#40), shown to a connected account. */
    nameSlot: (@Composable () -> Unit)? = null,
    onEditProfile: (() -> Unit)? = null,
) {
    val state by identity.state.collectAsStateWithLifecycle()
    val scope = operationScope
    val keyboard = LocalSoftwareKeyboardController.current
    val errorInView = remember { BringIntoViewRequester() }
    var form by rememberSaveable { mutableStateOf(Form.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // A password is never saved into the instance state or preferences.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // Leaving a form after a successful request returns to sign in with the email kept.
    LaunchedEffect(state.notice) {
        if (state.notice == AccessNotice.RESET_SENT) form = Form.SIGN_IN
    }

    val google = googleAuth?.rememberSignInWithGoogle(
        onResult = { result ->
            when (result) {
                NativeSignInResult.Success -> scope.launch { identity.completeGoogleSignIn() }
                NativeSignInResult.ClosedByUser -> Unit // Cancelling is a choice, not an error.
                is NativeSignInResult.Error, is NativeSignInResult.NetworkError -> identity.reportGoogleFailure()
            }
        },
        // Never fall back to a browser flow that was not designed or tested.
        fallback = { identity.reportGoogleFailure() },
    )

    fun submit(action: suspend (String) -> Unit) {
        keyboard?.hide()
        val entered = password
        password = ""
        scope.launch { action(entered) }
    }

    // Which fields the current form shows decides where an error is explained (#47).
    val onScreen = when (state.stage) {
        AccessStage.SIGNED_OUT -> when (form) {
            Form.SIGN_IN -> listOf(AccessField.EMAIL, AccessField.PASSWORD)
            Form.SIGN_UP -> listOf(AccessField.NAME, AccessField.EMAIL, AccessField.PASSWORD)
            Form.RESET -> listOf(AccessField.EMAIL)
        }
        AccessStage.RECOVERING_PASSWORD -> listOf(AccessField.PASSWORD)
        else -> emptyList()
    }
    val placement = state.error?.placement(onScreen) ?: ErrorPlacement.GENERAL
    val connected = state.stage == AccessStage.CONNECTED

    /** The error's text under the field that caused it; brought into view so the person sees what to fix. */
    @Composable
    fun ErrorUnder(field: AccessField) {
        val error = state.error ?: return
        if (placement.explainedUnder != field) return
        LaunchedEffect(error) { errorInView.bringIntoView() }
        Text(
            stringResource(error.message()),
            Modifier.padding(start = TeloTheme.spacing.large).testTag("error").bringIntoViewRequester(errorInView)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    /** Tells assistive technology which message belongs to a marked field. */
    @Composable
    fun Modifier.fieldError(field: AccessField): Modifier {
        val message = state.error?.takeIf { field in placement.marked }?.let { stringResource(it.message()) }
        return if (message == null) this else semantics { error(message) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .verticalScroll(rememberScrollState()).padding(TeloTheme.spacing.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
                Text("teloarreglo", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(48.dp))
            if (onLeaveLink != null && state.error == AccessError.LINK_INVALID) {
                TextButton(onClick = onLeaveLink, Modifier.testTag("back_from_account")) {
                    Text(stringResource(R.string.catalogue_back))
                }
            }
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.large),
            ) {
                if (connected) {
                    // With a session the marketing cover gives way to the account itself (#47).
                    Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                } else {
                    Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineLarge)
                    Text(stringResource(R.string.intro), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = TeloTheme.spacing.large))
                }
                // General messages (network, service, sent emails) above the form; field errors go under their field.
                state.notice?.let { notice ->
                    Text(
                        stringResource(notice.message()),
                        Modifier.testTag("notice").semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.error?.takeIf { placement == ErrorPlacement.GENERAL }?.let { error ->
                    LaunchedEffect(error) { errorInView.bringIntoView() }
                    Text(
                        stringResource(error.message()),
                        Modifier.testTag("error").bringIntoViewRequester(errorInView)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (connected) {
                    val profileId = state.account?.professionalProfileId
                    AccountSection(R.string.account_section_account, "account_section") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = TeloTheme.extended.card),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().testTag("account_card"),
                        ) {
                            Column(Modifier.padding(TeloTheme.spacing.large), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
                                Text(
                                    stringResource(R.string.connected_as, state.account?.email.orEmpty()),
                                    Modifier.testTag("account"),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        nameSlot?.invoke()
                        homeSlot?.invoke()
                    }
                    if (onEditProfile != null || profileId != null) {
                        AccountSection(R.string.account_section_professional, "professional_section") {
                            if (onEditProfile != null) {
                                Button(onClick = onEditProfile, modifier = Modifier.fillMaxWidth().testTag("profile_edit_open")) {
                                    Text(stringResource(if (profileId == null) R.string.profile_edit_offer else R.string.profile_edit_open))
                                }
                            }
                            if (profileId != null && onProfile != null) {
                                OutlinedButton(onClick = { onProfile(profileId) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.catalogue_owned))
                                }
                            }
                            if (profileId != null && profileReviews != null) ProfileReviewCard(profileReviews)
                        }
                    }
                    if (profileId != null && agenda != null) AgendaCard(agenda, scope)
                    if (profileId != null && subscriptions != null) SubscriptionCard(subscriptions, scope)
                } else Card(
                    colors = CardDefaults.cardColors(containerColor = TeloTheme.extended.card),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(Modifier.fillMaxWidth().padding(TeloTheme.spacing.extraLarge), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.large)) {
                        @Composable
                        fun EmailField() = Column(verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall)) {
                            OutlinedTextField(
                                value = email, onValueChange = { email = it },
                                label = { Text(stringResource(R.string.email)) },
                                enabled = !state.busy, singleLine = true,
                                isError = AccessField.EMAIL in placement.marked,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth().testTag("email").fieldError(AccessField.EMAIL),
                            )
                            ErrorUnder(AccessField.EMAIL)
                        }

                        @Composable
                        fun PasswordField(label: Int, tag: String) {
                            Column(verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall)) {
                                OutlinedTextField(
                                    value = password, onValueChange = { password = it },
                                    label = { Text(stringResource(label)) },
                                    enabled = !state.busy, singleLine = true,
                                    isError = AccessField.PASSWORD in placement.marked,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().testTag(tag).fieldError(AccessField.PASSWORD),
                                )
                                ErrorUnder(AccessField.PASSWORD)
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                                }
                            }
                        }

                        when (state.stage) {
                            AccessStage.CHECKING -> {
                                CircularProgressIndicator(Modifier.size(28.dp))
                                Text(stringResource(R.string.checking_session))
                            }
                            AccessStage.CONNECTED -> Unit // Laid out above as sections.
                            AccessStage.UNAVAILABLE -> {
                                Button(
                                    onClick = { scope.launch { identity.restore() } },
                                    modifier = Modifier.fillMaxWidth().testTag("retry"),
                                ) { Text(stringResource(R.string.retry)) }
                            }
                            AccessStage.AWAITING_VERIFICATION -> {
                                Text(stringResource(R.string.verify_title), style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    stringResource(R.string.verify_hint, state.pendingEmail.orEmpty()),
                                    Modifier.testTag("verifyHint"),
                                )
                                OutlinedButton(
                                    enabled = !state.busy,
                                    onClick = { scope.launch { identity.resendVerification(state.pendingEmail.orEmpty()) } },
                                    modifier = Modifier.fillMaxWidth().testTag("resend"),
                                ) { Text(stringResource(if (state.busy) R.string.working else R.string.resend)) }
                                Button(
                                    onClick = { form = Form.SIGN_IN; identity.dismiss() },
                                    modifier = Modifier.fillMaxWidth().testTag("verifiedSignIn"),
                                ) { Text(stringResource(R.string.verified_sign_in)) }
                            }
                            AccessStage.RECOVERING_PASSWORD -> {
                                Text(stringResource(R.string.new_password_title), style = MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.new_password_hint, state.account?.email.orEmpty()))
                                PasswordField(R.string.new_password, "newPassword")
                                Text(stringResource(R.string.password_rule), style = MaterialTheme.typography.bodySmall)
                                BusyButton(state.busy, R.string.save_password, "savePassword") {
                                    submit(identity::setNewPassword)
                                }
                                TextButton(
                                    enabled = !state.busy,
                                    onClick = { scope.launch { identity.signOut() } },
                                    modifier = Modifier.testTag("cancelRecovery"),
                                ) { Text(stringResource(R.string.cancel)) }
                            }
                            AccessStage.SIGNED_OUT -> when (form) {
                                Form.SIGN_IN -> {
                                    Text(stringResource(R.string.sign_in_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.sign_in_hint))
                                    EmailField()
                                    PasswordField(R.string.password, "password")
                                    BusyButton(state.busy, R.string.sign_in, "signIn") {
                                        submit { identity.signIn(email.trim(), it) }
                                    }
                                    if (state.error == AccessError.UNVERIFIED && state.pendingEmail != null) {
                                        OutlinedButton(
                                            enabled = !state.busy,
                                            onClick = { scope.launch { identity.resendVerification(state.pendingEmail.orEmpty()) } },
                                            modifier = Modifier.fillMaxWidth().testTag("resend"),
                                        ) { Text(stringResource(R.string.resend)) }
                                    }
                                    if (google != null) {
                                        Text(stringResource(R.string.or), Modifier.align(Alignment.CenterHorizontally))
                                        OutlinedButton(
                                            enabled = !state.busy,
                                            onClick = { google.startFlow() },
                                            modifier = Modifier.fillMaxWidth().testTag("google"),
                                        ) { Text(stringResource(R.string.google)) }
                                    }
                                    FormLinks(
                                        R.string.forgot_password to "toReset",
                                        R.string.no_account to "toSignUp",
                                        enabled = !state.busy,
                                    ) { tag ->
                                        identity.dismiss()
                                        form = if (tag == "toReset") Form.RESET else Form.SIGN_UP
                                    }
                                }
                                Form.SIGN_UP -> {
                                    Text(stringResource(R.string.sign_up_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.sign_up_hint))
                                    Column(verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall)) {
                                        AccountNameField(name, { name = it }, enabled = !state.busy,
                                            modifier = Modifier.fieldError(AccessField.NAME))
                                        ErrorUnder(AccessField.NAME)
                                    }
                                    EmailField()
                                    PasswordField(R.string.password, "password")
                                    Text(stringResource(R.string.password_rule), style = MaterialTheme.typography.bodySmall)
                                    BusyButton(state.busy, R.string.create_account, "signUp") {
                                        submit { identity.signUp(email.trim(), it, name) }
                                    }
                                    if (google != null) {
                                        Text(stringResource(R.string.or), Modifier.align(Alignment.CenterHorizontally))
                                        OutlinedButton(
                                            enabled = !state.busy,
                                            onClick = { google.startFlow() },
                                            modifier = Modifier.fillMaxWidth().testTag("google"),
                                        ) { Text(stringResource(R.string.google)) }
                                    }
                                    FormLinks(R.string.have_account to "toSignIn", enabled = !state.busy) {
                                        identity.dismiss(); form = Form.SIGN_IN
                                    }
                                }
                                Form.RESET -> {
                                    Text(stringResource(R.string.reset_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.reset_hint))
                                    EmailField()
                                    BusyButton(state.busy, R.string.send_link, "sendReset") {
                                        keyboard?.hide()
                                        scope.launch { identity.requestPasswordReset(email.trim()) }
                                    }
                                    FormLinks(R.string.back_to_sign_in to "toSignIn", enabled = !state.busy) {
                                        identity.dismiss(); form = Form.SIGN_IN
                                    }
                                }
                            }
                        }
                    }
                }
                // Preferences (#46): also offered to visitors, under the sign-in form.
                Card(
                    colors = CardDefaults.cardColors(containerColor = TeloTheme.extended.card),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(top = TeloTheme.spacing.small).fillMaxWidth().testTag("preferences"),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = TeloTheme.spacing.large, vertical = TeloTheme.spacing.large)) {
                        Text(stringResource(R.string.preferences_title), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                        LanguageMenu(onLanguageChange)
                    }
                }
                // Signing out closes the account, after everything else it holds (#47).
                if (connected) OutlinedButton(
                    enabled = !state.busy,
                    onClick = { scope.launch { identity.signOut() } },
                    modifier = Modifier.fillMaxWidth().testTag("signOut"),
                ) { Text(stringResource(if (state.busy) R.string.working else R.string.sign_out)) }
            }
            Spacer(Modifier.height(TeloTheme.spacing.extraLarge))
            Text(
                stringResource(if (BuildConfig.FLAVOR == "pruebas") R.string.environment_test else R.string.environment_presentation),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** A titled group of the connected account; the title is announced as a heading. */
@Composable
private fun AccountSection(title: Int, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(TeloTheme.spacing.small)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        content()
    }
}


@Composable
private fun ColumnScope.FormLinks(vararg links: Pair<Int, String>, enabled: Boolean, onClick: (String) -> Unit) {
    links.forEach { (label, tag) ->
        TextButton(
            enabled = enabled,
            onClick = { onClick(tag) },
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag(tag),
        ) { Text(stringResource(label)) }
    }
}

@Composable
internal fun LanguageMenu(onLanguageChange: (String) -> Unit) {
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
    AccessError.LINK_INVALID -> R.string.error_link_invalid
    AccessError.RATE_LIMITED -> R.string.error_rate_limited
    AccessError.WEAK_PASSWORD -> R.string.error_weak_password
    AccessError.GOOGLE -> R.string.error_google
    AccessError.NAME -> R.string.account_name_invalid
}

private fun AccessNotice.message(): Int = when (this) {
    AccessNotice.VERIFICATION_SENT -> R.string.notice_verification_sent
    AccessNotice.RESET_SENT -> R.string.notice_reset_sent
    AccessNotice.PASSWORD_CHANGED -> R.string.notice_password_changed
}
