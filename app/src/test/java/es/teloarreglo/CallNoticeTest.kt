package es.teloarreglo

import es.teloarreglo.notices.CallNoticeAction
import es.teloarreglo.notices.PushCall
import es.teloarreglo.notices.callNoticeAction
import es.teloarreglo.notices.pushCall
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «Llamar» in the call notice (#81): the dialer with the number read now, or the appointment when there is none. */
class CallNoticeTest {
    @Test fun withTheClientsNumberTheDialerOpensWithIt() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        val action = callNoticeAction("c1", "a1") { conversation, appointment ->
            asked += conversation to appointment
            "600 123 456"
        }
        assertEquals(CallNoticeAction.Dial("tel:600123456"), action)
        assertEquals(listOf("c1" to "a1"), asked)
    }

    @Test fun withoutANumberTheAppointmentOpensInstead() = runTest {
        // Finished, cancelled or unreadable appointment: the protected function gives nothing.
        assertEquals(CallNoticeAction.OpenAppointment, callNoticeAction("c1", "a1") { _, _ -> null })
        // Nothing dialable is no number either.
        assertEquals(CallNoticeAction.OpenAppointment, callNoticeAction("c1", "a1") { _, _ -> "  " })
    }

    @Test fun whenTheNumberCannotBeReadTheAppointmentOpens() = runTest {
        assertEquals(CallNoticeAction.OpenAppointment,
            callNoticeAction("c1", "a1") { _, _ -> throw IllegalStateException("no network") })
    }

    @Test fun onlyTheCallPushWithItsAppointmentCarriesTheCallAction() {
        assertEquals(PushCall("c1", "a1"),
            pushCall(mapOf("kind" to "call_client", "appointment_id" to "a1", "conversation_id" to "c1")))
        assertNull(pushCall(mapOf("kind" to "call_client", "appointment_id" to "", "conversation_id" to "c1")))
        assertNull(pushCall(mapOf("kind" to "call_client", "appointment_id" to "a1", "conversation_id" to "")))
        assertNull(pushCall(mapOf("kind" to "appointment", "event" to "accepted", "appointment_id" to "a1",
            "conversation_id" to "c1")))
    }
}
