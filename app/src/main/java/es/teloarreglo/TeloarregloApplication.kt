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
import es.teloarreglo.chat.Chat
import es.teloarreglo.chat.SupabaseChat
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
        client?.let { SupabaseIdentity(it, applicationScope, authLink) } ?: MissingConfiguration()
    }
    val catalogue: Catalogue by lazy { client?.let(::SupabaseCatalogue) ?: MissingCatalogueConfiguration() }
    val covers: ProfessionalCovers? by lazy { client?.let(::SupabaseProfessionalCovers) }
    val chat: Chat? by lazy { client?.let(::SupabaseChat) }

    /** Native Google sign-in, only when its public web client ID is configured for this build. */
    val googleAuth: ComposeAuth?
        get() = client?.takeIf { BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty() }?.composeAuth
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
    override suspend fun signUp(email: String, password: String) = Unit
    override suspend fun resendVerification(email: String) = Unit
    override suspend fun requestPasswordReset(email: String) = Unit
    override suspend fun handleAuthLink(link: String) = false
    override suspend fun setNewPassword(password: String) = Unit
    override suspend fun completeGoogleSignIn() = Unit
    override fun reportGoogleFailure() = Unit
    override fun dismiss() = Unit
}
