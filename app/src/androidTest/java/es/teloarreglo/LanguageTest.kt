package es.teloarreglo

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

/** #46: the language is changed from Account → Preferences, also without signing in. */
class LanguageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun await(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun languageChosenInAccountPreferencesSurvivesActivityRecreation() {
        val original = AppCompatDelegate.getApplicationLocales()
        try {
            await("catalogue_search")
            // The catalogue header no longer offers the language.
            compose.onNodeWithTag("language").assertDoesNotExist()
            compose.onNodeWithTag("tab_account").performClick()
            await("preferences")
            compose.onNodeWithTag("language").performScrollTo().performClick()
            compose.onNodeWithTag("language_de").performClick()
            compose.onNodeWithText("Willkommen zurück").performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            await("preferences")
            compose.onNodeWithTag("tab_account").assertIsSelected()
            compose.onNodeWithText("Willkommen zurück").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("tab_home").performClick()
            await("catalogue_search")
            compose.onNodeWithText("Hilfe für dein Zuhause finden").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity {
                AppCompatDelegate.setApplicationLocales(original)
            }
        }
    }
}
