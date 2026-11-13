package com.samaqu.keyboard.util

import com.samaqu.keyboard.data.CachedProduct
import com.samaqu.keyboard.data.CachedVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceWeightTest {

    private fun product(weight: Int) =
        CachedProduct("p", "Thobe", "Thobe", "Imalah", "B-01", 374_000, "", weight = weight)

    private fun variant(weight: Int?) =
        CachedVariant("v", "p", "default", "M", 5, null, 1, weight = weight ?: 0)

    @Test
    fun `a size's own weight beats the product weight`() {
        assertEquals(1_100, SamaQuText.weightOf(product(1_200), variant(1_100)))
    }

    @Test
    fun `a size without a weight uses the product weight`() {
        assertEquals(1_200, SamaQuText.weightOf(product(1_200), variant(null)))
        assertEquals(1_200, SamaQuText.weightOf(product(1_200), null))
    }

    @Test
    fun `no weight anywhere is zero`() {
        assertEquals(0, SamaQuText.weightOf(product(0), variant(null)))
    }

    @Test
    fun `the invoice weight adds up quantity times each line's weight`() {
        val draft = InvoiceDraft(
            items = listOf(
                InvoiceItem("Thobe", 2, 359_000, weightGram = 1_100),
                InvoiceItem("Cover Thobe + Hanger", 1, 35_000, weightGram = 100)
            )
        )

        assertEquals(2_300, draft.totalWeightGram)
    }

    @Test
    fun `changing a quantity changes the total weight`() {
        val draft = InvoiceDraft(items = listOf(InvoiceItem("Thobe", 1, 359_000, weightGram = 1_100)))

        assertEquals(3_300, draft.withQty(0, 3).totalWeightGram)
    }

    @Test
    fun `an invoice with no weights is zero`() {
        assertEquals(0, InvoiceDraft(items = listOf(InvoiceItem("Jasa", 1, 35_000))).totalWeightGram)
        assertEquals(0, InvoiceDraft().totalWeightGram)
    }
}
