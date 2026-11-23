package es.teloarreglo

import es.teloarreglo.chat.ChatMessage
import es.teloarreglo.chat.Receipts
import es.teloarreglo.chat.SupabaseChat
import es.teloarreglo.chat.Tick
import es.teloarreglo.chat.showsNotification
import es.teloarreglo.chat.tickOf
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReceiptsTest {
    private fun sentAt(createdAt: String) = ChatMessage("m", "c1", "me", "r", "Hola", createdAt)

    @Test fun ticksFollowTheOtherParticipantsMarks() {
        val message = sentAt("2026-11-23T10:00:00.123456+00:00")
        assertEquals(Tick.SENT, Receipts().tickOf(message))
        assertEquals(Tick.SENT, Receipts(deliveredAt = "2026-11-23T10:00:00.123455+00:00").tickOf(message))
        assertEquals(Tick.DELIVERED, Receipts(deliveredAt = "2026-11-23T10:00:00.123456+00:00").tickOf(message))
        assertEquals(Tick.DELIVERED, Receipts("2026-11-23T11:00:00+00:00", "2026-11-23T09:59:59+00:00").tickOf(message))
        assertEquals(Tick.READ, Receipts("2026-11-23T11:00:00+00:00", "2026-11-23T11:00:00+00:00").tickOf(message))
    }

    @Test fun readImpliesDeliveredEvenWithoutADeliveryMark() {
        assertEquals(Tick.READ, Receipts(readAt = "2026-11-23T11:00:00Z").tickOf(sentAt("2026-11-23T10:00:00Z")))
    }

    @Test fun messagePushForTheConversationOnScreenIsNotShown() {
        assertEquals(false, showsNotification("message", "c1", open = "c1"))
        assertEquals(true, showsNotification("message", "c1", open = "c2"))
        assertEquals(true, showsNotification("message", "c1", open = null))
        assertEquals(true, showsNotification("appointment", "c1", open = "c1"))
    }

    @Test fun receiptsComeFromTheServerForOneConversation() = runTest {
        var body = ""
        val client = createSupabaseClient("https://chat.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/rpc/conversation_receipts", request.url.encodedPath)
                body = (request.body as io.ktor.http.content.TextContent).text
                respond("""{"delivered_at":"2026-11-23T10:00:00+00:00","read_at":null}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            assertEquals(Receipts("2026-11-23T10:00:00+00:00", null), SupabaseChat(client).receipts("c1"))
            assertEquals("""{"p_conversation_id":"c1"}""", body)
        } finally { client.close() }
    }

    @Test fun deliveryMarksGoToTheServer() = runTest {
        val calls = mutableListOf<String>()
        val client = createSupabaseClient("https://chat.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val body = (request.body as? io.ktor.http.content.TextContent)?.text ?: "{}"
                calls += request.url.encodedPath + " " + body
                respond("", HttpStatusCode.NoContent)
            }
        }
        try {
            SupabaseChat(client).markDelivered("c1")
            SupabaseChat(client).markAllDelivered()
            assertEquals(listOf("""/rest/v1/rpc/mark_conversation_delivered {"p_conversation_id":"c1"}""",
                "/rest/v1/rpc/mark_conversations_delivered {}"), calls)
        } finally { client.close() }
    }
}
