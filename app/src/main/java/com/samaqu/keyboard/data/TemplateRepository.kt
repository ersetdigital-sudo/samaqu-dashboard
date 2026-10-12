package com.samaqu.keyboard.data

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.samaqu.keyboard.network.ApiService
import com.samaqu.keyboard.network.CategoryWrite
import com.samaqu.keyboard.network.RetrofitClient
import com.samaqu.keyboard.network.TemplateWrite
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * Single source of truth for chat templates.
 *
 * - [getTemplates] reads the local Room cache (offline-first).
 * - [sync] pulls from the backend and replaces the cache.
 * - On a fresh install an offline seed is inserted so Auto Text is usable
 *   immediately, before any backend is configured.
 * - [addTemplate] / [updateTemplate] / [deleteTemplate] and the matching category
 *   methods write to Supabase first and mirror the result into the cache from what the
 *   server answers, so a row always exists server-side before it exists on screen. Nothing
 *   is ever edited in the cache alone, because the next sync would discard it.
 * - Creates ask for `Prefer: return=representation`, which is what makes one round trip
 *   enough; the earlier version followed every write with a full [sync], paying a second
 *   request for data the write had already confirmed.
 */
class TemplateRepository(ctx: Context) {

    private val db: AppDatabase = AppDatabase.get(ctx)
    private val dao: TemplateDao = db.templateDao()
    private val prefs = Prefs(ctx)

    suspend fun getTemplates(): List<CategoryWithTemplates> {
        if (dao.categoryCount() == 0) {
            seedOffline()
        }
        return dao.getAllWithTemplates()
    }

    /** Category and template counts of the local cache, for the summary screen. */
    suspend fun counts(): Pair<Int, Int> {
        if (dao.categoryCount() == 0) {
            seedOffline()
        }
        return dao.categoryCount() to dao.templateCount()
    }

    /**
     * Pulls templates from Supabase. Returns success/failure via [Result].
     *
     * The clear-and-refill runs inside one transaction on purpose. Clearing first and
     * inserting afterwards - which is what this used to do - left both tables empty if the
     * insert half failed, and an empty cache is indistinguishable from a fresh install to
     * [getTemplates], so it re-seeded the built-in offline templates: the user's own
     * templates silently disappeared and the canned defaults looked like the real data.
     */
    suspend fun sync(): Result<Unit> = runCatching {
        val remote = api().getTemplates()

        db.withTransaction {
            // Postgres ids are bigint; the Room cache keys are Int, so narrow them here.
            val categories = remote.map { CachedCategory(it.id.toInt(), it.name) }
            val templates = remote.flatMap { category ->
                category.templates.map { CachedTemplate(it.id.toInt(), category.id.toInt(), it.content) }
            }
            dao.clearTemplates()
            dao.clearCategories()
            dao.insertCategories(categories)
            dao.insertTemplates(templates)
        }
    }

    // ---------------------------------------------------------------- writes

    /** Client pointed at whatever Supabase URL/key the user saved in Settings. */
    private fun api(): ApiService {
        RetrofitClient.init(prefs.supabaseUrl, prefs.supabaseAnonKey)
        return RetrofitClient.api
    }

    /** `?id=eq.<id>` is PostgREST's column filter syntax; there is no path-style id. */
    private fun categoryUrl(id: Int) = "rest/v1/categories?id=eq.$id"

    private fun templateUrl(id: Int) = "rest/v1/templates?id=eq.$id"

    /**
     * Runs a write whose answer is not needed (a PATCH, a DELETE).
     *
     * PostgREST explains refusals in the body (RLS denial, constraint, ...); that message is
     * surfaced instead of the bare "HTTP 401" Retrofit would put in the exception.
     */
    private suspend fun send(block: suspend () -> Response<ResponseBody>) {
        val response = block()
        if (!response.isSuccessful) error(postgrestMessage(response))
    }

