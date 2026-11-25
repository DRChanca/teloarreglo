package es.teloarreglo

import es.teloarreglo.appointments.*
import es.teloarreglo.search.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** What the client sees of the ventana de llegada (#84), from the answer of «leer llegada» (appointment_arrival). */
class ArrivalWindowTest {
    private val now = Instant.parse("2030-01-15T10:00:00Z")
    private val home = GeoPoint(43.3614, -5.8494)

    private fun view(json: String, at: Instant = now, house: GeoPoint? = home) = arrivalView(parseArrival(json), house, at)

    @Test fun aClosedWindowShowsNothing() {
        assertEquals(ArrivalView.Hidden, view("""{"state": "closed"}"""))
    }

    @Test fun anUnknownStateShowsNothing() {
        assertEquals(ArrivalView.Hidden, view("""{"state": "something_new"}"""))
    }

    @Test fun anOpenWindowWithoutSharingSaysSoAndNeverAPosition() {
        assertEquals(ArrivalView.NotSharing, view("""{"state": "not_sharing", "closes_at": "2030-01-15T11:00:00+00:00"}"""))
    }

    @Test fun beyondTwoKilometresTheMapShowsTheApproximateCircleAndWhenItWasUpdated() {
        val shown = view("""{"state": "sharing", "closes_at": "2030-01-15T11:00:00+00:00", "precision": "area",
            "latitude": 43.1, "longitude": -5.9, "radius_m": 500, "updated_at": "2030-01-15T09:58:50+00:00"}""")
        assertEquals(ArrivalView.Sharing(ArrivalMarker.Area(GeoPoint(43.1, -5.9), 500), home, ArrivalDistance.FAR,
            updatedMinutesAgo = 1), shown)
    }

    private fun exact(lat: Double, lng: Double) = """{"state": "sharing", "closes_at": "2030-01-15T11:00:00+00:00",
        "precision": "exact", "latitude": $lat, "longitude": $lng, "accuracy_m": 15, "updated_at": "2030-01-15T09:59:40+00:00"}"""

    @Test fun withinTwoKilometresTheMapShowsTheExactPointAndSaysLessThanTwoKilometres() {
        // About 1.1 km north of the address of the appointment.
        assertEquals(ArrivalView.Sharing(ArrivalMarker.Exact(GeoPoint(43.3714, -5.8494), 15.0), home, ArrivalDistance.NEAR,
            updatedMinutesAgo = 0), view(exact(43.3714, -5.8494)))
    }

    @Test fun anExactPointAtAHundredMetresOrLessIsArriving() {
        // About 89 m north of the address of the appointment.
        assertEquals(ArrivalDistance.ARRIVING, (view(exact(43.3622, -5.8494)) as ArrivalView.Sharing).distance)
        // About 145 m: still «A menos de 2 km».
        assertEquals(ArrivalDistance.NEAR, (view(exact(43.3627, -5.8494)) as ArrivalView.Sharing).distance)
    }

    @Test fun theUpdateTimeFollowsTheClockAndNeverGoesNegative() {
        assertEquals(1L, (view(exact(43.37, -5.85), at = now.plusSeconds(80)) as ArrivalView.Sharing).updatedMinutesAgo)
        // The device clock behind the server's: «just now», not «-2 min».
        assertEquals(0L, (view(exact(43.37, -5.85), at = now.minusSeconds(120)) as ArrivalView.Sharing).updatedMinutesAgo)
    }

    @Test fun aReadingOlderThanTwoMinutesIsNoLongerShown() {
        // The last answer is kept while reads fail; once it is more than 2 min old it is never shown as current.
        // updated_at is 09:59:40, so 10:01:40 is exactly 2 min and 10:01:41 is past them.
        assertEquals(2L, (view(exact(43.37, -5.85), at = now.plusSeconds(100)) as ArrivalView.Sharing).updatedMinutesAgo)
        assertEquals(ArrivalView.NotSharing, view(exact(43.37, -5.85), at = now.plusSeconds(101)))
        assertEquals(ArrivalView.NotSharing, view(exact(43.37, -5.85), at = now.plusSeconds(600)))
    }

    @Test fun withoutTheHomeAnExactPointIsOnlyNear() {
        assertEquals(ArrivalDistance.NEAR, (view(exact(43.3614, -5.8494), house = null) as ArrivalView.Sharing).distance)
    }
}
