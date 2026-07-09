package com.mydream.calllogger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single call record stored locally.
 *
 * The unique index on (number, date, type) lets repeated syncs from the device
 * call log be inserted with [androidx.room.OnConflictStrategy.IGNORE] without
 * creating duplicates, while still keeping records that the user may later clear
 * from the system call log.
 */
@Entity(
    tableName = "calls",
    indices = [Index(value = ["number", "date", "type"], unique = true)]
)
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,      // epoch millis when the call happened
    val duration: Long   // call length in seconds
)
