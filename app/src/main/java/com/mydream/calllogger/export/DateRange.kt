package com.mydream.calllogger.export

import java.util.Calendar

/** The duration filters the user can pick, each resolving to an inclusive time window. */
enum class DateRange(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    DAY_BEFORE_YESTERDAY("Day before yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    ALL("All");

    /** Returns [startMillisInclusive, endMillisInclusive] for this range relative to [now]. */
    fun bounds(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val startOfToday = startOfDay(now)
        return when (this) {
            TODAY -> startOfToday to now
            YESTERDAY -> addDays(startOfToday, -1) to (startOfToday - 1)
            DAY_BEFORE_YESTERDAY -> addDays(startOfToday, -2) to (addDays(startOfToday, -1) - 1)
            THIS_WEEK -> startOfWeek(now) to now
            THIS_MONTH -> startOfMonth(now) to now
            ALL -> 0L to now
        }
    }

    private fun startOfDay(now: Long): Long = calendar(now).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun addDays(base: Long, days: Int): Long =
        calendar(base).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    private fun startOfWeek(now: Long): Long {
        val c = calendar(startOfDay(now))
        c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
        // Setting DAY_OF_WEEK can jump forward when the first weekday is after
        // today; step back a week so the window never starts in the future.
        if (c.timeInMillis > now) c.add(Calendar.DAY_OF_MONTH, -7)
        return c.timeInMillis
    }

    private fun startOfMonth(now: Long): Long =
        calendar(startOfDay(now)).apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

    private fun calendar(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }
}
