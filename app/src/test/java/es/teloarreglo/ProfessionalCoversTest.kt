package es.teloarreglo

import es.teloarreglo.catalogue.SupabaseProfessionalCovers
import es.teloarreglo.catalogue.CoverStatus
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.minimalSettings
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.MemoryResumableCache
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProfessionalCoversTest {
    @Test fun anOwnerWithoutAnyCoverSubmissionCanOpenTheEditor() = runTest {
        val client = createSupabaseClient("https://covers.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                assertEquals("/rest/v1/rpc/professional_cover", request.url.encodedPath)
                respond("null", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try { assertNull(SupabaseProfessionalCovers(client).state("profile")) }
        finally { client.close() }
    }

    @Test fun anExistingCoverSubmissionStillLoadsItsReviewState() = runTest {
        val client = createSupabaseClient("https://covers.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine {
                respond("""{"id":"request","status":"pending","object_path":"profile/request.jpg","reason":null}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            val state = SupabaseProfessionalCovers(client).state("profile")
            assertEquals(CoverStatus.PENDING, state?.status)
            assertEquals("request", state?.id)
        } finally { client.close() }
    }

    @Test fun aStateServiceFailureIsNotMistakenForNoPreviousSubmission() = runTest {
        val client = createSupabaseClient("https://covers.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine {
                respond("""{"code":"unavailable","message":"Unavailable"}""",
                    HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            var failed = false
            try { SupabaseProfessionalCovers(client).state("profile") }
            catch (error: RestException) { assertEquals(503, error.statusCode); failed = true }
            assertTrue(failed)
        } finally { client.close() }
    }

    @Test fun aDuplicateImmutableUploadCanRecoverItsPendingSubmission() = runTest {
        val calls = mutableListOf<String>()
        val client = createSupabaseClient("https://covers.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Auth) { minimalSettings(sessionManager = MemorySessionManager()) }
            install(Postgrest)
            install(Storage) { resumable { cache = MemoryResumableCache() } }
            httpEngine = MockEngine { request ->
                val path = request.url.encodedPath
                calls.add(path)
                val headers = headersOf(HttpHeaders.ContentType, "application/json")
                when {
                    path.endsWith("/request_professional_cover") -> respond(
                        """{"id":"request","status":"uploading","object_path":"profile/request.jpg"}""",
                        HttpStatusCode.OK, headers)
                    path.contains("/storage/v1/object/") -> respond(
                        """{"statusCode":"409","error":"Duplicate","message":"already exists"}""",
                        HttpStatusCode.Conflict, headers)
                    else -> respond("""{"id":"request","status":"pending","object_path":"profile/request.jpg"}""",
                        HttpStatusCode.OK, headers)
                }
            }
        }
        try {
            val result = SupabaseProfessionalCovers(client).photo("profile", "request", byteArrayOf(1, 2, 3))
            assertEquals(CoverStatus.PENDING, result.status)
            assertEquals(listOf("/rest/v1/rpc/request_professional_cover",
                "/storage/v1/object/professional-covers/profile/request.jpg",
                "/rest/v1/rpc/submit_professional_cover"), calls)
        } finally { client.close() }
    }

    @Test fun anUploadServiceFailureCannotBeReportedAsSubmitted() = runTest {
        var submitted = false
        val client = createSupabaseClient("https://covers.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Auth) { minimalSettings(sessionManager = MemorySessionManager()) }
            install(Postgrest)
            install(Storage) { resumable { cache = MemoryResumableCache() } }
            httpEngine = MockEngine { request ->
                val path = request.url.encodedPath
                if (path.endsWith("/submit_professional_cover")) submitted = true
                val headers = headersOf(HttpHeaders.ContentType, "application/json")
                if (path.endsWith("/request_professional_cover")) respond(
                    """{"id":"request","status":"uploading","object_path":"profile/request.jpg"}""",
                    HttpStatusCode.OK, headers)
                else respond("""{"statusCode":"503","error":"Unavailable","message":"Unavailable"}""",
                    HttpStatusCode.ServiceUnavailable, headers)
            }
        }
        try {
            var failed = false
            try { SupabaseProfessionalCovers(client).photo("profile", "request", byteArrayOf(1)) }
            catch (error: RestException) { assertEquals(503, error.statusCode); failed = true }
            assertTrue(failed)
            assertFalse(submitted)
        } finally { client.close() }
    }
}
