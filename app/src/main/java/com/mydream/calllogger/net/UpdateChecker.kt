package com.mydream.calllogger.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer app build. Releases are tagged v1.0.<run>,
 * where <run> is the CI run number that also drives this app's versionCode — so a
 * newer release simply has a higher number than BuildConfig.VERSION_CODE. Requires
 * the repo to be public (the app calls the GitHub API unauthenticated).
 */
object UpdateChecker {

    private const val LATEST_RELEASE =
        "https://api.github.com/repos/NoTimeInnovations/call-logger/releases/latest"
    private const val TIMEOUT_MS = 15_000

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String)

    /** An Update if the latest release is newer than [currentVersionCode], else null. */
    fun check(currentVersionCode: Int): Update? {
        val conn = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name") // e.g. "v1.0.24"
            val latest = tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (latest <= currentVersionCode) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            apkUrl?.takeIf { it.isNotBlank() }?.let { Update(latest, tag.removePrefix("v"), it) }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
