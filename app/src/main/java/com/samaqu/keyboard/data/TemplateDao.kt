package com.samaqu.keyboard.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TemplateDao {

    @Transaction
    @Query("SELECT * FROM categories ORDER BY name")
    suspend fun getAllWithTemplates(): List<CategoryWithTemplates>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(cats: List<CachedCategory>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<CachedTemplate>)

    @Query("DELETE FROM templates")
    suspend fun clearTemplates()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun templateCount(): Int
}
