package es.teloarreglo

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** #47: the shared confirmation for irreversible actions. */
class DestructiveConfirmDialogTest {
    @get:Rule val compose = createComposeRule()

    private var result by mutableStateOf("")

    private fun show(busy: Boolean = false) = compose.setContent {
        TeloTheme {
            if (result.isEmpty()) DestructiveConfirmDialog(
                title = "¿Cancelar la cita?",
                text = { Text("No se puede deshacer.") },
                confirmLabel = "Cancelar la cita",
                onConfirm = { result = "confirmed" },
                onDismiss = { result = "dismissed" },
                confirmTag = "danger_confirm",
                busy = busy,
            )
        }
    }

    @Test fun showsTheQuestionAndRunsTheActionOnlyOnConfirm() {
        show()
        compose.onNodeWithText("¿Cancelar la cita?").assertIsDisplayed()
        compose.onNodeWithText("No se puede deshacer.").assertIsDisplayed()
        compose.onNodeWithTag("danger_confirm").performClick()
        compose.runOnIdle { assertEquals("confirmed", result) }
    }

    @Test fun cancelLeavesWithoutRunningTheAction() {
        show()
        compose.onNodeWithTag("destructive_dismiss").performClick()
        compose.runOnIdle { assertEquals("dismissed", result) }
    }

    @Test fun whileTheActionRunsNeitherButtonCanBePressedAgain() {
        show(busy = true)
        compose.onNodeWithTag("danger_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("destructive_dismiss").assertIsNotEnabled()
    }
}
