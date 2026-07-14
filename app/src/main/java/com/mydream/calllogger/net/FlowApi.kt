package com.mydream.calllogger.net

import com.mydream.calllogger.BuildConfig
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
