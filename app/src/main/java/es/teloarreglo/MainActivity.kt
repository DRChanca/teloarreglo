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
import es.teloarreglo.home.HomeStatus
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
            var profileEditor by rememberSaveable { mutableStateOf(false) }
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
            // Home address (#37): mandatory once the server confirms a connected account has none.
            var editingHome by rememberSaveable { mutableStateOf(false) }
            val homeSession = app.homeSession
            val homeStatus by (homeSession?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow(HomeStatus.Unknown) })
                .collectAsStateWithLifecycle()
            val connectedUser = access.account?.userId?.takeIf { access.stage == AccessStage.CONNECTED }
            LaunchedEffect(access.stage == AccessStage.CHECKING, connectedUser) {
                if (access.stage != AccessStage.CHECKING) homeSession?.load(connectedUser)
            }
            val home = (homeStatus as? HomeStatus.Present)?.home
            val mustGiveHome = connectedUser != null && homeStatus == HomeStatus.Missing &&
                access.account?.isAdministrator != true && !emailLinkPending
            val changeLanguage: (String) -> Unit = { tag ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            BackHandler(enabled = editingHome && !mustGiveHome) { editingHome = false }
            BackHandler(enabled = profileEditor && !editingHome && !mustGiveHome) { profileEditor = false; account = true }
            BackHandler(enabled = !editingHome && !profileEditor && (account || nearby || (chatVisible && access.stage != AccessStage.CONNECTED))) {
                account = false; nearby = false; chatVisible = false; contactProfile = null; conversationJson = null
            }
            if (homeSession != null && (mustGiveHome || (editingHome && connectedUser != null))) {
                key(connectedUser) {
                    HomeAddressScreen(homeSession, app.addressAutocomplete,
                        PinSource({ ensureLocationPermission() }, { currentLocation(this@MainActivity) },
                            mapsReady = deviceMapAvailability(this@MainActivity) == es.teloarreglo.search.MapAvailability.READY),
                        app.applicationScope, required = mustGiveHome,
                        onDone = { editingHome = false },
                        onSignOut = { editingHome = false; app.applicationScope.launch { app.identity.signOut() } })
                }
            } else if (profileEditor && app.professionalProfiles != null) {
                ProfileEditorScreen(app.professionalProfiles!!, app.concejos, app.applicationScope,
                    onBack = { profileEditor = false; account = true },
                    onStarted = { app.identity.restore() }, covers = app.covers)
            } else if (chatVisible && access.stage == AccessStage.CONNECTED && app.chat != null) {
                key(access.account!!.userId) {
                    ChatRoute(app.chat!!, access.account!!.userId, contactProfile,
                        conversationJson?.let { Json.decodeFromString<Conversation>(it) },
                        onOpened = { contactProfile = null; conversationJson = Json.encodeToString(it) },
                        onBack = {
                            if (conversationJson != null && ownProfile == null) conversationJson = null
                            else { chatVisible = false; contactProfile = null; conversationJson = null }
                        },
                        appointmentsSlot = app.appointments?.let { appointments -> { conversation ->
                            AppointmentsPanel(appointments, access.account!!.userId, conversation, app.applicationScope,
                                PinSource({ ensureLocationPermission() }, { currentLocation(this@MainActivity) },
                                    mapsReady = deviceMapAvailability(this@MainActivity) == es.teloarreglo.search.MapAvailability.READY,
                                    home = home))
                        } },
                        attachments = app.chatAttachments)
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
                    googleAuth = app.googleAuth, profileReviews = app.profileReviews,
                    subscriptions = app.subscriptions, agenda = app.agenda,
                    homeSlot = homeSession?.let { session -> { HomeSummaryCard(session, onChange = { editingHome = true }) } },
                    onEditProfile = app.professionalProfiles?.let { { profileEditor = true; account = false } })
            } else if (nearby && app.search != null) {
                NearbySearchScreen(app.search!!, app.catalogue,
                    ensurePermission = { ensureLocationPermission() },
                    locate = { currentLocation(this@MainActivity) },
                    onBack = { nearby = false },
                    onOpenProfile = { ownProfile = it; nearby = false },
                    concejos = app.concejos, map = remember { deviceMapAvailability(this@MainActivity) },
                    home = home, askIfAway = { homeSession?.askIfAway(it) == true },
                    onChangeHome = { editingHome = true })
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
        app.applicationScope.launch {
            app.identity.restore()
            // A home check that failed for lack of network is retried when the app comes back.
            app.homeSession?.takeIf { it.status.value == HomeStatus.Failed }
                ?.load(app.identity.state.value.account?.userId)
        }
    }

    private fun handleAuthLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        // Same scope as restore(): the exchange must not be cancelled by navigation.
        app.applicationScope.launch { app.identity.handleAuthLink(link) }
    }
}
