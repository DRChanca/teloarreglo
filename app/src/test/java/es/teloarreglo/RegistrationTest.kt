package es.teloarreglo

import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessNotice
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.SupabaseIdentity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.minimalSettings
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registration, verification, recovery and email links through Identity's public operations,
 * with only the external Auth/REST service replaced by a recorded fake.
 */
class RegistrationTest {
    private val link = "es.teloarreglo.pruebas://auth"
    private val user = """{"aud":"authenticated","id":"account-13","email":"nuevo@example.org"}"""
    private val session = """{"access_token":"access","refresh_token":"refresh","expires_in":3600,
        "token_type":"bearer","user":$user}"""
    private val account = """{"user_id":"account-13","email":"nuevo@example.org","is_administrator":false}"""

    /** A request seen by the fake service, with its body already read. */
    private data class Seen(val method: HttpMethod, val path: String, val query: Map<String, String>, val body: String)

    private class Fake { val seen = mutableListOf<Seen>() }

    private fun client(
        fake: Fake,
        handler: suspend MockRequestHandleScope.(HttpRequestData, Seen) -> HttpResponseData,
    ): SupabaseClient = createSupabaseClient("https://identity.example.org", "public-test-key") {
        defaultLogLevel = LogLevel.NONE
        httpEngine = MockEngine { request ->
            val seen = Seen(
                request.method, request.url.encodedPath,
                request.url.parameters.entries().associate { it.key to it.value.first() },
                request.body.toByteArray().decodeToString(),
            )
            fake.seen += seen
            handler(request, seen)
        }
        install(Auth) {
            minimalSettings(autoSaveToStorage = true, sessionManager = MemorySessionManager())
            flowType = FlowType.PKCE
        }
        install(Postgrest)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.authError(code: String, status: HttpStatusCode) =
        json("""{"code":${status.value},"error_code":"$code","msg":"$code"}""", status)

    /** Normal service: signup/recover/resend accepted, PKCE exchange and account RPC succeed. */
    private suspend fun MockRequestHandleScope.service(seen: Seen) = when (seen.path) {
        "/auth/v1/signup" -> json(user)
        "/auth/v1/token" -> json(session)
        "/auth/v1/user" -> json(user)
        "/rest/v1/rpc/current_account" -> json(account)
        else -> json("{}")
    }

    @Test fun signingUpWaitsForVerificationAndNeverSendsCapabilities() = runTest {
        val fake = Fake()
        val client = client(fake) { _, seen -> service(seen) }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.signUp("nuevo@example.org", "contraseña-larga")
            val state = identity.state.value
            assertEquals(AccessStage.AWAITING_VERIFICATION, state.stage)
            assertEquals("nuevo@example.org", state.pendingEmail)
            assertEquals(AccessNotice.VERIFICATION_SENT, state.notice)
            assertNull("an unverified account is not connected", state.account)
            val signup = fake.seen.single { it.path == "/auth/v1/signup" }
            assertEquals("$link?flow=signup", signup.query["redirect_to"])
            assertTrue("PKCE challenge required for the email link", signup.body.contains("code_challenge"))
            assertFalse("no role or capability is sent", signup.body.contains("administrator") || signup.body.contains("role"))
            assertTrue(fake.seen.none { it.path == "/rest/v1/rpc/current_account" })
        } finally { client.close() }
    }

