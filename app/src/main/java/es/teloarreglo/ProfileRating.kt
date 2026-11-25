package es.teloarreglo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** A Valoración drawn as stars: [full] whole stars and, after them, a half star when [half]. */
data class RatingStars(val full: Int, val half: Boolean)

/** Rounds a mean rating to the nearest half star, within 0 and 5 stars (#51). */
fun ratingStars(rating: Double): RatingStars {
    val halves = (rating * 2).roundToInt().coerceIn(0, 10)
    return RatingStars(full = halves / 2, half = halves % 2 == 1)
}

/**
 * A profile's Valoración (#51): [compact] «★ 4,5 (12)» for a search card, otherwise five stars rounded to the
 * half star plus mean and count. Without reviews it says «Sin reseñas». Read aloud as one «4,5 de 5, 12 reseñas».
 */
@Composable
fun ProfileRating(rating: Double?, reviewCount: Int, compact: Boolean, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (rating == null) {
        Text(stringResource(R.string.catalogue_no_reviews), modifier, style = style, color = muted)
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    val figures = "${String.format(locale, "%.1f", rating)} ($reviewCount)"
    val description = pluralStringResource(R.plurals.catalogue_rating, reviewCount, rating, reviewCount)
    val star = MaterialTheme.colorScheme.primary
    if (compact) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = star)) { append("★") }
                append(" $figures")
            },
            modifier.clearAndSetSemantics { contentDescription = description; text = AnnotatedString("★ $figures") },
            style = style, color = muted,
        )
        return
    }
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description; text = AnnotatedString(figures) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingStarRow(ratingStars(rating), star)
        Spacer(Modifier.width(TeloTheme.spacing.small))
        Text(figures, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

/** Size of a Valoración star, in sp so it follows the reader's font scale but not the screen's density (#66). */
private val StarSize = 18.sp

/**
 * Five stars in equal slots with the extraSmall gap (#66): [stars] full, then a half star, then empty ones.
 * Each star is tagged star_full/half/empty; the row adds no semantics, so its caller describes the rating.
 */
@Composable
internal fun RatingStarRow(stars: RatingStars, color: Color, modifier: Modifier = Modifier) {
    val size = with(LocalDensity.current) { StarSize.toDp() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TeloTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { index ->
            val fill = when {
                index < stars.full -> StarFill.FULL
                index == stars.full && stars.half -> StarFill.HALF
                else -> StarFill.EMPTY
            }
            Star(fill, color, size, Modifier.testTag("star_${fill.name.lowercase()}").clearAndSetSemantics {})
        }
    }
}

/** How much of one star is filled. */
internal enum class StarFill { FULL, HALF, EMPTY }

/**
 * One star drawn as a vector, so its width never depends on a font's glyphs: outlined, with its starting
 * half (or all of it) filled in [color].
 */
@Composable
internal fun Star(fill: StarFill, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(size)) {
        val path = starPath(this.size.minDimension)
        val stroke = this.size.minDimension / 12
        when (fill) {
            StarFill.FULL -> drawPath(path, color)
            StarFill.HALF -> {
                val half = this.size.width / 2
                clipRect(left = if (rtl) half else 0f, right = if (rtl) this.size.width else half) { drawPath(path, color) }
            }
            StarFill.EMPTY -> Unit
        }
        drawPath(path, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

/** A five-pointed star inscribed in a square of [side], point up, inset so its outline stays inside. */
private fun starPath(side: Float): Path {
    val center = side / 2
    val outer = side / 2 * 0.92f
    val inner = outer * 0.42f
    return Path().apply {
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / 5
            val x = center + (radius * cos(angle)).toFloat()
            val y = center + (radius * sin(angle)).toFloat() + side * 0.04f
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
