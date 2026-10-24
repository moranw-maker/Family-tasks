package com.example.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Task data model class annotated for both Room (local SQLite persistence)
 * and Firebase Firestore (cloud real-time synchronization).
 *
 * Explicitly includes the required fields:
 * - Task Name: [taskName] (or alias [name])
 * - Due Date: [dueDate]
 * - Assigned Family Member: [assignedFamilyMember]
 * - Completion Status: [isCompleted] (or alias [completionStatus])
 *
 * All parameters supply default arguments to generate the public no-argument
 * constructor required for Firestore document deserialization (via toObject()).
 */
@IgnoreExtraProperties
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey
    @DocumentId
    @ColumnInfo(name = "id")
    @get:PropertyName("id")
    @set:PropertyName("id")
    var id: String = "",

    @ColumnInfo(name = "user_id")
    @get:PropertyName("user_id")
    @set:PropertyName("user_id")
    var userId: String = "",

    @ColumnInfo(name = "task_name")
    @get:PropertyName("task_name")
    @set:PropertyName("task_name")
    var taskName: String = "",

    @ColumnInfo(name = "description")
    @get:PropertyName("description")
    @set:PropertyName("description")
    var description: String = "",

    @ColumnInfo(name = "due_date")
    @get:PropertyName("due_date")
    @set:PropertyName("due_date")
    var dueDate: String = "",

    @ColumnInfo(name = "due_date_epoch_day")
    @get:PropertyName("due_date_epoch_day")
    @set:PropertyName("due_date_epoch_day")
    var dueDateEpochDay: Long = 0L,

    @ColumnInfo(name = "due_time")
    @get:PropertyName("due_time")
    @set:PropertyName("due_time")
    var dueTime: String = "18:00",

    @ColumnInfo(name = "has_due_time")
    @get:PropertyName("has_due_time")
    @set:PropertyName("has_due_time")
    var hasDueTime: Boolean = false,

    @ColumnInfo(name = "reminder_offset")
    @get:PropertyName("reminder_offset")
    @set:PropertyName("reminder_offset")
    var reminderOffset: String = "NONE",

    @ColumnInfo(name = "assigned_family_member")
    @get:PropertyName("assigned_family_member")
    @set:PropertyName("assigned_family_member")
    var assignedFamilyMember: String = "",

    @ColumnInfo(name = "assigned_member_id")
    @get:PropertyName("assigned_member_id")
    @set:PropertyName("assigned_member_id")
    var assignedMemberId: String = "",

    @ColumnInfo(name = "is_completed")
    @get:PropertyName("is_completed")
    @set:PropertyName("is_completed")
    var isCompleted: Boolean = false,

    @ColumnInfo(name = "priority")
    @get:PropertyName("priority")
    @set:PropertyName("priority")
    var priority: String = "MEDIUM",

    @ColumnInfo(name = "category")
    @get:PropertyName("category")
    @set:PropertyName("category")
    var category: String = "OTHER",

    @ColumnInfo(name = "points")
    @get:PropertyName("points")
    @set:PropertyName("points")
    var points: Int = 10,

    @ColumnInfo(name = "recurrence")
    @get:PropertyName("recurrence")
    @set:PropertyName("recurrence")
    var recurrence: String = "DAILY",

    @ColumnInfo(name = "completed_at_millis")
    @get:PropertyName("completed_at_millis")
    @set:PropertyName("completed_at_millis")
    var completedAtMillis: Long? = null,

    @ColumnInfo(name = "completed_by_member_id")
    @get:PropertyName("completed_by_member_id")
    @set:PropertyName("completed_by_member_id")
    var completedByMemberId: String? = null,

    @ColumnInfo(name = "family_id")
    @get:PropertyName("family_id")
    @set:PropertyName("family_id")
    var familyId: String = "",

    @ColumnInfo(name = "approved_by_parent")
    @get:PropertyName("approved_by_parent")
    @set:PropertyName("approved_by_parent")
    var approvedByParent: Boolean = false,

    @ServerTimestamp
    @ColumnInfo(name = "created_at")
    @get:PropertyName("created_at")
    @set:PropertyName("created_at")
    var createdAt: Date? = null,

    @ServerTimestamp
    @ColumnInfo(name = "updated_at")
    @get:PropertyName("updated_at")
    @set:PropertyName("updated_at")
    var updatedAt: Date? = null
) {
    /**
     * Alias for task name
     */
    @get:Ignore
    @get:Exclude
    val name: String
        get() = taskName

    /**
     * Alias for completion status
     */
    @get:Ignore
    @get:Exclude
    val completionStatus: Boolean
        get() = isCompleted

    /**
     * Convenience secondary constructor with essential fields
     */
    @Ignore
    constructor(
        taskName: String,
        dueDate: String,
        assignedFamilyMember: String,
        isCompleted: Boolean = false
    ) : this(
        id = java.util.UUID.randomUUID().toString(),
        taskName = taskName,
        dueDate = dueDate,
        assignedFamilyMember = assignedFamilyMember,
        isCompleted = isCompleted
    )

    /**
     * Converts to UI domain model [FamilyTask]
     */
    @Exclude
    fun toFamilyTask(): FamilyTask {
        val parsedOffset = TaskReminderOffset.fromRaw(reminderOffset)
        return FamilyTask(
            id = id,
            title = taskName,
            description = description,
            category = try { TaskCategory.valueOf(category) } catch (_: Exception) { TaskCategory.OTHER },
            assignedToMemberId = assignedMemberId.ifEmpty { assignedFamilyMember },
            dateEpochDay = if (dueDateEpochDay != 0L) dueDateEpochDay else System.currentTimeMillis() / (1000 * 60 * 60 * 24),
            dueTimeFormatted = dueTime,
            hasDueTime = hasDueTime,
            reminderOffset = parsedOffset,
             reminderEnabled = hasDueTime && parsedOffset != TaskReminderOffset.NONE,
            priority = try { TaskPriority.valueOf(priority) } catch (_: Exception) { TaskPriority.MEDIUM },
            points = points,
            recurrence = try { TaskRecurrence.valueOf(recurrence) } catch (_: Exception) { TaskRecurrence.DAILY },
            isCompleted = isCompleted,
            completedAtMillis = completedAtMillis,
            completedByMemberId = completedByMemberId,
            familyId = familyId,
            approvedByParent = approvedByParent,
            createdAtMillis = createdAt?.time ?: System.currentTimeMillis(),
            updatedAtMillis = updatedAt?.time ?: System.currentTimeMillis()
        )
    }

    companion object {
        /**
         * Creates a dual-annotated [Task] from a domain [FamilyTask]
         */
        fun fromFamilyTask(
            familyTask: FamilyTask,
            assignedMemberName: String = "",
            userId: String = ""
        ): Task {
            return Task(
                id = familyTask.id,
                userId = userId,
                taskName = familyTask.title,
                description = familyTask.description,
                dueDate = familyTask.dueTimeFormatted,
                dueDateEpochDay = familyTask.dateEpochDay,
                dueTime = familyTask.dueTimeFormatted,
                hasDueTime = familyTask.hasDueTime,
                reminderOffset = familyTask.reminderOffset.name,
                assignedFamilyMember = assignedMemberName.ifEmpty { familyTask.assignedToMemberId },
                assignedMemberId = familyTask.assignedToMemberId,
                isCompleted = familyTask.isCompleted,
                priority = familyTask.priority.name,
                category = familyTask.category.name,
                points = familyTask.points,
                recurrence = familyTask.recurrence.name,
                completedAtMillis = familyTask.completedAtMillis,
                completedByMemberId = familyTask.completedByMemberId,
                familyId = familyTask.familyId,
                approvedByParent = familyTask.approvedByParent,
                createdAt = Date(familyTask.createdAtMillis),
                updatedAt = Date(familyTask.updatedAtMillis)
            )
        }
    }
}