    @Test fun aWeakPasswordIsExplainedAndTheUserStaysOnTheForm() = runTest {
        val client = client(Fake()) { _, _ -> authError("weak_password", HttpStatusCode.UnprocessableEntity) }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.signUp("nuevo@example.org", "corta")
            assertEquals(AccessStage.SIGNED_OUT, identity.state.value.stage)
            assertEquals(AccessError.WEAK_PASSWORD, identity.state.value.error)
        } finally { client.close() }
    }

    @Test fun resendingTooOftenIsReportedWithoutLeavingTheVerificationScreen() = runTest {
        val fake = Fake()
        val client = client(fake) { _, seen ->
            if (seen.path == "/auth/v1/resend") authError("over_email_send_rate_limit", HttpStatusCode.TooManyRequests)
            else service(seen)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.signUp("nuevo@example.org", "contraseña-larga")
            identity.resendVerification("nuevo@example.org")
            assertEquals(AccessStage.AWAITING_VERIFICATION, identity.state.value.stage)
            assertEquals(AccessError.RATE_LIMITED, identity.state.value.error)
            assertTrue(fake.seen.single { it.path == "/auth/v1/resend" }.body.contains("\"signup\""))
        } finally { client.close() }
    }

    @Test fun anUnverifiedSignInOffersToResendToTheSameAddress() = runTest {
        val client = client(Fake()) { _, _ -> authError("email_not_confirmed", HttpStatusCode.BadRequest) }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.signIn("nuevo@example.org", "contraseña-larga")
            assertEquals(AccessError.UNVERIFIED, identity.state.value.error)
            assertEquals("nuevo@example.org", identity.state.value.pendingEmail)
        } finally { client.close() }
    }

    @Test fun theVerificationLinkConnectsTheAccountOnTheSameDevice() = runTest {
        val fake = Fake()
        val client = client(fake) { _, seen -> service(seen) }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.signUp("nuevo@example.org", "contraseña-larga")
            assertTrue(identity.handleAuthLink("$link?flow=signup&code=one-time-code"))
            assertEquals(AccessStage.CONNECTED, identity.state.value.stage)
            assertEquals("account-13", identity.state.value.account?.userId)
            val exchange = fake.seen.single { it.path == "/auth/v1/token" }
            assertEquals("pkce", exchange.query["grant_type"])
            assertTrue(exchange.body.contains("one-time-code"))
            assertTrue("verifier stored at signup is sent", exchange.body.contains("code_verifier"))
        } finally { client.close() }
    }

    @Test fun passwordRecoveryEndsWithANewPasswordAndIsNotLostOnRestore() = runTest {
        val fake = Fake()
        val client = client(fake) { _, seen -> service(seen) }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.requestPasswordReset("nuevo@example.org")
            assertEquals(AccessNotice.RESET_SENT, identity.state.value.notice)
            assertEquals("$link?flow=recovery", fake.seen.single { it.path == "/auth/v1/recover" }.query["redirect_to"])

            assertTrue(identity.handleAuthLink("$link?flow=recovery&code=recovery-code"))
            assertEquals(AccessStage.RECOVERING_PASSWORD, identity.state.value.stage)
            identity.restore() // The app returning to the foreground must not skip the new password.
            assertEquals(AccessStage.RECOVERING_PASSWORD, identity.state.value.stage)

            identity.setNewPassword("otra-contraseña-larga")
            assertEquals(AccessStage.CONNECTED, identity.state.value.stage)
            assertEquals(AccessNotice.PASSWORD_CHANGED, identity.state.value.notice)
            val update = fake.seen.single { it.path == "/auth/v1/user" }
            assertEquals(HttpMethod.Put, update.method)
            assertTrue(update.body.contains("otra-contraseña-larga"))
        } finally { client.close() }
    }

    @Test fun aLinkOpenedOnAnotherDeviceIsInvalidWithoutInventingASession() = runTest {
        val fake = Fake()
        val client = client(fake) { _, seen -> service(seen) }
        try {
            // Fresh client: no code verifier was stored on this device.
            val identity = SupabaseIdentity(client, backgroundScope, link)
            assertTrue(identity.handleAuthLink("$link?flow=recovery&code=recovery-code"))
            assertEquals(AccessStage.SIGNED_OUT, identity.state.value.stage)
            assertEquals(AccessError.LINK_INVALID, identity.state.value.error)
            assertTrue(fake.seen.none { it.path == "/auth/v1/token" })
        } finally { client.close() }
    }

    @Test fun anExpiredOrUsedCodeIsInvalid() = runTest {
        val client = client(Fake()) { _, seen ->
            if (seen.path == "/auth/v1/token") authError("flow_state_not_found", HttpStatusCode.NotFound)
            else service(seen)
        }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.requestPasswordReset("nuevo@example.org")
            identity.handleAuthLink("$link?flow=recovery&code=used-code")
            assertEquals(AccessError.LINK_INVALID, identity.state.value.error)
            assertNull(identity.state.value.account)
        } finally { client.close() }
    }

    @Test fun anErrorReturnedByAuthInTheLinkIsShownAndSurvivesTheRestore() = runTest {
        val fake = Fake()
        val client = client(fake) { _, seen -> service(seen) }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            identity.handleAuthLink("$link?flow=signup#error=access_denied&error_code=otp_expired&error_description=Link+expired")
            assertEquals(AccessError.LINK_INVALID, identity.state.value.error)
            identity.restore()
            assertEquals(AccessStage.SIGNED_OUT, identity.state.value.stage)
            assertEquals(AccessError.LINK_INVALID, identity.state.value.error)
            assertTrue(fake.seen.none { it.path == "/auth/v1/token" })
        } finally { client.close() }
    }

    @Test fun linksForOtherAppsOrHostsAreIgnored() = runTest {
        val fake = Fake()
        val client = client(fake) { _, seen -> service(seen) }
        try {
            val identity = SupabaseIdentity(client, backgroundScope, link)
            assertFalse(identity.handleAuthLink("es.teloarreglo.presentacion://auth?code=x"))
            assertFalse(identity.handleAuthLink("https://evil.example.org/auth?code=x"))
            assertFalse(identity.handleAuthLink("es.teloarreglo.pruebas://otro?code=x"))
            assertTrue(fake.seen.isEmpty())
        } finally { client.close() }
    }
}
