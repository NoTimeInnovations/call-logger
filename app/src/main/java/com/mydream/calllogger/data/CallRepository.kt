package com.mydream.calllogger.data

import android.content.Context
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Single source of truth for call data. Reads the system call log via the
 * [CallLog] content provider and mirrors it into the local Room database so the
 * data survives even if the user later clears their phone's call history.
 */
class CallRepository(
    private val context: Context,
    private val dao: CallDao
) {
    fun observeRange(start: Long, end: Long): Flow<List<CallEntity>> = dao.observeRange(start, end)

    suspend fun getRange(start: Long, end: Long): List<CallEntity> = dao.getRange(start, end)

    suspend fun count(): Int = dao.count()

    /** Calls not yet accepted by the backend, oldest first. */
    suspend fun unsynced(limit: Int): List<CallEntity> = dao.unsynced(limit)

    /** Marks the given calls as uploaded. */
    suspend fun markSynced(ids: List<Long>) = dao.markSynced(ids)

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** ISO-3166 region for E.164 normalization; SIM/network country, defaulting to India. */
    private fun region(): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        return (iso ?: "IN").uppercase()
    }

    /**
     * Reads the device call log and stores any new entries locally.
     * Requires the READ_CALL_LOG permission. Returns the number of newly
     * inserted rows.
     */
    suspend fun syncFromDeviceCallLog(): Int = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val entities = ArrayList<CallEntity>()
        val region = region()
        val tz = TimeZone.getDefault().id
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext()) {
                val number = cursor.getString(numberIdx)?.takeIf { it.isNotBlank() } ?: "Unknown"
                val name = cursor.getString(nameIdx)
                val type = cursor.getInt(typeIdx)
                val date = cursor.getLong(dateIdx)
                val duration = cursor.getLong(durationIdx)
                entities.add(
                    CallEntity(
                        number = number,
                        name = name,
                        type = type,
                        date = date,
                        duration = duration,
                        eventKey = IdempotencyKey.of(number, date, type),
                        e164 = runCatching { PhoneNumberUtils.formatNumberToE164(number, region) }
                            .getOrNull(),
                        tzIana = tz
                    )
                )
            }
        }
        if (entities.isEmpty()) {
            0
        } else {
            val ids = dao.insertAll(entities)
            var inserted = 0
            ids.forEachIndexed { index, id ->
                if (id != -1L) {
                    inserted++
                } else {
                    // Duplicate row: refresh its name if the device now knows one.
                    entities[index].name?.takeIf { it.isNotBlank() }?.let { name ->
                        val e = entities[index]
                        dao.updateNameIfMissing(e.number, e.date, e.type, name)
                    }
                }
            }
            inserted
        }
    }
}
