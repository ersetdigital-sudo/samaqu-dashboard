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

    /**
     * Unlabelled text: the first line holding a phone number decides. The number may sit alone
     * on its line or follow the name (`Surya 085603324143`); whatever else is on that line is the
     * name, the line above it is the name when none was found there, and everything else is the
     * address.
     */
    private fun bare(lines: List<String>): CustomerInfo? {
        val filled = lines.filter { it.isNotEmpty() }
        val phoneAt = filled.indexOfFirst { phoneIn(it) != null }
        if (phoneAt < 0) return null

        val line = filled[phoneAt]
        val phone = phoneIn(line)!!
        val before = filled.take(phoneAt)
        val after = filled.drop(phoneAt + 1)

        var name = nameAround(line, phone)
        var addressBefore = before
        if (name.isEmpty()) {
            val above = before.firstOrNull()?.takeIf { looksLikeName(it) }
            if (above != null) {
                name = above
                addressBefore = before.drop(1)
            }
        }
        return CustomerInfo(name, normalizePhone(phone), (addressBefore + after).joinToString(", "))
    }

    /** A number that starts with 0 or 62 and has 9-15 digits, spaces or dashes between them allowed. */
    private val phoneToken = Regex("(?<!\\d)(?:\\+?62|0)[\\s-]?\\d(?:[\\s-]?\\d){7,13}(?!\\d)")

    private fun phoneIn(line: String): String? =
        phoneToken.findAll(line).map { it.value }.firstOrNull { token -> token.count { it.isDigit() } in 9..15 }

    /** What is left of [line] once the [phone] and words like "WA" or "HP" are taken out. */
    private fun nameAround(line: String, phone: String): String {
        val rest = line.replace(phone, " ")
            .replace(Regex("(?i)\\b(wa|whatsapp|hp|handphone|no|nomor|telp|tlp|telepon)\\b"), " ")
            .trim(' ', ',', ';', ':', '-', '/', '|', '(', ')')
            .replace(Regex("\\s+"), " ")
        return if (looksLikeName(rest)) rest else ""
    }

    private fun looksLikeName(text: String): Boolean =
        text.isNotEmpty() && text.length <= 40 && text.none { it.isDigit() }

    /** Digits only, with the country code written as the local leading zero. */
    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.startsWith("62") && digits.length >= 10) "0" + digits.removePrefix("62") else digits
    }
}
