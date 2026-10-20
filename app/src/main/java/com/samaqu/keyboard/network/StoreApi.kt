package com.samaqu.keyboard.network

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * The SAMAQU store website (Next.js on Vercel), as used by the keyboard's Ongkir panel.
 *
 * Deliberately a **separate** client from [RetrofitClient]: the Supabase client injects
 * `apikey` / `Authorization` headers into every call, and those must never be sent to the
 * store's own domain.
 *
 * Nothing secret travels here. The J&T credentials live in the website's server environment
 * (`JNT_TARIFF_KEY`, ...), so the keyboard only ever talks to a public endpoint that the
 * checkout page uses too.
 */
interface StoreApi {

    /**
     * Tariff check. The store maps the destination to a J&T area code itself; `district` is what
     * actually decides it and `city` disambiguates between same-named districts. Weight is in
     * grams.
     *
     * All three fields must be non-empty and the weight above zero, otherwise the endpoint
     * answers `400 {"error":"city, district, weight wajib"}` - verified against the live API.
     */
    @POST("api/shipping/jnt-cost")
    suspend fun jntCost(@Body body: JntCostRequest): JntCostResponse
}

object StoreClient {

    /** Trailing slash required by Retrofit. */
    const val BASE_URL = "https://www.samaqu.id/"

    private val service: StoreApi by lazy {
        val client = OkHttpClient.Builder()
            // J&T quotes are fetched by the store on the way through, so allow more
            // headroom than the Supabase client's 10s.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
            .build()
            .create(StoreApi::class.java)
    }

    val api: StoreApi
        get() = service
}
