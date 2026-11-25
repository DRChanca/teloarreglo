package es.teloarreglo

import es.teloarreglo.identity.AccessError
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where an access error is shown: under the field that caused it, or as a general message (#47). */
class AccessErrorPlacementTest {
    private val signIn = listOf(AccessField.EMAIL, AccessField.PASSWORD)
    private val signUp = listOf(AccessField.NAME, AccessField.EMAIL, AccessField.PASSWORD)

    @Test fun wrongCredentialsMarkBothFieldsAndExplainUnderThePassword() {
        assertEquals(
            ErrorPlacement(setOf(AccessField.EMAIL, AccessField.PASSWORD), AccessField.PASSWORD),
            AccessError.CREDENTIALS.placement(signIn),
        )
    }

    @Test fun aWeakPasswordPointsOnlyAtThePassword() {
        assertEquals(ErrorPlacement(setOf(AccessField.PASSWORD), AccessField.PASSWORD), AccessError.WEAK_PASSWORD.placement(signUp))
    }

    @Test fun missingInputIsExplainedUnderTheEmailWhenTheFormHasOne() {
        assertEquals(
            ErrorPlacement(setOf(AccessField.EMAIL, AccessField.PASSWORD), AccessField.EMAIL),
            AccessError.INPUT.placement(signIn),
        )
    }

    @Test fun missingInputInTheNewPasswordFormPointsAtThePassword() {
        assertEquals(ErrorPlacement(setOf(AccessField.PASSWORD), AccessField.PASSWORD), AccessError.INPUT.placement(listOf(AccessField.PASSWORD)))
    }

    @Test fun anInvalidNameIsExplainedUnderTheName() {
        assertEquals(ErrorPlacement(setOf(AccessField.NAME), AccessField.NAME), AccessError.NAME.placement(signUp))
    }

    @Test fun anUnverifiedEmailIsExplainedUnderTheEmail() {
        assertEquals(ErrorPlacement(setOf(AccessField.EMAIL), AccessField.EMAIL), AccessError.UNVERIFIED.placement(signIn))
    }

    @Test fun networkAndServiceFailuresAreGeneralMessages() {
        listOf(AccessError.NETWORK, AccessError.SERVICE, AccessError.RATE_LIMITED, AccessError.GOOGLE, AccessError.LINK_INVALID)
            .forEach { assertEquals(it.name, ErrorPlacement.GENERAL, it.placement(signIn)) }
    }

    @Test fun aFieldErrorWithoutItsFieldOnScreenBecomesGeneral() {
        assertEquals(ErrorPlacement.GENERAL, AccessError.WEAK_PASSWORD.placement(listOf(AccessField.EMAIL)))
        assertEquals(ErrorPlacement.GENERAL, AccessError.CREDENTIALS.placement(emptyList()))
    }
}
