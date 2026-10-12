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

    // Targeted change queries, used by the write paths. They exist so a save can mirror its
    // own result into the cache instead of re-reading every category and template.

    @Query("UPDATE templates SET content = :content WHERE id = :id")
    suspend fun updateTemplateContent(id: Int, content: String)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun deleteTemplateById(id: Int)

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun updateCategoryName(id: Int, name: String)

    /** Mirrors Postgres' `on delete cascade` when a category is removed. */
    @Query("DELETE FROM templates WHERE categoryId = :categoryId")
    suspend fun deleteTemplatesOfCategory(categoryId: Int)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategoryById(id: Int)
}
