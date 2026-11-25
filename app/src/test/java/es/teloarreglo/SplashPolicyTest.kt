package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Whether the start splash animates and how long it is held (#58). */
class SplashPolicyTest {
    @Test fun aColdStartOnAndroid12WithAnimationsAnimatesForOneSecond() {
        assertEquals(SplashPlan(animates = true, holdMillis = 1000), splashPlan(sdkInt = 31, animatorScale = 1f, coldStart = true))
    }

    @Test fun androidBefore12KeepsTheIconStillAndDoesNotHold() {
        assertEquals(SplashPlan(animates = false, holdMillis = 0), splashPlan(sdkInt = 30, animatorScale = 1f, coldStart = true))
        assertEquals(SplashPlan(animates = false, holdMillis = 0), splashPlan(sdkInt = 26, animatorScale = 1f, coldStart = true))
    }

    @Test fun removedAnimationsMeanNoAnimationAndNoWait() {
        assertEquals(SplashPlan(animates = false, holdMillis = 0), splashPlan(sdkInt = 34, animatorScale = 0f, coldStart = true))
    }

    @Test fun comingBackWithoutAColdStartDoesNotRepeatTheAnimation() {
        assertEquals(SplashPlan(animates = false, holdMillis = 0), splashPlan(sdkInt = 34, animatorScale = 0.5f, coldStart = false))
    }
}
