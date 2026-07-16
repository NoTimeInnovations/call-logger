package com.mydream.calllogger.net

import org.json.JSONObject

/**
 * A partner's WhatsApp Business connection status, as returned by the Worker's
 * `GET /wa-status`. `connected` + `number` come from our DB; `verified` and
 * `paymentIssue` come from a live Meta health check (both may be null if the
 * live check couldn't run). Meta exposes no "payment method on file" field —
 * `paymentIssue` means a billing problem is *blocking* messaging (health 141006).
 */
data class WaStatus(
    val connected: Boolean,
    val number: String? = null,
    val verified: Boolean? = null,
    val businessVerificationStatus: String? = null,
    val paymentIssue: Boolean? = null,
    val canSend: String? = null,
    val error: String? = null,
) {
    companion object {
        fun parse(json: String?): WaStatus? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                WaStatus(
                    connected = o.optBoolean("connected", false),
                    number = o.stringOrNull("number"),
                    verified = if (o.has("verified") && !o.isNull("verified")) o.optBoolean("verified") else null,
                    businessVerificationStatus = o.stringOrNull("businessVerificationStatus"),
                    paymentIssue = if (o.has("paymentIssue") && !o.isNull("paymentIssue")) o.optBoolean("paymentIssue") else null,
                    canSend = o.stringOrNull("canSend"),
                    error = o.stringOrNull("error"),
                )
            }.getOrNull()
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }
    }
}
