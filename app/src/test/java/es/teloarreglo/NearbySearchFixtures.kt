package es.teloarreglo

import es.teloarreglo.catalogue.Catalogue
import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.search.GeoPoint
import es.teloarreglo.search.ProfessionalSearch
import es.teloarreglo.search.SearchOutcome

/** Fixtures shared by the nearby search unit tests ([NearbySearchSessionTest], [AppSearchSessionTest]). */

/** What one search sent to the server. */
internal data class Sent(val point: GeoPoint, val trade: String?, val ineCode: String?)

/** Answers each search with the next outcome; the last one repeats. Records what each search sent. */
internal class FakeSearch(vararg outcomes: SearchOutcome) : ProfessionalSearch {
    private val queue = ArrayDeque(outcomes.toList())
    val sent = mutableListOf<Sent>()
    override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
        sent += Sent(GeoPoint(lat, lng), trade, ineCode)
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

/** A catalogue with a single plumber of Oviedo, "p1". */
internal val onePlumberCatalogue = object : Catalogue {
    override suspend fun list(trade: String?) = listOf(PublicProfile(
        "p1", "Fontanería Nalón", "d", listOf("plumbing"), listOf("Fugas"), listOf(Coverage("33044", "Oviedo")),
        listOf("es"), "+34 000 000 000", listOf("plumbing"), emptyList()))
    override suspend fun profile(id: String) = null
}
