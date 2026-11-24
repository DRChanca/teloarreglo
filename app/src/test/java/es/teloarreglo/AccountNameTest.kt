package es.teloarreglo

import es.teloarreglo.identity.AccountNameException
import es.teloarreglo.identity.AccountNameFailure
import es.teloarreglo.identity.AccountNameSession
import es.teloarreglo.identity.AccountNameStatus
import es.teloarreglo.identity.AccountNames
import es.teloarreglo.identity.SupabaseAccountNames
import es.teloarreglo.identity.cleanAccountName
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeNames : AccountNames {
    val stored = mutableMapOf<String, String>()
    var user: String? = null
    var offline = false
    override suspend fun current(): String? {
        if (offline) throw IOException("offline")
        return stored[user]
    }
    override suspend fun save(name: String): String {
        if (offline) throw IOException("offline")
        return name.trim().also { stored[user!!] = it }
    }
}

class AccountNameTest {
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://names.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun aNameIsTrimmedAndMustHaveBetweenOneAndEightyCharacters() {
        assertEquals("Ana López", cleanAccountName("  Ana López "))
        assertEquals("b".repeat(80), cleanAccountName("b".repeat(80)))
        assertNull(cleanAccountName("   "))
        assertNull(cleanAccountName("b".repeat(81)))
    }

    @Test fun theOwnNameIsReadFromTheServerAndMayBeMissing() = runTest {
        var answer = "\"Lucía Prueba\""
        val client = client { request ->
            assertEquals("/rest/v1/rpc/my_account_name", request.url.encodedPath)
            json(answer)
        }
        try {
            assertEquals("Lucía Prueba", SupabaseAccountNames(client).current())
            answer = "null"
            assertNull(SupabaseAccountNames(client).current())
        } finally { client.close() }
    }

    @Test fun savingSendsTheTrimmedNameAndReturnsWhatTheServerStored() = runTest {
        val client = client { request ->
            assertEquals("/rest/v1/rpc/set_account_name", request.url.encodedPath)
            assertEquals("""{"p_name":"Ana López"}""", (request.body as TextContent).text)
            json("\"Ana López\"")
        }
        try { assertEquals("Ana López", SupabaseAccountNames(client).save("  Ana López ")) } finally { client.close() }
    }

    @Test fun serverRejectionsAreKeptApartFromNetworkUncertainty() = runTest {
        for ((status, body, expected) in listOf(
            Triple(HttpStatusCode.BadRequest, """{"code":"22023","message":"invalid_account_name"}""", AccountNameFailure.INVALID),
            Triple(HttpStatusCode.Unauthorized, """{"code":"28000","message":"authentication_required"}""", AccountNameFailure.ACCESS),
        )) {
            val client = client { json(body, status) }
            try {
                SupabaseAccountNames(client).save("Ana")
                fail("expected $expected")
            } catch (error: AccountNameException) {
                assertEquals(expected, error.failure)
            } finally { client.close() }
        }
    }

    @Test fun anAccountWithoutNameIsAskedOnceAndThenContinues() = runTest {
        val names = FakeNames().apply { user = "a" }
        val session = AccountNameSession(names)
        session.load("a")
        assertEquals(AccountNameStatus.Missing, session.status.value)
        assertTrue(session.required)
        session.save("  Ana López ")
        assertEquals(AccountNameStatus.Present("Ana López"), session.status.value)
        assertFalse(session.required)
    }

    @Test fun aGoogleOrSignupNameNeedsNoQuestion() = runTest {
        val names = FakeNames().apply { user = "a"; stored["a"] = "Pedro Google" }
        val session = AccountNameSession(names)
        session.load("a")
        assertEquals(AccountNameStatus.Present("Pedro Google"), session.status.value)
        assertFalse(session.required)
    }

    @Test fun notKnowingBecauseOfTheNetworkNeverBlocksTheApp() = runTest {
        val session = AccountNameSession(FakeNames().apply { user = "a"; offline = true })
        session.load("a")
        assertEquals(AccountNameStatus.Failed, session.status.value)
        assertFalse(session.required)
    }

    @Test fun signingOutForgetsTheName() = runTest {
        val names = FakeNames().apply { user = "a"; stored["a"] = "Ana" }
        val session = AccountNameSession(names)
        session.load("a")
        session.load(null)
        assertEquals(AccountNameStatus.Unknown, session.status.value)
        names.user = "b"
        session.load("b")
        assertEquals("another account is asked for its own name", AccountNameStatus.Missing, session.status.value)
    }
}
