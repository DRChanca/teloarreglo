package es.teloarreglo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Colours with no Material 3 role, each with a light and a dark value (#45). */
@Immutable
internal data class TeloExtendedColors(
    /** Raised cards over the background: white in light, a lifted surface in dark. Text uses onSurface. */
    val card: Color,
    /** The two ticks of a read message, drawn over the own bubble (primaryContainer). */
    val readTick: Color,
    /** Border of a «Perfil destacado» card in the catalogue: brand green in light, purple in dark. */
    val featuredBorder: Color,
    val featuredBadge: Color,
    val onFeaturedBadge: Color,
    val subscriptionContainer: Color,
    val onSubscriptionContainer: Color,
    /** Gold of the filled part of a Valoración star on a search card (#67); 3:1 against both kinds of card. */
    val ratingStar: Color,
    /** Grey of the unfilled part of those stars (#67), at least 2:1 from the gold. */
    val ratingStarEmpty: Color,
)

@Immutable
internal data class TeloColors(val scheme: ColorScheme, val extended: TeloExtendedColors)

private val LightColors = TeloColors(
    // Unchanged light look: unset roles keep the Material 3 baseline values the app always had.
    scheme = lightColorScheme(
        primary = Color(0xFF246451),
        onPrimary = Color.White,
        secondary = Color(0xFF4D665A),
        secondaryContainer = Color(0xFFE2EFE5),
        onSecondaryContainer = Color(0xFF173E2E),
        background = Color(0xFFF7F8F5),
        surface = Color(0xFFF7F8F5),
        surfaceContainer = Color(0xFFF0F2EC),
    ),
    extended = TeloExtendedColors(
        card = Color.White,
        readTick = Color(0xFF00629E),
        featuredBorder = Color(0xFF246451),
        featuredBadge = Color(0xFF8A5A00),
        onFeaturedBadge = Color.White,
        subscriptionContainer = Color(0xFFF4F1E8),
        onSubscriptionContainer = Color(0xFF1C1B1F),
        ratingStar = Color(0xFFB27B00),
        ratingStarEmpty = Color(0xFFBEC4C0),
    ),
)

// Dark tones derived from the brand green #246451 following the Material 3 tonal rules
// (primary tone 80, containers tone 30, neutral surfaces tinted with the same hue).
private val DarkColors = TeloColors(
    scheme = darkColorScheme(
        primary = Color(0xFF8FD5BA),
        onPrimary = Color(0xFF00382A),
        primaryContainer = Color(0xFF0B513F),
        onPrimaryContainer = Color(0xFFABF2D5),
        inversePrimary = Color(0xFF246451),
        secondary = Color(0xFFB3CCC0),
        onSecondary = Color(0xFF1F352C),
        secondaryContainer = Color(0xFF354B42),
        onSecondaryContainer = Color(0xFFCFE9DC),
        tertiary = Color(0xFFA7CCE1),
        onTertiary = Color(0xFF0C3445),
        tertiaryContainer = Color(0xFF264B5C),
        onTertiaryContainer = Color(0xFFC2E8FD),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0F1512),
        onBackground = Color(0xFFDEE4DF),
        surface = Color(0xFF0F1512),
        onSurface = Color(0xFFDEE4DF),
        surfaceVariant = Color(0xFF3F4945),
        onSurfaceVariant = Color(0xFFBFC9C3),
        surfaceTint = Color(0xFF8FD5BA),
        inverseSurface = Color(0xFFDEE4DF),
        inverseOnSurface = Color(0xFF2C322F),
        outline = Color(0xFF89938E),
        outlineVariant = Color(0xFF3F4945),
        scrim = Color.Black,
        surfaceBright = Color(0xFF353B38),
        surfaceDim = Color(0xFF0F1512),
        surfaceContainerLowest = Color(0xFF0A0F0D),
        surfaceContainerLow = Color(0xFF171D1A),
        surfaceContainer = Color(0xFF1B211E),
        surfaceContainerHigh = Color(0xFF252B28),
        surfaceContainerHighest = Color(0xFF303633),
    ),
    extended = TeloExtendedColors(
        card = Color(0xFF1B211E),
        readTick = Color(0xFF8FD0FF),
        featuredBorder = Color(0xFFCFBCFF),
        featuredBadge = Color(0xFFF2BE5C),
        onFeaturedBadge = Color(0xFF3F2A00),
        subscriptionContainer = Color(0xFF2B2820),
        onSubscriptionContainer = Color(0xFFE9E2D3),
        ratingStar = Color(0xFFF2BE5C),
        ratingStarEmpty = Color(0xFF56605B),
    ),
)

/** The palette for each system mode: plain data, so its contrast is checked on the JVM. */
internal fun teloColors(dark: Boolean): TeloColors = if (dark) DarkColors else LightColors

/**
 * Title roles (#47): headlines and titleLarge are bold here, so screens set only the style.
 * - Screen title: headlineSmall (headlineMedium for the profile name on its own page).
 * - Section title (a block or card inside a screen): titleLarge; a smaller card heading: titleMedium.
 * - Item name in a list: titleMedium. Metadata: bodySmall / labelMedium.
 * Mark screen and section titles with Modifier.semantics { heading() }.
 */
internal val teloTypography: Typography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
    )
}

/**
 * Card corners (#47): large for section cards inside a screen, extraLarge for the main card of a form.
 * Other roles keep the Material 3 values (a default Card uses medium, 12 dp).
 */
internal val teloShapes = Shapes(
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** One spacing scale for paddings and gaps (#47): 4, 8, 12, 16, 24 dp; [screen] is the side gutter. */
@Immutable
internal data class TeloSpacing(
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val extraLarge: Dp = 24.dp,
    /** Horizontal padding between a screen's content and the window edges. */
    val screen: Dp = 16.dp,
)

private val LocalTeloExtendedColors = staticCompositionLocalOf { LightColors.extended }
private val LocalTeloSpacing = staticCompositionLocalOf { TeloSpacing() }

/** Applied once at the activity root; follows the system light/dark mode (no in-app selector). */
@Composable
internal fun TeloTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = teloColors(dark)
    CompositionLocalProvider(LocalTeloExtendedColors provides colors.extended, LocalTeloSpacing provides TeloSpacing()) {
        MaterialTheme(colorScheme = colors.scheme, typography = teloTypography, shapes = teloShapes, content = content)
    }
}

internal object TeloTheme {
    val extended: TeloExtendedColors
        @Composable @ReadOnlyComposable get() = LocalTeloExtendedColors.current
    val spacing: TeloSpacing
        @Composable @ReadOnlyComposable get() = LocalTeloSpacing.current
}
