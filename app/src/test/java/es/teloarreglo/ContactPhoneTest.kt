package es.teloarreglo

import es.teloarreglo.identity.ContactPhoneException
import es.teloarreglo.identity.ContactPhoneFailure
import es.teloarreglo.identity.ContactPhoneSession
import es.teloarreglo.identity.ContactPhoneStatus
import es.teloarreglo.identity.ContactPhones
import es.teloarreglo.identity.SupabaseContactPhones
import es.teloarreglo.identity.normalizeContactPhone
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

private class FakePhones : ContactPhones {
    val stored = mutableMapOf<String, String>()
    var user: String? = null
    var offline = false
    override suspend fun current(): String? {
        if (offline) throw IOException("offline")
        return stored[user]
    }
    override suspend fun save(phone: String): String {
        if (offline) throw IOException("offline")
        return normalizeContactPhone(phone)!!.also { stored[user!!] = it }
    }
}

/** Teléfono de contacto (#74): same cases as supabase/tests/contact_phones.test.sql. */
class ContactPhoneTest {
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://phones.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun acceptedNumbersAreNormalizedToE164() {
        assertEquals("+34612345678", normalizeContactPhone("612345678"))
        assertEquals("+34612345678", normalizeContactPhone(" 612 34 56 78 "))
        assertEquals("+34912345678", normalizeContactPhone("912-345-678"))
        assertEquals("+34699000111", normalizeContactPhone("+34 699 000 111"))
        assertEquals("+447911123456", normalizeContactPhone("+447911123456"))
        assertEquals("+14155552671", normalizeContactPhone("+1 (415) 555-2671"))
    }

    @Test fun badlyWrittenNumbersAreRejected() {
        for (bad in listOf("61234567a", "61234567", "6123456789", "512345678", "+0612345678", "+1234567",
            "+1234567890123456", "+34+612345678", "", "   ", "６１２３４５６７８")) {
            assertNull("«$bad» must be rejected", normalizeContactPhone(bad))
        }
    }

    @Test fun theOwnPhoneIsReadFromTheServerAndMayBeMissing() = runTest {
        var answer = "\"+34612345678\""
        val client = client { request ->
            assertEquals("/rest/v1/rpc/my_contact_phone", request.url.encodedPath)
            json(answer)
        }
        try {
            assertEquals("+34612345678", SupabaseContactPhones(client).current())
            answer = "null"
            assertNull(SupabaseContactPhones(client).current())
        } finally { client.close() }
    }

    @Test fun savingSendsTheNormalizedPhoneAndReturnsWhatTheServerStored() = runTest {
        val client = client { request ->
            assertEquals("/rest/v1/rpc/set_contact_phone", request.url.encodedPath)
            assertEquals("""{"p_phone":"+34612345678"}""", (request.body as TextContent).text)
            json("\"+34612345678\"")
        }
        try { assertEquals("+34612345678", SupabaseContactPhones(client).save("612 34 56 78")) } finally { client.close() }
    }

    @Test fun aBadlyWrittenPhoneIsRejectedBeforeReachingTheServer() = runTest {
        val client = client { fail("nothing must be sent"); json("null") }
        try {
            SupabaseContactPhones(client).save("12345")
            fail("expected INVALID")
        } catch (error: ContactPhoneException) {
            assertEquals(ContactPhoneFailure.INVALID, error.failure)
        } finally { client.close() }
    }

    @Test fun serverRejectionsAreKeptApartFromNetworkUncertainty() = runTest {
        for ((status, body, expected) in listOf(
            Triple(HttpStatusCode.BadRequest, """{"code":"22023","message":"invalid_contact_phone"}""", ContactPhoneFailure.INVALID),
            Triple(HttpStatusCode.Unauthorized, """{"code":"28000","message":"authentication_required"}""", ContactPhoneFailure.ACCESS),
        )) {
            val client = client { json(body, status) }
            try {
                SupabaseContactPhones(client).save("612345678")
                fail("expected $expected")
            } catch (error: ContactPhoneException) {
                assertEquals(expected, error.failure)
            } finally { client.close() }
        }
    }

    @Test fun anAccountWithoutPhoneIsAskedAndThenContinues() = runTest {
        val session = ContactPhoneSession(FakePhones().apply { user = "a" })
        session.load("a")
        assertEquals(ContactPhoneStatus.Missing, session.status.value)
        assertTrue(session.required)
        session.save("612 34 56 78")
        assertEquals(ContactPhoneStatus.Present("+34612345678"), session.status.value)
        assertEquals("+34612345678", session.phone)
        assertFalse(session.required)
    }

    @Test fun anAccountThatAlreadyHasAPhoneIsNotAsked() = runTest {
        val session = ContactPhoneSession(FakePhones().apply { user = "a"; stored["a"] = "+34612345678" })
        session.load("a")
        assertEquals(ContactPhoneStatus.Present("+34612345678"), session.status.value)
        assertFalse(session.required)
    }

    @Test fun notKnowingBecauseOfTheNetworkNeverBlocksTheApp() = runTest {
        val session = ContactPhoneSession(FakePhones().apply { user = "a"; offline = true })
        session.load("a")
        assertEquals(ContactPhoneStatus.Failed, session.status.value)
        assertFalse(session.required)
    }

    @Test fun signingOutForgetsThePhone() = runTest {
        val phones = FakePhones().apply { user = "a"; stored["a"] = "+34612345678" }
        val session = ContactPhoneSession(phones)
        session.load("a")
        session.load(null)
        assertEquals(ContactPhoneStatus.Unknown, session.status.value)
        phones.user = "b"
        session.load("b")
        assertEquals("another account is asked for its own phone", ContactPhoneStatus.Missing, session.status.value)
    }

    @Test fun thePhoneIsAskedOnlyAfterTheNameAndTheHome() {
        val client = Session(connected = true)
        val anyTab = Navigation().open(Tab.CHATS)
        assertEquals(MainScreen.NAME_STEP, anyTab.screen(client.copy(mustGiveName = true, mustGiveHome = true, mustGivePhone = true)))
        assertEquals(MainScreen.HOME_STEP, anyTab.screen(client.copy(mustGiveHome = true, mustGivePhone = true)))
        assertEquals(MainScreen.PHONE_STEP, anyTab.screen(client.copy(mustGivePhone = true)))
        assertFalse(MainScreen.PHONE_STEP.showsBar)
        assertEquals("not asked once given", MainScreen.CHATS, anyTab.screen(client))
    }
}
