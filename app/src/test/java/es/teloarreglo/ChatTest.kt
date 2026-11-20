package es.teloarreglo

import es.teloarreglo.chat.SupabaseChat
import es.teloarreglo.chat.ChatSendException
import es.teloarreglo.chat.SendFailure
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTest {
    @Test fun permissionRejectionIsNotPresentedAsUncertainNetworkDelivery() = runTest {
        val client = createSupabaseClient("https://chat.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine {
                respond("""{"code":"42501","message":"permission_denied","details":null,"hint":null}""",
                    HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            var failure: SendFailure? = null
            try { SupabaseChat(client).send("conversation", "key", "Hola") }
            catch (error: ChatSendException) { failure = error.failure }
            assertEquals(SendFailure.ACCESS, failure)
        } finally { client.close() }
    }
    @Test fun retryUsesSameRequestAndDoesNotInventSuccessWhenOffline() = runTest {
        var attempts = 0
        val client = createSupabaseClient("https://chat.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/rpc/send_text", request.url.encodedPath)
                val body = (request.body as io.ktor.http.content.TextContent).text
                assertEquals(true, body.contains("\"p_request_id\":\"retry-key\""))
                attempts++
                if (attempts == 1) throw java.io.IOException("response lost")
                respond("""{"id":"message","conversation_id":"conversation","author_id":"pro","request_id":"retry-key","body":"Hola","created_at":"2026-11-20T18:00:00Z"}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            val chat = SupabaseChat(client)
            var failed = false
            try { chat.send("conversation", "retry-key", "Hola") } catch (_: java.io.IOException) { failed = true }
            assertEquals(true, failed)
            assertEquals("message", chat.send("conversation", "retry-key", "Hola").id)
        } finally { client.close() }
    }

    @Test fun historyIsReadFromServerInStableOrderAfterReconnection() = runTest {
        val client = createSupabaseClient("https://chat.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/chat_messages", request.url.encodedPath)
                assertEquals("eq.conversation", request.url.parameters["conversation_id"])
                assertEquals("created_at.asc.nullslast,id.asc.nullslast", request.url.parameters["order"])
                respond("""[{"id":"message","conversation_id":"conversation","author_id":"pro","request_id":"key","body":"Persistido","created_at":"2026-11-20T18:00:00Z"}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try { assertEquals("Persistido", SupabaseChat(client).messages("conversation").single().body) }
        finally { client.close() }
    }

    @Test fun openingFromProfileReturnsServerConversation() = runTest {
        val client = createSupabaseClient("https://chat.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/rpc/open_conversation", request.url.encodedPath)
                respond("""{"id":"conversation","profile_id":"profile","client_id":"client","professional_id":"pro","name":"Fontanería"}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try { assertEquals("conversation", SupabaseChat(client).open("profile").id) }
        finally { client.close() }
    }
}
