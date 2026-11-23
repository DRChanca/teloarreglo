package es.teloarreglo

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import es.teloarreglo.identity.AccountNameSession
import es.teloarreglo.identity.AccountNameStatus
import es.teloarreglo.identity.AccountNames
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private class UiNames(var stored: String? = null) : AccountNames {
    var offline = false
    override suspend fun current(): String? = stored
    override suspend fun save(name: String): String {
        if (offline) throw IOException("offline")
        return name.trim().also { stored = it }
    }
}

/** Person's name (#40): signup field, one-time question and account card, with fake services. */
class AccountNameUiTest {
    @get:Rule val compose = createComposeRule()

    private fun awaitTag(tag: String) = compose.waitUntil(timeoutMillis = 5000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun signingUpAsksForTheNameAndSendsIt() {
        val identity = TestIdentity()
        compose.setContent { AccessScreen(identity, {}, operationScope = rememberCoroutineScope()) }
        compose.onNodeWithTag("toSignUp").performScrollTo().performClick()
        compose.onNodeWithTag("account_name").performTextInput("Nuria Nueva")
        compose.onNodeWithTag("email").performTextInput("nuevo@example.org")
        compose.onNodeWithTag("password").performTextInput("contraseña-larga")
        compose.onNodeWithTag("signUp").performScrollTo().performClick()
        awaitTag("verifyHint")
        assertEquals("Nuria Nueva", identity.signedUpName)
    }

    @Test fun anAccountWithoutNameSavesItOnceAndANetworkFailureKeepsTheText() {
        val names = UiNames()
        val session = AccountNameSession(names)
        runBlocking { session.load("a") }
        compose.setContent { AccountNameScreen(session, rememberCoroutineScope(), onSignOut = {}) }
        compose.onNodeWithTag("account_name_save").assertIsNotEnabled()
        compose.onNodeWithTag("account_name").performTextInput("  Ana López ")
        names.offline = true
        compose.onNodeWithTag("account_name_save").assertIsEnabled().performClick()
        awaitTag("account_name_error")
        compose.onNodeWithTag("account_name").assertTextContains("Ana López", substring = true)
        names.offline = false
        compose.onNodeWithTag("account_name_save").performClick()
        compose.waitUntil(5000) { session.status.value == AccountNameStatus.Present("Ana López") }
    }

    @Test fun theAccountCardShowsAndChangesTheName() {
        val session = AccountNameSession(UiNames("Lucía Prueba"))
        runBlocking { session.load("a") }
        compose.setContent { AccountNameCard(session, rememberCoroutineScope()) }
        compose.onNodeWithTag("account_name_current").assertTextContains("Lucía Prueba")
        compose.onNodeWithTag("account_name_change").performClick()
        compose.onNodeWithTag("account_name").performTextClearance()
        compose.onNodeWithTag("account_name").performTextInput("Lucía Cambiada")
        compose.onNodeWithTag("account_name_save").performClick()
        awaitTag("account_name_current")
        compose.onNodeWithTag("account_name_current").assertTextContains("Lucía Cambiada")
    }
}
