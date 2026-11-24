package es.teloarreglo

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import es.teloarreglo.catalogue.ProfileReview
import es.teloarreglo.catalogue.ProfileReviews
import es.teloarreglo.catalogue.ReviewStatus
import org.junit.Rule
import org.junit.Test

/** The fake replaces only the remote review read; the card is the real UI. */
class ProfileReviewUiTest {
    @get:Rule val compose = createComposeRule()

    private class FakeReviews(var next: () -> ProfileReview?) : ProfileReviews {
        override suspend fun mine() = next()
    }

    @Test fun aRejectedProfileShowsTheReasonAsWrittenByAdministration() {
        val reviews = FakeReviews { ProfileReview("p", ReviewStatus.REJECTED, "Falta la cobertura", "2026-11-23T11:00:00Z", false) }
        compose.setContent { ProfileReviewCard(reviews) }
        compose.onNodeWithTag("profile_review_reason").assertTextContains("Falta la cobertura", substring = true)
    }

    @Test fun aFailedReadOffersRetryAndThenShowsTheApproval() {
        val reviews = FakeReviews { error("offline") }
        compose.setContent { ProfileReviewCard(reviews) }
        reviews.next = { ProfileReview("p", ReviewStatus.APPROVED, "Datos coherentes", "2026-11-23T12:00:00Z", true) }
        compose.onNodeWithTag("profile_review_retry").performClick()
        compose.onNodeWithTag("profile_review_status").assertExists()
    }
}
