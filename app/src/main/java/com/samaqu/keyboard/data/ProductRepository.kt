package com.samaqu.keyboard.data

import android.content.Context
import com.samaqu.keyboard.network.ApiService
import com.samaqu.keyboard.network.Product
import com.samaqu.keyboard.network.RetrofitClient
import com.samaqu.keyboard.network.Variant

/**
 * The store's product catalogue, for the invoice's product picker.
 *
 * Products are owned by the store website and are read-only here: [sync] pulls the table and
 * replaces the cache, and there is no write path at all. The cache exists so a CS whose phone
 * lost signal can still pick a product and its price, exactly like the Auto Text templates.
 */
class ProductRepository(ctx: Context) {

    private val dao: ProductDao = AppDatabase.get(ctx).productDao()
    private val prefs = Prefs(ctx)

    /** Cached catalogue, cheapest first call and the one used while a refresh runs. */
    suspend fun getProducts(): List<CachedProduct> = dao.getAll()

    /** Cached size/colour/stock grid for every product. */
    suspend fun getVariants(): List<CachedVariant> = dao.getAllVariants()

    suspend fun count(): Int = dao.count()

    /**
     * Pulls `public.products` and `public.product_variants` from Supabase.
     *
     * Both requests are made before either cache is touched: a phone that loses connection
     * half-way through must keep the catalogue it already had rather than end up with
     * products and no variants (or the other way round).
     */
    suspend fun sync(): Result<Unit> = runCatching {
        val client = api()
        val products = client.getProducts()
        val variants = client.getVariants()

        dao.clear()
        dao.insertAll(products.map { it.toCached() })
        dao.clearVariants()
        dao.insertVariants(variants.map { it.toCached() })
    }

    private fun api(): ApiService {
        RetrofitClient.init(prefs.supabaseUrl, prefs.supabaseAnonKey)
        return RetrofitClient.api
    }

    private fun Product.toCached() = CachedProduct(
        id = id,
        name = name.orEmpty().trim(),
        category = category.orEmpty().trim(),
        series = series.orEmpty().trim(),
        kain = kain.orEmpty().trim(),
        price = price,
        // 60 of the 61 rows only carry the placeholder colour, and printing
        // "default" in the picker would just be noise.
        colors = colors.filterNot { it.equals("default", ignoreCase = true) }
            .joinToString(", ")
            .trim()
    )

    /**
     * The cache mirrors the table, "default" colour included: the placeholder is dropped when
     * the label is built for display, not when the row is stored, so nothing in the cache is a
     * re-written copy of the store's data.
     */
    private fun Variant.toCached() = CachedVariant(
        id = id,
        productId = productId,
        color = color.orEmpty().trim(),
        size = size.orEmpty().trim(),
        stock = stock,
        priceOverride = priceOverride,
        displayOrder = displayOrder
    )
}
