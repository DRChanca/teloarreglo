package es.teloarreglo.search

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class Concejo(@SerialName("ine_code") val ineCode: String, val name: String)

/** Never a coordinate or exact distance: the server returns a rounded band in metres. */
@Serializable
data class NearbyResult(
    @SerialName("profile_id") val profileId: String,
    @SerialName("distance_band_m") val distanceBandM: Int,
)

sealed interface SearchOutcome {
    data class Found(val concejo: Concejo, val results: List<NearbyResult>) : SearchOutcome
    /** Near a border or uncertain: the user chooses; the app never guesses. */
    data class ConfirmConcejo(val options: List<Concejo>) : SearchOutcome
    data object OutsideArea : SearchOutcome
    data class Failed(val reason: SearchError) : SearchOutcome
}

enum class SearchError { INPUT, UNKNOWN_CONCEJO, NETWORK, SERVICE }

/** Search by job location: coverage first, then geographic proximity (server side). */
interface ProfessionalSearch {
    suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String? = null, limit: Int = 20): SearchOutcome
}

@Serializable
private data class Reply(
    val status: String? = null,
    val error: String? = null,
    val concejo: Concejo? = null,
    val options: List<Concejo> = emptyList(),
    val results: List<NearbyResult> = emptyList(),
)

/** Calls the public Edge Function `search-professionals` with the publishable key. */
class HttpProfessionalSearch(
    supabaseUrl: String,
    private val publishableKey: String,
    private val http: HttpClient,
) : ProfessionalSearch {
    private val endpoint = supabaseUrl.trimEnd('/') + "/functions/v1/search-professionals"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(lat: Double, lng: Double, trade: String?, ineCode: String?, limit: Int): SearchOutcome {
        val body = buildJsonObject {
            put("lat", lat)
            put("lng", lng)
            if (trade != null) put("trade", trade)
            if (ineCode != null) put("ine_code", ineCode)
            put("limit", limit)
        }.toString()
        return try {
            val response = http.post(endpoint) {
                header("apikey", publishableKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val status = response.status.value
            val reply = runCatching { json.decodeFromString<Reply>(response.bodyAsText()) }.getOrNull()
            when {
                status == 200 && reply?.status == "ok" && reply.concejo != null ->
                    SearchOutcome.Found(reply.concejo, reply.results)
                status == 200 && reply?.status == "confirm_concejo" && reply.options.isNotEmpty() ->
                    SearchOutcome.ConfirmConcejo(reply.options)
                status == 200 && reply?.status == "outside_area" -> SearchOutcome.OutsideArea
                status == 400 && reply?.error == "unknown_concejo" -> SearchOutcome.Failed(SearchError.UNKNOWN_CONCEJO)
                status == 400 -> SearchOutcome.Failed(SearchError.INPUT)
                // 503, unexpected shapes and anything else: never an invented empty list.
                else -> SearchOutcome.Failed(SearchError.SERVICE)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            SearchOutcome.Failed(
                if (error is IOException || error is io.ktor.client.plugins.HttpRequestTimeoutException) SearchError.NETWORK
                else SearchError.SERVICE,
            )
        }
    }
}
