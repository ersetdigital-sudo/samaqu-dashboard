package com.samaqu.keyboard.data

import android.content.Context
import com.samaqu.keyboard.network.ApiService
import com.samaqu.keyboard.network.Product
import com.samaqu.keyboard.network.RetrofitClient
import com.samaqu.keyboard.network.Variant
import com.samaqu.keyboard.util.InvoiceFormatter
import com.samaqu.keyboard.util.SavedSettings

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

    /**
     * True while the cache was filled from the project the app currently reads. After the app
     * is pointed at another project (or upgraded from a build that never recorded one), the
     * old rows carry old prices and no fabric codes, so they must not reach an invoice.
     */
    private fun cacheIsCurrent(): Boolean = SavedSettings.sameUrl(prefs.catalogueSource, prefs.supabaseUrl)

    /** Cached catalogue, cheapest first call and the one used while a refresh runs. */
    suspend fun getProducts(): List<CachedProduct> = if (cacheIsCurrent()) dao.getAll() else emptyList()

    /** Cached size/colour/stock grid for every product. */
    suspend fun getVariants(): List<CachedVariant> = if (cacheIsCurrent()) dao.getAllVariants() else emptyList()

    suspend fun count(): Int = if (cacheIsCurrent()) dao.count() else 0

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
        dao.insertAll(products.map { it.toCachedProduct() })
        dao.clearVariants()
        dao.insertVariants(variants.map { it.toCached() })

        prefs.catalogueSource = prefs.supabaseUrl

        // The bank accounts for invoices ride along with the catalogue sync. A failed request
        // must neither fail the sync nor wipe the accounts already stored on the phone, but a
        // successful answer is authoritative: an account the owner deactivated disappears.
        val banks = runCatching { client.getPaymentMethods() }.getOrNull()
        prefs.paymentText = InvoiceFormatter.paymentTextAfterSync(banks, prefs.paymentText)
    }

    private fun api(): ApiService {
        RetrofitClient.init(prefs.supabaseUrl, prefs.supabaseAnonKey)
        return RetrofitClient.api
    }

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

/**
 * Cache row for a store product.
 *
 * The fabric code is the name of the embedded `jenis_kain` row (`B-01`); the products' own
 * `kain` column is only a fallback because the store leaves it empty.
 */
internal fun Product.toCachedProduct() = CachedProduct(
    id = id,
    name = name.orEmpty().trim(),
    category = category.orEmpty().trim(),
    series = series.orEmpty().trim(),
    kain = jenisKain?.name?.trim().orEmpty().ifBlank { kain.orEmpty().trim() },
    price = price,
    // Most rows only carry the placeholder colour, and printing "default" in the picker
    // would just be noise.
    colors = colors.filterNot { it.equals("default", ignoreCase = true) }
        .joinToString(", ")
        .trim()
)
