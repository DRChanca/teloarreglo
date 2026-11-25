package es.teloarreglo

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import es.teloarreglo.appointments.Review
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** One star's left edge and width, in dp. */
data class StarBox(val left: Float, val width: Float)

/** Stars keep the same width and the same gap between them, whatever glyph, fill or device density (#66). */
class RatingStarRowUiTest {
    @get:Rule val compose = createComposeRule()

    private val review = Review("r1", "p1", stars = 3, comment = "Bien", createdAt = Instant.parse("2026-11-01T10:00:00Z"))

    /** Shows [content] as on a device of [density] (px per dp), with the default font scale. */
    private fun showOn(density: Float, content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(density, 1f)) { content() } }

    /** Every star left to right, converted to dp with the [density] it was drawn at. */
    private fun starBoxes(density: Float): List<StarBox> =
        listOf("full", "half", "empty").flatMap {
            compose.onAllNodes(hasTestTag("star_$it"), useUnmergedTree = true).fetchSemanticsNodes()
        }.map { StarBox(it.boundsInRoot.left / density, it.boundsInRoot.width / density) }.sortedBy { it.left }

    /** The single gap between stars, after checking there are five of the same width, evenly spaced. */
    private fun evenGap(boxes: List<StarBox>): Float {
        assertEquals(5, boxes.size)
        boxes.forEach { assertEquals("widths $boxes", boxes.first().width, it.width, 0.5f) }
        val gaps = boxes.zipWithNext { a, b -> b.left - (a.left + a.width) }
        gaps.forEach { assertEquals("gaps $gaps", gaps.first(), it, 0.5f) }
        return gaps.first()
    }

    @Test fun aReviewDrawsFiveEvenlySpacedStars() {
        showOn(2f) { Column { ReviewBody(review) } }
        evenGap(starBoxes(2f))
    }

    @Test fun aProfileRatingWithAHalfStarIsEvenlySpaced() {
        showOn(2f) { ProfileRating(2.5, reviewCount = 4, compact = false) }
        evenGap(starBoxes(2f))
    }

    /** A small, low-density phone and a large, high-density one draw the same gap in dp: 4 dp, the extraSmall step. */
    @Test fun theGapIsTheSameOnSmallAndLargeDevices() {
        showOn(1.5f) { ProfileRating(3.5, reviewCount = 4, compact = false) }
        assertEquals(4f, evenGap(starBoxes(1.5f)), 0.5f)
    }

    @Test fun theGapIsTheSameOnAHighDensityDevice() {
        showOn(3.5f) { ProfileRating(3.5, reviewCount = 4, compact = false) }
        assertEquals(4f, evenGap(starBoxes(3.5f)), 0.5f)
    }
}
