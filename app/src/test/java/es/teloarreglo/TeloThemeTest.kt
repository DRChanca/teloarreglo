package es.teloarreglo

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** #45: every text/background pair of both palettes is readable (WCAG AA, 4.5:1). */
class TeloThemeTest {
    @Test
    fun everyTextPairMeetsAaInTheLightTheme() = assertReadable(teloColors(dark = false), "light")

    @Test
    fun everyTextPairMeetsAaInTheDarkTheme() = assertReadable(teloColors(dark = true), "dark")

    @Test
    fun theLightThemeKeepsTheBrandColours() {
        val light = teloColors(dark = false).scheme
        assertEquals(Color(0xFF246451), light.primary)
        assertEquals(Color(0xFFF7F8F5), light.background)
    }

    @Test
    fun theDarkThemeHasADarkBackground() {
        val dark = teloColors(dark = true).scheme
        assertTrue("background must be dark", luminance(dark.background) < 0.05)
        assertTrue("surface must be dark", luminance(dark.surface) < 0.05)
    }

    @Test
    fun theFeaturedBorderIsBrandGreenInLightAndPurpleInDark() {
        assertEquals(Color(0xFF246451), teloColors(dark = false).extended.featuredBorder)
        val border = teloColors(dark = true).extended.featuredBorder
        assertTrue("dark featured border must be purple: $border", border.blue > border.green && border.red > border.green)
    }

    @Test
    fun theFeaturedBorderStandsOutFromItsCardInBothThemes() {
        for (dark in listOf(false, true)) {
            val colors = teloColors(dark)
            val ratio = contrast(colors.extended.featuredBorder, colors.scheme.secondaryContainer)
            // WCAG non-text contrast (1.4.11): 3:1 against the adjacent colour.
            assertTrue("featured border vs card ${if (dark) "dark" else "light"}: $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun screenAndSectionTitlesAreBoldWithoutEachScreenAskingForIt() {
        val type = teloTypography
        for ((role, style) in mapOf(
            "headlineLarge" to type.headlineLarge, "headlineMedium" to type.headlineMedium,
            "headlineSmall" to type.headlineSmall, "titleLarge" to type.titleLarge,
        )) assertEquals(role, FontWeight.Bold, style.fontWeight)
    }

    @Test
    fun spacingFollowsOneScaleOf4_8_12_16_24() {
        val s = TeloSpacing()
        assertEquals(listOf(4.dp, 8.dp, 12.dp, 16.dp, 24.dp), listOf(s.extraSmall, s.small, s.medium, s.large, s.extraLarge))
        assertEquals("screen gutter", 16.dp, s.screen)
    }

    @Test
    fun sectionCardsAndMainCardsHaveTheirOwnCorners() {
        assertEquals(RoundedCornerShape(16.dp), teloShapes.large)
        assertEquals(RoundedCornerShape(24.dp), teloShapes.extraLarge)
    }

    private fun assertReadable(colors: TeloColors, theme: String) {
        val s = colors.scheme
        val x = colors.extended
        val pairs = mapOf(
            "onBackground/background" to (s.onBackground to s.background),
            "onSurface/surface" to (s.onSurface to s.surface),
            "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
            "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
            "onSurface/surfaceContainerLowest" to (s.onSurface to s.surfaceContainerLowest),
            "onSurface/surfaceContainerLow" to (s.onSurface to s.surfaceContainerLow),
            "onSurface/surfaceContainer" to (s.onSurface to s.surfaceContainer),
            "onSurface/surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
            "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
            "primary/surface" to (s.primary to s.surface),
            "onPrimary/primary" to (s.onPrimary to s.primary),
            "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "error/surface" to (s.error to s.surface),
            "onError/error" to (s.onError to s.error),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
            "primary/secondaryContainer" to (s.primary to s.secondaryContainer),
            "onSurface/card" to (s.onSurface to x.card),
            "primary/card" to (s.primary to x.card),
            "error/card" to (s.error to x.card),
            "readTick/primaryContainer" to (x.readTick to s.primaryContainer),
            "onFeaturedBadge/featuredBadge" to (x.onFeaturedBadge to x.featuredBadge),
            "onSubscriptionContainer/subscriptionContainer" to (x.onSubscriptionContainer to x.subscriptionContainer),
        )
        val failing = pairs.mapValues { (_, pair) -> contrast(pair.first, pair.second) }.filterValues { it < 4.5 }
        assertTrue("$theme theme below AA: $failing", failing.isEmpty())
    }

    // WCAG 2.x relative luminance and contrast ratio, written from the spec.
    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (light + 0.05) / (dark + 0.05)
    }
}
