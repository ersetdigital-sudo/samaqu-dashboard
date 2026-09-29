package com.samaqu.keyboard.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.samaqu.keyboard.network.JntCostOption
import com.samaqu.keyboard.network.JntCostRequest
import com.samaqu.keyboard.network.StoreClient
import retrofit2.HttpException

/**
 * Shipping cost lookups for the keyboard's Ongkir panel.
 *
 * All the work happens on the store's server: it maps the destination to a J&T area code from
 * its own 7k-row table and calls J&T with the credentials it keeps in its environment. The
 * keyboard therefore needs no API key of its own, and nothing has to be added to the website.
 */
class ShippingRepository {

    /**
     * @param city kabupaten/kota - must not be blank, the endpoint rejects empty fields.
     * @param district kecamatan, the part that actually decides the area code.
     * @param weightGram parcel weight in grams, must be above zero.
     */
    suspend fun cost(city: String, district: String, weightGram: Int): Result<List<JntCostOption>> =
        try {
            Result.success(
                StoreClient.api
                    .jntCost(
                        JntCostRequest(
                            city = city.trim().uppercase(),
                            district = district.trim().uppercase(),
                            weight = weightGram
                        )
                    )
                    .data
            )
        } catch (e: HttpException) {
            // The store answers 404 with `{"error": "Tidak ada opsi pengiriman J&T ..."}`
            // when the area cannot be resolved, which is the message worth showing.
            Result.failure(IllegalStateException(storeMessage(e), e))
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun storeMessage(e: HttpException): String {
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val detail = runCatching {
            Gson().fromJson(body, JsonObject::class.java)?.get("error")?.asString
        }.getOrNull()
        return detail?.takeIf { it.isNotBlank() } ?: "HTTP ${e.code()}"
    }
}
