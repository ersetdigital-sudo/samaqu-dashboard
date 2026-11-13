package com.samaqu.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerInfoParserTest {

    private fun parse(text: String) = CustomerInfoParser.parse(text)

    @Test
    fun `labelled lines fill name phone and address`() {
        val info = parse(
            "Nama: Budi Santoso\nNo. WA: 08123456789\nAlamat: Jl. Sudirman No.123, Kec. Ciracas, Jakarta Timur 13730"
        )

        assertEquals("Budi Santoso", info.name)
        assertEquals("08123456789", info.phone)
        assertEquals("Jl. Sudirman No.123, Kec. Ciracas, Jakarta Timur 13730", info.address)
    }

    @Test
    fun `label wording and case do not matter`() {
        val info = parse("nama lengkap : Siti\nNomor WhatsApp = 0812 3456 789\nALAMAT LENGKAP : Jl. Mawar 3")

        assertEquals("Siti", info.name)
        assertEquals("08123456789", info.phone)
        assertEquals("Jl. Mawar 3", info.address)
    }

    @Test
    fun `other labels for the same things are understood`() {
        assertEquals("Andi", parse("Penerima: Andi").name)
        assertEquals("08111222333", parse("No HP: 08111222333").phone)
        assertEquals("08111222333", parse("Telp: 08111222333").phone)
        assertEquals("08111222333", parse("WA: 08111222333").phone)
    }

    @Test
    fun `an address can run over several lines`() {
        val info = parse("Nama: Budi\nAlamat:\nJl. Merdeka 5\nRT 1 RW 2\nKota X\nNo. WA: 0811")

        assertEquals("Jl. Merdeka 5, RT 1 RW 2, Kota X", info.address)
        assertEquals("0811", info.phone)
    }

    @Test
    fun `an unknown label ends the address and is ignored`() {
        val info = parse("Alamat: Jl. Mawar 3\nPesanan: Thobe M\nNama: Budi")

        assertEquals("Jl. Mawar 3", info.address)
        assertEquals("Budi", info.name)
    }

    @Test
    fun `phone numbers are reduced to local digits`() {
        assertEquals("08123456789", parse("No WA: 0812-3456-789").phone)
        assertEquals("08123456789", parse("No WA: +62 812-3456-789").phone)
        assertEquals("08123456789", parse("No WA: 6281 23456789").phone)
    }

    @Test
    fun `without labels the lines are read as name then phone then address`() {
        val info = parse("Budi Santoso\n081234567890\nJl. Mawar 3, Bandung")

        assertEquals("Budi Santoso", info.name)
        assertEquals("081234567890", info.phone)
        assertEquals("Jl. Mawar 3, Bandung", info.address)
    }

    @Test
    fun `text that is not customer data gives nothing`() {
        assertTrue(parse("Halo kak, ada stok thobe M?").isEmpty)
        assertTrue(parse("").isEmpty)
        assertTrue(parse("   \n  ").isEmpty)
    }

    @Test
    fun `a partly filled form returns only what is there`() {
        val info = parse("Nama: Budi\nNo. WA:\nAlamat:")

        assertEquals("Budi", info.name)
        assertEquals("", info.phone)
        assertEquals("", info.address)
    }
}
