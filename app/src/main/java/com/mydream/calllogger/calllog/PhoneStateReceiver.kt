package com.mydream.calllogger.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.mydream.calllogger.data.AppDatabase
import com.mydream.calllogger.data.CallRepository
import com.mydream.calllogger.net.CallUploader
import com.mydream.calllogger.work.CallSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fires on every phone-state change. When a call ends the device returns to the
 * IDLE state and the system writes the new call-log entry a moment later.
 *
 * We enqueue the WorkManager sync FIRST — it survives process death, runs after the
 * app is terminated, re-scans the device call log itself, and retries — so a call
 * that arrives while the app is dead / the phone is locked is still captured even if
 * this receiver's process is killed a moment from now. The inline block below is then
 * a best-effort low-latency attempt while we're already awake; if it loses the race
 * (or is killed) the worker still does the job, and the local DB + server both dedupe.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        val appContext = context.applicationContext

        // Master switch OFF → don't capture or upload anything.
        if (!com.mydream.calllogger.prefs.SettingsManager(appContext).active) return

        // Reliable path first — guaranteed to be scheduled even if we're killed next.
        CallSync.enqueueNow(appContext)

        // Best-effort low-latency capture while the process is still awake.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Give the platform time to persist the finished call.
                delay(2500)
                val db = AppDatabase.getInstance(appContext)
                CallRepository(appContext, db.callDao()).syncFromDeviceCallLog()
                CallUploader.uploadPending(appContext)
            } catch (_: Exception) {
                // READ_CALL_LOG may be missing; the worker will retry regardless.
            } finally {
                pending.finish()
            }
        }
    }
}
