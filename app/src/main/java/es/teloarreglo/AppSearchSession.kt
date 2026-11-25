package es.teloarreglo

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.home.HomeAddress
import es.teloarreglo.identity.AccessStage
import es.teloarreglo.identity.AccessState
import es.teloarreglo.search.Located
import es.teloarreglo.search.ProfessionalSearch
import kotlinx.coroutines.CoroutineScope

/**
 * The search of the app (#59), kept with the home session for as long as the process lives: it starts the
 * default search (every trade, from home, without GPS) as soon as the account and its home are known, so
 * the search screen usually finds it ordered, and leaving the screen does not lose it. No retries or caches.
 */
class AppSearchSession internal constructor(
    search: ProfessionalSearch, private val catalogue: Catalogue, private val scope: CoroutineScope,
) {
    /** Without the search screen there is no GPS nor permission prompt: only the home is used. */
    private val homeOnly = ProximitySource(search, ensurePermission = { false }, locate = { Located.Unavailable })
    private var user: String? = null

    /** The search the screen shows; a new account gets a new one. */
    internal var proximity by mutableStateOf(Proximity(homeOnly, catalogue, scope))
        private set

    /** The trade chosen on the search screen, kept with the search so that returning to the tab keeps it. */
    val trade: MutableState<String?> = object : MutableState<String?> {
        override var value: String?
            get() = proximity.trade
            set(next) = proximity.changeTrade(next)
        override fun component1() = value
        override fun component2(): (String?) -> Unit = { value = it }
    }

    /**
     * Follows the [access] state and the account's [home]. Ignored while the session is being checked (every
     * return to the foreground checks it again). A new account, or signing out, forgets the previous search
     * and trade; a client account with a home searches from it once. Administrators never search.
     */
    fun onAccess(access: AccessState, home: HomeAddress?) {
        if (access.stage == AccessStage.CHECKING) return
        val account = access.account?.takeIf { access.stage == AccessStage.CONNECTED }
        if (account?.userId != user) {
            user = account?.userId
            proximity.stop()
            proximity = Proximity(homeOnly, catalogue, scope)
        }
        if (account != null && !account.isAdministrator && home != null) proximity.ensureFromHome(home)
    }
}