    /** Runs a write that answers with the affected rows (`Prefer: return=representation`). */
    private suspend fun <T> sentRows(block: suspend () -> Response<List<T>>): List<T> {
        val response = block()
        if (!response.isSuccessful) error(postgrestMessage(response))
        return response.body().orEmpty()
    }

    private fun postgrestMessage(response: Response<*>): String {
        val body = runCatching { response.errorBody()?.string() }.getOrNull()
        val detail = runCatching {
            Gson().fromJson(body, com.google.gson.JsonObject::class.java)
                ?.get("message")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return detail ?: "HTTP ${response.code()}"
    }

    suspend fun addCategory(name: String, displayOrder: Int): Result<Unit> = runCatching {
        val rows = sentRows {
            api().createCategory(CategoryWrite(name = name, displayOrder = displayOrder))
        }
        // No representation means the new id is unknown, so fall back to a full read rather
        // than invent a key for the cache.
        val row = rows.firstOrNull() ?: return@runCatching sync().getOrThrow()
        dao.insertCategories(listOf(CachedCategory(row.id.toInt(), row.name)))
    }

    suspend fun renameCategory(id: Int, name: String): Result<Unit> = runCatching {
        send { api().updateCategory(categoryUrl(id), CategoryWrite(name = name)) }
        dao.updateCategoryName(id, name)
    }

    /** Deletes a category; its templates go with it via `on delete cascade`. */
    suspend fun deleteCategory(id: Int): Result<Unit> = runCatching {
        send { api().deleteCategory(categoryUrl(id)) }
        // The cascade runs in Postgres, so the cache has to mirror it explicitly.
        dao.deleteTemplatesOfCategory(id)
        dao.deleteCategoryById(id)
    }

    suspend fun addTemplate(categoryId: Int, content: String, displayOrder: Int): Result<Unit> =
        runCatching {
            val rows = sentRows {
                api().createTemplate(
                    TemplateWrite(
                        categoryId = categoryId.toLong(),
                        content = content,
                        displayOrder = displayOrder
                    )
                )
            }
            val row = rows.firstOrNull() ?: return@runCatching sync().getOrThrow()
            dao.insertTemplates(
                listOf(CachedTemplate(row.id.toInt(), row.categoryId.toInt(), row.content))
            )
        }

    suspend fun updateTemplate(id: Int, content: String): Result<Unit> = runCatching {
        send { api().updateTemplate(templateUrl(id), TemplateWrite(content = content)) }
        dao.updateTemplateContent(id, content)
    }

    suspend fun deleteTemplate(id: Int): Result<Unit> = runCatching {
        send { api().deleteTemplate(templateUrl(id)) }
        dao.deleteTemplateById(id)
    }

    private suspend fun seedOffline() {
        val categories = listOf(
            CachedCategory(1, "Sapaan"),
            CachedCategory(2, "Order"),
            CachedCategory(3, "Pengiriman"),
            CachedCategory(4, "Penutup")
        )
        val templates = listOf(
            CachedTemplate(1, 1, "Halo Kak 😊 Ada yang bisa kami bantu?"),
            CachedTemplate(2, 1, "Terima kasih sudah menghubungi SAMAQU 🙏"),
            CachedTemplate(3, 2, "Pesanan sudah kami terima ya kak. Mohon kirim bukti transfer agar segera kami proses."),
            CachedTemplate(4, 2, "Stok masih tersedia kak, silakan lanjut order ya 😊"),
            CachedTemplate(5, 3, "Pesanan akan dikirim hari ini. Estimasi 2-4 hari kerja ya kak 🚚"),
            CachedTemplate(6, 3, "Nomor resi sudah kami update ya kak, silakan dicek 📦"),
            CachedTemplate(7, 4, "Ditunggu order berikutnya ya kak 🙏 Terima kasih!"),
            CachedTemplate(8, 4, "Jika ada kendala, jangan ragu chat kami ya kak 💬")
        )
        dao.insertCategories(categories)
        dao.insertTemplates(templates)
    }
}
