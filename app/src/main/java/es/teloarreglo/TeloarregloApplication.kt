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
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.logging.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.seconds

class TeloarregloApplication : Application() {
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy {
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
                }
                install(Postgrest)
                requestTimeout = 15.seconds
            }
        }
    }
    val identity: Identity by lazy { client?.let { SupabaseIdentity(it, applicationScope) } ?: MissingConfiguration() }
    val catalogue: Catalogue by lazy { client?.let(::SupabaseCatalogue) ?: MissingCatalogueConfiguration() }
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
}
