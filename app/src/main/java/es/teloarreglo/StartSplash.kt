package es.teloarreglo

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.core.view.doOnPreDraw

/**
 * The splash is animated at most once per process. `savedInstanceState == null` alone is not enough: after
 * leaving with «Atrás» the process lives on, and opening the app again creates the activity without saved
 * state, a warm start that must not repeat the animation (#57, story 15).
 */
private var splashSeen = false

/**
 * Installs the start splash (#57, #58). Must run before `super.onCreate`. The system splash only shows the
 * still trunk; when it animates, the app flies the woodpecker in over it once the app is ready and the splash
 * is on screen, so the system launch transition cannot hide the flight. It never waits for data.
 */
fun Activity.installStartSplash(coldStart: Boolean) {
    val createdAt = SystemClock.uptimeMillis()
    val splash = installSplashScreen()
    val animatorScale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val animates = splashAnimates(Build.VERSION.SDK_INT, animatorScale, coldStart = coldStart && !splashSeen)
    splashSeen = true
    if (!animates) return
    splash.setOnExitAnimationListener { provider -> playWoodpeckerAndLeave(provider, createdAt) }
}

/**
 * Replaces the splash icon with the woodpecker drawable (avd_woodpecker: the same masked trunk plus the bird),
 * plays the flight and the pecks, then shrinks the icon and fades the splash into the app.
 *
 * The splash icon view shows the central 72 units of the 108-unit icon (icon x and y 18..90); the woodpecker
 * drawable covers icon x -56..90 and y 18..90, so it is laid out at the same scale, 74 units to the left.
 * The system icon is hidden only after the drawable has been drawn on top of it, so nothing flickers.
 */
private fun Activity.playWoodpeckerAndLeave(provider: SplashScreenViewProvider, createdAt: Long) {
    val splashView = provider.view as? ViewGroup ?: return provider.remove()
    // The system may hand over a splash without an icon (some launchers, instrumentation): then it just leaves.
    val icon = try { provider.iconView } catch (_: NullPointerException) { null } ?: return provider.remove()
    val unit = icon.width / 72f
    val iconAt = IntArray(2).also(icon::getLocationInWindow)
    val splashAt = IntArray(2).also(splashView::getLocationInWindow)
    val woodpecker = ImageView(this).apply {
        setImageResource(R.drawable.avd_woodpecker)
        scaleType = ImageView.ScaleType.FIT_XY
        layoutParams = FrameLayout.LayoutParams((146 * unit).toInt(), icon.height).apply {
            leftMargin = iconAt[0] - splashAt[0] - (74 * unit).toInt()
            topMargin = iconAt[1] - splashAt[1]
        }
        // Shrinks around the centre of the icon (icon x 54).
        pivotX = 110 * unit
        pivotY = icon.height / 2f
    }
    splashView.addView(woodpecker)
    woodpecker.doOnPreDraw { woodpecker.post { icon.visibility = View.INVISIBLE } }

    val animationMillis = resources.getInteger(R.integer.splash_animation_millis).toLong()
    val exitMillis = resources.getInteger(R.integer.splash_exit_millis).toLong()
    val leadInMillis = resources.getInteger(R.integer.splash_lead_in_millis).toLong()
    splashView.postDelayed({
        (woodpecker.drawable as Animatable).start()
        // The exit, like the woodpecker, can run on the render thread (no listeners or actions), so it stays
        // smooth while the app is busy with its first screen. A twin animator with the same timing (and the
        // same animator duration scale) removes the splash once it has faded.
        val curve = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        woodpecker.animate().scaleX(0.6f).scaleY(0.6f)
            .setStartDelay(animationMillis).setDuration(exitMillis).setInterpolator(curve).start()
        splashView.animate().alpha(0f)
            .setStartDelay(animationMillis).setDuration(exitMillis).setInterpolator(curve).start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = animationMillis
            duration = exitMillis
            doOnEnd { provider.remove() }
            start()
        }
    }, splashFlightDelay(createdAt, SystemClock.uptimeMillis(), leadInMillis))
}
