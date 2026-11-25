package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Test

/** A profile's Valoración is drawn rounded to the nearest half star (#51). */
class RatingStarsTest {
    @Test fun roundsToTheNearestHalfStar() {
        assertEquals(RatingStars(full = 4, half = true), ratingStars(4.5))
        assertEquals(RatingStars(full = 4, half = true), ratingStars(4.3))
        assertEquals(RatingStars(full = 4, half = false), ratingStars(4.2))
        assertEquals(RatingStars(full = 5, half = false), ratingStars(4.8))
        assertEquals(RatingStars(full = 3, half = true), ratingStars(3.74))
        assertEquals(RatingStars(full = 4, half = false), ratingStars(3.75))
        assertEquals(RatingStars(full = 1, half = false), ratingStars(1.0))
    }

    @Test fun staysWithinZeroAndFiveStars() {
        assertEquals(RatingStars(full = 5, half = false), ratingStars(5.4))
        assertEquals(RatingStars(full = 0, half = false), ratingStars(-1.0))
    }
}
