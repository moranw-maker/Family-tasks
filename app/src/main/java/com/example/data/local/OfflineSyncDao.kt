package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineSyncDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueAction(action: OfflineSyncQueueEntity): Long

    @Query("SELECT * FROM offline_sync_queue ORDER BY timestamp ASC")
    suspend fun getPendingActions(): List<OfflineSyncQueueEntity>

    @Query("DELETE FROM offline_sync_queue WHERE id = :id")
    suspend fun removeActionById(id: Long)

    @Query("DELETE FROM offline_sync_queue WHERE task_id = :taskId")
    suspend fun removeActionsByTaskId(taskId: String)

    @Query("SELECT COUNT(*) FROM offline_sync_queue")
    fun getPendingCount(): Flow<Int>
}
