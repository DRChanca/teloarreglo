package es.teloarreglo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TradesTest {
    @Test
    fun cleaningIsOfferedWithItsOwnLabel() {
        assertTrue("cleaning" in catalogueTrades)
        assertEquals(R.string.trade_cleaning, tradeLabel("cleaning"))
    }

    @Test
    fun everyOfferedTradeHasADistinctLabel() {
        assertEquals(catalogueTrades.size, catalogueTrades.map(::tradeLabel).toSet().size)
    }
}
