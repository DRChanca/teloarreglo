package es.teloarreglo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.subscription.Subscription
import es.teloarreglo.subscription.SubscriptionException
import es.teloarreglo.subscription.SubscriptionFailure
import es.teloarreglo.subscription.Subscriptions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The fake replaces only the server; the scene is the real UI. Not a payment flow. */
class SubscriptionUiTest {
    @get:Rule val compose = createComposeRule()

    private class FakeSubscriptions(var zones: List<Coverage> = listOf(Coverage("33044", "Oviedo"))) : Subscriptions {
        var simulated = false
        val calls = mutableListOf<Boolean>()
        var failNext: SubscriptionFailure? = null
        override suspend fun mine() = Subscription("p", 500, "EUR", "month", simulated, false, zones)
        override suspend fun simulate(active: Boolean): Subscription {
            calls += active
            failNext?.let { failNext = null; throw SubscriptionException(it, RuntimeException()) }
            simulated = active
            return mine()
        }
    }

    /** As in the account screen, the scene lives inside a vertically scrolling column. */
    private fun show(subscriptions: Subscriptions) = compose.setContent {
        Column(Modifier.verticalScroll(rememberScrollState())) { SubscriptionCard(subscriptions) }
    }

    @Test fun theSceneShowsPriceBenefitsAndWhatTheFreeProfileKeeps() {
        show(FakeSubscriptions())
        compose.onNodeWithTag("subscription_open").performClick()
        compose.onNodeWithTag("subscription_benefits").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("subscription_free_keeps").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("subscription_featured_zones").assertTextContains("Oviedo", substring = true)
        compose.onNodeWithTag("subscription_state").assertExists()
    }

    @Test fun tryingAndCancellingTheSimulationNeverAsksForPaymentData() {
        val fake = FakeSubscriptions()
        show(fake)
        compose.onNodeWithTag("subscription_open").performClick()
        compose.onNodeWithTag("subscription_toggle").performScrollTo().performClick()
        compose.waitUntil { fake.simulated }
        compose.onNodeWithTag("subscription_toggle").performScrollTo().performClick()
        compose.waitUntil { fake.calls.size == 2 }
        assertEquals(listOf(true, false), fake.calls)
    }

    @Test fun anUnconfirmedChangeIsNotShownAsActive() {
        val fake = FakeSubscriptions().apply { failNext = SubscriptionFailure.UNCONFIRMED }
        show(fake)
        compose.onNodeWithTag("subscription_open").performClick()
        compose.onNodeWithTag("subscription_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("subscription_error").assertExists()
        assertEquals(false, fake.simulated)
    }

    @Test fun anUnpublishedProfileIsToldThereAreNoDestacadoZonesYet() {
        show(FakeSubscriptions(zones = emptyList()))
        compose.onNodeWithTag("subscription_open").performClick()
        compose.onNodeWithTag("subscription_no_zones").assertExists()
    }
}
