package es.teloarreglo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.LayoutDirection
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
    val stars = ratingStars(rating)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description; text = AnnotatedString(figures) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { index ->
            val kind = when {
                index < stars.full -> "full"
                index == stars.full && stars.half -> "half"
                else -> "empty"
            }
            Star(kind, star)
        }
        Spacer(Modifier.width(TeloTheme.spacing.small))
        Text(figures, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

/** One star: filled, empty, or an empty star with its starting half filled. */
@Composable
private fun Star(kind: String, color: Color) {
    val style = MaterialTheme.typography.titleMedium
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.testTag("star_$kind").clearAndSetSemantics {}) {
        Text(if (kind == "full") "★" else "☆", style = style, color = color)
        if (kind == "half") Text("★", Modifier.drawWithContent {
            val half = size.width / 2
            clipRect(left = if (rtl) half else 0f, right = if (rtl) size.width else half) { this@drawWithContent.drawContent() }
        }, style = style, color = color)
    }
}
