package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether the start splash animates and when the woodpecker flies in (#57, #58). */
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

    @Test fun theFlightWaitsOnlyUntilTheLaunchTransitionHasHadTimeToEnd() {
        // Created at 5000 ms with a 400 ms lead-in: ready at 5100 → flies at 5400; ready late → flies at once.
        assertEquals(300, splashFlightDelay(createdAt = 5_000, now = 5_100, leadInMillis = 400))
        assertEquals(0, splashFlightDelay(createdAt = 5_000, now = 6_400, leadInMillis = 400))
        assertEquals(400, splashFlightDelay(createdAt = 5_000, now = 5_000, leadInMillis = 400))
    }
}
