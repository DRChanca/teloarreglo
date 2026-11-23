package es.teloarreglo

import es.teloarreglo.appointments.*
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReviewsTest {
    private val reviewJson = """{"id":"rev","appointment_id":"appt","profile_id":"profile","stars":4,
        "comment":"Rápido y limpio","created_at":"2026-11-23T10:00:00+00:00"}"""

    private suspend fun <T> withClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
        block: suspend (Reviews) -> T,
    ): T {
        val client = createSupabaseClient("https://reviews.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }
        return try { block(SupabaseReviews(client)) } finally { client.close() }
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    private fun error(code: String, message: String) = """{"code":"$code","message":"$message","details":null,"hint":null}"""

    @Test fun publishingSendsStarsCommentKeyAndTheAcknowledgedPrivacyNotice() = runTest {
        var body: JsonObject? = null
        val review = withClient({ request ->
            assertEquals("/rest/v1/rpc/publish_review", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            json(reviewJson)
        }) { it.publish("appt", "key", 4, "Rápido y limpio") }
        assertEquals(mapOf("p_appointment_id" to "appt", "p_request_id" to "key", "p_stars" to 4,
            "p_comment" to "Rápido y limpio", "p_privacy_acknowledged" to true),
            body!!.mapValues { (_, v) -> v.jsonPrimitive.let { it.booleanOrNull ?: it.intOrNull ?: it.content } })
        assertEquals(4, review.stars)
        assertEquals("Rápido y limpio", review.comment)
    }

    @Test fun serverRefusalsKeepTheirReasonApartFromNetworkUncertainty() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Forbidden, error("42501", "permission_denied"), ReviewFailure.ACCESS),
            Triple(HttpStatusCode.Conflict, error("PT409", "review_exists"), ReviewFailure.ALREADY_REVIEWED),
            Triple(HttpStatusCode.Conflict, error("PT409", "performance_disputed"), ReviewFailure.PERFORMANCE_DISPUTED),
            Triple(HttpStatusCode.Conflict, error("PT409", "not_performed"), ReviewFailure.NOT_PERFORMED),
            Triple(HttpStatusCode.Conflict, error("PT409", "awaiting_confirmation"), ReviewFailure.NOT_ENABLED),
            Triple(HttpStatusCode.Conflict, error("PT409", "not_done"), ReviewFailure.NOT_ENABLED),
            Triple(HttpStatusCode.BadRequest, error("22023", "retry_conflict"), ReviewFailure.RETRY_CONFLICT),
            Triple(HttpStatusCode.BadRequest, error("22023", "invalid_review"), ReviewFailure.INVALID),
        )
        for ((status, body, expected) in cases) {
            val failure = try {
                withClient({ json(body, status) }) { it.publish("appt", "key", 5, "x") }; null
            } catch (error: ReviewException) { error.failure }
            assertEquals(body, expected, failure)
        }
        var network = false
        try { withClient({ throw java.io.IOException("offline") }) { it.publish("appt", "key", 5, "x") } }
        catch (error: Exception) { network = error !is ReviewException }
        assertTrue("a lost answer is not a refusal: the same key is retried", network)
    }

    @Test fun statesOfAConversationComeFromTheServerByAppointment() = runTest {
        val states = withClient({ request ->
            assertEquals("/rest/v1/rpc/review_states", request.url.encodedPath)
            assertEquals("conv", Json.parseToJsonElement((request.body as TextContent).text).jsonObject["p_conversation_id"]!!.jsonPrimitive.content)
            json("""[{"appointment_id":"appt","state":"published","review":$reviewJson},
                {"appointment_id":"other","state":"performance_disputed","review":null},
                {"appointment_id":"free","state":"enabled","review":null}]""")
        }) { it.states("conv") }
        assertEquals(ReviewState.PUBLISHED, states.getValue("appt").state)
        assertEquals(4, states.getValue("appt").review!!.stars)
        assertEquals(ReviewState.PERFORMANCE_DISPUTED, states.getValue("other").state)
        assertNull(states.getValue("other").review)
        assertTrue(states.getValue("free").canPublish)
        assertFalse(states.getValue("appt").canPublish)
    }

    @Test fun aProfileShowsItsPublicReviewsNewestFirstWithoutASession() = runTest {
        val reviews = withClient({ request ->
            assertEquals("/rest/v1/reviews", request.url.encodedPath)
            assertEquals("eq.profile", request.url.parameters["profile_id"])
            assertTrue(request.url.parameters["order"]!!.startsWith("created_at.desc"))
            json("""[{"id":"r2","profile_id":"profile","stars":2,"comment":"Tarde","created_at":"2026-11-23T11:00:00+00:00"},
                {"id":"r1","profile_id":"profile","stars":5,"comment":"Bien","created_at":"2026-11-22T11:00:00+00:00"}]""")
        }) { it.forProfile("profile") }
        assertEquals(listOf("Tarde", "Bien"), reviews.map { it.comment })
        assertNull(reviews.first().appointmentId)
    }
}
