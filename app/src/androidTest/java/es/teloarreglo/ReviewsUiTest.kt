package es.teloarreglo

import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.appointments.*
import es.teloarreglo.chat.Conversation
import es.teloarreglo.search.Located
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** In-memory stand-in with the server's observable rules for reviews: one per appointment, keys, eligibility. */
private class FakeReviews(private val stateOf: () -> ReviewState) : Reviews {
    val published = mutableListOf<Review>()
    val keys = mutableListOf<String>()
    var loseNextResponse = false

    override suspend fun states(conversationId: String) = mapOf("a1" to published.firstOrNull().let {
        if (it != null) AppointmentReviewState("a1", ReviewState.PUBLISHED, it) else AppointmentReviewState("a1", stateOf())
    })

    override suspend fun publish(appointmentId: String, requestId: String, stars: Int, comment: String): Review {
        keys += requestId
        val existing = published.firstOrNull()
        val review = when {
            existing != null && keys.first() == requestId -> existing
            existing != null -> throw ReviewException(ReviewFailure.ALREADY_REVIEWED, Exception())
            stateOf() != ReviewState.ENABLED -> throw ReviewException(ReviewFailure.NOT_ENABLED, Exception())
            else -> Review("r1", "profile", stars, comment, Instant.now(), appointmentId).also { published += it }
        }
        if (loseNextResponse) { loseNextResponse = false; throw java.io.IOException("lost") }
        return review
    }

    override suspend fun forProfile(profileId: String) = published.map { it.copy(appointmentId = null) }
}

class ReviewsUiTest {
    @get:Rule val compose = createComposeRule()
    private val conversation = Conversation("conversation", "profile", "client", "pro", "Fontanería")
    private val noPin = PinSource({ false }, { Located.Unavailable })

    private fun waitForTag(tag: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun completedAppointment() = FakeAppointments().apply {
        kotlinx.coroutines.runBlocking {
            request("conversation", "key", AppointmentRequest("Fuga", Instant.now().plusSeconds(86_400),
                Instant.now().plusSeconds(93_600), "Calle Ficticia 1", 43.36, -5.85))
            val proposal = propose("a1", 1, "p", ProposalDraft("Cambiar sifón", Instant.now().plusSeconds(86_400), 60, 0,
                null, true, true))
            accept(proposal.id); startWork("a1"); markDone("a1"); confirmDone("a1")
        }
    }

    /** #28: only after the public-content notice; a lost answer is retried with the same key and never doubles. */
    @Test fun clientReviewsACompletedAppointmentAndTheProfessionalSeesIt() {
        val appointments = completedAppointment()
        val reviews = FakeReviews { ReviewState.ENABLED }.apply { loseNextResponse = true }
        var account by mutableStateOf("pro")
        compose.setContent {
            AppointmentsPanel(appointments, account, conversation, rememberCoroutineScope(), noPin, reviews = reviews)
        }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        compose.onAllNodesWithTag("review_write").assertCountEquals(0)
        account = "client"
        waitForTag("review_write")
        compose.onNodeWithTag("review_write").performScrollTo().performClick()
        compose.onNodeWithTag("review_star_4").performClick()
        compose.onNodeWithTag("review_comment").performTextInput("Rápido y limpio")
        compose.onNodeWithTag("review_privacy").assertExists()
        compose.onNodeWithTag("review_send").assertIsNotEnabled()
        compose.onNodeWithTag("review_privacy_accept").performClick()
        compose.onNodeWithTag("review_send").performClick()
        waitForTag("review_error")
        compose.onNodeWithTag("review_comment").assertIsNotEnabled()
        compose.onNodeWithTag("review_send").performClick()
        waitForTag("review_a1")
        assertEquals(1, reviews.published.size)
        assertEquals(1, reviews.keys.distinct().size)
        assertEquals(4, reviews.published.single().stars)
        account = "pro"
        waitForTag("review_a1")
        compose.onAllNodesWithTag("review_write").assertCountEquals(0)
    }

    @Test fun aDisputeAboutWhetherItHappenedIsExplainedInsteadOfTheButton() {
        val reviews = FakeReviews { ReviewState.PERFORMANCE_DISPUTED }
        compose.setContent {
            AppointmentsPanel(completedAppointment(), "client", conversation, rememberCoroutineScope(), noPin, reviews = reviews)
        }
        waitForTag("appointment_card")
        compose.onNodeWithTag("appointment_card").performClick()
        waitForTag("review_reason_a1")
        compose.onAllNodesWithTag("review_write").assertCountEquals(0)
    }

    @Test fun theProfileListsPublishedReviewsWithoutPrivateData() {
        val reviews = FakeReviews { ReviewState.ENABLED }.apply {
            published += Review("r1", "profile", 5, "Muy bien", Instant.now(), "a1")
        }
        compose.setContent { ProfileReviewList("profile", reviews) }
        waitForTag("public_review_r1")
        compose.onNodeWithText("Muy bien").assertExists()
        compose.onNodeWithText("Calle Ficticia 1").assertDoesNotExist()
    }

    @Test fun thePrivacyNoticeExistsInEverySupportedLanguage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texts = listOf("es", "en", "fr", "de").map { tag ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            context.createConfigurationContext(config).getString(R.string.review_privacy)
        }
        assertEquals(4, texts.distinct().size)
    }
}
