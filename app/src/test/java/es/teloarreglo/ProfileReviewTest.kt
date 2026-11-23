package es.teloarreglo

import es.teloarreglo.catalogue.ProfileReview
import es.teloarreglo.catalogue.ReviewStatus
import es.teloarreglo.catalogue.ReviewSummary
import es.teloarreglo.catalogue.summary
import es.teloarreglo.catalogue.SupabaseProfileReviews
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProfileReviewTest {
    private fun client(status: HttpStatusCode, body: String, check: (String) -> Unit = {}) =
        createSupabaseClient("https://review.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                check(request.url.encodedPath)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

    @Test fun anOwnerSeesTheRejectionReasonAndDateDecidedInThePanel() = runTest {
        val client = client(HttpStatusCode.OK, """{"submission_id":"s","profile_id":"p","version":3,
            "status":"rejected","submitted_at":"2026-11-23T10:00:00+00:00","reason":"Falta el teléfono",
            "decided_at":"2026-11-23T11:00:00+00:00","published":false}""") {
            assertEquals("/rest/v1/rpc/my_profile_review", it)
        }
        try {
            val review = SupabaseProfileReviews(client).mine()!!
            assertEquals(ReviewStatus.REJECTED, review.status)
            assertEquals("Falta el teléfono", review.reason)
            assertEquals("2026-11-23T11:00:00+00:00", review.decidedAt)
            assertFalse(review.published)
        } finally { client.close() }
    }

    @Test fun aPreparedApprovedProfileWithoutSubmissionsIsShownAsPublished() = runTest {
        val client = client(HttpStatusCode.OK, """{"profile_id":"p","status":null,"published":true}""")
        try {
            val review = SupabaseProfileReviews(client).mine()!!
            assertNull(review.status)
            assertTrue(review.published)
        } finally { client.close() }
    }

    @Test fun theOwnerIsToldWhetherThePublicVersionStaysVisible() {
        fun summary(status: ReviewStatus?, published: Boolean) = ProfileReview("p", status, published = published).summary()
        assertEquals(ReviewSummary.PUBLISHED, summary(null, true))
        assertEquals(ReviewSummary.PUBLISHED, summary(ReviewStatus.APPROVED, true))
        assertEquals(ReviewSummary.PENDING_FIRST, summary(ReviewStatus.PENDING, false))
        assertEquals(ReviewSummary.PENDING_CHANGE, summary(ReviewStatus.PENDING, true))
        assertEquals(ReviewSummary.REJECTED_FIRST, summary(ReviewStatus.REJECTED, false))
        assertEquals(ReviewSummary.REJECTED_CHANGE, summary(ReviewStatus.REJECTED, true))
        assertEquals(ReviewSummary.NOT_SUBMITTED, summary(null, false))
    }

    @Test fun anAccountWithoutProfessionalProfileHasNoReview() = runTest {
        val client = client(HttpStatusCode.OK, "null")
        try { assertNull(SupabaseProfileReviews(client).mine()) } finally { client.close() }
    }

    @Test fun aServiceFailureIsReportedInsteadOfLookingLikeNoReview() = runTest {
        val client = client(HttpStatusCode.ServiceUnavailable, """{"code":"unavailable","message":"Unavailable"}""")
        try {
            assertThrows(RestException::class.java) { kotlinx.coroutines.runBlocking { SupabaseProfileReviews(client).mine() } }
        } finally { client.close() }
    }
}
