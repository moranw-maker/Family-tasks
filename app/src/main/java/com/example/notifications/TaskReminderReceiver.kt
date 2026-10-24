package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.model.TaskReminderOffset
import com.example.util.NotificationHelper

/**
 * Receives scheduled `AlarmManager` broadcasts for family task execution-time reminders
 * ([TaskReminderScheduler.ACTION_TRIGGER_TASK_REMINDER]) even when the app is closed.
 *
 * Verifies the versioned `scheduleSignature` before firing so that:
 * 1. An already-fired reminder version is never displayed twice.
 * 2. A stale reminder from a previous time/offset (or a cancelled/completed task) is ignored.
 * 3. If a task's time or reminder offset was edited to a new schedule, the new `scheduleSignature`
 *    is recognized as a fresh version and triggers normally.
 */
class TaskReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskReminderScheduler.ACTION_TRIGGER_TASK_REMINDER) return

        val taskId = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_ID)?.trim().orEmpty()
        if (taskId.isEmpty()) return

        val scheduleSignature = intent.getStringExtra(TaskReminderScheduler.EXTRA_SCHEDULE_SIGNATURE).orEmpty()
        if (scheduleSignature.isNotBlank()) {
            // 1. Prevent duplicate firing of the exact same schedule version
            if (TaskReminderScheduler.isSignatureAlreadyFired(context, scheduleSignature)) {
                return
            }
            // 2. Verify this signature still matches the active scheduled signature for taskId
            val activeSignature = TaskReminderScheduler.getActiveScheduledSignature(context, taskId)
            if (activeSignature != null && activeSignature != scheduleSignature) {
                return
            }
        }

        val taskTitle = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: "משימה משפחתית"
        val dueTimeFormatted = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_DUE_TIME)
            ?.takeIf { it.isNotBlank() }
            ?: ""
        val offsetRaw = intent.getStringExtra(TaskReminderScheduler.EXTRA_TASK_REMINDER_OFFSET)
        val reminderOffset = TaskReminderOffset.fromRaw(offsetRaw)
        val points = intent.getIntExtra(TaskReminderScheduler.EXTRA_TASK_POINTS, 0)
        val assigneeName = intent.getStringExtra(TaskReminderScheduler.EXTRA_ASSIGNEE_NAME).orEmpty()

        // Mark this versioned schedule signature as fired before displaying the notification
        if (scheduleSignature.isNotBlank()) {
            TaskReminderScheduler.markSignatureFired(context, taskId, scheduleSignature)
        }

        NotificationHelper.showTaskScheduledReminderNotification(
            context = context,
            taskId = taskId,
            taskTitle = taskTitle,
            dueTimeFormatted = dueTimeFormatted,
            reminderOffsetLabelHe = reminderOffset.labelHe,
            assigneeName = assigneeName,
            points = points,
            notificationId = TaskReminderScheduler.notificationIdForTask(taskId)
        )
    }
}
