package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #46: the bottom bar as a visitor sees it, driven through the main activity. */
class MainNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun await(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun pressBack() = compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    @Test fun eachTabOpensItsScreen() {
        await("catalogue_search")
        compose.onNodeWithTag("tab_home").assertIsSelected()
        compose.onNodeWithTag("tab_account").performClick()
        await("email")
        compose.onNodeWithTag("tab_account").assertIsSelected()
        // ADR 0001: no separate «Cerca» tab; «Buscar» is the single search.
        assertTrue(compose.onAllNodesWithTag("tab_nearby").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("tab_home").performClick()
        await("catalogue_search")
        compose.onNodeWithTag("catalogue_search").assertIsDisplayed()
    }

    @Test fun backFromATabReturnsHomeAndBackAtHomeLeaves() {
        await("catalogue_search")
        compose.onNodeWithTag("tab_account").performClick()
        await("email")
        pressBack()
        await("catalogue_search")
        compose.onNodeWithTag("tab_home").assertIsSelected()
        pressBack()
        compose.waitForIdle()
        var finishing = false
        compose.activityRule.scenario.onActivity { finishing = it.isFinishing }
        assertTrue("back at home should close the app", finishing)
    }

    @Test fun visitorIsAskedToSignInForConversationsAndNotices() {
        await("catalogue_search")
        compose.onNodeWithTag("tab_chats").performClick()
        await("signIn")
        compose.onNodeWithTag("signIn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tab_chats").assertIsSelected()
        compose.onNodeWithTag("tab_home").performClick()
        await("catalogue_search")
        compose.onNodeWithTag("tab_notices").performClick()
        await("signIn")
        compose.onNodeWithTag("signIn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("bottom_bar").assertIsDisplayed()
    }
}
