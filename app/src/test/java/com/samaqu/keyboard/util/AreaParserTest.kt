package com.samaqu.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AreaParserTest {

    private fun parse(address: String) = AreaParser.parse(address)

    @Test
    fun `marketplace style address gives city then district`() {
        val area = parse(
            "Jalan Komplek Marinir Rt 002/Rw 004 No 1 Rangkapan Jaya Baru, Pangkalan Gas Elpiji, " +
                "Rumah Pagar Hitam Putih, KOTA DEPOK, PANCORAN MAS, JAWA BARAT, ID, 16431"
        )

        assertEquals("PANCORAN MAS", area.district)
        assertEquals("KOTA DEPOK", area.city)
    }

    @Test
    fun `an explicit Kec label wins and the next part is the city`() {
        val area = parse("Jl. Sudirman No.123, Kel. Contoh, Kec. Ciracas, Jakarta Timur, DKI Jakarta 13730")

        assertEquals("Ciracas", area.district)
        assertEquals("Jakarta Timur", area.city)
    }

    @Test
    fun `Kecamatan and Kota written out in full`() {
        val area = parse("Jl. Tgk. Wahab Dahlawi, Kecamatan Muara Dua, Kota Lhokseumawe, Aceh 24352")

        assertEquals("Muara Dua", area.district)
        assertEquals("Kota Lhokseumawe", area.city)
    }

    @Test
    fun `Kab abbreviation becomes Kabupaten so it is not mistaken for the city of the same name`() {
        val area = parse("Jl. Raya 1, Kec. Cibinong, Kab. Bogor, Jawa Barat")

        assertEquals("Cibinong", area.district)
        assertEquals("Kabupaten Bogor", area.city)
    }

    @Test
    fun `case does not matter`() {
        val area = parse("jl. mawar 3, kota depok, pancoran mas, jawa barat")

        assertEquals("pancoran mas", area.district)
        assertEquals("kota depok", area.city)
    }

    @Test
    fun `an address with no area words gives nothing`() {
        val area = parse("Jl. Mawar 3 Rt 1")

        assertEquals("", area.district)
        assertEquals("", area.city)
        assertEquals("", parse("").city)
    }

    @Test
    fun `only the city is returned when no district can be found`() {
        val area = parse("Jl. Mawar 3, Kota Bandung")

        assertEquals("", area.district)
        assertEquals("Kota Bandung", area.city)
    }
}
