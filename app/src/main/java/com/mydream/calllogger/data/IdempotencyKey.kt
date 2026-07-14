package com.mydream.calllogger.data

import java.security.MessageDigest

/**
 * Stable, content-only idempotency key for a call. Hashed from the immutable call
 * facts (digits of the number, timestamp, type) — deliberately NOT the account or
 * device — so the same call always yields the same key across resyncs, reinstalls
 * and account changes. The backend dedupes on (account_email, event_key).
 */
object IdempotencyKey {
    fun of(number: String, date: Long, type: Int): String {
        val digits = number.filter { it.isDigit() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$digits|$date|$type".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
