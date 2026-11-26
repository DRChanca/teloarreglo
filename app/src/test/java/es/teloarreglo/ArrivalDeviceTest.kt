package es.teloarreglo

import es.teloarreglo.appointments.ArrivalChoice
import es.teloarreglo.appointments.decodeArrivalChoice
import es.teloarreglo.appointments.encodeArrivalChoice
import es.teloarreglo.search.arrivalProviders
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** The professional's phone in the ventana de llegada (#72): where a fix comes from, and how a choice is kept. */
class ArrivalDeviceTest {
    private fun providers(sdk: Int, vararg enabled: String) = arrivalProviders(sdk, { it in enabled }, 20_000)

    @Test fun fromAndroid12TheFusedProviderGivesAFixAlsoIndoors() {
        assertEquals(listOf("fused" to 20_000L), providers(35, "fused", "gps", "network"))
    }

    @Test fun withoutTheFusedProviderGpsFallsBackToTheNetwork() {
        // Indoors GPS alone gives nothing: half the time for GPS, then the network.
        assertEquals(listOf("gps" to 10_000L, "network" to 10_000L), providers(30, "gps", "network"))
        assertEquals(listOf("gps" to 10_000L, "network" to 10_000L), providers(35, "gps", "network"))
        assertEquals(listOf("network" to 20_000L), providers(30, "network"))
        assertEquals(listOf("gps" to 20_000L), providers(30, "gps"))
    }

    @Test fun withLocationOffThereIsNoProvider() {
        assertEquals(emptyList<Pair<String, Long>>(), providers(35))
        // «fused» before Android 12 is not the system's.
        assertEquals(emptyList<Pair<String, Long>>(), providers(30, "fused"))
    }

    private val start = Instant.parse("2030-01-15T10:00:00Z")

    @Test fun aChoiceIsKeptWithWhetherTheServerKnowsIt() {
        for (choice in listOf(ArrivalChoice("a1", "Lucía | Pérez", start, announced = true), ArrivalChoice("a1", "0", start))) {
            assertEquals(choice, decodeArrivalChoice("a1", encodeArrivalChoice(choice)))
        }
    }

    @Test fun aChoiceKeptByAnEarlierVersionReadsAsNotYetToldToTheServer() {
        assertEquals(ArrivalChoice("a1", "Lucía", start), decodeArrivalChoice("a1", "${start.epochSecond}|Lucía"))
        assertEquals(null, decodeArrivalChoice("a1", "garbage"))
    }
}
