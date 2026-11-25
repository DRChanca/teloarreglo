package es.teloarreglo

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * The splash is animated at most once per process. `savedInstanceState == null` alone is not enough: after
 * leaving with «Atrás» the process lives on, and opening the app again creates the activity without saved
 * state, a warm start that must not repeat the animation (#57, story 15).
 */
private var splashSeen = false

/** Length of the exit: the icon shrinks and the splash fades into the app. */
private const val SPLASH_EXIT_MILLIS = 300L

/**
 * Installs the start splash (#58). Must run before `super.onCreate`. When it animates, the exit waits for
 * what is left of the animation since the splash was shown ([splashExitDelay]), never for data.
 */
fun Activity.installStartSplash(coldStart: Boolean) {
    val installedAt = SystemClock.uptimeMillis()
    val splash = installSplashScreen()
    val animatorScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val animates = splashAnimates(Build.VERSION.SDK_INT, animatorScale, coldStart = coldStart && !splashSeen)
    splashSeen = true
    if (!animates) return
    val animationMillis = resources.getInteger(R.integer.splash_animation_millis).toLong()
    splash.setOnExitAnimationListener { provider ->
        val shownAt = provider.iconAnimationStartMillis.takeIf { it > 0 } ?: installedAt
        val icon = provider.iconView
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 0.6f),
                ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 0.6f),
                ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f),
            )
            startDelay = splashExitDelay(shownAt, SystemClock.uptimeMillis(), animationMillis)
            duration = SPLASH_EXIT_MILLIS
            interpolator = FastOutSlowInInterpolator()
            doOnEnd { provider.remove() }
            start()
        }
    }
}
