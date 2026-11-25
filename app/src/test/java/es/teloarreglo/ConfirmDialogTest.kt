package es.teloarreglo

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** #65: confirming that the work is done reads as a positive action, not as a destructive one. */
class ConfirmDialogTest {
    @Test
    fun aPositiveConfirmationUsesTheBrandGreenInBothModes() {
        assertEquals(Color(0xFF246451), confirmActionColor(teloColors(dark = false).scheme, destructive = false))
        assertEquals(Color(0xFF8FD5BA), confirmActionColor(teloColors(dark = true).scheme, destructive = false))
    }

    @Test
    fun aDestructiveConfirmationKeepsTheErrorColour() {
        assertEquals(Color(0xFFFFB4AB), confirmActionColor(teloColors(dark = true).scheme, destructive = true))
    }
}
