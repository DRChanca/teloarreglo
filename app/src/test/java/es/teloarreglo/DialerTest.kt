package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The number handed to the dialer with ACTION_DIAL (#75). */
class DialerTest {
    @Test fun spanish_number_with_spaces_goes_to_the_dialer_as_digits() {
        assertEquals("tel:600123456", dialUri("600 123 456"))
    }

    @Test fun international_prefix_is_kept() {
        assertEquals("tel:+34600123456", dialUri(" +34 600-12.34 (56) "))
    }

    @Test fun double_zero_prefix_is_left_as_written() {
        assertEquals("tel:0034985000000", dialUri("0034 985 000 000"))
    }

    @Test fun plus_only_counts_at_the_start() {
        assertEquals("tel:600123456", dialUri("600+123+456"))
    }

    @Test fun nothing_to_dial_gives_no_uri() {
        assertNull(dialUri(""))
        assertNull(dialUri("   "))
        assertNull(dialUri("+"))
        assertNull(dialUri("sin teléfono"))
    }
}
