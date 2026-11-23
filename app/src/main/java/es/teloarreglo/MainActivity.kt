package es.teloarreglo

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import es.teloarreglo.search.currentLocation
import es.teloarreglo.search.hasLocationPermission
import kotlinx.coroutines.CompletableDeferred
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.chat.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class MainActivity : AppCompatActivity() {
    private var permissionAnswer: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        permissionAnswer?.complete(granted.values.any { it })
        permissionAnswer = null
    }

    /** Asks for location only when the user starts a nearby search. Approximate is enough. */
    private suspend fun ensureLocationPermission(): Boolean {
        if (hasLocationPermission(this)) return true
        val answer = CompletableDeferred<Boolean>().also { permissionAnswer = it }
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        return answer.await()
    }
    private val app get() = application as TeloarregloApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a configuration change the link was already handled; codes are single use.
        if (savedInstanceState == null) handleAuthLink(intent)
        setContent {
            var account by rememberSaveable { mutableStateOf(false) }
            var ownProfile by rememberSaveable { mutableStateOf<String?>(null) }
            var chatVisible by rememberSaveable { mutableStateOf(false) }
            var contactProfile by rememberSaveable { mutableStateOf<String?>(null) }
            var conversationJson by rememberSaveable { mutableStateOf<String?>(null) }
            var nearby by rememberSaveable { mutableStateOf(false) }
            val app = application as TeloarregloApplication
            val access by app.identity.state.collectAsStateWithLifecycle()
            var knownOwner by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(access) {
                if (access.stage != AccessStage.CHECKING) knownOwner = access.account?.professionalProfileId
            }
            val owner = if (access.stage == AccessStage.CHECKING) knownOwner else access.account?.professionalProfileId
            // An email link (new password or invalid link) must be answered on the account screen.
            val emailLinkPending = access.stage == AccessStage.RECOVERING_PASSWORD ||
                access.error == AccessError.LINK_INVALID
            val changeLanguage: (String) -> Unit = { tag ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            BackHandler(enabled = account || nearby || (chatVisible && access.stage != AccessStage.CONNECTED)) {
                account = false; nearby = false; chatVisible = false; contactProfile = null; conversationJson = null
            }
            if (chatVisible && access.stage == AccessStage.CONNECTED && app.chat != null) {
                key(access.account!!.userId) {
                    ChatRoute(app.chat!!, access.account!!.userId, contactProfile,
                        conversationJson?.let { Json.decodeFromString<Conversation>(it) },
                        onOpened = { contactProfile = null; conversationJson = Json.encodeToString(it) },
                        onBack = {
                            if (conversationJson != null && ownProfile == null) conversationJson = null
                            else { chatVisible = false; contactProfile = null; conversationJson = null }
                        }, attachments = app.chatAttachments)
                }
            } else if (account || chatVisible || emailLinkPending) {
                AccessScreen(app.identity, changeLanguage,
                    onCatalogue = {
                        // Also clears a shown invalid-link error so the catalogue can be reached.
                        app.identity.dismiss()
                        account = false; chatVisible = false; contactProfile = null; conversationJson = null
                    },
                    onProfile = { ownProfile = it; account = false; chatVisible = false; conversationJson = null },
                    operationScope = app.applicationScope,
                    googleAuth = app.googleAuth)
            } else if (nearby && app.search != null) {
                NearbySearchScreen(app.search!!, app.catalogue,
                    ensurePermission = { ensureLocationPermission() },
                    locate = { currentLocation(this@MainActivity) },
                    onBack = { nearby = false },
                    onOpenProfile = { ownProfile = it; nearby = false })
            } else {
                CatalogueScreen(app.catalogue, onAccount = { ownProfile = null; account = true }, onLanguageChange = changeLanguage,
                    initialProfileId = ownProfile, covers = app.covers, ownedProfileId = owner,
                    onContact = { ownProfile = it; contactProfile = it; conversationJson = null; chatVisible = true; account = false },
                    onChats = { ownProfile = null; contactProfile = null; conversationJson = null; chatVisible = true; account = false },
                    onNearby = if (app.search != null) ({ ownProfile = null; nearby = true }) else null)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthLink(intent)
    }

    override fun onStart() {
        super.onStart()
        app.applicationScope.launch { app.identity.restore() }
    }

    private fun handleAuthLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        // Same scope as restore(): the exchange must not be cancelled by navigation.
        app.applicationScope.launch { app.identity.handleAuthLink(link) }
    }
}
