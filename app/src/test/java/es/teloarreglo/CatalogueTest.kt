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
        } finally { client.close() }
    }
}
