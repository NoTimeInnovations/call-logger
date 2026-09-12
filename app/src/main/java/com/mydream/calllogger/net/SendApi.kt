package com.mydream.calllogger.net

import com.mydream.calllogger.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sends an approved template to a set of callers "now" by creating a scheduled message
 * on the Worker (POST /schedule) with `scheduledAt` = now and an explicit selected
 * audience. Reuses the Worker's existing send engine (opt-out, dedupe, tier caps,
 * per-target logging) — no new send path. Authenticated by the per-device token.
 */
object SendApi {

    private const val TIMEOUT_MS = 20_000

    data class Recipient(val number: String, val name: String?)
    data class Result(val ok: Boolean, val error: String?)

    fun sendNow(
        token: String,
        template: String,
        language: String,
        params: List<String>,
        recipients: List<Recipient>,
    ): Result {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return Result(false, "Not connected yet.")
        if (recipients.isEmpty()) return Result(false, "No recipients selected.")

        val contacts = JSONArray().apply {
            recipients.forEach { r ->
                put(JSONObject().apply {
                    put("number", r.number)
                    put("name", r.name ?: JSONObject.NULL)
                })
            }
        }
        val payload = JSONObject().apply {
            put("template", template)
            put("language", language)
            put("params", JSONArray(params))
            put("name", "Day send")
            put("scheduledAt", nowIso())
            put("audience", JSONObject().apply {
                put("mode", "selected")
                put("contacts", contacts)
            })
        }
        val conn = (URL("$base/schedule").openConnection() as HttpURLConnection).apply {
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
            if (code in 200..299) {
                Result(true, null)
            } else {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()?.ifBlank { null }
                Result(false, msg ?: "Send failed ($code)")
            }
        } catch (_: Exception) {
            Result(false, "Network error — please try again.")
        } finally {
            conn.disconnect()
        }
    }

    /** ISO-8601 UTC timestamp built without java.time (minSdk 24 has no desugaring). */
    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
