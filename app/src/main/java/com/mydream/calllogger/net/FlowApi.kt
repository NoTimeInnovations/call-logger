package com.mydream.calllogger.net

import com.mydream.calllogger.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small worker call the app still makes directly, authenticated by the per-device
 * token. Flow editing and testing now live on the web (menuthere.com/flow/<id>), so
 * only the WhatsApp status card remains here.
 */
object FlowApi {

    private const val TIMEOUT_MS = 20_000

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

    /** POST /flow-enabled — enable/disable the partner's flow. Returns true on success. */
    fun setFlowEnabled(token: String, enabled: Boolean): Boolean {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return false
        val conn = (URL("$base/flow-enabled").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write("{\"enabled\":$enabled}".toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
