package es.teloarreglo

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class LanguageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun manualLanguageSurvivesActivityRecreation() {
        val original = AppCompatDelegate.getApplicationLocales()
        try {
            compose.onNodeWithTag("language").performScrollTo().performClick()
            compose.onNodeWithTag("language_de").performClick()
            compose.onNodeWithText("Willkommen zurück").performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Willkommen zurück").performScrollTo().assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity {
                AppCompatDelegate.setApplicationLocales(original)
            }
        }
    }
}
