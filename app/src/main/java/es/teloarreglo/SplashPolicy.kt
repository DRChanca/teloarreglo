package es.teloarreglo

/**
 * Whether the start splash animates (#58): only on a cold start on Android 12+ with system animations on
 * ([animatorScale] > 0). Otherwise there is no hold, and before Android 12 the icon is still.
 */
fun splashAnimates(sdkInt: Int, animatorScale: Float, coldStart: Boolean): Boolean =
    sdkInt >= 31 && animatorScale > 0f && coldStart

/**
 * How long an animated splash still stays once the app is ready ([now]): what is left of the animation of
 * [animationMillis] since it was [shownAt], so it always plays in full. It never depends on data.
 */
fun splashExitDelay(shownAt: Long, now: Long, animationMillis: Long): Long =
    (shownAt + animationMillis - now).coerceIn(0, animationMillis)
