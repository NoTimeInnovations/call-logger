package com.mydream.calllogger.net

import com.mydream.calllogger.BuildConfig
import com.mydream.calllogger.data.CallEntity
import com.mydream.calllogger.data.IdempotencyKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Cloudflare Worker. Uses the built-in HttpURLConnection + org.json
 * (no extra dependencies).
 *
 * - [register] exchanges the partner email for a per-device token (gated by the shared
 *   app key, which is a Worker credential — NOT a Hasura credential).
 * - [ingest] uploads calls authenticated by that token; the server derives the account,
 *   so the app never sends (or can spoof) the account email.
 */
object IngestClient {

    private const val TIMEOUT_MS = 20_000

    /** Registers this device for [email]; returns a per-device token, or null on failure/non-partner. */
    fun register(email: String, deviceId: String): String? {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        val appKey = BuildConfig.INGEST_APP_KEY
        if (base.isBlank() || appKey.isBlank()) return null

        val payload = JSONObject().apply {
            put("email", email)
            put("deviceId", deviceId)
        }
        val conn = open("$base/register", "Bearer $appKey")
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(resp).optString("token").ifBlank { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Uploads calls using a per-device [token]. Returns the HTTP status code, or -1 on error. */
    fun ingest(token: String, deviceId: String, calls: List<CallEntity>, flowBaselineMs: Long = 0L): Int {
        val base = BuildConfig.INGEST_BASE_URL.trimEnd('/')
        if (base.isBlank() || token.isBlank() || calls.isEmpty()) return -1

        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("appVersion", BuildConfig.VERSION_NAME)
            // Server gates follow-up flows to calls at/after this install baseline.
            if (flowBaselineMs > 0L) put("flowBaselineMs", flowBaselineMs)
            put("calls", JSONArray().apply {
                for (c in calls) {
                    put(JSONObject().apply {
                        put("eventKey", c.eventKey.ifBlank { IdempotencyKey.of(c.number, c.date, c.type) })
                        put("numberRaw", c.number)
                        put("e164", c.e164 ?: JSONObject.NULL)
                        put("callType", c.type)
                        put("callEpochMs", c.date)
                        put("durationSec", c.duration)
                        put("tzIana", c.tzIana)
                        put("cachedName", c.name ?: JSONObject.NULL)
                    })
                }
            })
        }
        val conn = open("$base/ingest", "Bearer $token")
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Exception) {
            -1
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, authorization: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", authorization)
        }
}
