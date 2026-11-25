package es.teloarreglo

import es.teloarreglo.appointments.AppointmentStage
import es.teloarreglo.catalogue.AppointmentZone
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.SupabaseWorkZones
import es.teloarreglo.catalogue.WorkZoneException
import es.teloarreglo.catalogue.WorkZoneFailure
import es.teloarreglo.catalogue.ZoneConcejo
import es.teloarreglo.catalogue.outsideZone
import es.teloarreglo.catalogue.showsZoneNotice
import es.teloarreglo.catalogue.withConcejo
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.*
import org.junit.Test

/** The work zone informs, it never restricts (ADR 0001). */
class WorkZonesTest {
    private val requests = mutableListOf<Pair<String, String>>()

    private fun client(status: HttpStatusCode, body: String) =
        createSupabaseClient("https://zones.example.org", "public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Postgrest)
            httpEngine = MockEngine { request ->
                requests += request.url.encodedPath to ((request.body as? TextContent)?.text ?: "")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

    private fun error(code: String, message: String) = """{"code":"$code","message":"$message","details":null,"hint":null}"""

    private val oviedo = Coverage("33044", "Oviedo")
    private val gijon = ZoneConcejo("33024", "Gijón")

    @Test fun anAppointmentOutsideTheZoneNamesItsConcejo() = runTest {
        val client = client(HttpStatusCode.OK, """{"in_zone":false,"concejo":{"ine_code":"33024","name":"Gijón"}}""")
        try {
            val zone = SupabaseWorkZones(client).appointmentZone("appt")
            assertEquals(AppointmentZone(false, gijon), zone)
            assertEquals("/rest/v1/rpc/appointment_zone", requests.single().first)
            assertEquals("appt", Json.parseToJsonElement(requests.single().second).jsonObject["p_appointment_id"]!!.jsonPrimitive.content)
        } finally { client.close() }
    }

    @Test fun aNonParticipantGetsNothing() = runTest {
        val client = client(HttpStatusCode.OK, "null")
        try { assertNull(SupabaseWorkZones(client).appointmentZone("appt")) } finally { client.close() }
    }

    @Test fun theOwnerReplacesTheZoneAndGetsTheOneNowPublic() = runTest {
        val client = client(HttpStatusCode.OK, """[{"code":"33024","name":"Gijón"},{"code":"33044","name":"Oviedo"}]""")
        try {
            val zone = SupabaseWorkZones(client).setMine(listOf(oviedo).withConcejo(gijon))
            assertEquals(listOf(Coverage("33024", "Gijón"), oviedo), zone)
            assertEquals("/rest/v1/rpc/set_my_work_zone", requests.single().first)
            val codes = Json.parseToJsonElement(requests.single().second).jsonObject["p_codes"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("33044", "33024"), codes)
        } finally { client.close() }
    }

    @Test fun zoneRefusalsMapToStableFailures() = runTest {
        val cases = listOf(
            Triple(HttpStatusCode.Forbidden, error("42501", "not_published"), WorkZoneFailure.NOT_PUBLISHED),
            Triple(HttpStatusCode.BadRequest, error("22023", "zone_empty"), WorkZoneFailure.EMPTY),
            Triple(HttpStatusCode.BadRequest, error("22023", "unknown_concejo"), WorkZoneFailure.UNKNOWN_CONCEJO),
            Triple(HttpStatusCode.Unauthorized, error("PGRST301", "jwt expired"), WorkZoneFailure.ACCESS),
        )
        for ((status, body, expected) in cases) {
            val client = client(status, body)
            val failure = try { SupabaseWorkZones(client).setMine(listOf("33044")); null }
                catch (e: WorkZoneException) { e.failure } finally { client.close() }
            assertEquals(expected, failure)
        }
    }

    @Test fun anUnexpectedServerErrorIsNotPassedOffAsARefusal() = runTest {
        val client = client(HttpStatusCode.InternalServerError, error("XX000", "boom"))
        try {
            assertThrows(RestException::class.java) { kotlinx.coroutines.runBlocking { SupabaseWorkZones(client).setMine(listOf("33044")) } }
        } finally { client.close() }
    }

    @Test fun thePointResolvesToTheNearestConcejo() = runTest {
        val client = client(HttpStatusCode.OK, """[{"ine_code":"33044","name":"Oviedo","distance_m":120.5},
            {"ine_code":"33024","name":"Gijón","distance_m":0}]""")
        try {
            assertEquals(gijon, SupabaseWorkZones(client).concejoAt(43.53, -5.66))
            assertEquals("/rest/v1/rpc/resolve_concejo", requests.single().first)
            val body = Json.parseToJsonElement(requests.single().second).jsonObject
            assertEquals(43.53, body["p_lat"]!!.jsonPrimitive.double, 0.0)
            assertEquals(-5.66, body["p_lng"]!!.jsonPrimitive.double, 0.0)
        } finally { client.close() }
    }

    @Test fun aPointOutsideAsturiasHasNoConcejo() = runTest {
        val client = client(HttpStatusCode.OK, "[]")
        try { assertNull(SupabaseWorkZones(client).concejoAt(40.4, -3.7)) } finally { client.close() }
    }

    @Test fun thePublicZoneComesFromTheApprovedProfile() = runTest {
        val client = client(HttpStatusCode.OK, """[{"profile_id":"p","name":"Ana","description":"","trades":["plumbing"],
            "services":[],"coverage":[{"code":"33044","name":"Oviedo"}],"languages":["es"],"phone":"+34 600 000 000",
            "images":[],"prices":[]}]""")
        try {
            assertEquals(listOf(oviedo), SupabaseWorkZones(client).publicZone("p"))
            assertEquals("/rest/v1/rpc/catalogue", requests.single().first)
        } finally { client.close() }
    }

    @Test fun addingAConcejoAlreadyInTheZoneChangesNothing() {
        assertEquals(listOf("33044"), listOf(oviedo).withConcejo(ZoneConcejo("33044", "Oviedo")))
    }

    @Test fun onlyAKnownConcejoOutsideAKnownZoneWarns() {
        assertTrue(outsideZone(listOf(oviedo), gijon))
        assertFalse(outsideZone(listOf(oviedo), ZoneConcejo("33044", "Oviedo")))
        assertFalse(outsideZone(null, gijon))
        assertFalse(outsideZone(listOf(oviedo), null))
    }

    @Test fun theNoticeIsForAppointmentsStillUnderWay() {
        val outside = AppointmentZone(false, gijon)
        assertTrue(showsZoneNotice(AppointmentStage.PENDING, outside))
        assertTrue(showsZoneNotice(AppointmentStage.CONFIRMED, outside))
        assertFalse(showsZoneNotice(AppointmentStage.CANCELLED, outside))
        assertFalse(showsZoneNotice(AppointmentStage.COMPLETED, outside))
        assertFalse(showsZoneNotice(AppointmentStage.PENDING, AppointmentZone(true, gijon)))
        assertFalse(showsZoneNotice(AppointmentStage.PENDING, null))
    }
}
