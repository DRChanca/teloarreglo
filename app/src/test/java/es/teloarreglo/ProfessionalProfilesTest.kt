package es.teloarreglo

import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.ProfileEditException
import es.teloarreglo.catalogue.ProfileEditFailure
import es.teloarreglo.catalogue.ProfileField
import es.teloarreglo.catalogue.ProfileContent
import es.teloarreglo.catalogue.ReviewStatus
import es.teloarreglo.catalogue.SupabaseProfessionalProfiles
import es.teloarreglo.catalogue.missingFields
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProfessionalProfilesTest {
    private val requests = mutableListOf<Pair<String, String>>()

    private fun client(status: HttpStatusCode, body: String) =
        createSupabaseClient("https://profiles.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                requests += request.url.encodedPath to ((request.body as? TextContent)?.text ?: "")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

    private val own = """{"profile_id":"p","private_address":"Calle privada 1",
        "public":{"name":"Fontanería Ana","description":"Aprobada","trades":["plumbing"],"services":["Fugas"],
          "coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],"phone":"+34 600 000 000",
          "images":["plumbing"],"prices":[{"service":"Fuga","scope":"x","unit":"visita","amount_cents":4000,"includes":"","excludes":""}],
          "business_address":null},
        "editable":{"name":"Fontanería Ana","description":"Cambio pendiente","trades":["plumbing"],"services":["Fugas"],
          "coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],"phone":"+34 600 000 000",
          "images":["plumbing"],"prices":[{"service":"Fuga","scope":"x","unit":"visita","amount_cents":4000,"includes":"","excludes":""}],
          "business_address":null},
        "review":{"submission_id":"s","profile_id":"p","version":4,"status":"pending","published":true}}"""

    @Test fun theOwnerEditsThePendingChangeWhileTheApprovedVersionStaysPublic() = runTest {
        val client = client(HttpStatusCode.OK, own)
        try {
            val profile = SupabaseProfessionalProfiles(client).mine()!!
            assertEquals("/rest/v1/rpc/my_professional_profile", requests.single().first)
            assertEquals("Aprobada", profile.public!!.description)
            assertEquals("Cambio pendiente", profile.editable.description)
            assertEquals(ReviewStatus.PENDING, profile.review.status)
            assertTrue(profile.review.published)
            assertEquals("Calle privada 1", profile.privateAddress)
        } finally { client.close() }
    }

    @Test fun anAccountWithoutProfessionalProfileHasNothingToEdit() = runTest {
        val client = client(HttpStatusCode.OK, "null")
        try { assertNull(SupabaseProfessionalProfiles(client).mine()) } finally { client.close() }
    }

    @Test fun theHiringAccountStartsItsOwnProfessionalProfile() = runTest {
        val client = client(HttpStatusCode.OK, """{"profile_id":"new","private_address":null,"public":null,
            "editable":{"name":"","description":"","trades":[],"services":[],"coverage":[],"languages":["es"],
              "phone":"","images":[],"prices":[],"business_address":null},
            "review":{"profile_id":"new","status":null,"published":false}}""")
        try {
            val profile = SupabaseProfessionalProfiles(client).start()
            assertEquals("/rest/v1/rpc/start_professional_profile", requests.single().first)
            assertEquals("new", profile.profileId)
            assertNull(profile.public)
            assertEquals(setOf(ProfileField.NAME, ProfileField.TRADES, ProfileField.COVERAGE, ProfileField.PHONE),
                profile.editable.missingFields())
        } finally { client.close() }
    }

    @Test fun presentingSendsTheRequestIdAndKeepsPhotosAndPricesUntouched() = runTest {
        val client = client(HttpStatusCode.OK, """{"submission_id":"s5","profile_id":"p","version":5,
            "status":"pending","submitted_at":"2026-11-23T10:00:00+00:00","published":true}""")
        try {
            val editable = Json { ignoreUnknownKeys = true }.decodeFromString<es.teloarreglo.catalogue.OwnProfile>(own).editable
            val changed = editable.copy(description = "Nueva", coverage = editable.coverage + Coverage("33024", "Gijón"))
            val review = SupabaseProfessionalProfiles(client).submit("11111111-1111-4111-8111-111111111111", changed)
            val (path, body) = requests.single()
            assertEquals("/rest/v1/rpc/submit_professional_profile", path)
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("11111111-1111-4111-8111-111111111111", json["p_submission"]!!.jsonPrimitive.content)
            val content = json["p_content"]!!.jsonObject
            assertEquals("Nueva", content["description"]!!.jsonPrimitive.content)
            assertEquals(2, content["coverage"]!!.jsonArray.size)
            assertEquals(4000, content["prices"]!!.jsonArray[0].jsonObject["amount_cents"]!!.jsonPrimitive.content.toInt())
            assertEquals("plumbing", content["images"]!!.jsonArray[0].jsonPrimitive.content)
            assertFalse("a private address is never sent as public content", "private_address" in content)
            assertEquals(ReviewStatus.PENDING, review.status)
            assertTrue(review.published)
        } finally { client.close() }
    }

    @Test fun refusalsAreToldApartFromUncertainDelivery() = runTest {
        suspend fun failureOf(status: HttpStatusCode, code: String, message: String): ProfileEditFailure? {
            val client = client(status, """{"code":"$code","message":"$message"}""")
            return try {
                SupabaseProfessionalProfiles(client).submit("r", ProfileContent())
                null
            } catch (e: ProfileEditException) { e.failure } catch (_: RestException) { null } finally { client.close() }
        }
        assertEquals(ProfileEditFailure.INVALID, failureOf(HttpStatusCode.BadRequest, "22023", "invalid_profile_content"))
        assertEquals(ProfileEditFailure.CONFLICT, failureOf(HttpStatusCode.BadRequest, "22023", "request_conflict"))
        assertEquals(ProfileEditFailure.NO_PROFILE, failureOf(HttpStatusCode.BadRequest, "22023", "profile_not_found"))
        assertEquals(ProfileEditFailure.ACCESS, failureOf(HttpStatusCode.Unauthorized, "28000", "authentication_required"))
        assertNull("an unavailable service is not a refusal", failureOf(HttpStatusCode.ServiceUnavailable, "x", "Unavailable"))
    }

    @Test fun thePrivateAddressIsSavedApartFromTheReviewedVersion() = runTest {
        val client = client(HttpStatusCode.OK, own)
        try {
            SupabaseProfessionalProfiles(client).savePrivateAddress("Calle privada 1")
            val (path, body) = requests.single()
            assertEquals("/rest/v1/rpc/save_professional_private_address", path)
            assertEquals("Calle privada 1", Json.parseToJsonElement(body).jsonObject["p_address"]!!.jsonPrimitive.content)
        } finally { client.close() }
    }
}
