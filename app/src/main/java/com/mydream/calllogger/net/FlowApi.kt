package com.mydream.calllogger.net

import com.mydream.calllogger.BuildConfig
import org.json.JSONObject
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

    data class RunFlowResult(val ok: Boolean, val contact: String?, val error: String?)

    /**
     * POST /run-flow — manually run the partner's saved flow on [number], simulating a
     * call of [simType] ('missed'|'incoming') so call-type conditions in the flow are
     * evaluated (a real manual run has no call type). Used by the in-app "Test flow".
     */
    fun runFlow(token: String, number: String, simType: String): RunFlowResult {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank()) return RunFlowResult(false, null, "Not connected yet.")
        val payload = JSONObject().apply {
            put("number", number)
            put("simType", simType)
        }
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
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299 && obj?.optBoolean("ok") == true) {
                RunFlowResult(true, obj.optString("contact").ifBlank { number }, null)
            } else {
                RunFlowResult(false, null, obj?.optString("error")?.ifBlank { null } ?: "Could not start the flow ($code).")
            }
        } catch (_: Exception) {
            RunFlowResult(false, null, "Network error — please try again.")
        } finally {
            conn.disconnect()
        }
    }
}
