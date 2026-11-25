package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether the start splash animates and how long it is held (#58). */
class SplashPolicyTest {
    @Test fun aColdStartOnAndroid12WithAnimationsAnimates() {
        assertTrue(splashAnimates(sdkInt = 31, animatorScale = 1f, coldStart = true))
    }

    @Test fun androidBefore12KeepsTheIconStill() {
        assertFalse(splashAnimates(sdkInt = 30, animatorScale = 1f, coldStart = true))
        assertFalse(splashAnimates(sdkInt = 26, animatorScale = 1f, coldStart = true))
    }

    @Test fun removedAnimationsMeanNoAnimation() {
        assertFalse(splashAnimates(sdkInt = 34, animatorScale = 0f, coldStart = true))
    }

    @Test fun comingBackWithoutAColdStartDoesNotRepeatTheAnimation() {
        assertFalse(splashAnimates(sdkInt = 34, animatorScale = 0.5f, coldStart = false))
    }

    @Test fun theExitWaitsOnlyForWhatIsLeftOfTheAnimationSinceItWasShown() {
        // Shown at 5000 ms for 1000 ms: ready at 5300 → 700 ms more; ready late → no wait at all.
        assertEquals(700, splashExitDelay(shownAt = 5_000, now = 5_300, animationMillis = 1_000))
        assertEquals(0, splashExitDelay(shownAt = 5_000, now = 6_400, animationMillis = 1_000))
        assertEquals(1_000, splashExitDelay(shownAt = 5_000, now = 5_000, animationMillis = 1_000))
    }
}
