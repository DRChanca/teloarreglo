package es.teloarreglo.home

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import es.teloarreglo.search.GeoPoint
import kotlinx.coroutines.tasks.await

/**
 * Google Places (New) autocomplete for the home address (#37), with the same public key as Maps
 * (`BuildConfig.MAPS_API_KEY`, never written in Git). It only works once Places API (New) is enabled
 * on that key in Google Cloud; until then every call fails and [AddressLookup] falls back to pin,
 * GPS and the address typed by hand. One session token groups the keystrokes and the final details
 * request, as Google bills them; a new token starts after each chosen place.
 */
class GooglePlacesAutocomplete private constructor(private val client: PlacesClient) : AddressAutocomplete {
    private var token = AutocompleteSessionToken.newInstance()

    override suspend fun suggest(query: String): List<AddressSuggestion> {
        val request = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setSessionToken(token)
            .setCountries("ES")
            .setLocationBias(ASTURIAS_BOUNDS)
            .build()
        return client.findAutocompletePredictions(request).await().autocompletePredictions.map {
            AddressSuggestion(it.placeId, it.getPrimaryText(null).toString(), it.getSecondaryText(null).toString())
        }
    }

    override suspend fun details(suggestion: AddressSuggestion): PlaceDetails {
        val request = FetchPlaceRequest.builder(suggestion.placeId, listOf(Place.Field.ID, Place.Field.FORMATTED_ADDRESS, Place.Field.LOCATION, Place.Field.ADDRESS_COMPONENTS))
            .setSessionToken(token)
            .build()
        try {
            val place = client.fetchPlace(request).await().place
            val location = checkNotNull(place.location) { "place without location" }
            // Address components (same Essentials field group as the formatted address) split the street from
            // its number and locality, so the form shows the Spanish fields without repeating the number (#41).
            val components = place.addressComponents?.asList().orEmpty()
            fun component(type: String) = components.firstOrNull { type in it.types }?.name?.takeIf { it.isNotBlank() }
            val town = component("locality") ?: component("postal_town") ?: component("administrative_area_level_3")
            val province = component("administrative_area_level_2")?.takeIf { it != town }
            val locality = listOfNotNull(listOfNotNull(component("postal_code"), town).joinToString(" ").ifBlank { null }, province)
                .joinToString(", ").ifBlank { null }
            return PlaceDetails(place.formattedAddress ?: suggestion.primary, GeoPoint(location.latitude, location.longitude),
                place.id ?: suggestion.placeId, street = component("route"), number = component("street_number"),
                locality = locality)
        } finally {
            token = AutocompleteSessionToken.newInstance()
        }
    }

    companion object {
        /** Asturias, used only to rank suggestions; addresses elsewhere in Spain still appear. */
        private val ASTURIAS_BOUNDS = RectangularBounds.newInstance(LatLng(42.88, -7.19), LatLng(43.67, -4.51))

        /** Null without a key or if the SDK cannot start: the screen then offers the manual alternatives. */
        fun create(context: Context, apiKey: String): GooglePlacesAutocomplete? {
            if (apiKey.isBlank()) return null
            return runCatching {
                if (!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(context.applicationContext, apiKey)
                GooglePlacesAutocomplete(Places.createClient(context.applicationContext))
            }.getOrNull()
        }
    }
}
