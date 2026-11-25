package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.search.Located
import es.teloarreglo.search.ProfessionalSearch
import kotlinx.coroutines.CoroutineScope

/**
 * The search of the app (#59), kept with the home session for as long as the process lives: it starts the
 * default search (every trade, from home, without GPS) as soon as the account and its home are known, so
 * the search screen usually finds it ordered, and leaving the screen does not lose it. No retries or caches.
 */
class AppSearchSession internal constructor(search: ProfessionalSearch, catalogue: Catalogue, scope: CoroutineScope) {
    /** Without the search screen there is no GPS nor permission prompt: only the home is used. */
    internal val base = ProximitySource(search, ensurePermission = { false }, locate = { Located.Unavailable })
    internal val proximity = Proximity(base, catalogue, scope)
    private var user: String? = null

    /**
     * Follows the connected account ([userId] null for visitors). A new account forgets the previous search;
     * a client account with a [home] searches from it once. Administrators never search.
     */
    fun onAccount(userId: String?, administrator: Boolean, home: HomeAddress?) {
        if (userId != user) { user = userId; proximity.reset() }
        if (userId != null && !administrator && home != null) proximity.ensureFromHome(home)
    }
}
