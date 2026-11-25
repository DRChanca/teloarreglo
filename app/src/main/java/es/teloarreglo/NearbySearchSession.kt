package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import kotlinx.coroutines.CancellationException

/** What the nearby search shows. Positions are kept only for the current search. */
internal sealed interface NearbyState {
    data object Idle : NearbyState
    data object Locating : NearbyState
    data object Searching : NearbyState
    data object PermissionDenied : NearbyState
    data object LocationUnavailable : NearbyState
    data object Uncertain : NearbyState
    data object OutsideArea : NearbyState
    data class Confirm(val options: List<Concejo>) : NearbyState
    /** [featured]: «Destacado» profile ids, already placed first by the server (#33). */
    data class Results(
        val concejo: Concejo, val rows: List<Pair<PublicProfile, Int>>, val from: GeoPoint,
        val featured: Set<String> = emptySet(),
    ) : NearbyState
    data class Failed(val reason: SearchError) : NearbyState
}

/** Where a nearby search starts from: the saved home (#37), one GPS fix, or a concejo chosen by hand. */
internal sealed interface SearchOrigin {
    data object Home : SearchOrigin
    data object Gps : SearchOrigin
    data class Manual(val concejo: String) : SearchOrigin
}

/** One search request without the trade. The position is only kept in memory for this screen. */
internal data class NearbyQuery(val origin: SearchOrigin, val point: GeoPoint, val ineCode: String?)

/**
 * Runs nearby searches and remembers the last one (#47), so that «Reintentar» and a trade change repeat it
 * from the same origin and concejo instead of asking for GPS again.
 */
internal class NearbySearchSession(private val search: ProfessionalSearch, private val catalogue: Catalogue) {
    var last: NearbyQuery? = null
        private set

    suspend fun search(query: NearbyQuery, trade: String?): NearbyState {
        last = query
        return when (val outcome = search.search(query.point.lat, query.point.lng, trade, query.ineCode)) {
            is SearchOutcome.Found -> try {
                val byId = catalogue.list(trade).associateBy { it.id }
                NearbyState.Results(outcome.concejo, outcome.results.mapNotNull { r -> byId[r.profileId]?.let { it to r.distanceBandM } },
                    query.point, outcome.results.filter { it.featured }.mapTo(mutableSetOf()) { it.profileId })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                NearbyState.Failed(SearchError.SERVICE)
            }
            is SearchOutcome.ConfirmConcejo -> NearbyState.Confirm(outcome.options)
            SearchOutcome.OutsideArea -> NearbyState.OutsideArea
            is SearchOutcome.Failed -> NearbyState.Failed(outcome.reason)
        }
    }

    /** The last search again, with [trade]; null when nothing was searched yet. */
    suspend fun repeat(trade: String?): NearbyState? = last?.let { search(it, trade) }

    /** The concejo the user chose at a border, from the same position and origin. */
    suspend fun confirm(ineCode: String, trade: String?): NearbyState? = last?.let { search(it.copy(ineCode = ineCode), trade) }
}
