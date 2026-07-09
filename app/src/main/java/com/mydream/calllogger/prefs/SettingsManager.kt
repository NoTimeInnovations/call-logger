package com.mydream.calllogger.prefs

import android.content.Context

/** Stores the onboarding email locally. Setup is considered complete once an email is saved. */
class SettingsManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("call_logger_prefs", Context.MODE_PRIVATE)

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value).apply()
        }

    val isOnboardingComplete: Boolean
        get() = !prefs.getString(KEY_EMAIL, null).isNullOrBlank()

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_EMAIL = "email"
    }
}
