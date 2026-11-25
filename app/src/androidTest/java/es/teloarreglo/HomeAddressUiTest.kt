package es.teloarreglo

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.home.HomeAddresses
import es.teloarreglo.home.HomeDraft
import es.teloarreglo.home.HomeSession
import es.teloarreglo.search.Located
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test

/** The mandatory home step never blocks without saying why (#47). */
class HomeAddressUiTest {
    @get:Rule val compose = createComposeRule()
    private val noPin = PinSource({ false }, { Located.Unavailable })
    private val nowhere = object : HomeAddresses {
        override suspend fun current(): HomeAddress? = null
        override suspend fun save(draft: HomeDraft) = error("must not save")
    }
    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun theDisabledSaveButtonSaysWhatIsMissing() {
        compose.setContent {
            HomeAddressScreen(HomeSession(nowhere), null, noPin, CoroutineScope(Dispatchers.Main), required = true,
                onDone = {}, onSignOut = {})
        }
        compose.onNodeWithTag("home_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("home_save_reason").performScrollTo()
            .assertTextContains(text(R.string.home_gap_street), substring = true)
            .assertTextContains(text(R.string.home_gap_location), substring = true)

        compose.onNodeWithTag("home_street").performTextReplacement("Calle Uría")
        compose.onNodeWithTag("home_save_reason").performScrollTo()
            .assertTextContains(text(R.string.home_gap_location), substring = true)
            .assert(hasText(text(R.string.home_gap_street), substring = true).not())
        compose.onNodeWithTag("home_save").assertIsNotEnabled()
    }
}
