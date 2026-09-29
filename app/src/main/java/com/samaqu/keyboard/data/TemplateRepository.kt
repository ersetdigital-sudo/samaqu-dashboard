package com.samaqu.keyboard.data

import android.content.Context
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
 *   methods write to Supabase first and then re-sync, so a row always exists
 *   server-side before it exists on screen. Nothing is ever edited in the cache alone,
 *   because the next sync would discard it.
 */
class TemplateRepository(ctx: Context) {

    private val dao: TemplateDao = AppDatabase.get(ctx).templateDao()
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

    /** Pulls templates from Supabase. Returns success/failure via [Result]. */
    suspend fun sync(): Result<Unit> = runCatching {
        val remote = api().getTemplates()

        dao.clearTemplates()
        dao.clearCategories()
        // Postgres ids are bigint; the Room cache keys are Int, so narrow them here.
        dao.insertCategories(remote.map { CachedCategory(it.id.toInt(), it.name) })
        dao.insertTemplates(
            remote.flatMap { category ->
                category.templates.map { CachedTemplate(it.id.toInt(), category.id.toInt(), it.content) }
            }
        )
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

    /** Runs a mutation on Supabase, then replaces the cache with the server's answer. */
    private suspend fun write(block: suspend () -> Response<ResponseBody>): Result<Unit> = runCatching {
        val response = block()
        if (!response.isSuccessful) {
            // PostgREST explains itself in the body (RLS denial, constraint, ...); surface
            // that instead of the bare "HTTP 401" Retrofit would put in the exception.
            error(postgrestMessage(response))
        }
        sync().getOrThrow()
    }

    private fun postgrestMessage(response: Response<ResponseBody>): String {
        val body = runCatching { response.errorBody()?.string() }.getOrNull()
        val detail = runCatching {
            Gson().fromJson(body, com.google.gson.JsonObject::class.java)
                ?.get("message")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return detail ?: "HTTP ${response.code()}"
    }

    suspend fun addCategory(name: String, displayOrder: Int): Result<Unit> =
        write { api().createCategory(CategoryWrite(name = name, displayOrder = displayOrder)) }

    suspend fun renameCategory(id: Int, name: String): Result<Unit> =
        write { api().updateCategory(categoryUrl(id), CategoryWrite(name = name)) }

    /** Deletes a category; its templates go with it via `on delete cascade`. */
    suspend fun deleteCategory(id: Int): Result<Unit> =
        write { api().deleteCategory(categoryUrl(id)) }

    suspend fun addTemplate(categoryId: Int, content: String, displayOrder: Int): Result<Unit> =
        write {
            api().createTemplate(
                TemplateWrite(categoryId = categoryId.toLong(), content = content, displayOrder = displayOrder)
            )
        }

    suspend fun updateTemplate(id: Int, content: String): Result<Unit> =
        write { api().updateTemplate(templateUrl(id), TemplateWrite(content = content)) }

    suspend fun deleteTemplate(id: Int): Result<Unit> =
        write { api().deleteTemplate(templateUrl(id)) }

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
