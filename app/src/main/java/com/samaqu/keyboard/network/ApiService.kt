package com.samaqu.keyboard.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Supabase/PostgREST contract used by the keyboard.
 *
 * Base URL is the project root (e.g. `https://xyz.supabase.co/`) and is normalised by
 * [RetrofitClient.init]. The `apikey` / `Authorization` headers are injected globally by
 * [RetrofitClient], so no method needs to pass credentials itself.
 *
 * The two catalogue queries ([getProducts], [getVariants]) are deliberately unpaginated: the
 * store holds 61 products and 305 variants, and one round trip beats paging a list a CS opens
 * for a second at a time. The `order=` clauses are what keep the result stable, and PostgREST
 * stops at 1000 rows per request - if the catalogue ever grows past that, the picker needs real
 * paging rather than a bigger limit, or the tail would silently go missing.
 *
 * The `select=` projections keep payloads small and must match the live schema of the
 * `samaqu` project (see `supabase_setup.sql` for the tables this app owns):
 *  - `categories` / `templates` are read-only tables created for Auto Text sync. For
 *    [getTemplates] PostgREST's embedded resource syntax `templates(...)` nests them inside
 *    every category, which relies on the foreign key `templates.category_id -> categories.id`.
 *  - `orders` and `products` belong to the store website, so only the necessary columns are
 *    projected and no column may be invented: product stock lives in `product_variants.stock`,
 *    not in `products`.
 */
interface ApiService {

    /** Public read: `[{id, name, templates:[{id, content}]}]`, categories ordered by `display_order`. */
    @GET("rest/v1/categories?select=id,name,templates(id,content)&order=display_order.asc,id.asc")
    suspend fun getTemplates(): List<CategoryWithTemplates>

    /** Orders, newest first. "pending" filtering is done client-side by [SamaQuText.pendingOnly]. */
    @GET("rest/v1/orders?select=id,order_number,customer_name,status,total,created_at&order=created_at.desc")
    suspend fun getOrders(): List<OrderItem>

    @GET("rest/v1/products?select=id,name,category,series,kain,price,colors,image&order=name.asc")
    suspend fun getProducts(): List<Product>

    /**
     * Size/colour grid of every product, in one call (~300 rows today). Small enough to cache
     * whole, which is what lets the picker show real stock while offline.
     */
    @GET("rest/v1/product_variants?select=id,product_id,color,size,stock,price_override,display_order&order=display_order.asc")
    suspend fun getVariants(): List<Variant>

    // ------------------------------------------------------------------ write access
    //
    // Only `categories` and `templates` are writable: they hold the canned replies the
    // store owner edits from the keyboard's dashboard and contain no customer data.
    // `orders` and `products` stay read-only - RLS rejects writes from the anon key.
    //
    // Each mutation returns the raw [Response] instead of Unit so the caller can read
    // PostgREST's `{"message": ...}` body when RLS or a constraint rejects the write, and
    // so a 204 No Content never has to be mapped onto a non-null Kotlin return type.

    @POST("rest/v1/categories")
    suspend fun createCategory(@Body body: CategoryWrite): Response<ResponseBody>

    @PATCH
    suspend fun updateCategory(@Url url: String, @Body body: CategoryWrite): Response<ResponseBody>

    @DELETE
    suspend fun deleteCategory(@Url url: String): Response<ResponseBody>

    @POST("rest/v1/templates")
    suspend fun createTemplate(@Body body: TemplateWrite): Response<ResponseBody>

    @PATCH
    suspend fun updateTemplate(@Url url: String, @Body body: TemplateWrite): Response<ResponseBody>

    @DELETE
    suspend fun deleteTemplate(@Url url: String): Response<ResponseBody>
}
