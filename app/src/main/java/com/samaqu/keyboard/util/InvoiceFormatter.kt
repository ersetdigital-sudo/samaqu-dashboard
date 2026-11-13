package com.samaqu.keyboard.util

import com.samaqu.keyboard.network.JntCostOption
import com.samaqu.keyboard.network.PaymentMethod

/** One line of an invoice: what was ordered, how many, and the price of one. */
data class InvoiceItem(
    val label: String,
    val qty: Int,
    val unitPrice: Long
) {
    val lineTotal: Long get() = qty * unitPrice
}

/** The courier option chosen for the order. Every field may be blank when the CS typed a cost by hand. */
data class InvoiceShipping(
    val courier: String = "",
    val service: String = "",
    val etd: String = "",
    val cost: Long = 0
)

/**
 * Everything the invoice panel collects. Immutable: each edit returns a new draft, so the
 * panel can re-render from it and the edits can be unit-tested without Android.
 */
data class InvoiceDraft(
    val buyer: String = "",
    val phone: String = "",
    val address: String = "",
    val items: List<InvoiceItem> = emptyList(),
    val shipping: InvoiceShipping? = null
) {
    val subtotal: Long get() = items.sumOf { it.lineTotal }
    val total: Long get() = subtotal + (shipping?.cost ?: 0)

    fun withItem(item: InvoiceItem): InvoiceDraft = copy(items = items + item)

    fun withoutItem(index: Int): InvoiceDraft =
        if (index !in items.indices) this else copy(items = items.filterIndexed { i, _ -> i != index })

    /** A line always keeps at least one piece; removing it is [withoutItem]'s job. */
    fun withQty(index: Int, qty: Int): InvoiceDraft =
        updateItem(index) { it.copy(qty = qty.coerceAtLeast(1)) }

    fun withUnitPrice(index: Int, price: Long): InvoiceDraft =
        updateItem(index) { it.copy(unitPrice = price) }

    private fun updateItem(index: Int, change: (InvoiceItem) -> InvoiceItem): InvoiceDraft =
        if (index !in items.indices) this
        else copy(items = items.mapIndexed { i, item -> if (i == index) change(item) else item })
}

/**
 * A quoted option as the invoice names it: courier plus the short service code (`ECO`), which
 * is how the team writes it; the long name is only the fallback when no code came with it.
 */
fun JntCostOption.toInvoiceShipping(): InvoiceShipping =
    InvoiceShipping(courier, serviceCode.ifBlank { service }, etd, cost)

/**
 * Writes the invoice text the sales team sends to customers.
 *
 * The layout mirrors the invoice the team used to type by hand; see the "Latar belakang"
 * section of the design doc. Pure Kotlin, no Android dependencies.
 */
object InvoiceFormatter {

    const val DEFAULT_CLOSING = "Kalau sudah transfer, kirim bukti transfernya di sini ya Ka."

    /** `374000` -> `Rp374.000`. Note: no space after "Rp", unlike [SamaQuText]'s panel text. */
    fun rupiah(value: Long): String = "Rp" + SamaQuText.rupiah(value)

    /** `Ongkir J&T ECO (estimasi 3-7 hari): Rp40.500`, tidied when courier, service or estimate is missing. */
    fun shippingLine(s: InvoiceShipping): String {
        val courier = s.courier.trim()
        val service = s.service.trim()
        // The store's long service name can already start with the courier ("J&T ECO (Hemat)").
        val name = if (service.startsWith(courier, ignoreCase = true)) service
        else listOf(courier, service).filter { it.isNotEmpty() }.joinToString(" ")
        val etd = s.etd.trim()
        return buildString {
            append("Ongkir")
            if (name.isNotEmpty()) append(' ').append(name)
            if (etd.isNotEmpty()) {
                val spoken = if (etd.contains("hari", ignoreCase = true)) etd else "$etd hari"
                append(" (estimasi ").append(spoken).append(')')
            }
            append(": ").append(rupiah(s.cost))
        }
    }

    /**
     * Bank block for the invoice: `Bank BSI 7153280058` then `a.n. Ardo Babus Abdullah`, one
     * pair per account. Accounts without a number are skipped; no bank gives an empty string.
     */
    fun paymentText(methods: List<PaymentMethod>): String =
        methods
            .filter { it.methodType.equals("bank", ignoreCase = true) && !it.accountNumber.isNullOrBlank() }
            .joinToString("\n") { m ->
                val head = listOfNotNull(m.bankName?.trim(), m.accountNumber?.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                if (m.accountName.isNullOrBlank()) head else "$head\na.n. ${m.accountName.trim()}"
            }

    /**
     * What to store after a catalogue sync: [fetched] is the list the server returned, or null
     * when the request failed. A failure keeps the text already on the phone, but a successful
     * answer is the truth even when it is empty - an account the owner deactivated must stop
     * appearing on invoices.
     */
    fun paymentTextAfterSync(fetched: List<PaymentMethod>?, current: String): String =
        if (fetched == null) current else paymentText(fetched)

    /**
     * The shipping line for the cost the CS sees in the field. A blank field means no shipping
     * line at all (and forgets a courier picked earlier); otherwise the amount replaces the cost
     * of the [choice] picked in the Ongkir panel, or stands alone when none was picked.
     */
    fun shippingFor(costText: String, choice: InvoiceShipping?): InvoiceShipping? {
        if (costText.isBlank()) return null
        val cost = costText.filter { it.isDigit() }.toLongOrNull() ?: 0L
        return (choice ?: InvoiceShipping()).copy(cost = cost)
    }

    /**
     * The whole invoice. [paymentText] is the bank block (already multi-line) and [closing]
     * the last sentence; either may be blank, in which case its section is left out.
     */
    fun format(draft: InvoiceDraft, paymentText: String, closing: String): String {
        val buyer = draft.buyer.trim()
        val phone = draft.phone.trim()
        val address = draft.address.trim()
        val payment = paymentText.trim()
        val ending = closing.trim()

        val lines = mutableListOf<String>()
        lines += "Berikut invoice pesanannya ya Ka${if (buyer.isEmpty()) "k" else " $buyer"}."
        lines += ""
        lines += "INVOICE PESANAN SAMAQU"
        if (buyer.isNotEmpty()) lines += "Nama: $buyer"
        if (phone.isNotEmpty()) lines += "No. WA: $phone"
        if (address.isNotEmpty()) lines += "Alamat: $address"
        lines += ""
        lines += "Pesanan:"
        draft.items.forEach { lines += "${it.qty} x ${it.label.trim()} - ${rupiah(it.lineTotal)}" }
        draft.shipping?.let { lines += shippingLine(it) }
        lines += ""
        lines += "Total: ${rupiah(draft.total)}"
        if (payment.isNotEmpty()) {
            lines += ""
            lines += "Pembayaran via transfer:"
            lines += payment
        }
        if (ending.isNotEmpty()) {
            lines += ""
            lines += ending
        }
        return lines.joinToString("\n")
    }
}
