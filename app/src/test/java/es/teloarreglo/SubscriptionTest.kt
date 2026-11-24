package es.teloarreglo

import es.teloarreglo.subscription.SubscriptionFailure
import es.teloarreglo.subscription.SubscriptionException
import es.teloarreglo.subscription.SupabaseSubscriptions
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SubscriptionTest {
    private val offer = """{"profile_id":"p","price_cents":500,"currency":"EUR","period":"month",
        "simulated":%s,"charged":false,"featured_zones":[{"code":"33044","name":"Oviedo"}]}"""

    private fun client(status: HttpStatusCode, body: String, check: (String, String) -> Unit = { _, _ -> }) =
        createSupabaseClient("https://subscription.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                check(request.url.encodedPath, (request.body as? TextContent)?.text ?: "")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

    @Test fun aProfessionalSeesThePriceAsAProposalWithoutAnyCharge() = runTest {
        val client = client(HttpStatusCode.OK, offer.format("false")) { path, _ ->
            assertEquals("/rest/v1/rpc/my_subscription", path)
        }
        try {
            val subscription = SupabaseSubscriptions(client).mine()!!
            assertEquals(500, subscription.priceCents)
            assertEquals("EUR", subscription.currency)
            assertFalse(subscription.simulated)
            assertFalse(subscription.charged)
            assertEquals(listOf("Oviedo"), subscription.featuredZones.map { it.name })
        } finally { client.close() }
    }

    @Test fun aClientWithoutProfessionalProfileHasNoSubscriptionScene() = runTest {
        val client = client(HttpStatusCode.OK, "null")
        try { assertNull(SupabaseSubscriptions(client).mine()) } finally { client.close() }
    }

    @Test fun tryingTheSimulationSendsOnlyTheChoiceAndNoPaymentData() = runTest {
        var sent = ""
        val client = client(HttpStatusCode.OK, offer.format("true")) { path, body ->
            assertEquals("/rest/v1/rpc/simulate_subscription", path); sent = body
        }
        try {
            assertTrue(SupabaseSubscriptions(client).simulate(true).simulated)
            assertEquals("""{"p_active":true}""", sent)
        } finally { client.close() }
    }

    @Test fun aPermissionErrorIsNotConfusedWithANetworkFailure() = runTest {
        val client = client(HttpStatusCode.Forbidden,
            """{"code":"42501","message":"professional_profile_required","details":null,"hint":null}""")
        try {
            val error = runCatching { SupabaseSubscriptions(client).simulate(true) }.exceptionOrNull()
            assertEquals(SubscriptionFailure.ACCESS, (error as SubscriptionException).failure)
        } finally { client.close() }
    }

    @Test fun anUnexpectedServerFailureIsReportedAsUnconfirmed() = runTest {
        val client = client(HttpStatusCode.InternalServerError, """{"code":"XX000","message":"boom"}""")
        try {
            val error = runCatching { SupabaseSubscriptions(client).simulate(false) }.exceptionOrNull()
            assertEquals(SubscriptionFailure.UNCONFIRMED, (error as SubscriptionException).failure)
        } finally { client.close() }
    }
}
