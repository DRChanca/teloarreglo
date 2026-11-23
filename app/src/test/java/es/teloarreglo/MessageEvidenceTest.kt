package es.teloarreglo

import es.teloarreglo.appointments.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class MessageEvidenceTest {
    /** Records each call; fails the listed messages once, with the given error. */
    private class RecordingIncidents(val failOnce: MutableMap<String, Exception> = mutableMapOf()) : Incidents {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun open(appointmentId: String, requestId: String, kind: IncidentKind, description: String) =
            error("not used")
        override suspend fun addEvidence(incidentId: String, requestId: String, draft: EvidenceDraft): IncidentEvidence {
            val message = (draft as EvidenceDraft.Message).messageId
            calls += message to requestId
            failOnce.remove(message)?.let { throw it }
            return IncidentEvidence("e$message", incidentId, "me", requestId, EvidenceKind.MESSAGE, messageId = message,
                createdAt = Instant.parse("2026-11-23T10:00:00Z"))
        }
    }

    @Test fun eachSelectedMessageIsSentOnceWithItsOwnKey() = runTest {
        val incidents = RecordingIncidents()
        val result = incidents.addMessages("inc", listOf("m1", "m2"))
        assertEquals(listOf("m1", "m2"), result.added)
        assertNull(result.failed)
        assertEquals(listOf("m1", "m2"), incidents.calls.map { it.first })
        assertNotEquals(incidents.calls[0].second, incidents.calls[1].second)
    }

    @Test fun aRetryAfterALostAnswerReusesTheSameKeys() = runTest {
        val incidents = RecordingIncidents(mutableMapOf("m2" to IOException("timeout")))
        val first = incidents.addMessages("inc", listOf("m1", "m2", "m3"))
        assertEquals(listOf("m1"), first.added)
        assertEquals(EvidenceBatchFailure(null), first.failed)
        val retry = incidents.addMessages("inc", listOf("m2", "m3"))
        assertEquals(listOf("m2", "m3"), retry.added)
        val keys = incidents.calls.filter { it.first == "m2" }.map { it.second }.distinct()
        assertEquals(1, keys.size)
    }

    @Test fun aClosedIncidentStopsTheBatchWithItsReason() = runTest {
        val incidents = RecordingIncidents(mutableMapOf("m1" to IncidentException(IncidentFailure.CLOSED, Exception())))
        val result = incidents.addMessages("inc", listOf("m1", "m2"))
        assertTrue(result.added.isEmpty())
        assertEquals(EvidenceBatchFailure(IncidentFailure.CLOSED), result.failed)
        assertEquals(listOf("m1"), incidents.calls.map { it.first })
    }

    @Test fun theSameMessageKeepsItsKeyPerIncidentOnly() {
        assertEquals(messageEvidenceKey("inc", "m1"), messageEvidenceKey("inc", "m1"))
        assertNotEquals(messageEvidenceKey("inc", "m1"), messageEvidenceKey("other", "m1"))
    }
}
