package es.teloarreglo

import es.teloarreglo.notices.NoticeTopic
import es.teloarreglo.notices.PushDevices
import es.teloarreglo.notices.PushPreferences
import es.teloarreglo.notices.PushRegistration
import es.teloarreglo.notices.PushStatus
import es.teloarreglo.notices.PushTokenSource
import es.teloarreglo.notices.SupabaseNotices
import es.teloarreglo.notices.topic
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NoticesTest {
    private val requests = mutableListOf<Pair<String, String>>()
    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        createSupabaseClient("https://notices.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                requests += request.url.encodedPath to ((request.body as? io.ktor.http.content.TextContent)?.text ?: "")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

    @Test fun theAccountReadsItsPersistedNoticesWithReadStateAndTarget() = runTest {
        val client = client("""[
            {"id":"n1","kind":"message","payload":{"conversation_id":"c1"},"created_at":"2026-11-23T10:00:00+00:00","read":false,"count":3},
            {"id":"n2","kind":"appointment","payload":{"conversation_id":"c1","appointment_id":"a1","event":"done"},"created_at":"2026-11-23T09:00:00+00:00","read":true,"count":1},
            {"id":"n3","kind":"profile_review","payload":{"status":"rejected","reason":"Falta"},"created_at":"2026-11-22T09:00:00+00:00","read":false,"count":1}]""")
        try {
            val notices = SupabaseNotices(client).list()
            assertEquals("/rest/v1/rpc/my_notices", requests.single().first)
            assertEquals(listOf("n1", "n2", "n3"), notices.map { it.id })
            assertEquals(3, notices[0].count)
            assertFalse(notices[0].read)
            assertEquals("c1", notices[1].conversationId)
            assertEquals(NoticeTopic.DONE, notices[1].topic())
            assertEquals(NoticeTopic.PROFILE_REJECTED, notices[2].topic())
            assertNull(notices[2].conversationId)
        } finally { client.close() }
    }

    @Test fun markingReadSendsOnlyTheChosenIds() = runTest {
        val client = client("null")
        try {
            SupabaseNotices(client).markRead(listOf("n1", "n2"))
            assertEquals("/rest/v1/rpc/mark_notices_read", requests.single().first)
            assertEquals("""{"p_ids":["n1","n2"]}""", requests.single().second)
        } finally { client.close() }
    }

    @Test fun everyKnownEventHasItsOwnTopicAndNewOnesFallBackToAGenericOne() {
        fun t(kind: String, event: String?) = es.teloarreglo.notices.Notice("i", kind, kotlinx.serialization.json.buildJsonObject {
            if (event != null) put(if (kind == "profile_review") "status" else "event", kotlinx.serialization.json.JsonPrimitive(event))
        }, "2026-11-23T10:00:00Z").topic()
        assertEquals(NoticeTopic.MESSAGE, t("message", null))
        assertEquals(NoticeTopic.REQUEST, t("appointment", "requested"))
        assertEquals(NoticeTopic.PROPOSAL, t("appointment", "proposed"))
        assertEquals(NoticeTopic.REJECTED, t("appointment", "rejected"))
        assertEquals(NoticeTopic.CONFIRMED, t("appointment", "accepted"))
        assertEquals(NoticeTopic.CHANGE, t("appointment", "change_proposed"))
        assertEquals(NoticeTopic.CHANGE, t("appointment", "address_correction_requested"))
        assertEquals(NoticeTopic.CHANGE_DECIDED, t("appointment", "change_accepted"))
        assertEquals(NoticeTopic.CHANGE_DECIDED, t("appointment", "change_rejected"))
        assertEquals(NoticeTopic.STARTED, t("appointment", "started"))
        assertEquals(NoticeTopic.CANCELLED, t("appointment", "cancelled"))
        assertEquals(NoticeTopic.DONE, t("appointment", "done"))
        assertEquals(NoticeTopic.REMINDER, t("appointment", "reminder"))
        assertEquals(NoticeTopic.COMPLETED, t("appointment", "completed"))
        assertEquals(NoticeTopic.INCIDENT, t("appointment", "incident_reported"))
        assertEquals(NoticeTopic.APPOINTMENT_OTHER, t("appointment", "something_new"))
        assertEquals(NoticeTopic.PROFILE_APPROVED, t("profile_review", "approved"))
        assertEquals(NoticeTopic.OTHER, t("future_kind", null))
    }

    private class FakeDevices : PushDevices {
        val calls = mutableListOf<String>()
        var fail = false
        override suspend fun register(installationId: String, token: String, enabled: Boolean) {
            if (fail) error("offline")
            calls += "register $installationId $token $enabled"
        }
        override suspend fun unregister(installationId: String) {
            if (fail) error("offline")
            calls += "unregister $installationId"
        }
    }
    private class FakeTokens(override val available: Boolean = true, var value: String? = "fcm-1") : PushTokenSource {
        override suspend fun token() = value
    }
    private class MemoryPreferences : PushPreferences {
        override val installationId = "install-1"
        override var enabled = true
        override var signedInUser: String? = null
    }

    @Test fun aSignedInAccountRegistersThisDeviceAndOnlyItsPushesAreShown() = runTest {
        val devices = FakeDevices(); val prefs = MemoryPreferences()
        val push = PushRegistration(devices, FakeTokens(), prefs) { true }
        push.sync("user-a")
        assertEquals(listOf("register install-1 fcm-1 true"), devices.calls)
        assertEquals(PushStatus.ACTIVE, push.status.value)
        assertTrue(push.accepts("user-a"))
        assertFalse(push.accepts("user-b"))
    }

    @Test fun withoutAndroidPermissionTheDeviceIsRegisteredButNotPushed() = runTest {
        val devices = FakeDevices()
        val push = PushRegistration(devices, FakeTokens(), MemoryPreferences()) { false }
        push.sync("user-a")
        assertEquals(listOf("register install-1 fcm-1 false"), devices.calls)
        assertEquals(PushStatus.PERMISSION_NEEDED, push.status.value)
    }

    @Test fun turningNotificationsOffIsRememberedAndSentToTheServer() = runTest {
        val devices = FakeDevices(); val prefs = MemoryPreferences()
        val push = PushRegistration(devices, FakeTokens(), prefs) { true }
        push.sync("user-a")
        push.setEnabled(false)
        assertFalse(prefs.enabled)
        assertEquals("register install-1 fcm-1 false", devices.calls.last())
        assertEquals(PushStatus.OFF, push.status.value)
    }

    @Test fun signingOutUnregistersFirstAndStopsShowingThatAccountsPushesEvenOffline() = runTest {
        val devices = FakeDevices(); val prefs = MemoryPreferences()
        val push = PushRegistration(devices, FakeTokens(), prefs) { true }
        push.sync("user-a")
        devices.fail = true
        push.beforeSignOut()
        assertNull(prefs.signedInUser)
        assertFalse(push.accepts("user-a"))
        devices.fail = false
        push.beforeSignOut()
        assertEquals("unregister install-1", devices.calls.last())
    }

    @Test fun withoutFirebaseConfigurationNoticesStayInTheAppOnly() = runTest {
        val devices = FakeDevices()
        val push = PushRegistration(devices, FakeTokens(available = false), MemoryPreferences()) { true }
        push.sync("user-a")
        assertEquals(emptyList<String>(), devices.calls)
        assertEquals(PushStatus.NOT_CONFIGURED, push.status.value)
    }

    @Test fun aRegistrationFailureIsShownAndDoesNotThrow() = runTest {
        val devices = FakeDevices().apply { fail = true }
        val push = PushRegistration(devices, FakeTokens(), MemoryPreferences()) { true }
        push.sync("user-a")
        assertEquals(PushStatus.FAILED, push.status.value)
    }
}
