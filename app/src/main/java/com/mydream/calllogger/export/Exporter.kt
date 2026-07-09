package com.mydream.calllogger.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.mydream.calllogger.data.CallEntity
import com.mydream.calllogger.data.CallTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Builds an .xlsx from the filtered calls and saves it into the public Downloads folder. */
class Exporter(private val context: Context) {

    data class Result(val uri: Uri, val fileName: String)

    suspend fun exportToDownloads(
        range: DateRange,
        calls: List<CallEntity>,
        nowMillis: Long
    ): Result = withContext(Dispatchers.IO) {
        val headers = listOf(
            "#", "Name", "Number", "Type", "Date", "Time", "Duration", "Duration (sec)"
        )
        val rows = calls.mapIndexed { i, c ->
            listOf(
                XlsxWriter.Number((i + 1).toDouble()),
                XlsxWriter.Text(c.name ?: ""),
                XlsxWriter.Text(c.number),
                XlsxWriter.Text(CallTypes.label(c.type)),
                XlsxWriter.Text(Formatters.date(c.date)),
                XlsxWriter.Text(Formatters.time(c.date)),
                XlsxWriter.Text(Formatters.duration(c.duration)),
                XlsxWriter.Number(c.duration.toDouble())
            )
        }
        val fileName = "CallLog_${range.name.lowercase()}_${Formatters.fileStamp(nowMillis)}.xlsx"

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(fileName, headers, rows)
        } else {
            saveToLegacyDownloads(fileName, headers, rows)
        }
        Result(uri, fileName)
    }

    private fun saveViaMediaStore(
        fileName: String,
        headers: List<String>,
        rows: List<List<XlsxWriter.Cell>>
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a file in Downloads")
        resolver.openOutputStream(uri)?.use { os ->
            XlsxWriter.write(os, "Call Log", headers, rows)
        } ?: error("Could not open the output file")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyDownloads(
        fileName: String,
        headers: List<String>,
        rows: List<List<XlsxWriter.Cell>>
    ): Uri {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { os ->
            XlsxWriter.write(os, "Call Log", headers, rows)
        }
        // Return a shareable content:// uri rather than a raw file:// path.
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    companion object {
        const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
