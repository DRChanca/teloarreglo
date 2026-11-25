package es.teloarreglo

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** #45: with the phone in dark mode the app opens on the catalogue with a dark background. */
class DarkThemeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun forceDark() = instrumentation.runOnMainSync {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }

    @After fun followSystem() = instrumentation.runOnMainSync {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun appOpensOnTheCatalogueInDarkMode() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("catalogue_search")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("catalogue_search").assertIsDisplayed()
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            // A corner of the screen shows the app background, never a light flash.
            val corner = pixels[pixels.width - 2, pixels.height / 2]
            assertTrue("background should be dark, was $corner", corner.luminance() < 0.1f)
        }
    }
}
