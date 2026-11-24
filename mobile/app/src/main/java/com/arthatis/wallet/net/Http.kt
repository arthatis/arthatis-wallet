package com.arthatis.wallet.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, val code: String?, val body: JsonObject?) : Exception("HTTP $status ${code ?: ""}")

/** Minimal JSON-over-HTTP client. No third-party dependency; no telemetry. */
class Http(private val base: () -> String?, private val token: () -> String?) {
    suspend fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
        auth: Boolean = true,
    ): JsonObject = withContext(Dispatchers.IO) {
        val root = base() ?: throw ApiException(0, "no_server", null)
        val connection = URL("$root$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("accept", "application/json")
            if (auth) token()?.let { connection.setRequestProperty("authorization", "Bearer $it") }
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("content-type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (status !in 200..299) {
                throw ApiException(status, (json?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content, json)
            }
            json ?: JsonObject(emptyMap())
        } finally {
            connection.disconnect()
        }
    }
}
