package com.samaqu.keyboard.util

/** What could be read about a customer from a pasted message; blank when it was not there. */
data class CustomerInfo(
    val name: String = "",
    val phone: String = "",
    val address: String = ""
) {
    val isEmpty: Boolean get() = name.isBlank() && phone.isBlank() && address.isBlank()
}

/**
 * Reads a customer's name, WhatsApp number and address out of a pasted message, so the CS does
 * not retype what the customer already sent. Pure Kotlin, no Android dependencies.
 *
 * Two shapes are understood:
 *  - labelled lines, the usual order form (`Nama: ...`, `No. WA: ...`, `Alamat: ...`), where an
 *    address may continue on the following lines;
 *  - bare lines: a phone number on a line of its own, the name above it and the address below.
 *
 * Anything else gives an empty [CustomerInfo], never a guess.
 */
object CustomerInfoParser {

    private enum class Kind { NAME, PHONE, ADDRESS, OTHER }

    private val labelLine = Regex("^([\\p{L}][\\p{L}0-9 ./]{0,24}?)\\s*[:=]\\s*(.*)$")
    private val phoneWords = setOf("wa", "whatsapp", "hp", "handphone", "telp", "telepon", "telpon", "phone")

    fun parse(text: String): CustomerInfo {
        val lines = text.lines().map { it.trim() }
        return labelled(lines) ?: bare(lines) ?: CustomerInfo()
    }

    private fun kindOf(label: String): Kind {
        val lower = label.lowercase().trim()
        val words = Regex("\\p{L}+").findAll(lower).map { it.value }.toList()
        return when {
            "alamat" in lower -> Kind.ADDRESS
            "nama" in lower || lower in setOf("penerima", "pembeli", "customer", "pelanggan") -> Kind.NAME
            words.any { it in phoneWords } || words == listOf("no") || words == listOf("nomor") -> Kind.PHONE
            else -> Kind.OTHER
        }
    }

    private fun labelled(lines: List<String>): CustomerInfo? {
        var name = ""
        var phone = ""
        val address = mutableListOf<String>()
        var seenKnown = false
        var inAddress = false

        for (line in lines) {
            val match = labelLine.matchEntire(line)
            if (match != null) {
                val value = match.groupValues[2].trim()
                inAddress = false
                when (kindOf(match.groupValues[1])) {
                    Kind.NAME -> { seenKnown = true; if (value.isNotEmpty()) name = value }
                    Kind.PHONE -> { seenKnown = true; if (value.isNotEmpty()) phone = normalizePhone(value) }
                    Kind.ADDRESS -> {
                        seenKnown = true
                        inAddress = true
                        address.clear()
                        if (value.isNotEmpty()) address += value
                    }
                    Kind.OTHER -> Unit
                }
            } else if (line.isEmpty()) {
                inAddress = false
            } else if (inAddress) {
                address += line
            }
        }
        return if (seenKnown) CustomerInfo(name, phone, address.joinToString(", ")) else null
    }

    private fun bare(lines: List<String>): CustomerInfo? {
        val filled = lines.filter { it.isNotEmpty() }
        val phoneAt = filled.indexOfFirst(::looksLikePhone)
        if (phoneAt < 0) return null

        val before = filled.take(phoneAt)
        val after = filled.drop(phoneAt + 1)
        val name = before.firstOrNull()?.takeIf { l -> l.none { it.isDigit() } && l.length <= 40 }.orEmpty()
        val address = (if (name.isNotEmpty()) before.drop(1) else before) + after

        return CustomerInfo(name, normalizePhone(filled[phoneAt]), address.joinToString(", "))
    }

    /** A line made only of phone-number characters, with enough digits to be a real number. */
    private fun looksLikePhone(line: String): Boolean =
        line.count { it.isDigit() } >= 9 && line.all { it.isDigit() || it in " +-().," }

    /** Digits only, with the country code written as the local leading zero. */
    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.startsWith("62") && digits.length >= 10) "0" + digits.removePrefix("62") else digits
    }
}
