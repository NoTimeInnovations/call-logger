package com.mydream.calllogger.net

import com.mydream.calllogger.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Reads/writes the partner's flow via the Worker, authenticated by the per-device token. */
object FlowApi {

    private const val TIMEOUT_MS = 20_000

    /** GET /flow — returns the raw JSON body, or null on error. */
    fun getFlow(token: String): String? {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return null
        val conn = (URL("$base/flow").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** GET /wa-status — WhatsApp connection status as raw JSON, or null on error. */
    fun getWaStatus(token: String): String? {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return null
        val conn = (URL("$base/wa-status").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** POST /run-flow — manually run the flow on [number]. Returns (ok, userMessage). */
    fun runFlow(token: String, number: String, name: String?): Pair<Boolean, String> {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return false to "Not connected"
        val payload = JSONObject().put("number", number)
        if (!name.isNullOrBlank()) payload.put("name", name)
        val conn = (URL("$base/run-flow").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code in 200..299 && json?.optBoolean("ok") == true) {
                true to "Flow started for ${json.optString("contact", number)}"
            } else {
                false to (json?.optString("error").orEmptyIfBlank() ?: "Failed (HTTP $code)")
            }
        } catch (e: Exception) {
            false to (e.message ?: "Network error")
        } finally {
            conn.disconnect()
        }
    }

    private fun String?.orEmptyIfBlank(): String? = this?.ifBlank { null }

    /** PUT /flow — returns the HTTP status code, or -1 on error. */
    fun putFlow(token: String, body: String): Int {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return -1
        val conn = (URL("$base/flow").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Exception) {
            -1
        } finally {
            conn.disconnect()
        }
    }
}
