package com.mydream.calllogger.net

import android.content.Context
import com.mydream.calllogger.data.AppDatabase
import com.mydream.calllogger.data.CallRepository
import com.mydream.calllogger.prefs.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uploads all locally-stored calls that haven't reached the backend yet. Shared by
 * the [com.mydream.calllogger.calllog.PhoneStateReceiver] (immediate, low-latency
 * upload right after a call) and [com.mydream.calllogger.work.CallSyncWorker]
 * (reliable retrying backup). Registers a device token on first use.
 *
 * Returns true if everything uploaded (or nothing was pending / not onboarded);
 * false if the caller should retry later.
 */
object CallUploader {

    private const val BATCH_SIZE = 200

    suspend fun uploadPending(context: Context): Boolean = withContext(Dispatchers.IO) {
        val settings = SettingsManager(context)
        val email = settings.email
        if (email.isNullOrBlank()) return@withContext true // not onboarded yet

        val account = AccountManager(context)
        var token = account.token
        if (token.isNullOrBlank()) {
            token = IngestClient.register(email, settings.deviceId) ?: return@withContext false
            account.token = token
        }

        val repo = CallRepository(context, AppDatabase.getInstance(context).callDao())
        try {
            while (true) {
                val batch = repo.unsynced(BATCH_SIZE)
                if (batch.isEmpty()) break
                when (IngestClient.ingest(token!!, settings.deviceId, batch)) {
                    in 200..299 -> repo.markSynced(batch.map { it.id })
                    401 -> {
                        account.clear() // token revoked -> re-register next run
                        return@withContext false
                    }
                    else -> return@withContext false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
