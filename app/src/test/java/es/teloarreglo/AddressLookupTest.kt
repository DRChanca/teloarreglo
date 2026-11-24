package es.teloarreglo

import es.teloarreglo.home.AddressAutocomplete
import es.teloarreglo.home.AddressLookup
import es.teloarreglo.home.AddressSuggestion
import es.teloarreglo.home.ChoiceResult
import es.teloarreglo.home.LookupResult
import es.teloarreglo.home.PlaceDetails
import es.teloarreglo.search.GeoPoint
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Places stand-in: the real SDK needs a key, network and an enabled API. */
private class FakePlaces(var failure: Exception? = null) : AddressAutocomplete {
    val queries = mutableListOf<String>()
    val uria = AddressSuggestion("place-uria", "Calle Uría, 10", "Oviedo, Asturias, España")
    override suspend fun suggest(query: String): List<AddressSuggestion> {
        failure?.let { throw it }
        queries += query
        return listOf(uria)
    }
    override suspend fun details(suggestion: AddressSuggestion): PlaceDetails {
        failure?.let { throw it }
        return PlaceDetails("Calle Uría, 10, 33003 Oviedo, Asturias, España", GeoPoint(43.3619, -5.8494), suggestion.placeId)
    }
}

class AddressLookupTest {
    @Test fun typingAnAddressSuggestsPlacesAndChoosingOneGivesTextAndPoint() = runTest {
        val places = FakePlaces()
        val lookup = AddressLookup(places)
        val found = lookup.suggest("  calle uría 10 ") as LookupResult.Suggestions
        assertEquals(listOf("calle uría 10"), places.queries)
        val chosen = lookup.choose(found.items.single()) as ChoiceResult.Chosen
        assertEquals("Calle Uría, 10, 33003 Oviedo, Asturias, España", chosen.place.address)
        assertEquals(GeoPoint(43.3619, -5.8494), chosen.place.point)
        assertEquals("place-uria", chosen.place.placeId)
    }

    @Test fun upToSevenCharactersDoNotCallPlaces() = runTest {
        val places = FakePlaces()
        val lookup = AddressLookup(places)
        assertEquals(LookupResult.TooShort, lookup.suggest("ca"))
        assertEquals(LookupResult.TooShort, lookup.suggest("  c. uría   "))
        assertTrue(places.queries.isEmpty())
        assertTrue(lookup.suggest("c. uría 1 ") is LookupResult.Suggestions)
        assertEquals(listOf("c. uría 1"), places.queries)
    }

    @Test fun withoutKeyPlacesIsUnavailableFromTheStart() = runTest {
        val lookup = AddressLookup(null)
        assertFalse(lookup.available)
        assertEquals(LookupResult.Unavailable, lookup.suggest("calle uría 10"))
    }

    @Test fun aPlacesFailureSwitchesToPinGpsAndManualTextForTheRestOfTheScreen() = runTest {
        val places = FakePlaces(IOException("API not enabled"))
        val lookup = AddressLookup(places)
        assertEquals(LookupResult.Unavailable, lookup.suggest("calle uría 10"))
        assertFalse("fallback stays chosen", lookup.available)
        places.failure = null
        assertEquals(LookupResult.Unavailable, lookup.suggest("calle uría 10"))
    }

    @Test fun failingToFetchTheChosenPlaceAlsoFallsBack() = runTest {
        val places = FakePlaces()
        val lookup = AddressLookup(places)
        val found = lookup.suggest("calle uría 10") as LookupResult.Suggestions
        places.failure = IllegalStateException("quota")
        assertEquals(ChoiceResult.Unavailable, lookup.choose(found.items.single()))
        assertFalse(lookup.available)
    }

    @Test fun cancellingATypedQueryIsNotAFailure() = runTest {
        val lookup = AddressLookup(FakePlaces(CancellationException("newer text")))
        try { lookup.suggest("calle uría 10"); fail("cancellation must propagate") } catch (_: CancellationException) {}
        assertTrue(lookup.available)
    }
}
