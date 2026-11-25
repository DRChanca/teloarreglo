package es.teloarreglo

import es.teloarreglo.catalogue.Coverage
import es.teloarreglo.catalogue.ProfileSection.*
import es.teloarreglo.catalogue.PublicProfile
import es.teloarreglo.catalogue.profileSections
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSectionsTest {
    private val plumber = PublicProfile(
        "profile-14", "Fontanería Nalón", "Reparaciones domésticas", listOf("plumbing"),
        listOf("Fugas"), listOf(Coverage("33044", "Oviedo")), listOf("es", "en"),
        "+34 000 000 014", emptyList(), emptyList(),
        businessAddress = "Calle Gascona, local ficticio A, Oviedo",
    )

    @Test fun whatDecidesTheChoiceComesBeforeTheContactDetails() {
        assertEquals(
            listOf(COVER, IDENTITY, DESCRIPTION, PRICES, REVIEWS, SERVICES, COVERAGE, ADDRESS, LANGUAGES, PHONE),
            profileSections(plumber, showReviews = true),
        )
    }

    @Test fun absentDataLeavesNoEmptySection() {
        assertEquals(
            listOf(COVER, IDENTITY, DESCRIPTION, PRICES, SERVICES, COVERAGE, PHONE),
            profileSections(plumber.copy(languages = listOf("es"), businessAddress = " "), showReviews = false),
        )
    }
}
