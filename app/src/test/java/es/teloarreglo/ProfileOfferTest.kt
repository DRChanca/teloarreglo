package es.teloarreglo

import es.teloarreglo.catalogue.PriceDraft
import es.teloarreglo.catalogue.ReferencePrice
import es.teloarreglo.catalogue.parseServices
import es.teloarreglo.catalogue.toDraft
import es.teloarreglo.catalogue.toPrices
import org.junit.Assert.*
import org.junit.Test

class ProfileOfferTest {
    @Test fun servicesAreOnePerLineTrimmedWithoutBlanks() {
        assertEquals(listOf("Fugas", "Grifería"), parseServices("  Fugas \n\n Grifería\n"))
        assertEquals(30, parseServices((1..40).joinToString("\n") { "S$it" }).size)
        assertEquals(120, parseServices("x".repeat(200)).single().length)
    }

    @Test fun aPublishedPriceIsEditedInEurosAndSentBackInCents() {
        val price = ReferencePrice("Reparación de fuga", "Una hora", "hour", 3550, "IVA", "Materiales")
        val draft = price.toDraft()
        assertEquals("35,50", draft.amount)
        assertEquals("35", ReferencePrice("Visita", "", "job", 3500).toDraft().amount)
        assertEquals("0", ReferencePrice("Visita", "", "job", 0).toDraft().amount)
        assertEquals(listOf(price), listOf(draft).toPrices())
        assertEquals(4000, listOf(draft.copy(amount = "40")).toPrices()!!.single().amountCents)
    }

    @Test fun anIncompletePriceIsNotSent() {
        val draft = PriceDraft(service = "Fuga", scope = "Una hora", unit = "hour", amount = "35")
        assertNotNull(listOf(draft).toPrices())
        assertNull("a price needs a service", listOf(draft.copy(service = "  ")).toPrices())
        assertNull("a price needs an amount", listOf(draft.copy(amount = "")).toPrices())
        assertNull("amounts are euros with at most two decimals", listOf(draft.copy(amount = "3,555")).toPrices())
        assertNull("only the catalogue units", listOf(draft.copy(unit = "day")).toPrices())
        assertEquals("texts are trimmed", "Fuga", listOf(draft.copy(service = " Fuga ")).toPrices()!!.single().service)
        assertEquals(emptyList<ReferencePrice>(), emptyList<PriceDraft>().toPrices())
    }
}
