package es.teloarreglo.home

import kotlinx.serialization.Serializable

/**
 * A Spanish home address split into the fields people expect after the street (#41): número,
 * bloque, portal, escalera, piso and puerta, then postal code and locality. Only the street is
 * required. [text] is the single line that is stored as the home address and copied into an
 * appointment request; the parts are kept beside it so the form can be edited again.
 */
@Serializable
data class AddressParts(
    val street: String,
    val number: String = "",
    val block: String = "",
    val portal: String = "",
    val staircase: String = "",
    val floor: String = "",
    val door: String = "",
    /** Postal code, locality and province, e.g. «33003 Oviedo, Asturias». */
    val locality: String = "",
) {
    val valid: Boolean
        get() = street.isNotBlank() && street.trim().length <= MAX_STREET && locality.trim().length <= MAX_LOCALITY &&
            listOf(number, block, portal, staircase, floor, door).all { it.trim().length <= MAX_DETAIL }

    /** «Calle Uría 10, bloque 2, portal 3, esc. A, 4º B, 33003 Oviedo, Asturias»; empty fields are left out. */
    fun text(): String {
        val head = listOf(street.trim(), number.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        val floorText = floor.trim().let { if (it.isNotEmpty() && it.all(Char::isDigit)) "$it" + "º" else it }
        val floorAndDoor = when {
            floorText.isNotEmpty() -> listOf(floorText, door.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            door.isNotBlank() -> "puerta ${door.trim()}"
            else -> ""
        }
        return listOf(
            head,
            block.trim().let { if (it.isEmpty()) "" else "bloque $it" },
            portal.trim().let { if (it.isEmpty()) "" else "portal $it" },
            staircase.trim().let { if (it.isEmpty()) "" else "esc. $it" },
            floorAndDoor,
            locality.trim(),
        ).filter { it.isNotEmpty() }.joinToString(", ")
    }

    /** The same parts with surrounding spaces removed, as they are saved. */
    fun trimmed() = AddressParts(street.trim(), number.trim(), block.trim(), portal.trim(), staircase.trim(),
        floor.trim(), door.trim(), locality.trim())

    companion object {
        const val MAX_STREET = 160
        const val MAX_DETAIL = 20
        const val MAX_LOCALITY = 100

        /** A chosen place fills street, number and locality; without those details its whole address is the street. */
        fun from(place: PlaceDetails): AddressParts {
            val street = place.street?.takeIf { it.isNotBlank() } ?: return AddressParts(place.address)
            return AddressParts(street, number = place.number.orEmpty(), locality = place.locality.orEmpty())
        }
    }
}

/** What still stops the home from being saved (#47), so the disabled button can say why. */
enum class HomeGap { STREET, TOO_LONG, LOCATION }

/** In the order the form asks for them; empty when the home can be saved. Same rules as [AddressParts.valid]. */
fun homeSaveGaps(parts: AddressParts, hasPoint: Boolean): List<HomeGap> = buildList {
    if (parts.street.isBlank()) add(HomeGap.STREET)
    else if (!parts.valid) add(HomeGap.TOO_LONG)
    if (!hasPoint) add(HomeGap.LOCATION)
}
