package es.teloarreglo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import es.teloarreglo.identity.AccessError
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.AccessState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CompletableDeferred

/** UI boundary; the fake replaces only the remote identity service, never a UI state. */
class AccessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leavingAccountDuringSignInDoesNotStrandThePendingOperation() {
        val response = CompletableDeferred<Unit>()
        val identity = TestIdentity().apply { failure = null; signInResponse = response }
        compose.setContent {
            val operations = rememberCoroutineScope()
            var account by remember { mutableStateOf(true) }
            // Leaving the account is the bottom bar's job (#47); here a plain button stands in for it.
            if (account) {
                Column {
                    Button(onClick = { account = false }, Modifier.testTag("leave_account")) { Text("Home") }
                    AccessScreen(identity, {}, operationScope = operations)
                }
            } else {
                Button(onClick = { account = true }, Modifier.testTag("return_account")) { Text("Account") }
            }
        }
        compose.onNodeWithTag("email").performTextInput("cliente@example.org")
        compose.onNodeWithTag("password").performTextInput("correcta")
        compose.onNodeWithTag("signIn").performScrollTo().performClick()
        compose.onNodeWithTag("leave_account").performClick()
        response.complete(Unit)
        compose.onNodeWithTag("return_account").performClick()
        awaitTag("account")
        compose.onNodeWithTag("account").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("signOut").performScrollTo().performClick()
        awaitTag("email")
        compose.onNodeWithTag("email").performScrollTo().assertIsDisplayed()
    }

    @Test fun signedInAccountCanSignOutWithoutLeavingAnAuthenticatedScreen() {
        val identity = TestIdentity().apply { failure = null; signInDelayMillis = 250 }
        compose.setContent { AccessScreen(identity = identity, onLanguageChange = {}) }
        compose.onNodeWithTag("email").performTextInput("cliente@example.org")
        compose.onNodeWithTag("password").performTextInput("correcta")
        compose.onNodeWithTag("signIn").performScrollTo().performClick()
        awaitTag("account")
        compose.onNodeWithTag("account").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("signOut").performScrollTo().performClick()
        awaitTag("email")
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
        awaitTag("error")
        compose.onNodeWithTag("error").assertIsDisplayed()
        compose.onNodeWithTag("signIn").assertIsDisplayed()
    }

    @Test fun theAccountTabHasNoBackButtonOfItsOwn() {
        compose.setContent { AccessScreen(TestIdentity(), {}, onLeaveLink = {}) }
        compose.onNodeWithTag("email").assertIsDisplayed()
        compose.onNodeWithTag("back_from_account").assertDoesNotExist()
    }

    @Test fun anInvalidEmailLinkCanBeLeftWithBack() {
        val identity = TestIdentity().apply {
            state.value = AccessState(AccessStage.SIGNED_OUT, error = AccessError.LINK_INVALID)
        }
        var left by mutableStateOf(false)
        compose.setContent { AccessScreen(identity, {}, onLeaveLink = { identity.dismiss(); left = true }) }
        compose.onNodeWithTag("back_from_account").performScrollTo().performClick()
        compose.runOnIdle { assert(left) }
        compose.onNodeWithTag("back_from_account").assertDoesNotExist()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
        }
    }
}
