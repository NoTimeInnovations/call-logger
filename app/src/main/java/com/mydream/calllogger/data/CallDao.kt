package com.mydream.calllogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {

    /** Returns row ids; ignored (duplicate) rows come back as -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(calls: List<CallEntity>): List<Long>

    /**
     * Backfills a contact name onto an already-stored call. The system often
     * resolves CACHED_NAME after the first sync, so this refreshes rows whose
     * name is still missing without disturbing the auto-generated id.
     */
    @Query(
        "UPDATE calls SET name = :name " +
            "WHERE number = :number AND date = :date AND type = :type " +
            "AND (name IS NULL OR name = '')"
    )
    suspend fun updateNameIfMissing(number: String, date: Long, type: Int, name: String)

    @Query("SELECT * FROM calls WHERE date BETWEEN :start AND :end ORDER BY date DESC")
    fun observeRange(start: Long, end: Long): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE date BETWEEN :start AND :end ORDER BY date DESC")
    suspend fun getRange(start: Long, end: Long): List<CallEntity>

    @Query("SELECT COUNT(*) FROM calls")
    suspend fun count(): Int

    /** Oldest-first batch of calls not yet accepted by the backend. */
    @Query("SELECT * FROM calls WHERE synced = 0 ORDER BY date ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<CallEntity>

    /** Marks the given calls as uploaded, once the backend has returned 2xx. */
    @Query("UPDATE calls SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** Live count of calls still waiting to upload (for a future "N pending" UI). */
    @Query("SELECT COUNT(*) FROM calls WHERE synced = 0")
    fun observePendingCount(): Flow<Int>
}
