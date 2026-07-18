package com.mydream.calllogger.prefs

import android.content.Context
import java.util.UUID

/** Stores the onboarding email locally. Setup is considered complete once an email is saved. */
class SettingsManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("call_logger_prefs", Context.MODE_PRIVATE)

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value).apply()
        }

    /** Stable per-install identifier (random UUID, not ANDROID_ID). Generated once. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    val isOnboardingComplete: Boolean
        get() = !prefs.getString(KEY_EMAIL, null).isNullOrBlank()

    /** Whether we've already shown the battery-optimisation exemption prompt once. */
    var batteryPromptShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, value).apply()
        }

    /** Master switch: when false, the app stops syncing calls and pauses the flow. Default on. */
    var active: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ACTIVE, value).apply()
        }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_EMAIL = "email"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
        private const val KEY_ACTIVE = "active"
    }
}
