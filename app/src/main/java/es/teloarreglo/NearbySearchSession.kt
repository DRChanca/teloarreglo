package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.Concejo
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchError
import es.teloarreglo.search.SearchOutcome
import kotlinx.coroutines.CancellationException

/**
 * One profile in the search order. [bandM]: rounded distance from the server, null when the profile has no
 * registered location. [inZone]: false when the concejo of the search is outside its work zone (ADR 0001).
 */
internal data class SearchRow(val profile: PublicProfile, val bandM: Int?, val inZone: Boolean)

/** What the proximity order shows. Positions are kept only for the current search. */
internal sealed interface NearbyState {
    data object Idle : NearbyState
    data object Locating : NearbyState
    data object Searching : NearbyState
    data object PermissionDenied : NearbyState
    data object LocationUnavailable : NearbyState
    data object Uncertain : NearbyState
    data object OutsideArea : NearbyState
    data class Confirm(val options: List<Concejo>) : NearbyState
    /** [featured]: «Destacado» of this concejo's zone, already placed first by the server (#33, ADR 0001). */
    data class Results(
        val concejo: Concejo, val rows: List<SearchRow>, val from: GeoPoint,
        val featured: Set<String> = emptySet(),
    ) : NearbyState
    data class Failed(val reason: SearchError) : NearbyState
}

/** Where a search starts from: the saved home (#37), one GPS fix, or a concejo chosen by hand. */
internal sealed interface SearchOrigin {
    data object Home : SearchOrigin
    data object Gps : SearchOrigin
    data class Manual(val concejo: String) : SearchOrigin
}

/** One search request without the trade. The position is only kept in memory for this screen. */
internal data class NearbyQuery(val origin: SearchOrigin, val point: GeoPoint, val ineCode: String?)

/**
 * Runs searches and remembers the last one (#47), so that «Reintentar» and a trade change repeat it
 * from the same origin and concejo instead of asking for GPS again.
 */
internal class NearbySearchSession(private val search: ProfessionalSearch, private val catalogue: Catalogue) {
    var last: NearbyQuery? = null
        private set

    suspend fun search(query: NearbyQuery, trade: String?): NearbyState {
        last = query
        return when (val outcome = search.search(query.point.lat, query.point.lng, trade, query.ineCode)) {
            is SearchOutcome.Found -> try {
                val profiles = catalogue.list(trade)
                NearbyState.Results(outcome.concejo, rows(outcome, profiles), query.point,
                    outcome.results.filter { it.featured }.mapTo(mutableSetOf()) { it.profileId })
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

    /**
     * The server order, then every published profile it could not place (no registered location) so that
     * none disappears from the only search; their zone is read from the public profile.
     */
    private fun rows(found: SearchOutcome.Found, profiles: List<PublicProfile>): List<SearchRow> {
        val byId = profiles.associateBy { it.id }
        val ranked = found.results.mapNotNull { r -> byId[r.profileId]?.let { SearchRow(it, r.distanceBandM, r.inZone) } }
        val placed = ranked.mapTo(mutableSetOf()) { it.profile.id }
        return ranked + profiles.filter { it.id !in placed }
            .map { p -> SearchRow(p, null, p.coverage.any { it.code == found.concejo.ineCode }) }
    }
}
