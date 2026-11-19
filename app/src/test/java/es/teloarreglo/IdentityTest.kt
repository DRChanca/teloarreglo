package es.teloarreglo

import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.SupabaseIdentity
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.minimalSettings
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Identity's public operations with only the external HTTP service replaced. */
class IdentityTest {
    @Test fun connectionFailureDoesNotInventSuccessOrForgetTheSavedSession() = runTest {
        val saved = MemorySessionManager(
            UserSession("old-access", "valid-refresh", expiresIn = 3600, tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = "account-12")),
        )
        val client = createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { throw IOException("network unavailable") }
            install(Auth) { minimalSettings(autoSaveToStorage = true, sessionManager = saved) }
            install(Postgrest)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            assertEquals(AccessStage.UNAVAILABLE, identity.state.value.stage)
            assertEquals(AccessError.NETWORK, identity.state.value.error)
            assertEquals("valid-refresh", saved.loadSession()?.refreshToken)
        } finally { client.close() }
    }

    @Test fun revokedSessionRequestsSignInAndRemovesItsStoredToken() = runTest {
        val saved = MemorySessionManager(
            UserSession("old-access", "revoked-refresh", expiresIn = 3600, tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = "account-12")),
        )
        val client = createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine {
                respond(
                    """{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""",
                    HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            install(Auth) { minimalSettings(autoSaveToStorage = true, sessionManager = saved) }
            install(Postgrest)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            assertEquals(AccessStage.SIGNED_OUT, identity.state.value.stage)
            assertEquals(AccessError.EXPIRED, identity.state.value.error)
            assertEquals(null, saved.loadSession())
        } finally { client.close() }
    }

    @Test fun reopeningRefreshesAnExpiredAccessTokenBeforeShowingTheAccount() = runTest {
        val saved = MemorySessionManager(
            UserSession("expired-access", "valid-refresh", expiresIn = -100, tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = "account-12", email = "cliente@example.org")),
        )
        val client = createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                val body = if (request.url.encodedPath == "/auth/v1/token") {
                    """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer",
                       "user":{"aud":"authenticated","id":"account-12","email":"cliente@example.org"}}"""
                } else {
                    """{"user_id":"account-12","email":"cliente@example.org","is_administrator":false}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) { minimalSettings(autoSaveToStorage = true, sessionManager = saved) }
            install(Postgrest)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            assertEquals(AccessStage.CONNECTED, identity.state.value.stage)
            assertEquals("account-12", identity.state.value.account?.userId)
        } finally {
            client.close()
        }
    }

    @Test fun invalidCredentialsRemainSignedOut() = runTest {
        val client = createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine {
                respond(
                    """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            install(Auth) { minimalSettings(autoSaveToStorage = true, sessionManager = MemorySessionManager()) }
            install(Postgrest)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            identity.signIn("cliente@example.org", "wrong")
            assertEquals(AccessStage.SIGNED_OUT, identity.state.value.stage)
            assertEquals(AccessError.CREDENTIALS, identity.state.value.error)
        } finally {
            client.close()
        }
    }
}
