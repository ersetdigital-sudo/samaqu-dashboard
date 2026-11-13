package com.samaqu.keyboard.util

/** The district (kecamatan) and city or regency (kota / kabupaten) read from an address. */
data class Area(val district: String = "", val city: String = "")

/**
 * Finds the district and city in a delivery address so the Ongkir panel can be pre-filled.
 * Pure Kotlin, no Android dependencies.
 *
 * It looks for the words people write: `Kec.` / `Kecamatan` for the district and `Kota` /
 * `Kab.` / `Kabupaten` for the city. When only one of the two is labelled, the neighbouring
 * part of the address fills the other: the marketplace order (`..., KOTA DEPOK, PANCORAN MAS,
 * JAWA BARAT, ID, 16431`) puts the district right after the city, and `Kec. Ciracas, Jakarta
 * Timur` puts the city right after the district. Anything it cannot place is left blank
 * rather than guessed.
 */
object AreaParser {

    private val districtLabel = Regex("^(?:kecamatan|kec)\\.?\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val cityLabel = Regex("^(kota|kabupaten|kab)\\.?\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** Starts of province names, so a province is never taken for a district or a city. */
    private val provinceStarts = listOf(
        "aceh", "sumatera", "riau", "kepulauan", "jambi", "bengkulu", "lampung", "bangka", "dki",
        "jawa", "banten", "bali", "nusa", "kalimantan", "sulawesi", "maluku", "papua", "gorontalo",
        "yogyakarta", "di yogyakarta", "indonesia"
    )

    fun parse(address: String): Area {
        val parts = address.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        var district = ""
        var districtAt = -1
        var city = ""
        var cityAt = -1
        parts.forEachIndexed { i, part ->
            if (district.isEmpty()) {
                districtLabel.matchEntire(part)?.let { district = withoutPostalCode(it.groupValues[1]); districtAt = i }
            }
            if (city.isEmpty()) {
                cityLabel.matchEntire(part)?.let {
                    // "Kota" stays as it was written; only the abbreviation "Kab." is spelled out,
                    // so a regency is never mistaken for the city of the same name.
                    city = if (it.groupValues[1].lowercase().startsWith("kab")) {
                        "Kabupaten " + withoutPostalCode(it.groupValues[2])
                    } else {
                        withoutPostalCode(part)
                    }
                    cityAt = i
                }
            }
        }

        if (district.isEmpty() && cityAt >= 0) {
            neighbour(parts, cityAt + 1)?.let { district = it }
        }
        if (city.isEmpty() && districtAt >= 0) {
            neighbour(parts, districtAt + 1)?.let { city = it }
        }
        return Area(district, city)
    }

    /** The part at [index] when it reads like a place name and not a province, number or country. */
    private fun neighbour(parts: List<String>, index: Int): String? {
        val part = parts.getOrNull(index)?.let(::withoutPostalCode) ?: return null
        val lower = part.lowercase()
        if (part.isEmpty() || part.any { it.isDigit() } || part.length == 2) return null
        if (provinceStarts.any { lower.startsWith(it) }) return null
        return part
    }

    private fun withoutPostalCode(text: String): String = text.replace(Regex("\\s+\\d{5}$"), "").trim()
}
