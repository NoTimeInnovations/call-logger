package com.mydream.calllogger.net

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Securely stores the per-device ingest token minted by the Worker's /register
 * endpoint. Backed by the Android Keystore (AES-256). This token — not any Hasura
 * credential — is what authenticates uploads; the server derives the partner account
 * from it, so it must be kept out of plaintext prefs and cloud backups.
 *
 * If the encrypted store can't be created (rare device/Keystore issues) it falls
 * back to a private prefs file so the app keeps functioning.
 */
class AccountManager(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "call_logger_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }.getOrElse {
        context.applicationContext
            .getSharedPreferences("call_logger_secure_fallback", Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) { prefs.edit().putString(KEY_TOKEN, value).apply() }

    /** The partner this device is attached to — used to open the web flow editor. */
    var partnerId: String?
        get() = prefs.getString(KEY_PARTNER_ID, null)
        set(value) { prefs.edit().putString(KEY_PARTNER_ID, value).apply() }

    val isRegistered: Boolean get() = !token.isNullOrBlank()

    /** Drops the token (e.g. after a 401) so the next sync re-registers. */
    fun clear() = prefs.edit().remove(KEY_TOKEN).apply()

    private companion object {
        const val KEY_TOKEN = "ingest_token"
        const val KEY_PARTNER_ID = "partner_id"
    }
}
