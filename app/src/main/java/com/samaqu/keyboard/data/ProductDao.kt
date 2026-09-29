package com.samaqu.keyboard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProductDao {

    /** Sorted the way the picker shows them: colour first, then the series/model. */
    @Query("SELECT * FROM cached_products ORDER BY name COLLATE NOCASE, series COLLATE NOCASE")
    suspend fun getAll(): List<CachedProduct>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<CachedProduct>)

    @Query("DELETE FROM cached_products")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM cached_products")
    suspend fun count(): Int

    /** Whole size/colour grid; small enough (~300 rows) to hold in one list. */
    @Query("SELECT * FROM cached_variants ORDER BY displayOrder, size")
    suspend fun getAllVariants(): List<CachedVariant>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariants(variants: List<CachedVariant>)

    @Query("DELETE FROM cached_variants")
    suspend fun clearVariants()
}
