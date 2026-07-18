package com.mydream.calllogger.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules the call upload worker. */
object CallSync {

    private const val UNIQUE_ONESHOT = "call-sync-now"
    private const val UNIQUE_PERIODIC = "call-sync-periodic"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Kick an immediate upload attempt (after a new call is captured / app opened). */
    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Stop all scheduled call syncing — used when the master switch is turned OFF. */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_PERIODIC)
        wm.cancelUniqueWork(UNIQUE_ONESHOT)
    }

    /** Safety net so anything missed still uploads within ~15 minutes. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
