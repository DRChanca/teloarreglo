package es.teloarreglo.home

import es.teloarreglo.search.GeoPoint
import kotlinx.coroutines.CancellationException

/** One autocomplete row as Places shows it: street first, then locality. */
data class AddressSuggestion(val placeId: String, val primary: String, val secondary: String)

/** A chosen place: full formatted address and its point. */
data class PlaceDetails(val address: String, val point: GeoPoint, val placeId: String)

/**
 * Address autocomplete boundary (#37). The real one is Google Places (New) for Android; tests use a
 * stand-in. Implementations throw on any failure (no key accepted, API not enabled, quota, network).
 */
interface AddressAutocomplete {
    suspend fun suggest(query: String): List<AddressSuggestion>
    suspend fun details(suggestion: AddressSuggestion): PlaceDetails
}

sealed interface LookupResult {
    data class Suggestions(val items: List<AddressSuggestion>) : LookupResult
    data object TooShort : LookupResult
    /** Places cannot be used: the screen offers pin, GPS and the address typed by hand. */
    data object Unavailable : LookupResult
}

sealed interface ChoiceResult {
    data class Chosen(val place: PlaceDetails) : ChoiceResult
    data object Unavailable : ChoiceResult
}

/** Below this many characters Places is not called: each request has a cost. */
const val MIN_ADDRESS_QUERY = 3

/**
 * Places with a fallback that never breaks the screen. [places] is null without a Maps key. After the
 * first failure the lookup stays unavailable for the rest of the screen, so the user is not shown
 * suggestions that come and go and Places is not called again while it is failing.
 */
class AddressLookup(private val places: AddressAutocomplete?) {
    var available: Boolean = places != null
        private set

    suspend fun suggest(query: String): LookupResult {
        val text = query.trim()
        val service = places?.takeIf { available } ?: return LookupResult.Unavailable
        if (text.length < MIN_ADDRESS_QUERY) return LookupResult.TooShort
        return guarded(LookupResult.Unavailable) { LookupResult.Suggestions(service.suggest(text)) }
    }

    suspend fun choose(suggestion: AddressSuggestion): ChoiceResult {
        val service = places?.takeIf { available } ?: return ChoiceResult.Unavailable
        return guarded(ChoiceResult.Unavailable) {
            service.details(suggestion).takeIf { it.point.valid && it.address.isNotBlank() }?.let(ChoiceResult::Chosen)
                ?: ChoiceResult.Unavailable
        }
    }

    private inline fun <T> guarded(unavailable: T, block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is CancellationException) throw error
        available = false
        unavailable
    }
}
