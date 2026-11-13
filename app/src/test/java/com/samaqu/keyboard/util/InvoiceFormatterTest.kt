package com.samaqu.keyboard.util

import com.google.gson.Gson
import com.samaqu.keyboard.network.JntCostOption
import com.samaqu.keyboard.network.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceFormatterTest {

    private val address = "Jl. Tgk. Wahab Dahlawi (depan Mushalla Babuttaqwa), Dusun Ujong Tunong, " +
        "Desa Paya Punteut, Kec. Muara Dua, Kota Lhokseumawe, Aceh 24352"

    private val thobe = InvoiceItem("Thobe Broken White Imalah (kain B-01), size M", 1, 374_000)
    private val eco = InvoiceShipping("J&T", "ECO", "3-7", 40_500)
    private val bank = "Bank BSI 7153280058\na.n. Ardo Babus Abdullah"

    private fun nasir() = InvoiceDraft(
        buyer = "Muhammad Nasir",
        phone = "0852-6041-2200",
        address = address,
        items = listOf(thobe),
        shipping = eco
    )

    // --------------------------------------------------------------- the owner's invoice

    @Test
    fun `format reproduces the owner's invoice exactly`() {
        val draft = InvoiceDraft(
            buyer = "Budi Santoso",
            phone = "08123456789",
            address = "Jl. Sudirman No.123, Kel. Contoh, Kec. Ciracas, Jakarta Timur, DKI Jakarta 13730",
            items = listOf(
                InvoiceItem("Thobe Broken White Jiharkah (kain B-01), size M", 1, 359_000),
                InvoiceItem("Cover Thobe + Hanger", 1, 35_000)
            ),
            shipping = InvoiceShipping("J&T", "EZ", "2-4 hari", 9_000)
        )
        val expected = listOf(
            "Berikut invoice pesanannya ya Ka Budi Santoso.",
            "",
            "INVOICE PESANAN SAMAQU",
            "",
            "Nama: Budi Santoso",
            "No. WA: 08123456789",
            "Alamat: Jl. Sudirman No.123, Kel. Contoh, Kec. Ciracas, Jakarta Timur, DKI Jakarta 13730",
            "",
            "Pesanan:",
            "1 x Thobe Broken White Jiharkah (kain B-01), size M",
            "Rp359.000",
            "",
            "1 x Cover Thobe + Hanger",
            "Rp35.000",
            "",
            "Ongkir J&T EZ (estimasi 2-4 hari): Rp9.000",
            "",
            "Total: Rp403.000",
            "",
            "Pembayaran via transfer:",
            "Bank BSI 7153280058",
            "a.n. Ardo Babus Abdullah",
            "",
            "Kalau sudah transfer, kirim bukti transfernya di sini ya Ka."
        ).joinToString("\n")

        assertEquals(expected, InvoiceFormatter.format(draft, bank, InvoiceFormatter.DEFAULT_CLOSING))
    }

    @Test
    fun `without any customer data the header is followed by a single blank line`() {
        val text = InvoiceFormatter.format(InvoiceDraft(items = listOf(thobe)), "", "")

        assertTrue(text.startsWith("Berikut invoice pesanannya ya Kak.\n\nINVOICE PESANAN SAMAQU\n\nPesanan:\n"))
    }

    @Test
    fun `no shipping goes straight from the last item to the total`() {
        val text = InvoiceFormatter.format(InvoiceDraft(items = listOf(thobe)), "", "")

        assertTrue(text.endsWith("Pesanan:\n1 x ${thobe.label}\nRp374.000\n\nTotal: Rp374.000"))
    }

    // ------------------------------------------------------------------------- money

    @Test
    fun `rupiah has no space after Rp`() {
        assertEquals("Rp374.000", InvoiceFormatter.rupiah(374_000))
        assertEquals("Rp0", InvoiceFormatter.rupiah(0))
    }

    @Test
    fun `quantity above one multiplies the line`() {
        val draft = InvoiceDraft(items = listOf(InvoiceItem("Kandora Navy, size L", 2, 374_000)))
        val text = InvoiceFormatter.format(draft, "", "")

        assertTrue(text.contains("2 x Kandora Navy, size L\nRp748.000"))
        assertEquals(748_000L, draft.total)
        assertTrue(text.contains("Total: Rp748.000"))
    }

    // ------------------------------------------------------------- optional customer data

    @Test
    fun `blank buyer greets Kak and skips the Nama line`() {
        val text = InvoiceFormatter.format(nasir().copy(buyer = "  "), bank, "")

        assertTrue(text.startsWith("Berikut invoice pesanannya ya Kak.\n"))
        assertFalse(text.contains("Nama:"))
    }

    @Test
    fun `blank phone and address are skipped`() {
        val text = InvoiceFormatter.format(nasir().copy(phone = "", address = " "), bank, "")

        assertFalse(text.contains("No. WA:"))
        assertFalse(text.contains("Alamat:"))
    }

    // ------------------------------------------------------------------------ shipping

    @Test
    fun `no shipping skips the ongkir line and total equals subtotal`() {
        val draft = nasir().copy(shipping = null)
        val text = InvoiceFormatter.format(draft, bank, "")

        assertFalse(text.contains("Ongkir"))
        assertEquals(draft.subtotal, draft.total)
        assertTrue(text.contains("Total: Rp374.000"))
    }

    @Test
    fun `shippingLine does not repeat hari`() {
        assertEquals(
            "Ongkir J&T ECO (estimasi 3-7 hari): Rp40.500",
            InvoiceFormatter.shippingLine(InvoiceShipping("J&T", "ECO", "3-7 hari", 40_500))
        )
        assertEquals(
            "Ongkir J&T ECO (estimasi 3-7 hari): Rp40.500",
            InvoiceFormatter.shippingLine(eco)
        )
    }

    @Test
    fun `shippingLine without etd or service stays tidy`() {
        assertEquals(
            "Ongkir J&T ECO: Rp40.500",
            InvoiceFormatter.shippingLine(InvoiceShipping("J&T", "ECO", "", 40_500))
        )
        assertEquals("Ongkir: Rp40.500", InvoiceFormatter.shippingLine(InvoiceShipping(cost = 40_500)))
    }

    // ----------------------------------------------------------------- payment & closing

    @Test
    fun `blank payment text skips the transfer section`() {
        val text = InvoiceFormatter.format(nasir(), "  ", InvoiceFormatter.DEFAULT_CLOSING)

        assertFalse(text.contains("Pembayaran via transfer"))
        assertTrue(text.endsWith(InvoiceFormatter.DEFAULT_CLOSING))
    }

    @Test
    fun `blank closing is omitted`() {
        val text = InvoiceFormatter.format(nasir(), bank, "")

        assertTrue(text.endsWith("a.n. Ardo Babus Abdullah"))
    }

    // ------------------------------------------------------------------- draft editing

    @Test
    fun `withQty clamps to one and ignores a bad index`() {
        val draft = InvoiceDraft(items = listOf(thobe))

        assertEquals(1, draft.withQty(0, 0).items[0].qty)
        assertEquals(1, draft.withQty(0, -4).items[0].qty)
        assertEquals(3, draft.withQty(0, 3).items[0].qty)
        assertEquals(draft, draft.withQty(5, 2))
    }

    @Test
    fun `withoutItem removes by index and ignores a bad index`() {
        val a = InvoiceItem("A", 1, 10_000)
        val b = InvoiceItem("B", 1, 20_000)
        val draft = InvoiceDraft(items = listOf(a, b))

        assertEquals(listOf(b), draft.withoutItem(0).items)
        assertEquals(draft, draft.withoutItem(9))
    }

    @Test
    fun `withUnitPrice changes only that line`() {
        val a = InvoiceItem("A", 1, 10_000)
        val b = InvoiceItem("B", 1, 20_000)
        val draft = InvoiceDraft(items = listOf(a, b)).withUnitPrice(1, 25_000)

        assertEquals(10_000L, draft.items[0].unitPrice)
        assertEquals(25_000L, draft.items[1].unitPrice)
    }

    // ------------------------------------------------------------------- payment text

    private fun bank(name: String?, number: String?, holder: String?) =
        PaymentMethod(methodType = "bank", bankName = name, accountName = holder, accountNumber = number)

    @Test
    fun `paymentText writes bank, number and account name`() {
        val text = InvoiceFormatter.paymentText(listOf(bank("Bank BSI", "7153280058", "Ardo Babus Abdullah")))

        assertEquals("Bank BSI 7153280058\na.n. Ardo Babus Abdullah", text)
    }

    @Test
    fun `paymentText joins several banks and skips incomplete ones`() {
        val text = InvoiceFormatter.paymentText(
            listOf(
                bank("Bank BSI", "7153280058", "Ardo Babus Abdullah"),
                bank("Bank X", null, "Nobody"),
                bank("BCA", "123", "Ardo")
            )
        )

        assertEquals("Bank BSI 7153280058\na.n. Ardo Babus Abdullah\nBCA 123\na.n. Ardo", text)
    }

    @Test
    fun `paymentText is empty when there is no bank`() {
        assertEquals("", InvoiceFormatter.paymentText(emptyList()))
        assertEquals("", InvoiceFormatter.paymentText(listOf(PaymentMethod(methodType = "mayar"))))
    }

    // ------------------------------------------------- stored bank text after a sync

    private val oldBank = "Bank Lama 111\na.n. Lama"

    @Test
    fun `a failed fetch keeps the stored bank text`() {
        assertEquals(oldBank, InvoiceFormatter.paymentTextAfterSync(null, oldBank))
    }

    @Test
    fun `a successful fetch replaces the stored bank text`() {
        val fetched = listOf(bank("Bank BSI", "7153280058", "Ardo Babus Abdullah"))

        assertEquals(
            "Bank BSI 7153280058\na.n. Ardo Babus Abdullah",
            InvoiceFormatter.paymentTextAfterSync(fetched, oldBank)
        )
    }

    @Test
    fun `a successful fetch with no active bank clears the stored text`() {
        assertEquals("", InvoiceFormatter.paymentTextAfterSync(emptyList(), oldBank))
        assertEquals("", InvoiceFormatter.paymentTextAfterSync(listOf(bank("X", null, "Y")), oldBank))
    }

    // ------------------------------------------------------------ chosen courier

    @Test
    fun `a blank cost field means no shipping line even after a courier was picked`() {
        assertEquals(null, InvoiceFormatter.shippingFor("", eco))
        assertEquals(null, InvoiceFormatter.shippingFor("  ", null))
    }

    @Test
    fun `a typed cost keeps the picked courier and replaces only the amount`() {
        assertEquals(eco.copy(cost = 45_000), InvoiceFormatter.shippingFor("45000", eco))
    }

    @Test
    fun `a typed cost without a picked courier is a plain ongkir line`() {
        assertEquals(InvoiceShipping(cost = 12_000), InvoiceFormatter.shippingFor("12.000", null))
        assertEquals(InvoiceShipping(cost = 0), InvoiceFormatter.shippingFor("0", null))
    }

    // ------------------------------------------- the store's real ongkir answer

    private fun option(json: String): JntCostOption = Gson().fromJson(json, JntCostOption::class.java)

    private val realEco = """{"courier":"J&T","service":"J&T ECO (Hemat)","serviceCode":"ECO",
        "description":"Layanan ECO J&T","cost":40500,"etd":"3-7 hari"}"""

    @Test
    fun `the store's ECO answer becomes the line the team writes`() {
        val shipping = option(realEco).toInvoiceShipping()

        assertEquals("Ongkir J&T ECO (estimasi 3-7 hari): Rp40.500", InvoiceFormatter.shippingLine(shipping))
    }

    @Test
    fun `without a service code the long service name is used without repeating the courier`() {
        val shipping = option(
            """{"courier":"J&T","service":"J&T EZ (Reguler)","cost":58000,"etd":"2-4 hari"}"""
        ).toInvoiceShipping()

        assertEquals("Ongkir J&T EZ (Reguler) (estimasi 2-4 hari): Rp58.000", InvoiceFormatter.shippingLine(shipping))
    }

    @Test
    fun `shippingLine does not repeat a courier that the service already starts with`() {
        assertEquals(
            "Ongkir J&T ECO: Rp40.500",
            InvoiceFormatter.shippingLine(InvoiceShipping("J&T", "J&T ECO", "", 40_500))
        )
    }

    // ------------------------------------------ the short label under the Ongkir field

    @Test
    fun `the label names the courier service once and the estimate`() {
        assertEquals("J&T ECO • 3-7 hari", InvoiceFormatter.shippingSummary(option(realEco).toInvoiceShipping()))
        assertEquals(
            "J&T EZ (Reguler) • 2-4 hari",
            InvoiceFormatter.shippingSummary(InvoiceShipping("J&T", "J&T EZ (Reguler)", "2-4 hari", 58_000))
        )
    }

    @Test
    fun `the label skips what is missing`() {
        assertEquals("J&T ECO", InvoiceFormatter.shippingSummary(InvoiceShipping("J&T", "ECO", "", 0)))
        assertEquals("", InvoiceFormatter.shippingSummary(InvoiceShipping(cost = 5_000)))
    }
}
