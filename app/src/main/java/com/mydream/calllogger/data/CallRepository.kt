package com.mydream.calllogger.data

import android.content.Context
import android.provider.CallLog
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
                        duration = duration
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
