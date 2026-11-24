package com.arthatis.wallet.data

import com.arthatis.wallet.core.RateOverride
import com.arthatis.wallet.core.Rates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/** Daily rates straight from the phone to api.frankfurter.dev (v1). Nothing passes through our server. */
class RatesRepo(private val prefs: Prefs) {
    private val foreign = listOf("USD", "SGD")

    private fun cached(): Map<String, BigDecimal> =
        prefs.cachedRates.split(';').mapNotNull { part ->
            val (code, value) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            value.toBigDecimalOrNull()?.let { code to it }
        }.toMap()

    fun build(overrides: List<RateOverride>): Rates {
        val manual = overrides.filter { it.deletedAt == null }.mapNotNull { o -> o.rate.toBigDecimalOrNull()?.let { o.id to it } }.toMap()
        return Rates(cached(), manual)
    }

    /** Returns true when at least one rate was refreshed. Failures keep the last good cache. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val fresh = cached().toMutableMap()
        var any = false
        for (code in foreign) {
            val value = runCatching { fetch(code) }.getOrNull() ?: continue
            fresh[code] = value; any = true
        }
        if (any) {
            prefs.cachedRates = fresh.entries.joinToString(";") { "${it.key}=${it.value.toPlainString()}" }
            prefs.ratesFetchedAt = System.currentTimeMillis()
        }
        any
    }

    private fun fetch(base: String): BigDecimal? {
        val connection = URL("https://api.frankfurter.dev/v1/latest?base=$base&symbols=IDR").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000; connection.readTimeout = 8_000
            if (connection.responseCode !in 200..299) return null
            val body = Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            return (body["rates"] as? JsonObject)?.get("IDR")?.jsonPrimitive?.content?.toBigDecimalOrNull()
        } finally {
            connection.disconnect()
        }
    }
}
