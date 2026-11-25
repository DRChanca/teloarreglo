package es.teloarreglo

import es.teloarreglo.identity.AccessError

/** A field of the access forms that an error can point at. */
enum class AccessField { NAME, EMAIL, PASSWORD }

/**
 * Where an access error is shown (#47): the fields marked as wrong and the one whose supporting text
 * explains it. With no field on screen to point at, it is a general message above the form.
 */
data class ErrorPlacement(val marked: Set<AccessField>, val explainedUnder: AccessField?) {
    companion object { val GENERAL = ErrorPlacement(emptySet(), null) }
}

/** [onScreen]: the fields the current form shows. The first field listed per error carries the text. */
fun AccessError.placement(onScreen: List<AccessField>): ErrorPlacement {
    val fields = when (this) {
        AccessError.CREDENTIALS -> listOf(AccessField.PASSWORD, AccessField.EMAIL)
        AccessError.INPUT -> listOf(AccessField.EMAIL, AccessField.PASSWORD)
        AccessError.WEAK_PASSWORD -> listOf(AccessField.PASSWORD)
        AccessError.UNVERIFIED -> listOf(AccessField.EMAIL)
        AccessError.NAME -> listOf(AccessField.NAME)
        else -> emptyList()
    }.filter { it in onScreen }
    return if (fields.isEmpty()) ErrorPlacement.GENERAL else ErrorPlacement(fields.toSet(), fields.first())
}
