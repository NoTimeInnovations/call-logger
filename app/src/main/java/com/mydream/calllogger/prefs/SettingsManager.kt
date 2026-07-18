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

    /**
     * Epoch-ms captured at onboarding. Calls that happened BEFORE this are pre-install
     * history and must not trigger follow-up flows — the server gates on it so a fresh
     * install (which uploads the whole call log at once) never messages past callers.
     * 0 until set (older installs / already-onboarded users => server does not gate).
     */
    var flowBaselineMs: Long
        get() = prefs.getLong(KEY_FLOW_BASELINE_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_FLOW_BASELINE_MS, value).apply()
        }

    /** Sets the flow baseline to [nowMs] once, on first onboarding; never moves it later. */
    fun ensureFlowBaseline(nowMs: Long) {
        if (prefs.getLong(KEY_FLOW_BASELINE_MS, 0L) <= 0L) {
            prefs.edit().putLong(KEY_FLOW_BASELINE_MS, nowMs).apply()
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_EMAIL = "email"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
        private const val KEY_ACTIVE = "active"
        private const val KEY_FLOW_BASELINE_MS = "flow_baseline_ms"
    }
}
