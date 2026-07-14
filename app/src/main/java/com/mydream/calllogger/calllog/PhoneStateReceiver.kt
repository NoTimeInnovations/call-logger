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
 * IDLE state and the system writes the new call-log entry a moment later, so we
 * wait briefly and then sync it into the local database — giving near real-time
 * capture on top of the full sync that runs whenever the app is opened.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Give the platform time to persist the finished call.
                delay(2500)
                val db = AppDatabase.getInstance(appContext)
                CallRepository(appContext, db.callDao()).syncFromDeviceCallLog()
                // Upload right now for low latency (no WorkManager scheduling delay)…
                CallUploader.uploadPending(appContext)
                // …and enqueue the worker as a retrying backup if that attempt failed/timed out.
                CallSync.enqueueNow(appContext)
            } catch (_: Exception) {
                // READ_CALL_LOG may be missing; nothing else to do.
            } finally {
                pending.finish()
            }
        }
    }
}
