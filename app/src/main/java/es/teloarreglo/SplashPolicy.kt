package es.teloarreglo

/**
 * Whether the start splash animates (#58): only on a cold start on Android 12+ with system animations on
 * ([animatorScale] > 0). Otherwise there is no hold, and before Android 12 the icon is still.
 */
fun splashAnimates(sdkInt: Int, animatorScale: Float, coldStart: Boolean): Boolean =
    sdkInt >= 31 && animatorScale > 0f && coldStart

/**
 * How long the woodpecker still waits, once the app is ready ([now]), before flying in (#57): what is left of
 * [leadInMillis] since the activity was [createdAt], so a very fast start does not play the flight under the
 * system launch transition. A slow start never waits; it never depends on data.
 */
fun splashFlightDelay(createdAt: Long, now: Long, leadInMillis: Long): Long =
    (createdAt + leadInMillis - now).coerceIn(0, leadInMillis)
