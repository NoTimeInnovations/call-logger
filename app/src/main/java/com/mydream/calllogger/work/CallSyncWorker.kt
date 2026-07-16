package com.mydream.calllogger.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mydream.calllogger.data.AppDatabase
import com.mydream.calllogger.data.CallRepository
import com.mydream.calllogger.net.CallUploader

/**
 * Reliable capture + upload under WorkManager (survives process death, waits for
 * connectivity, retries with backoff).
 *
 * Crucially this RE-SCANS the device call log itself before uploading — it does not
 * rely on the [com.mydream.calllogger.calllog.PhoneStateReceiver] having captured the
 * call. When the app is terminated and the phone is locked (Doze), that receiver's
 * async work is often killed before it can persist the call; WorkManager, however, is
 * restarted by the system to run this worker, and the OS's own call log always holds
 * the call. So a call that arrives while the app is dead/locked is still recorded here
 * — immediately via the one-shot enqueue, and within ~15 min via the periodic net as a
 * backstop. The server dedupes on event_key.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 1) Pull any new calls from the device call log into the local DB. Best-effort:
        //    READ_CALL_LOG could be revoked — still upload whatever is already stored.
        try {
            val db = AppDatabase.getInstance(applicationContext)
            CallRepository(applicationContext, db.callDao()).syncFromDeviceCallLog()
        } catch (_: Exception) {
        }
        // 2) Upload everything not yet synced; retry later if the network/backend fails.
        return if (CallUploader.uploadPending(applicationContext)) Result.success() else Result.retry()
    }
}
