package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for queuing offline changes when connectivity is unavailable.
 */
@Entity(tableName = "offline_sync_queue")
data class OfflineSyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "task_id")
    val taskId: String,

    @ColumnInfo(name = "family_id")
    val familyId: String,

    @ColumnInfo(name = "action_type")
    val actionType: String, // "CREATE", "UPDATE", "TOGGLE", "DELETE"

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
