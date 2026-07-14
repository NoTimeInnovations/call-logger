package com.mydream.calllogger.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mydream.calllogger.data.AppDatabase
import com.mydream.calllogger.data.CallRepository
import com.mydream.calllogger.net.AccountManager
import com.mydream.calllogger.net.IngestClient
import com.mydream.calllogger.prefs.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads locally-stored calls that haven't reached the backend yet. Runs under
 * WorkManager so it survives process death, waits for connectivity, and retries
 * with backoff. Only rows the backend actually accepted (2xx) are marked synced,
 * so nothing is lost if an upload fails; the backend dedupes on event_key, so a
 * retry after a partial failure never creates duplicates.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SettingsManager(applicationContext)
        val email = settings.email
        if (email.isNullOrBlank()) return@withContext Result.success() // not onboarded yet

        val account = AccountManager(applicationContext)
        // Register this device on first run (or after the token was cleared).
        var token = account.token
        if (token.isNullOrBlank()) {
            token = IngestClient.register(email, settings.deviceId)
                ?: return@withContext Result.retry() // offline, or email isn't a partner yet
            account.token = token
        }

        val repo = CallRepository(applicationContext, AppDatabase.getInstance(applicationContext).callDao())
        try {
            while (true) {
                val batch = repo.unsynced(BATCH_SIZE)
                if (batch.isEmpty()) break
                when (IngestClient.ingest(token!!, settings.deviceId, batch)) {
                    in 200..299 -> repo.markSynced(batch.map { it.id })
                    401 -> {
                        account.clear() // token revoked/expired -> re-register next run
                        return@withContext Result.retry()
                    }
                    else -> return@withContext Result.retry()
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 200
    }
}
