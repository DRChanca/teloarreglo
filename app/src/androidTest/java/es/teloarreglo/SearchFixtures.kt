package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchOutcome

/** Fixtures shared by the search screen tests ([SearchUiTest], [SearchFiltersUiTest]). */

/** An approved plumber of Oviedo; [reviews] defaults to 3 when there is a [rating], none otherwise. */
internal fun fakeProfile(
    id: String, name: String, zone: Coverage = Coverage("33044", "Oviedo"), featured: Boolean = false,
    rating: Double? = null, reviews: Int = if (rating == null) 0 else 3,
) = PublicProfile(id, name, "d", listOf("plumbing"), listOf("Fugas"), listOf(zone),
    listOf("es"), "+34 000 000 000", listOf("plumbing"), emptyList(),
    rating = rating, reviewCount = reviews, featured = featured)

/** A catalogue that serves whatever [profiles] holds when asked, for every trade. */
internal class FakeCatalogue(private val profiles: () -> List<PublicProfile>) : Catalogue {
    override suspend fun list(trade: String?) = profiles()
    override suspend fun profile(id: String) = profiles().firstOrNull { it.id == id }
}

/** Answers each search with the next outcome; the last one repeats. Records what each search sent. */
internal class FakeSearch(vararg outcomes: SearchOutcome) : ProfessionalSearch {
    private val queue = ArrayDeque(outcomes.toList())
    val sentConcejos = mutableListOf<String?>()
    val sentPoints = mutableListOf<Pair<Double, Double>>()
    val sentTrades = mutableListOf<String?>()
    val calls get() = sentPoints.size

    override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
        sentConcejos += ineCode
        sentTrades += trade
        sentPoints += lat to lng
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}
