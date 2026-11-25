package es.teloarreglo

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import es.teloarreglo.identity.ContactPhoneSession
import es.teloarreglo.identity.ContactPhoneStatus
import es.teloarreglo.identity.ContactPhones
import es.teloarreglo.identity.normalizeContactPhone
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private class UiPhones(var stored: String? = null) : ContactPhones {
    var offline = false
    var sent = 0
    override suspend fun current(): String? = stored
    override suspend fun save(phone: String): String {
        sent++
        if (offline) throw IOException("offline")
        return normalizeContactPhone(phone)!!.also { stored = it }
    }
}

/** Contact phone step (#74) with fake services: explains its use, rejects bad numbers, continues once saved. */
class ContactPhoneUiTest {
    @get:Rule val compose = createComposeRule()

    private fun awaitTag(tag: String) = compose.waitUntil(timeoutMillis = 5000) {
        // The error lives in the field's supporting text, merged into the field.
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun theStepExplainsTheUseRejectsABadNumberAndSavesAGoodOne() {
        val phones = UiPhones()
        val session = ContactPhoneSession(phones)
        runBlocking { session.load("a") }
        compose.setContent { ContactPhoneScreen(session, rememberCoroutineScope(), onSignOut = {}) }
        val intro = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.contact_phone_intro)
        compose.onNodeWithTag("contact_phone_intro").assertTextContains(intro)
        compose.onNodeWithTag("contact_phone_save").assertIsNotEnabled()

        compose.onNodeWithTag("contact_phone").performTextInput("12345")
        compose.onNodeWithTag("contact_phone_save").assertIsEnabled().performClick()
        awaitTag("contact_phone_error")
        assertEquals("a badly written number is never sent", 0, phones.sent)

        compose.onNodeWithTag("contact_phone").performTextClearance()
        compose.onNodeWithTag("contact_phone").performTextInput("612 34 56 78")
        phones.offline = true
        compose.onNodeWithTag("contact_phone_save").performClick()
        awaitTag("contact_phone_error")
        compose.onNodeWithTag("contact_phone").assertTextContains("612 34 56 78", substring = true)

        phones.offline = false
        compose.onNodeWithTag("contact_phone_save").performClick()
        compose.waitUntil(5000) { session.status.value == ContactPhoneStatus.Present("+34612345678") }
        assertEquals("saved: the mandatory step is over", false, session.required)
    }
}
