package com.samaqu.keyboard.network

import com.google.gson.GsonBuilder
import com.samaqu.keyboard.util.SamaQuText
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit holder pointed at a Supabase project.
 *
 * Every request automatically carries the Supabase headers:
 *  - `apikey: <anon key>` — required by the Supabase API gateway.
 *  - `Authorization: Bearer <anon key>` — selects the `anon` Postgres role, so Row Level
 *    Security policies decide which rows are readable.
 *
 * The base URL and the key can both be re-pointed at runtime from Settings.
 */
object RetrofitClient {

    /** Used only when nothing is configured yet, so building a client never throws. */
    private const val PLACEHOLDER_BASE_URL = "https://not-configured.invalid/"

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var anonKey: String = ""

    @Volatile
    private var service: ApiService? = null

    /** Normalises the URL (trailing slash required by Retrofit) and rebuilds if either value changed. */
    fun init(url: String, anonKey: String) {
        val normalized = SamaQuText.normalizeBaseUrl(url)
        if (normalized != baseUrl || anonKey != this.anonKey || service == null) {
            baseUrl = normalized
            this.anonKey = anonKey
            service = build(normalized, anonKey)
        }
    }

    val api: ApiService
        get() = service ?: synchronized(this) {
            service ?: build(baseUrl, anonKey).also { service = it }
        }

    private fun build(url: String, key: String): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val supabaseHeaders = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("apikey", key)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(supabaseHeaders)
            .addInterceptor(logging)
            .build()

        val gson = GsonBuilder().setLenient().create()

        return Retrofit.Builder()
            .baseUrl(url.ifBlank { PLACEHOLDER_BASE_URL })
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }
}
