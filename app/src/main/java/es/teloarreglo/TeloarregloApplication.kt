package es.teloarreglo

import android.app.Application
import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.AccessState
import es.teloarreglo.identity.EncryptedSessionManager
import es.teloarreglo.identity.Identity
import es.teloarreglo.identity.SupabaseIdentity
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.catalogue.SupabaseCatalogue
import es.teloarreglo.catalogue.ProfessionalCovers
import es.teloarreglo.catalogue.SupabaseProfessionalCovers
import es.teloarreglo.appointments.Appointments
import es.teloarreglo.appointments.SupabaseAppointments
import es.teloarreglo.catalogue.ProfileReviews
import es.teloarreglo.catalogue.SupabaseProfileReviews
import es.teloarreglo.chat.Chat
import es.teloarreglo.chat.ChatAttachments
import es.teloarreglo.chat.SupabaseChatAttachments
import es.teloarreglo.chat.SupabaseChat
import es.teloarreglo.search.HttpProfessionalSearch
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.ConcejoDirectory
import es.teloarreglo.search.SupabaseConcejoDirectory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.compose.auth.ComposeAuth
import io.github.jan.supabase.compose.auth.composeAuth
import io.github.jan.supabase.compose.auth.googleNativeLogin
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.seconds

/** Host of the links that return from verification and recovery emails. */
const val AUTH_LINK_HOST = "auth"

