package com.samaqu.keyboard.network

import com.google.gson.annotations.SerializedName

/** One row of `public.templates` (a canned chat reply). `id` is a Postgres `bigint`. */
data class RemoteTemplate(
    val id: Long,
    val content: String
)

/** A `public.categories` row with its `templates` embedded by PostgREST. */
data class CategoryWithTemplates(
    val id: Long,
    val name: String,
    val templates: List<RemoteTemplate> = emptyList()
)

/**
 * One row of `public.orders`, trimmed to what the keyboard displays.
 *
 * `id` is a UUID, while the human-readable reference is `order_number` (e.g. `SMQ-20260928-727`).
 * `total` is a whole number of rupiah. Known statuses: `pending`, `diproses`, `selesai`, `dibatalkan`.
 */
data class OrderItem(
    val id: String,
    @SerializedName("order_number") val orderNumber: String? = null,
    @SerializedName("customer_name") val customerName: String? = null,
    val status: String,
    val total: Long = 0,
    @SerializedName("created_at") val createdAt: String? = null
)

/**
 * One row of `public.products`. `colors` and `image` come back as a JSON array / URL string;
 * stock is not part of this table (see `product_variants`).
 */
data class Product(
    val id: String,
    val name: String,
    val category: String? = null,
    val series: String? = null,
    val kain: String? = null,
    val price: Long = 0,
    val colors: List<String> = emptyList(),
    val image: String? = null
)

/**
 * One row of `public.product_variants`: the size/colour grid of a product together with the
 * stock the store holds for it.
 *
 * `price_override` is null on every row the store has today, so the product's own price is
 * what normally applies; it is still modelled because a per-size price is exactly the kind
 * of thing the store will turn on later, and silently ignoring it would quote a wrong price.
 */
data class Variant(
    val id: String,
    @SerializedName("product_id") val productId: String,
    val color: String? = null,
    val size: String? = null,
    val stock: Int = 0,
    @SerializedName("price_override") val priceOverride: Long? = null,
    @SerializedName("display_order") val displayOrder: Int = 0
)

/**
 * Write payloads for PostgREST.
 *
 * Every field is nullable because Gson omits nulls by default: a PATCH therefore carries
 * only the columns being changed, while a POST still satisfies the NOT NULL constraints
 * (the caller supplies `name` for a category, `category_id` + `content` for a template).
 */
data class CategoryWrite(
    val name: String? = null,
    @SerializedName("display_order") val displayOrder: Int? = null
)

data class TemplateWrite(
    @SerializedName("category_id") val categoryId: Long? = null,
    val content: String? = null,
    @SerializedName("display_order") val displayOrder: Int? = null
)

// ------------------------------------------------------------- store website API

/** Body of `POST /api/shipping/jnt-cost` on the store site. `weight` is in grams. */
data class JntCostRequest(
    val city: String,
    val district: String,
    val weight: Int
)

/** One shipping option, e.g. `{"courier":"J&T","service":"EZ","cost":11000}`. */
data class JntCostOption(
    val courier: String = "",
    val service: String = "",
    val description: String = "",
    val cost: Long = 0,
    val etd: String = ""
)

/** The store wraps its answers as `{ "data": [...] }`. */
data class JntCostResponse(
    val data: List<JntCostOption> = emptyList()
)
