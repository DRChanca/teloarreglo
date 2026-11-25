package es.teloarreglo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** #64: the time of a cita is chosen on hour and minute wheels and reaches the form as "HH:mm". */
class TimeWheelUiTest {
    @get:Rule val compose = createComposeRule()

    private var time by mutableStateOf("09:00")

    private fun show(enabled: Boolean = true) = compose.setContent {
        TeloTheme { TimeField(time, "Hora de inicio", enabled, "start") { time = it } }
    }

    @Test fun theWheelsOpenOnTheFormTime() {
        time = "10:15"
        show()
        compose.onNodeWithTag("start").performClick()
        compose.onNodeWithTag("time_wheel_hour_10").assertIsSelected()
        compose.onNodeWithTag("time_wheel_minute_15").assertIsSelected()
    }

    @Test fun theTimeChosenOnTheWheelsReachesTheForm() {
        show()
        compose.onNodeWithTag("start").performClick()
        compose.onNodeWithTag("time_wheel_hour").performScrollToIndex(14)
        compose.onNodeWithTag("time_wheel_minute").performScrollToIndex(6) // 0, 5, … 30
        compose.onNodeWithTag("time_wheel_confirm").performClick()
        compose.runOnIdle { assertEquals("14:30", time) }
        compose.onNodeWithTag("start").assert(hasStateDescription("14:30"))
    }

    @Test fun tappingAMinuteBringsItToTheMiddle() {
        show()
        compose.onNodeWithTag("start").performClick()
        compose.onNodeWithTag("time_wheel_minute_10").performClick()
        compose.onNodeWithTag("time_wheel_minute_10").assertIsSelected()
        compose.onNodeWithTag("time_wheel_confirm").performClick()
        compose.runOnIdle { assertEquals("09:10", time) }
    }

    @Test fun cancellingKeepsTheTimeAsItWas() {
        show()
        compose.onNodeWithTag("start").performClick()
        compose.onNodeWithTag("time_wheel_hour").performScrollToIndex(18)
        compose.onNodeWithTag("time_wheel_dismiss").performClick()
        compose.runOnIdle { assertEquals("09:00", time) }
        compose.onAllNodesWithTag("time_wheel_hour").assertCountEquals(0)
    }

    @Test fun aLockedFormDoesNotOpenTheWheels() {
        show(enabled = false)
        compose.onNodeWithTag("start").assertIsNotEnabled()
        compose.onNodeWithTag("start").performClick()
        compose.onAllNodesWithTag("time_wheel_hour").assertCountEquals(0)
    }

    /** #63 on top of #64: a problem with the start time is shown under the wheel field, visible and findable. */
    @Test fun aProblemIsShownUnderTheField() {
        compose.setContent {
            TeloTheme {
                TimeField(time, "Hora de inicio", true, "start", isError = true,
                    supportingText = { Text("La hora ya ha pasado", Modifier.testTag("start_error")) }) { time = it }
            }
        }
        compose.onNodeWithTag("start_error", useUnmergedTree = true).assertIsDisplayed()
    }
}
