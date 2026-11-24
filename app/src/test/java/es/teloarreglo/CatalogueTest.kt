package es.teloarreglo

import es.teloarreglo.catalogue.SupabaseCatalogue
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogueTest {
    @Test fun unpublishedDetailIsAbsentRatherThanInvented() = runTest {
        val client = createSupabaseClient("https://catalogue.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine {
                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try { assertEquals(null, SupabaseCatalogue(client).profile("missing")) }
        finally { client.close() }
    }

    @Test fun featuredBadgeComesFromTheServerRule() = runTest {
        var sent = ""
        val client = createSupabaseClient("https://catalogue.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/rpc/is_featured", request.url.encodedPath)
                sent = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond("true", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            assertEquals(true, SupabaseCatalogue(client).featured("profile-14"))
            assertEquals("""{"p_profile_id":"profile-14"}""", sent)
        } finally { client.close() }
    }

    @Test fun unavailableServiceDoesNotBecomeAnEmptyCatalogue() = runTest {
        val client = createSupabaseClient("https://catalogue.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine { throw java.io.IOException("offline") }
        }
        try {
            var failed = false
            try { SupabaseCatalogue(client).list() } catch (_: Exception) { failed = true }
            assertEquals(true, failed)
        } finally { client.close() }
    }

    @Test fun visitorCanReadPublicServicesAndPricesWithoutASession() = runTest {
        val client = createSupabaseClient("https://catalogue.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/rpc/catalogue", request.url.encodedPath)
                respond(
                    """[{"profile_id":"profile-14","name":"Fontanería Nalón","description":"Reparaciones",
                       "trades":["plumbing"],"services":["Fugas"],"coverage":[{"code":"33044","name":"Oviedo"}],
                       "languages":["es","en"],"phone":"+34 000 000 014","images":["plumbing"],
                       "business_address":"Calle Gascona, local ficticio A, Oviedo",
                       "prices":[{"service":"Reparación","scope":"Mano de obra","unit":"hour","amount_cents":3500,
                       "includes":"IVA","excludes":"Materiales y desplazamiento"}],"rating":null,"review_count":0}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        try {
            val profiles = SupabaseCatalogue(client).list("plumbing")
            assertEquals("Fontanería Nalón", profiles.single().name)
            assertEquals(3500, profiles.single().prices.single().amountCents)
            assertEquals(null, profiles.single().rating)
            assertEquals("Calle Gascona, local ficticio A, Oviedo", profiles.single().businessAddress)
        } finally { client.close() }
    }

    @Test fun catalogueListCarriesTheFeaturedFlagInServerOrder() = runTest {
        val client = createSupabaseClient("https://catalogue.example.org", "public-key") {
            install(Postgrest)
            httpEngine = MockEngine {
                respond(
                    """[{"profile_id":"b","name":"Zeta","description":"","trades":["plumbing"],"services":[],
                       "coverage":[],"languages":["es"],"phone":"","images":[],"prices":[],"featured":true},
                       {"profile_id":"a","name":"Alfa","description":"","trades":["plumbing"],"services":[],
                       "coverage":[],"languages":["es"],"phone":"","images":[],"prices":[],"featured":false}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        try {
            val profiles = SupabaseCatalogue(client).list("plumbing")
            assertEquals(listOf("b" to true, "a" to false), profiles.map { it.id to it.featured })
        } finally { client.close() }
    }
}
