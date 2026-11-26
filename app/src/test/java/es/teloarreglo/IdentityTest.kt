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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** Identity's public operations with only the external HTTP service replaced. */
class IdentityTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun anAutomaticRefreshServerFailureIsNotReportedAsLostConnectivity() = runTest {
        val client = createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                if (request.url.parameters["grant_type"] == "refresh_token") {
                    respond(
                        """{"msg":"Server unavailable"}""", HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    val body = if (request.url.encodedPath == "/auth/v1/token") {
                        """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"token_type":"bearer",
                           "user":{"aud":"authenticated","id":"account-12","email":"cliente@example.org"}}"""
                    } else {
                        """{"user_id":"account-12","email":"cliente@example.org","is_administrator":false}"""
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            install(Auth) {
                minimalSettings(autoSaveToStorage = true, sessionManager = MemorySessionManager())
                coroutineDispatcher = StandardTestDispatcher(testScheduler)
            }
            install(Postgrest)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            identity.signIn("cliente@example.org", "correct")
            backgroundScope.launch {
                client.auth.importSession(
                    UserSession("expired", "refresh", expiresIn = -1, tokenType = "bearer",
                        user = UserInfo(aud = "authenticated", id = "account-12")),
                    autoRefresh = true,
                )
            }
            val failure = identity.state.first { it.stage == AccessStage.UNAVAILABLE }
            assertEquals(AccessError.SERVICE, failure.error)
        } finally { client.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun anOldSignOutEventCannotOverwriteASubsequentlyVerifiedSession() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        val client = createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                val refreshing = request.url.parameters["grant_type"] == "refresh_token"
                if (refreshing) {
                    refreshStarted.complete(Unit)
                    finishRefresh.await()
                }
                val body = if (request.url.encodedPath == "/auth/v1/token") {
                    """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer",
                       "user":{"aud":"authenticated","id":"account-12","email":"cliente@example.org"}}"""
                } else {
                    """{"user_id":"account-12","email":"cliente@example.org","is_administrator":false}"""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                minimalSettings(autoSaveToStorage = true, sessionManager = MemorySessionManager())
                coroutineDispatcher = StandardTestDispatcher(testScheduler)
            }
            install(Postgrest)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            identity.signIn("cliente@example.org", "correct")
            val previous = client.auth.currentSessionOrNull()!!
            val restoring = async { identity.restore() }
            refreshStarted.await()
            // Another Auth consumer invalidates the previous session while renewal is in flight.
            client.auth.clearSession()
            runCurrent() // Let the event observer reach the locked restoration.
            client.auth.importSession(previous, autoRefresh = false)
            finishRefresh.complete(Unit)
            restoring.await()
            runCurrent()
            assertEquals(AccessStage.CONNECTED, identity.state.value.stage)
        } finally { client.close() }
    }

    /** A connected client whose service answers or fails as [online] says; the account is account-12. */
    private fun switchableClient(online: () -> Boolean, revoked: () -> Boolean = { false }) =
        createSupabaseClient("https://identity.example.org", "public-test-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                if (!online()) throw IOException("network unavailable")
                if (revoked() && request.url.parameters["grant_type"] == "refresh_token") {
                    respond("""{"code":400,"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""",
                        HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    val body = if (request.url.encodedPath == "/auth/v1/token") {
                        """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"token_type":"bearer",
                           "user":{"aud":"authenticated","id":"account-12","email":"cliente@example.org"}}"""
                    } else {
                        """{"user_id":"account-12","email":"cliente@example.org","is_administrator":false}"""
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            install(Auth) { minimalSettings(autoSaveToStorage = true, sessionManager = MemorySessionManager()) }
            install(Postgrest)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun comingBackToTheAppKeepsAConnectedAccountConnectedWhileItIsChecked() = runTest {
        // #72: the re-check of onStart used to pass through CHECKING, which closed the open screen and its sheets.
        val client = switchableClient(online = { true })
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            identity.signIn("cliente@example.org", "correct")
            assertEquals(AccessStage.CONNECTED, identity.state.value.stage)
            val seen = mutableListOf<AccessStage>()
            val watching = backgroundScope.launch { identity.state.collect { seen += it.stage } }
            runCurrent()
            identity.restore()
            runCurrent()
            watching.cancel()
            assertEquals(listOf(AccessStage.CONNECTED), seen.distinct())
            assertEquals("account-12", identity.state.value.account?.userId)
        } finally { client.close() }
    }

    @Test fun comingBackWithoutNetworkKeepsTheVerifiedSession() = runTest {
        var online = true
        val client = switchableClient(online = { online })
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            identity.signIn("cliente@example.org", "correct")
            online = false
            identity.restore()
            assertEquals(AccessStage.CONNECTED, identity.state.value.stage)
        } finally { client.close() }
    }

    @Test fun comingBackWithARevokedSessionStillSignsOut() = runTest {
        var revoked = false
        val client = switchableClient(online = { true }, revoked = { revoked })
        try {
            val identity = SupabaseIdentity(client, backgroundScope)
            identity.restore()
            identity.signIn("cliente@example.org", "correct")
            revoked = true
            identity.restore()
            assertEquals(AccessStage.SIGNED_OUT, identity.state.value.stage)
            assertEquals(AccessError.EXPIRED, identity.state.value.error)
        } finally { client.close() }
    }

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
