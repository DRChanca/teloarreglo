package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/** UI boundary; the fake replaces only the remote identity service, never a UI state. */
class AccessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun signedInAccountCanSignOutWithoutLeavingAnAuthenticatedScreen() {
        val identity = TestIdentity().apply { failure = null }
        compose.setContent { AccessScreen(identity = identity, onLanguageChange = {}) }
        compose.onNodeWithTag("email").performTextInput("cliente@example.org")
        compose.onNodeWithTag("password").performTextInput("correcta")
        compose.onNodeWithTag("signIn").performScrollTo().performClick()
        compose.onNodeWithTag("account").assertIsDisplayed()
        compose.onNodeWithTag("signOut").performScrollTo().performClick()
        compose.onNodeWithTag("account").assertDoesNotExist()
        compose.onNodeWithTag("email").performScrollTo().assertIsDisplayed()
    }

    @Test fun incorrectCredentialsDoNotOpenTheAccount() {
        val identity = TestIdentity()
        compose.setContent {
            AccessScreen(identity = identity, onLanguageChange = {})
        }
        compose.onNodeWithTag("email").performTextInput("cliente@example.org")
        compose.onNodeWithTag("password").performTextInput("incorrecta")
        compose.onNodeWithTag("signIn").performScrollTo().performClick()
        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithTag("signIn").assertIsDisplayed()
    }
}
