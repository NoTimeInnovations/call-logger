package com.mydream.calllogger.net

import com.mydream.calllogger.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * WhatsApp template endpoints on the Worker, authenticated by the per-device token
 * (the server derives the partner from it). Same HttpURLConnection + org.json style
 * as [IngestClient]/[FlowApi]; no extra dependencies.
 */
object TemplateApi {

    private const val TIMEOUT_MS = 20_000

    /** GET /templates — the partner's WhatsApp templates, or null on error/not-connected. */
    fun list(token: String): List<WaTemplate>? {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return null
        val conn = (URL("$base/templates").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode in 200..299) {
                WaTemplate.parseList(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    data class SubmitResult(val ok: Boolean, val status: String?, val error: String?)

    /**
     * POST /submit-template — submit a template for Meta approval on the partner's own
     * WABA. Returns ok + status ('PENDING') on success, or a user-facing error message.
     */
    fun submit(
        token: String,
        name: String,
        language: String,
        category: String,
        components: JSONArray,
    ): SubmitResult {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return SubmitResult(false, null, "Not connected yet.")
        val payload = JSONObject().apply {
            put("name", name)
            put("language", language)
            put("category", category)
            put("components", components)
        }
        val conn = (URL("$base/submit-template").openConnection() as HttpURLConnection).apply {
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
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299 && obj?.optBoolean("ok") == true) {
                SubmitResult(true, obj.optString("status", "PENDING"), null)
            } else {
                val msg = obj?.optString("detail")?.ifBlank { null }
                    ?: obj?.optString("error")?.ifBlank { null }
                    ?: "Submit failed ($code)"
                SubmitResult(false, null, msg)
            }
        } catch (_: Exception) {
            SubmitResult(false, null, "Network error — please try again.")
        } finally {
            conn.disconnect()
        }
    }
}
