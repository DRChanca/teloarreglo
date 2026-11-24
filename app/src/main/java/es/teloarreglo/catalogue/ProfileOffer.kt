package es.teloarreglo.catalogue

import es.teloarreglo.appointments.parseEuroCents
import kotlinx.serialization.Serializable

/**
 * Services and reference prices the owner publishes directly, without review (David, 2026-11-23).
 * The editor keeps amounts as typed text; only complete rows are sent, in cents. The server checks
 * the same limits and has the last word.
 */
val PRICE_UNITS = listOf("hour", "sqm", "unit", "job")
const val MAX_SERVICES = 30
const val MAX_PRICES = 20

fun parseServices(text: String): List<String> =
    text.lines().map(String::trim).filter(String::isNotEmpty).map { it.take(120) }.take(MAX_SERVICES)

@Serializable
data class PriceDraft(
    val service: String = "",
    val scope: String = "",
    val unit: String = "job",
    val amount: String = "",
    val includes: String = "",
    val excludes: String = "",
)

fun ReferencePrice.toDraft() = PriceDraft(service, scope, unit,
    (amountCents / 100).toString() + if (amountCents % 100 == 0) "" else ",%02d".format(amountCents % 100),
    includes, excludes)

private fun PriceDraft.toPrice(): ReferencePrice? {
    val cents = parseEuroCents(amount) ?: return null
    if (service.isBlank() || unit !in PRICE_UNITS) return null
    return ReferencePrice(service.trim().take(120), scope.trim().take(300), unit, cents,
        includes.trim().take(300), excludes.trim().take(300))
}

/** All rows as prices, or null while any row is incomplete. */
fun List<PriceDraft>.toPrices(): List<ReferencePrice>? = map { it.toPrice() ?: return null }
