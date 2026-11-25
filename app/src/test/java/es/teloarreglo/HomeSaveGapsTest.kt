package es.teloarreglo

import es.teloarreglo.home.AddressParts
import es.teloarreglo.home.HomeGap
import es.teloarreglo.home.homeSaveGaps
import org.junit.Assert.assertEquals
import org.junit.Test

/** While «Guardar dirección» is disabled the screen says why (#47); the rules stay those of #37/#41. */
class HomeSaveGapsTest {
    @Test fun aCompleteAddressWithAPointHasNothingMissing() {
        assertEquals(emptyList<HomeGap>(), homeSaveGaps(AddressParts("Calle Uría", number = "10"), hasPoint = true))
    }

    @Test fun anEmptyFormLacksTheStreetAndThePoint() {
        assertEquals(listOf(HomeGap.STREET, HomeGap.LOCATION), homeSaveGaps(AddressParts(" "), hasPoint = false))
    }

    @Test fun onlyTheStreetIsRequiredAmongTheFieldsSoAMissingNumberDoesNotBlock() {
        assertEquals(listOf(HomeGap.LOCATION), homeSaveGaps(AddressParts("Calle Uría"), hasPoint = false))
    }

    @Test fun aFieldLongerThanAllowedIsNamedAsTooLong() {
        assertEquals(listOf(HomeGap.TOO_LONG),
            homeSaveGaps(AddressParts("Calle Uría", floor = "x".repeat(AddressParts.MAX_DETAIL + 1)), hasPoint = true))
    }
}
