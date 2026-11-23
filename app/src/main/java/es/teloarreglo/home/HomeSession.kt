package es.teloarreglo.home

import es.teloarreglo.search.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HomeStatus {
    /** Signed out, or not checked yet. */
    data object Unknown : HomeStatus
    /** The server confirmed this account has no home: the app asks for it before continuing. */
    data object Missing : HomeStatus
    data class Present(val home: HomeAddress) : HomeStatus
    /** Could not check (network): nothing is blocked; checked again on the next start. */
    data object Failed : HomeStatus
}

/**
 * The signed-in account's home for this session. Only a confirmed [HomeStatus.Missing] makes the
 * home step mandatory, so a network failure never locks the app. Changing account forgets the home
 * and lets "you are far from home" be asked again.
 */
class HomeSession(private val homes: HomeAddresses) {
    private val mutable = MutableStateFlow<HomeStatus>(HomeStatus.Unknown)
    val status: StateFlow<HomeStatus> = mutable.asStateFlow()
    private var owner: String? = null
    private val away = AwayFromHome()

    val required: Boolean get() = status.value == HomeStatus.Missing
    val home: HomeAddress? get() = (status.value as? HomeStatus.Present)?.home

    /** Loads the home of [userId] (null when signed out). */
    suspend fun load(userId: String?) {
        if (userId != owner) { owner = userId; away.reset(); mutable.value = HomeStatus.Unknown }
        if (userId == null) return
        val result = try { homes.current()?.let(HomeStatus::Present) ?: HomeStatus.Missing } catch (error: Exception) {
            if (error is CancellationException) throw error
            HomeStatus.Failed
        }
        // Ignore an answer that arrives after the account changed.
        if (owner == userId && (result != HomeStatus.Failed || mutable.value !is HomeStatus.Present)) mutable.value = result
    }

    /** Saves the home; failures propagate so the screen can show them and keep the draft. */
    suspend fun save(draft: HomeDraft): HomeAddress {
        val expected = owner
        return homes.save(draft).also { if (owner == expected) mutable.value = HomeStatus.Present(it) }
    }

    /** True the first time in this session that [here] is more than 5 km from home. */
    fun askIfAway(here: GeoPoint): Boolean = away.shouldAsk(home?.point, here)
}
