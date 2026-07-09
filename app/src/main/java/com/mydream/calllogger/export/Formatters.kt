package com.mydream.calllogger.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Date/time/duration formatting helpers. New [SimpleDateFormat] instances are
 * created per call because that class is not thread-safe and formatting happens
 * from both the UI thread and background export.
 */
object Formatters {

    fun date(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

    fun time(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

    fun dateTime(millis: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))

    fun fileStamp(millis: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis))

    /** Formats a call length (seconds) as H:MM:SS, or M:SS when under an hour. */
    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
