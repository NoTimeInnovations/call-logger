package com.mydream.calllogger.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the OS battery-optimisation (Doze) exemption. Without the exemption,
 * Android defers the call-sync worker for long stretches while the phone is locked,
 * so calls that arrive when the app is terminated show up late (or not until the app
 * is next opened). A call logger legitimately needs to run in the background.
 */
object BatteryOptimization {

    /** True if the app is already exempt from battery optimisation (or the OS is too old to matter). */
    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Directly ask the user to exempt this app (one-tap system dialog). Falls back to the
     * battery-optimisation settings list if the direct request is unavailable on the device.
     */
    fun request(context: Context) {
        val pkg = context.packageName
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(direct) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
