package es.teloarreglo

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/** The splash is animated at most once per process: the first activity creation without saved state. */
private var splashSeen = false

/** Length of the exit: the icon shrinks and the splash fades into the app. */
private const val SPLASH_EXIT_MILLIS = 300L

/**
 * Installs the start splash (#58). Must run before `super.onCreate`. The hold is fixed by [splashPlan]
 * and counted from the process start, so it ends with the icon animation and never waits for data.
 */
fun Activity.installStartSplash(coldStart: Boolean) {
    val splash = installSplashScreen()
    val animatorScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val plan = splashPlan(Build.VERSION.SDK_INT, animatorScale, coldStart = coldStart && !splashSeen)
    splashSeen = true
    if (!plan.animates) return
    val holdUntil = Process.getStartUptimeMillis() + plan.holdMillis
    splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() < holdUntil }
    splash.setOnExitAnimationListener { provider ->
        val icon = provider.iconView
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 0.6f),
                ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 0.6f),
                ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f),
            )
            duration = SPLASH_EXIT_MILLIS
            interpolator = FastOutSlowInInterpolator()
            doOnEnd { provider.remove() }
            start()
        }
    }
}
