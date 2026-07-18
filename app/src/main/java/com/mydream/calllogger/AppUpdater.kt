package com.mydream.calllogger

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads the OTA APK with the system DownloadManager (shows a download
 * notification) and launches the package installer when it finishes. Installing
 * needs the REQUEST_INSTALL_PACKAGES permission; Android prompts the user to allow
 * "install unknown apps" the first time if it isn't already granted.
 */
object AppUpdater {

    private const val FILE_NAME = "call-logger-update.apk"

    fun downloadAndInstall(context: Context, apkUrl: String) {
        val appCtx = context.applicationContext
        val dm = appCtx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Clear any previous download so the installer never picks up a stale file.
        val dest = File(appCtx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        dest.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Updating Call Logger")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appCtx, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
        val id = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                runCatching { appCtx.unregisterReceiver(this) }
                install(appCtx, dest)
            }
        }
        ContextCompat.registerReceiver(
            appCtx,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun install(context: Context, apk: File) {
        if (!apk.exists()) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }
}
