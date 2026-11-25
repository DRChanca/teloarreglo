package es.teloarreglo

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import es.teloarreglo.search.currentLocation
import es.teloarreglo.search.hasLocationPermission
import kotlinx.coroutines.CompletableDeferred
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.home.HomeStatus
import es.teloarreglo.identity.AccountNameStatus

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

    /** Notification permission (#32): asked from the notices screen; if refused before, opens system settings. */
    private suspend fun ensureNotificationPermission() {
        if (es.teloarreglo.notices.notificationPermissionGranted(this)) return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val answer = CompletableDeferred<Boolean>().also { permissionAnswer = it }
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            if (answer.await() && es.teloarreglo.notices.notificationPermissionGranted(this)) return
        }
        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** Set when the activity is opened from a push notification. */
    private val openNotices = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Start splash with the woodpecker (#58); animated only on a cold start.
        installStartSplash(coldStart = savedInstanceState == null)
        // Bars drawn by the app: their icons follow the active light/dark theme (#45).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a configuration change the link was already handled; codes are single use.
        if (savedInstanceState == null) handleAuthLink(intent)
        if (intent?.getBooleanExtra(es.teloarreglo.notices.EXTRA_OPEN_NOTICES, false) == true) openNotices.value = true
        // One theme for the whole app, following the system light/dark mode (#45).
        setContent { TeloTheme {
            // Selected tab and open subscreen (#46), kept across recreation.
            var nav by rememberSaveable(stateSaver = Navigation.saver) { mutableStateOf(Navigation()) }
            var unreadNotices by remember { mutableIntStateOf(0) }
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
            val homeSession = app.homeSession
            val homeStatus by (homeSession?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow(HomeStatus.Unknown) })
                .collectAsStateWithLifecycle()
            val connectedUser = access.account?.userId?.takeIf { access.stage == AccessStage.CONNECTED }
            // Notices (#32): this device follows the signed-in account; the unread count is read from the server.
            LaunchedEffect(access.stage == AccessStage.CHECKING, connectedUser) {
                if (access.stage != AccessStage.CHECKING) app.push?.sync(connectedUser)
            }
            LaunchedEffect(connectedUser, nav.tab, nav.conversation?.id) {
                // The app is open on this account: its conversations reached this device (senders' second tick).
                if (connectedUser != null) try { app.chat?.markAllDelivered() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                unreadNotices = if (connectedUser == null) 0 else try {
                    app.notices?.list()?.count { !it.read } ?: 0
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { unreadNotices }
            }
            val openFromPush by openNotices
            LaunchedEffect(openFromPush) {
                if (openFromPush) { nav = Navigation().open(Tab.NOTICES); openNotices.value = false }
            }
            LaunchedEffect(access.stage == AccessStage.CHECKING, connectedUser) {
                if (access.stage != AccessStage.CHECKING) homeSession?.load(connectedUser)
            }
            // Person's name (#40): asked once, before the home step, when the server confirms it is missing.
            val nameSession = app.accountNameSession
            val nameStatus by (nameSession?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow(AccountNameStatus.Unknown) })
                .collectAsStateWithLifecycle()
            LaunchedEffect(access.stage == AccessStage.CHECKING, connectedUser) {
                if (access.stage != AccessStage.CHECKING) nameSession?.load(connectedUser)
            }
            val mustGiveName = nameSession != null && connectedUser != null && nameStatus == AccountNameStatus.Missing &&
                access.account?.isAdministrator != true && !emailLinkPending
            val home = (homeStatus as? HomeStatus.Present)?.home
            // Search from home (#59): started as soon as the account and its home are known, during the splash.
            // Not while checking: every return to the foreground checks the session again.
            LaunchedEffect(access.stage == AccessStage.CHECKING, connectedUser, access.account?.isAdministrator, home) {
                if (access.stage != AccessStage.CHECKING)
                    app.searchSession?.onAccount(connectedUser, access.account?.isAdministrator == true, home)
            }
            val mustGiveHome = homeSession != null && connectedUser != null && homeStatus == HomeStatus.Missing &&
                access.account?.isAdministrator != true && !emailLinkPending
            val changeLanguage: (String) -> Unit = { tag ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            val screen = nav.screen(Session(
                connected = connectedUser != null && app.chat != null && app.notices != null,
                mustGiveName = mustGiveName, mustGiveHome = mustGiveHome, emailLinkPending = emailLinkPending,
            ))
            // Back from a tab goes Home; at Home it leaves the app. Mandatory steps keep the system default.
            val mandatory = screen == MainScreen.NAME_STEP || screen == MainScreen.HOME_STEP
            BackHandler(enabled = !mandatory && nav.back() != null) { nav.back()?.let { nav = it } }
            Scaffold(
                // Each screen handles the system insets itself; the scaffold only reserves the bar.
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    if (screen.showsBar) MainBottomBar(nav.tab,
                        unreadNotices = unreadNotices, onSelect = { nav = nav.open(it) })
                },
            ) { padding ->
                Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                    when (screen) {
                        MainScreen.NAME_STEP -> key(connectedUser) {
                            AccountNameScreen(nameSession!!, app.applicationScope,
                                onSignOut = { app.applicationScope.launch { app.identity.signOut() } })
                        }
                        MainScreen.HOME_STEP, MainScreen.HOME_EDITOR -> key(connectedUser) {
                            HomeAddressScreen(homeSession!!, app.addressAutocomplete,
                                PinSource({ ensureLocationPermission() }, { currentLocation(this@MainActivity) },
                                    mapsReady = deviceMapAvailability(this@MainActivity) == es.teloarreglo.search.MapAvailability.READY),
                                app.applicationScope, required = mustGiveHome,
                                onDone = { nav = nav.doneEditingHome() },
                                onSignOut = { nav = nav.doneEditingHome(); app.applicationScope.launch { app.identity.signOut() } })
                        }
                        MainScreen.PROFILE_EDITOR -> app.professionalProfiles?.let { profiles ->
                            ProfileEditorScreen(profiles, app.concejos, app.applicationScope,
                                onBack = { nav = nav.back() ?: Navigation() },
                                onStarted = { app.identity.restore() }, covers = app.covers, agenda = app.agenda,
                                zones = app.workZones)
                        }
                        MainScreen.NOTICES -> key(connectedUser) {
                            NoticesScreen(app.notices!!, app.push, connectedUser!!, app.applicationScope,
                                askPermission = { ensureNotificationPermission() },
                                onOpenConversation = { id ->
                                    es.teloarreglo.notices.openNoticeConversation(id, { app.chat?.conversations() }, { it.id }) {
                                        nav = Navigation().openFromNotice(it)
                                    }
                                },
                                onOpenAccount = { nav = nav.open(Tab.ACCOUNT) },
                                onBack = { nav = Navigation() })
                        }
                        MainScreen.CHATS, MainScreen.CONVERSATION -> key(connectedUser) {
                            ChatRoute(app.chat!!, connectedUser!!, nav.contactProfile, nav.conversation,
                                onOpened = { nav = nav.openConversation(it) },
                                onBack = { nav = nav.back() ?: Navigation() },
                                appointmentsSlot = app.appointments?.let { appointments -> { conversation ->
                                    AppointmentsPanel(appointments, connectedUser, conversation, app.applicationScope,
                                        PinSource({ ensureLocationPermission() }, { currentLocation(this@MainActivity) },
                                            mapsReady = deviceMapAvailability(this@MainActivity) == es.teloarreglo.search.MapAvailability.READY,
                                            home = home),
                                        incidentTools = app.incidents?.let { IncidentTools(it, app.chatAttachments) },
                                        reviews = app.reviews, zones = app.workZones)
                                } },
                                attachments = app.chatAttachments,
                                evidence = app.incidents?.let { remember(it) { ChatEvidenceLink(it, app.applicationScope) } },
                                onBrowse = { nav = nav.open(Tab.HOME) })
                        }
                        MainScreen.ACCESS, MainScreen.EMAIL_LINK -> AccessScreen(app.identity, changeLanguage,
                            onLeaveLink = {
                                // Clears the invalid-link error so the catalogue can be reached.
                                app.identity.dismiss()
                                nav = Navigation()
                            },
                            onProfile = { nav = nav.showProfile(it) },
                            operationScope = app.applicationScope,
                            googleAuth = app.googleAuth, profileReviews = app.profileReviews,
                            subscriptions = app.subscriptions, agenda = app.agenda,
                            nameSlot = nameSession?.let { session -> { AccountNameCard(session, app.applicationScope) } },
                            homeSlot = homeSession?.let { session -> { HomeSummaryCard(session, onChange = { nav = nav.editHome() }) } },
                            onEditProfile = app.professionalProfiles?.let { { nav = nav.editProfile() } })
                        MainScreen.CATALOGUE -> CatalogueScreen(app.catalogue,
                            initialProfileId = nav.profile, covers = app.covers, ownedProfileId = owner,
                            onContact = { nav = nav.showProfile(it).contact(it) },
                            // Opened from another tab (Cuenta): closing the profile returns there.
                            onCloseProfile = nav.returnTo?.let { { nav = nav.back() ?: Navigation() } },
                            reviews = app.reviews, searchSession = app.searchSession,
                            // Single search (ADR 0001): from the saved home, GPS or a concejo, by distance.
                            proximitySource = app.search?.let { search ->
                                ProximitySource(search,
                                    ensurePermission = { ensureLocationPermission() },
                                    locate = { currentLocation(this@MainActivity) },
                                    concejos = app.concejos, map = remember { deviceMapAvailability(this@MainActivity) },
                                    home = home, askIfAway = { homeSession?.askIfAway(it) == true },
                                    onChangeHome = { nav = nav.editHome() })
                            })
                    }
                }
            }
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthLink(intent)
        if (intent.getBooleanExtra(es.teloarreglo.notices.EXTRA_OPEN_NOTICES, false)) openNotices.value = true
    }

    override fun onStart() {
        super.onStart()
        app.applicationScope.launch {
            app.identity.restore()
            // A home check that failed for lack of network is retried when the app comes back.
            app.homeSession?.takeIf { it.status.value == HomeStatus.Failed }
                ?.load(app.identity.state.value.account?.userId)
            app.accountNameSession?.takeIf { it.status.value == AccountNameStatus.Failed }
                ?.load(app.identity.state.value.account?.userId)
        }
    }

    private fun handleAuthLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        // Same scope as restore(): the exchange must not be cancelled by navigation.
        app.applicationScope.launch { app.identity.handleAuthLink(link) }
    }
}
