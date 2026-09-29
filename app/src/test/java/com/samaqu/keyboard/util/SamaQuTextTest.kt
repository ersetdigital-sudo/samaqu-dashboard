package com.samaqu.keyboard.util

import com.samaqu.keyboard.data.CachedProduct
import com.samaqu.keyboard.data.CachedVariant
import com.samaqu.keyboard.network.OrderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamaQuTextTest {

    // ---------------------------------------------------------- base URL

    @Test
    fun `normalizeBaseUrl adds trailing slash`() {
        assertEquals("http://10.0.2.2:3000/", SamaQuText.normalizeBaseUrl("http://10.0.2.2:3000"))
    }

    @Test
    fun `normalizeBaseUrl keeps existing trailing slash`() {
        assertEquals("https://api.example.com/", SamaQuText.normalizeBaseUrl("https://api.example.com/"))
    }

    // ------------------------------------------------------ order filtering

    private fun order(
        id: String,
        status: String,
        customerName: String? = "Ani",
        orderNumber: String? = "SMQ-20260101-001"
    ) = OrderItem(
        id = id,
        orderNumber = orderNumber,
        customerName = customerName,
        status = status,
        total = 100_000,
        createdAt = "2026-01-01T00:00:00+00:00"
    )

    @Test
    fun `pendingOnly keeps only pending status case-insensitively`() {
        val orders = listOf(
            order("1", "pending"),
            order("2", "PENDING"),
            order("3", "diproses"),
            order("4", "selesai"),
            order("5", "dibatalkan")
        )
        val result = SamaQuText.pendingOnly(orders)
        assertEquals(2, result.size)
        assertEquals(listOf("1", "2"), result.map { it.id })
    }

    @Test
    fun `pendingOnly returns empty when nothing pending`() {
        val result = SamaQuText.pendingOnly(listOf(order("9", "selesai")))
        assertTrue(result.isEmpty())
    }

    // ------------------------------------------------------- order greeting

    @Test
    fun `orderGreeting uses buyer name and order number`() {
        val msg = SamaQuText.orderGreeting(order("42", "pending", "Rina", "SMQ-20260928-727"))
        assertTrue(msg.contains("Halo Rina"))
        assertTrue(msg.contains("Pesanan #SMQ-20260928-727"))
        assertTrue(msg.contains("bukti pembayaran"))
    }

    @Test
    fun `orderGreeting falls back to Kakak when buyer missing`() {
        assertTrue(SamaQuText.orderGreeting(order("7", "pending", null)).contains("Halo Kakak"))
        assertTrue(SamaQuText.orderGreeting(order("8", "pending", "   ")).contains("Halo Kakak"))
    }

    @Test
    fun `orderGreeting trims customer name and falls back to a short id`() {
        val msg = SamaQuText.orderGreeting(order("8b649117-afe9-4e4a", "pending", "Akmal ", null))
        assertTrue(msg.contains("Halo Akmal 😊"))
        assertTrue(msg.contains("Pesanan #8b649117"))
    }

    // --------------------------------------------------------------- ongkir

    @Test
    fun `rupiah groups thousands the Indonesian way`() {
        assertEquals("15.000", SamaQuText.rupiah(15_000))
        assertEquals("1.250.000", SamaQuText.rupiah(1_250_000))
        assertEquals("0", SamaQuText.rupiah(0))
    }

    // -------------------------------------------------------- product picker

    private fun product(
        id: String,
        name: String,
        category: String = "Thobe",
        series: String = "",
        kain: String = "",
        price: Long = 374_000,
        colors: String = ""
    ) = CachedProduct(id, name, category, series, kain, price, colors)

    private val catalogue = listOf(
        product("thobe-navy-bayati", "Thobe Navy", series = "Bayati", price = 374_000),
        product("thobe-navy-jiharkah", "Thobe Navy", series = "Jiharkah", price = 359_000),
        product("thobe-latte-nahawand", "Thobe Latte", series = "Nahawand", price = 359_000),
        product("kandora-superblack", "Kandora Superblack", category = "Kandora", series = "Imalah")
    )

    @Test
    fun `filterProducts returns everything when the query and category are empty`() {
        assertEquals(4, SamaQuText.filterProducts(catalogue, "").size)
        assertEquals(4, SamaQuText.filterProducts(catalogue, "   ", null).size)
    }

    @Test
    fun `filterProducts matches name series colour and slug case-insensitively`() {
        assertEquals(2, SamaQuText.filterProducts(catalogue, "thobe navy").size)
        assertEquals(1, SamaQuText.filterProducts(catalogue, "JIHARKAH").size)
        assertEquals("thobe-navy-bayati", SamaQuText.filterProducts(catalogue, "bayati").first().id)
        assertEquals(1, SamaQuText.filterProducts(catalogue, "kandora-").size)
        assertTrue(SamaQuText.filterProducts(catalogue, "kaos").isEmpty())
    }

    @Test
    fun `filterProducts narrows by category and combines it with the query`() {
        val kandora = SamaQuText.filterProducts(catalogue, "", "Kandora")
        assertEquals(listOf("kandora-superblack"), kandora.map { it.id })

        // Right name, wrong category
        assertTrue(SamaQuText.filterProducts(catalogue, "latte", "Kandora").isEmpty())
        assertEquals(1, SamaQuText.filterProducts(catalogue, "latte", "Thobe").size)
    }

    @Test
    fun `productCategories lists distinct categories alphabetically`() {
        assertEquals(listOf("Kandora", "Thobe"), SamaQuText.productCategories(catalogue))
    }

    @Test
    fun `productLabel pairs the name with its series`() {
        assertEquals("Thobe Navy • Bayati", SamaQuText.productLabel(catalogue[0]))
    }

    @Test
    fun `productLabel is just the name when there is no series`() {
        assertEquals("Thobe Navy", SamaQuText.productLabel(product("x", "Thobe Navy")))
    }

    // -------------------------------------------------------- product variants

    private fun variant(
        id: String,
        productId: String = "thobe-navy-bayati",
        color: String = "default",
        size: String = "M",
        stock: Int = 5,
        priceOverride: Long? = null,
        displayOrder: Int = 2
    ) = CachedVariant(id, productId, color, size, stock, priceOverride, displayOrder)

    private val grid = listOf(
        variant("v2", size = "M", stock = 15, displayOrder = 2),
        variant("v1", size = "S", stock = 0, displayOrder = 1),
        variant("v4", size = "XL", stock = 0, displayOrder = 4),
        variant("v3", size = "L", stock = 2, displayOrder = 3),
        variant("other", productId = "thobe-latte-nahawand", size = "M")
    )

    @Test
    fun `variantsOf keeps one product and follows the store's display order`() {
        val sizes = SamaQuText.variantsOf(grid, "thobe-navy-bayati")
        assertEquals(listOf("S", "M", "L", "XL"), sizes.map { it.size })
    }

    @Test
    fun `variantsOf is empty for a product without a size grid`() {
        assertTrue(SamaQuText.variantsOf(grid, "kandora-superblack").isEmpty())
    }

    @Test
    fun `variantLabel adds the size and drops the placeholder colour`() {
        val product = catalogue[0]
        assertEquals("Thobe Navy • Bayati (M)", SamaQuText.variantLabel(product, variant("v", size = "M")))
        assertEquals("Thobe Navy • Bayati", SamaQuText.variantLabel(product, null))
    }

    @Test
    fun `variantLabel keeps a real colour`() {
        val label = SamaQuText.variantLabel(
            catalogue[0],
            variant("v", color = "Superblack", size = "L")
        )
        assertEquals("Thobe Navy • Bayati (Superblack, L)", label)
    }

    @Test
    fun `priceOf prefers the variant override and falls back to the product`() {
        val product = catalogue[0]
        assertEquals(374_000L, SamaQuText.priceOf(product, variant("v")))
        assertEquals(389_000L, SamaQuText.priceOf(product, variant("v", priceOverride = 389_000)))
        assertEquals(374_000L, SamaQuText.priceOf(product, null))
    }

    @Test
    fun `stock helpers total the grid and count the sold out sizes`() {
        val sizes = SamaQuText.variantsOf(grid, "thobe-navy-bayati")
        assertEquals(17, SamaQuText.totalStock(sizes))
        assertEquals(2, SamaQuText.soldOutCount(sizes))
    }

    // ------------------------------------------------------------- invoice

    @Test
    fun `keyboardInvoice computes subtotal and total in Rupiah`() {
        val text = SamaQuText.keyboardInvoice(
            buyer = "Ani",
            product = "Kaos",
            qty = 2,
            price = 10000.0,
            ongkir = 5000.0
        )
        // 2 * 10000 = 20.000 ; total = 25.000
        assertTrue("expected subtotal 20.000 in: $text", text.contains("20.000"))
        assertTrue("expected total 25.000 in: $text", text.contains("25.000"))
        assertTrue(text.contains("*INVOICE SAMAQU*"))
        assertTrue(text.contains("Qty      : 2 pcs"))
    }

    @Test
    fun `fullInvoice appends payment line only when provided`() {
        val withPayment = SamaQuText.fullInvoice("Ani", "Kaos", 1, 15000.0, 0.0, "BCA 123")
        assertTrue(withPayment.contains("Transfer : BCA 123"))
        assertTrue(withPayment.contains("15.000"))

        val withoutPayment = SamaQuText.fullInvoice("Ani", "Kaos", 1, 15000.0, 0.0, "  ")
        assertFalse(withoutPayment.contains("Transfer :"))
    }
}