class TeloarregloApplication : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Firebase must exist before FCM wakes the messaging service with the app closed.
        firebaseTokens
    }

    /** Each variant has its own scheme so both APKs can be installed side by side. */
    val authLink = "${BuildConfig.APPLICATION_ID}://$AUTH_LINK_HOST"

    private val client: SupabaseClient? by lazy {
        if (!BuildConfig.SUPABASE_URL.startsWith("https://") ||
            !BuildConfig.SUPABASE_KEY.startsWith("sb_publishable_")
        ) {
            null
        } else {
            createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
                defaultLogLevel = LogLevel.NONE
                install(Auth) {
                    autoLoadFromStorage = false
                    sessionManager = EncryptedSessionManager(this@TeloarregloApplication)
                    // Email links carry a one-time code that only this device can exchange.
                    flowType = FlowType.PKCE
                    scheme = BuildConfig.APPLICATION_ID
                    host = AUTH_LINK_HOST
                }
                install(Postgrest)
                install(Storage) { resumable { cache = MemoryResumableCache() } }
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()) {
                    install(ComposeAuth) { googleNativeLogin(serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID) }
                }
                requestTimeout = 15.seconds
            }
        }
    }

    val identity: Identity by lazy {
        client?.let { c -> SupabaseIdentity(c, applicationScope, authLink).let { inner -> push?.let { PushAwareIdentity(inner, it) } ?: inner } }
            ?: MissingConfiguration()
    }

    /** Persisted notices of the signed-in account (#32); null without configuration. */
    val notices: es.teloarreglo.notices.Notices? by lazy { client?.let { es.teloarreglo.notices.SupabaseNotices(it) } }
    private val firebaseTokens by lazy { es.teloarreglo.notices.FirebasePushTokens(this) }
    val pushPreferences by lazy { es.teloarreglo.notices.SharedPushPreferences(this) }
    /** Push registration of this installation (#32); without Firebase configuration it reports NOT_CONFIGURED. */
    val push: es.teloarreglo.notices.PushRegistration? by lazy {
        client?.let {
            es.teloarreglo.notices.PushRegistration(es.teloarreglo.notices.SupabaseNotices(it),
                firebaseTokens, pushPreferences) {
                es.teloarreglo.notices.notificationPermissionGranted(this)
            }
        }
    }
    val catalogue: Catalogue by lazy { client?.let(::SupabaseCatalogue) ?: MissingCatalogueConfiguration() }
    val covers: ProfessionalCovers? by lazy { client?.let(::SupabaseProfessionalCovers) }
    val chat: Chat? by lazy { client?.let(::SupabaseChat) }
    val appointments: Appointments? by lazy { client?.let(::SupabaseAppointments) }
    /** Incidents and private evidence of appointments (#26). */
    val incidents: es.teloarreglo.appointments.Incidents? by lazy { client?.let { es.teloarreglo.appointments.SupabaseIncidents(it) } }
    /** Reviews of performed appointments and the public ones of a profile (#28). */
    val reviews: es.teloarreglo.appointments.Reviews? by lazy { client?.let { es.teloarreglo.appointments.SupabaseReviews(it) } }
    /** The professional's single agenda (#21); confirmations reserve through it on the server. */
    val agenda: es.teloarreglo.appointments.Agenda? by lazy { client?.let { es.teloarreglo.appointments.SupabaseAgenda(it) } }
    val chatAttachments: ChatAttachments? by lazy { client?.let(::SupabaseChatAttachments) }
    /** Owner's profile review result decided in the private panel (#29). */
    val profileReviews: ProfileReviews? by lazy { client?.let(::SupabaseProfileReviews) }
    /** Owner presents and changes their professional profile for review (#30). */
    val professionalProfiles: es.teloarreglo.catalogue.ProfessionalProfiles? by lazy {
        client?.let { es.teloarreglo.catalogue.SupabaseProfessionalProfiles(it) }
    }
    /** Simulated optional subscription (#33); never charges. */
    val subscriptions: es.teloarreglo.subscription.Subscriptions? by lazy { client?.let { es.teloarreglo.subscription.SupabaseSubscriptions(it) } }
    /** Nearby search through the public Edge Function; null without configuration. */
    val search: ProfessionalSearch? by lazy {
        client?.let { HttpProfessionalSearch(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY, HttpClient(OkHttp)) }
    }

    /** Concejos for the manual job location (#17); null without configuration. */
    val concejos: ConcejoDirectory? by lazy { client?.let(::SupabaseConcejoDirectory) }

    /** Private home address of the signed-in account (#37); null without configuration. */
    val homeSession: es.teloarreglo.home.HomeSession? by lazy {
        client?.let { es.teloarreglo.home.HomeSession(es.teloarreglo.home.SupabaseHomeAddresses(it)) }
    }

    /** The person's own name (#40), asked once if the account has none; null without configuration. */
    val accountNameSession: es.teloarreglo.identity.AccountNameSession? by lazy {
        client?.let { es.teloarreglo.identity.AccountNameSession(es.teloarreglo.identity.SupabaseAccountNames(it)) }
    }

    /** Google Places (New) autocomplete with the Maps key; null without key (pin/GPS fallback). */
    val addressAutocomplete: es.teloarreglo.home.AddressAutocomplete? by lazy {
        es.teloarreglo.home.GooglePlacesAutocomplete.create(this, BuildConfig.MAPS_API_KEY)
    }

    /** Native Google sign-in, only when its public web client ID is configured for this build. */
    val googleAuth: ComposeAuth?
        get() = client?.takeIf { BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty() }?.composeAuth
}

/** Unregisters this device for push before the session ends, so the next account never sees these pushes. */
private class PushAwareIdentity(private val inner: Identity, private val push: es.teloarreglo.notices.PushRegistration) :
    Identity by inner {
    override suspend fun signOut() {
        push.beforeSignOut()
        inner.signOut()
    }
}

private class MissingCatalogueConfiguration : Catalogue {
    override suspend fun list(trade: String?): List<PublicProfile> = error("configuration_required")
    override suspend fun profile(id: String): PublicProfile? = error("configuration_required")
}

private class MissingConfiguration : Identity {
    override val state = MutableStateFlow(
        AccessState(AccessStage.SIGNED_OUT, error = AccessError.CONFIGURATION),
    )
    override suspend fun restore() = Unit
    override suspend fun signIn(email: String, password: String) = Unit
    override suspend fun signOut() = Unit
    override suspend fun signUp(email: String, password: String, name: String) = Unit
    override suspend fun resendVerification(email: String) = Unit
    override suspend fun requestPasswordReset(email: String) = Unit
    override suspend fun handleAuthLink(link: String) = false
    override suspend fun setNewPassword(password: String) = Unit
    override suspend fun completeGoogleSignIn() = Unit
    override fun reportGoogleFailure() = Unit
    override fun dismiss() = Unit
}
