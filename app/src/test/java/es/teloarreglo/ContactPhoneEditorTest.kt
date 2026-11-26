package es.teloarreglo

import es.teloarreglo.identity.ContactPhoneSession
import es.teloarreglo.identity.ContactPhones
import es.teloarreglo.identity.normalizeContactPhone
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class EditorPhones(var stored: String? = null) : ContactPhones {
    var offline = false
    var sent = 0
    override suspend fun current(): String? = stored
    override suspend fun save(phone: String): String {
        sent++
        if (offline) throw IOException("offline")
        return normalizeContactPhone(phone)!!.also { stored = it }
    }
}

/** Ajustes › Cuenta (#78): changing the contact phone with the same rules as the mandatory step (#74). */
class ContactPhoneEditorTest {
    @Test fun theCurrentPhoneIsShownAndOfferedForEditing() = runTest {
        val session = ContactPhoneSession(EditorPhones("+34612345678")).apply { load("a") }
        val editor = ContactPhoneEditor(session)
        assertEquals("+34612345678", session.phone)
        assertFalse(editor.editing)
        editor.start()
        assertTrue(editor.editing)
        assertEquals("+34612345678", editor.typed)
        assertNull(editor.error)
    }

    @Test fun aBadNumberShowsTheErrorAndIsNeverSent() = runTest {
        val phones = EditorPhones("+34612345678")
        val session = ContactPhoneSession(phones).apply { load("a") }
        val editor = ContactPhoneEditor(session)
        editor.start()
        editor.type("12345")
        assertFalse(editor.save())
        assertEquals(R.string.contact_phone_invalid, editor.error)
        assertEquals(0, phones.sent)
        assertTrue("keeps editing", editor.editing)
        assertEquals("12345", editor.typed)
        editor.type("123456")
        assertNull("typing clears the error", editor.error)
    }

    @Test fun aGoodNumberIsSavedAndBecomesTheCurrentOne() = runTest {
        val phones = EditorPhones("+34612345678")
        val session = ContactPhoneSession(phones).apply { load("a") }
        val editor = ContactPhoneEditor(session)
        editor.start()
        editor.type("+1 (415) 555-2671")
        assertTrue(editor.save())
        assertFalse(editor.editing)
        assertFalse(editor.busy)
        assertEquals("+14155552671", phones.stored)
        assertEquals("+14155552671", session.phone)
    }

    @Test fun aServerFailureIsShownWithoutLosingWhatWasTyped() = runTest {
        val phones = EditorPhones("+34612345678").apply { offline = true }
        val session = ContactPhoneSession(phones).apply { load("a") }
        val editor = ContactPhoneEditor(session)
        editor.start()
        editor.type("699 11 22 33")
        assertFalse(editor.save())
        assertEquals(R.string.contact_phone_network, editor.error)
        assertTrue(editor.editing)
        assertFalse(editor.busy)
        assertEquals("699 11 22 33", editor.typed)
        assertEquals("unchanged", "+34612345678", session.phone)
    }

    @Test fun savingFromSettingsEndsTheMandatoryStep() = runTest {
        val session = ContactPhoneSession(EditorPhones()).apply { load("a") }
        assertTrue(session.required)
        val editor = ContactPhoneEditor(session)
        editor.start()
        assertEquals("nothing to prefill", "", editor.typed)
        editor.type("612345678")
        assertTrue(editor.save())
        assertFalse("not asked again", session.required)
    }

    @Test fun cancellingDropsTheDraftAndTheError() = runTest {
        val session = ContactPhoneSession(EditorPhones("+34612345678")).apply { load("a") }
        val editor = ContactPhoneEditor(session)
        editor.start()
        editor.type("1")
        editor.save()
        editor.cancel()
        assertFalse(editor.editing)
        assertNull(editor.error)
        editor.start()
        assertEquals("+34612345678", editor.typed)
    }
}
