package com.samaqu.keyboard.util

import com.samaqu.keyboard.data.CachedProduct
import com.samaqu.keyboard.data.CachedVariant
import com.samaqu.keyboard.network.OrderItem
import java.text.NumberFormat
import java.util.Locale

/**
 * Pure helpers (no Android dependencies, so they can be unit-tested on the JVM).
 * The one Android-shaped type it touches, [CachedProduct], is a plain data class.
 * Used by [com.samaqu.keyboard.ime.SamaQuIME] and the UI fragments.
 */
object SamaQuText {

    /** Retrofit requires a trailing slash. */
    fun normalizeBaseUrl(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    /** Orders whose status is "pending" (case-insensitive). */
    fun pendingOnly(orders: List<OrderItem>): List<OrderItem> =
        orders.filter { it.status.equals("pending", ignoreCase = true) }

    /**
     * Greeting sent when tapping a pending order.
     *
     * Falls back to "Kakak" when the customer name is missing and to a short slice of the
     * UUID when `order_number` was not returned.
     */
    fun orderGreeting(order: OrderItem): String {
        val buyer = order.customerName?.trim().orEmpty().ifBlank { "Kakak" }
        val number = order.orderNumber?.trim().orEmpty().ifBlank { order.id.take(8) }
        return "Halo $buyer 😊\n\n" +
            "Pesanan #$number sudah kami terima ya kak.\n" +
            "Mohon kirimkan bukti pembayaran ke sini agar pesanan segera kami proses.\n\n" +
            "Terima kasih! 🙏"
    }

    /**
     * Whole rupiah with Indonesian grouping, e.g. `15000` -> `"15.000"`.
     * Used by the ongkir panel, which gets plain integers from J&T.
     */
    fun rupiah(value: Long): String =
        NumberFormat.getInstance(Locale("id", "ID")).format(value)

    private fun rp(value: Double): String =
        "Rp " + NumberFormat.getInstance(Locale("id", "ID")).format(value)

    // ------------------------------------------------------------ product picker

    /**
     * Catalogue rows matching [query], optionally limited to one [category].
     *
     * An empty [query] and a null/blank [category] both mean "everything", which is what
     * the picker shows when it opens.
     */
    fun filterProducts(
        products: List<CachedProduct>,
        query: String,
        category: String? = null
    ): List<CachedProduct> {
        val needle = query.trim().lowercase()
        return products.filter { p ->
            val inCategory = category.isNullOrBlank() || p.category.equals(category, true)
            inCategory && (needle.isEmpty() || p.searchText().contains(needle))
        }
    }

    /**
     * Distinct categories in the catalogue, alphabetically, for the picker's filter chips.
     */
    fun productCategories(products: List<CachedProduct>): List<String> =
        products.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    /**
     * Product line written into the invoice, e.g. `Thobe Navy • Bayati`.
     *
     * The series has to be there: "Thobe Navy" exists as three different models and the
     * price differs between them, so the name alone would be ambiguous.
     */
    fun productLabel(product: CachedProduct): String =
        if (product.series.isBlank()) product.name else "${product.name} • ${product.series}"

    // ------------------------------------------------------------------ variants

    /** Variants of one product, in the order the store set for its size grid. */
    fun variantsOf(variants: List<CachedVariant>, productId: String): List<CachedVariant> =
        variants.filter { it.productId == productId }
            .sortedWith(compareBy({ it.displayOrder }, { it.size }, { it.color }))

    /**
     * Invoice line for a product, refined with the picked size, e.g. `Thobe Navy • Bayati (M)`.
     *
     * A null [variant] means the product has no size grid, and the plain product label is used.
     * The placeholder colour the store stores as "default" is left out of the line.
     */
    fun variantLabel(product: CachedProduct, variant: CachedVariant?): String {
        val base = productLabel(product)
        if (variant == null) return base

        val color = variant.color.takeUnless { it.equals("default", ignoreCase = true) }.orEmpty()
        val detail = listOf(color, variant.size)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        return if (detail.isBlank()) base else "$base ($detail)"
    }

    /** Price that applies to a variant: its own override when the store sets one. */
    fun priceOf(product: CachedProduct, variant: CachedVariant?): Long =
        variant?.priceOverride?.takeIf { it > 0 } ?: product.price

    /** Stock the store holds across a product's whole size grid. */
    fun totalStock(variants: List<CachedVariant>): Int = variants.sumOf { it.stock }

    /** How many sizes of a product are sold out. */
    fun soldOutCount(variants: List<CachedVariant>): Int = variants.count { it.stock <= 0 }

    /** Everything a picker search may match on, lower-cased. */
    private fun CachedProduct.searchText(): String =
        listOf(id, name, series, kain, colors).joinToString(" ").lowercase()

    /** Compact invoice inserted from the keyboard's invoice panel. */
    fun keyboardInvoice(
        buyer: String,
        product: String,
        qty: Int,
        price: Double,
        ongkir: Double
    ): String = buildString {
        append("🧾 *INVOICE SAMAQU*\n")
        append("Pembeli  : $buyer\n")
        append("Produk   : $product\n")
        append("Qty      : $qty pcs\n")
        append("Subtotal : ${rp(qty * price)}\n")
        append("Ongkir   : ${rp(ongkir)}\n")
        append("*TOTAL    : ${rp(qty * price + ongkir)}*")
    }

    /** Full invoice shown in the dashboard Invoice tab. */
    fun fullInvoice(
        buyer: String,
        product: String,
        qty: Int,
        price: Double,
        ongkir: Double,
        payment: String
    ): String {
        val subtotal = qty * price
        val total = subtotal + ongkir
        return buildString {
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("🧾 INVOICE SAMAQU\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("Pembeli  : $buyer\n")
            append("Produk   : $product\n")
            append("Qty      : $qty pcs\n")
            append("Harga    : ${rp(price)}/pcs\n")
            append("Subtotal : ${rp(subtotal)}\n")
            append("Ongkir   : ${rp(ongkir)}\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("TOTAL    : ${rp(total)}\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            if (payment.isNotBlank()) append("Transfer : $payment")
        }
    }
}
