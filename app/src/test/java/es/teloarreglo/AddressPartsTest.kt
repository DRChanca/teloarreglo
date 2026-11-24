package es.teloarreglo

import es.teloarreglo.home.AddressParts
import es.teloarreglo.home.PlaceDetails
import es.teloarreglo.search.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spanish home address fields after the street (#41): número, bloque, portal, escalera, piso, puerta. */
class AddressPartsTest {
    @Test fun allTheUsualSpanishFieldsMakeOneReadableLine() {
        val parts = AddressParts(street = "Calle Uría", number = "10", block = "2", portal = "3", staircase = "A",
            floor = "4", door = "B", locality = "33003 Oviedo, Asturias")
        assertEquals("Calle Uría 10, bloque 2, portal 3, esc. A, 4º B, 33003 Oviedo, Asturias", parts.text())
    }

    @Test fun emptyFieldsAreLeftOutAndWordsForTheFloorAreKept() {
        assertEquals("Calle Uría 10, bajo izquierda, Oviedo",
            AddressParts("Calle Uría", number = "10", floor = "bajo", door = "izquierda", locality = "Oviedo").text())
        assertEquals("Calle Uría, puerta 2", AddressParts(" Calle Uría ", door = " 2 ").text())
        assertEquals("Calle Uría 10, 3º", AddressParts("Calle Uría", number = "10", floor = "3º").text())
    }

    @Test fun aChosenPlaceFillsStreetNumberAndLocalitySoTheNumberIsNotRepeated() {
        val place = PlaceDetails("Calle Uría, 10, 33003 Oviedo, Asturias, España", GeoPoint(43.3619, -5.8494), "place-uria",
            street = "Calle Uría", number = "10", locality = "33003 Oviedo, Asturias")
        assertEquals(AddressParts("Calle Uría", number = "10", locality = "33003 Oviedo, Asturias"), AddressParts.from(place))
    }

    @Test fun aPlaceWithoutStreetDetailsKeepsItsWholeAddressAsTheStreet() {
        val place = PlaceDetails("Plaza de la Catedral, Oviedo, España", GeoPoint(43.3625, -5.8433), "place-catedral")
        assertEquals(AddressParts("Plaza de la Catedral, Oviedo, España"), AddressParts.from(place))
    }

    @Test fun onlyTheStreetIsRequiredAndEveryFieldHasALimit() {
        assertFalse(AddressParts(" ").valid)
        assertTrue(AddressParts("Calle Uría").valid)
        assertFalse(AddressParts("Calle Uría", floor = "x".repeat(AddressParts.MAX_DETAIL + 1)).valid)
        assertFalse(AddressParts("x".repeat(AddressParts.MAX_STREET + 1)).valid)
    }
}
