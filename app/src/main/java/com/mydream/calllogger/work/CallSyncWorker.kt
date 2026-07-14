package com.mydream.calllogger.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mydream.calllogger.net.CallUploader

/**
 * Reliable backup uploader under WorkManager (survives process death, waits for
 * connectivity, retries with backoff). The receiver uploads immediately after a call
 * for low latency; this catches anything missed. Server dedupes on event_key.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (CallUploader.uploadPending(applicationContext)) Result.success() else Result.retry()
}
