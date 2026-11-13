package com.samaqu.keyboard.data

import com.google.gson.Gson
import com.samaqu.keyboard.network.Product
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductMappingTest {

    private fun parse(json: String): Product = Gson().fromJson(json, Product::class.java)

    @Test
    fun `kain comes from the jenis_kain embed`() {
        val product = parse(
            """{"id":"thobe-broken-white-imalah","name":"Thobe Broken White ","series":"Imalah",
               "kain":null,"price":374000,"colors":["default"],"jenis_kain":{"name":"B-01"}}"""
        )

        val cached = product.toCachedProduct()

        assertEquals("B-01", cached.kain)
        assertEquals("Thobe Broken White", cached.name)
        assertEquals("", cached.colors)
        assertEquals(374_000L, cached.price)
    }

    @Test
    fun `kain falls back to the products column and then to empty`() {
        val legacy = parse("""{"id":"a","name":"A","kain":"A-02","price":1000,"colors":[]}""")
        val none = parse("""{"id":"b","name":"B","kain":null,"price":1000,"colors":[],"jenis_kain":null}""")
        val blankEmbed = parse("""{"id":"c","name":"C","kain":"","price":1000,"colors":[],"jenis_kain":{"name":" "}}""")

        assertEquals("A-02", legacy.toCachedProduct().kain)
        assertEquals("", none.toCachedProduct().kain)
        assertEquals("", blankEmbed.toCachedProduct().kain)
    }

    @Test
    fun `real colours are kept and the default placeholder is dropped`() {
        val product = parse("""{"id":"k","name":"Kabak","price":80000,"colors":["Kabak 01","default","Kabak 02"]}""")

        assertEquals("Kabak 01, Kabak 02", product.toCachedProduct().colors)
    }
}
