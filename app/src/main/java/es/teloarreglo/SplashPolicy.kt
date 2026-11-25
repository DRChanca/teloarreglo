package es.teloarreglo

/** What the start splash does (#58): whether its icon animates and how long it stays on screen. */
data class SplashPlan(val animates: Boolean, val holdMillis: Long)

/** Length of the woodpecker animation; the splash API advises not to exceed about 1000 ms. */
const val SPLASH_ANIMATION_MILLIS = 1000L

/**
 * Animates and holds for a fixed time only on a cold start on Android 12+ with system animations on
 * ([animatorScale] > 0). Otherwise there is no hold, and before Android 12 the icon is still.
 * The hold never depends on data being loaded.
 */
fun splashPlan(sdkInt: Int, animatorScale: Float, coldStart: Boolean): SplashPlan =
    if (sdkInt >= 31 && animatorScale > 0f && coldStart) SplashPlan(animates = true, holdMillis = SPLASH_ANIMATION_MILLIS)
    else SplashPlan(animates = false, holdMillis = 0)
