package es.teloarreglo

import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.EncryptedSessionManager
import es.teloarreglo.identity.SupabaseIdentity
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.minimalSettings
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Public identity boundary, real Android storage/Keystore; only HTTP is controlled. */
class PersistedIdentityTest {
    @Test fun aNewClientRecoversTheEncryptedSessionAndLogoutRemovesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageName = "identity-test-${UUID.randomUUID()}"
        fun client() = createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                val body = when (request.url.encodedPath) {
                    "/auth/v1/token" ->
                        """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"token_type":"bearer",
                           "user":{"aud":"authenticated","id":"account-12","email":"cliente@example.org"}}"""
                    "/rest/v1/rpc/current_account" ->
                        """{"user_id":"account-12","email":"cliente@example.org","is_administrator":false}"""
                    else -> ""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                minimalSettings(
                    autoSaveToStorage = true,
                    sessionManager = EncryptedSessionManager(context, storageName),
                )
            }
            install(Postgrest)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val first = client()
            try {
                val identity = SupabaseIdentity(first, scope)
                identity.restore()
                identity.signIn("cliente@example.org", "test-password")
                assertEquals(AccessStage.CONNECTED, identity.state.value.stage)
            } finally { first.close() }

            val second = client()
            try {
                val restored = SupabaseIdentity(second, scope)
                restored.restore()
                assertEquals(AccessStage.CONNECTED, restored.state.value.stage)
                assertEquals("account-12", restored.state.value.account?.userId)
                restored.signOut()
                assertEquals(AccessStage.SIGNED_OUT, restored.state.value.stage)
            } finally { second.close() }

            val third = client()
            try {
                val signedOut = SupabaseIdentity(third, scope)
                signedOut.restore()
                assertEquals(AccessStage.SIGNED_OUT, signedOut.state.value.stage)
            } finally { third.close() }
        } finally {
            scope.cancel()
            EncryptedSessionManager(context, storageName).deleteSession()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry("${context.packageName}.$storageName.session")
            }
            context.deleteSharedPreferences(storageName)
        }
    }
}
