package com.samaqu.keyboard.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "categories")
data class CachedCategory(
    @PrimaryKey val id: Int,
    val name: String
)

@Entity(tableName = "templates")
data class CachedTemplate(
    @PrimaryKey val id: Int,
    val categoryId: Int,
    val content: String
)

/**
 * One row of the store's `products` table, cached so the invoice's product picker keeps
 * working without a network round trip.
 *
 * `id` is a slug (`thobe-navy-bayati`), [series] is the model name that tells two products
 * with the same colour apart, and `kain` is empty on every row the store has today (kept so
 * the column does not have to be added back later).
 */
@Entity(tableName = "cached_products")
data class CachedProduct(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val series: String,
    val kain: String,
    val price: Long,
    /** Comma-joined colours, with the placeholder "default" already filtered out. */
    val colors: String
)

/**
 * One row of the store's `product_variants`: a size (and colour) of a product, with the stock
 * the store actually holds for it.
 *
 * Cached for the same reason the products are: more than two thirds of the grid is out of
 * stock, and a CS quoting a sold-out size is the mistake this cache exists to prevent.
 */
@Entity(tableName = "cached_variants")
data class CachedVariant(
    @PrimaryKey val id: String,
    val productId: String,
    val color: String,
    val size: String,
    val stock: Int,
    /** Per-size price when the store sets one; null means "use the product price". */
    val priceOverride: Long?,
    val displayOrder: Int
)

/** Category with its templates, as exposed to the UI. */
data class CategoryWithTemplates(
    @Embedded val category: CachedCategory,
    @Relation(parentColumn = "id", entityColumn = "categoryId")
    val templates: List<CachedTemplate>
)
