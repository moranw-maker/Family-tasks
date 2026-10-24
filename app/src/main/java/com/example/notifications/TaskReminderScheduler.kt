package com.example.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.model.FamilyTask
import com.example.model.TaskReminderOffset
import com.example.util.NotificationHelper
import java.time.ZoneId
import kotlin.math.abs

/**
 * Schedules, updates, and cancels local Android `AlarmManager` reminders for family tasks
 * assigned to the current device's family member.
 *
 * Key architecture & safety guarantees:
 * 1. Completely isolated from [FamilyAlarmScheduler] (uses dedicated `310_000+` request codes,
 *    dedicated [TaskReminderReceiver], and dedicated SharedPreferences).
 * 2. Deterministic per-task `PendingIntent` (`pendingIntentRequestCode(taskId)`) ensures that
 *    changing a task's time, reminder offset, assignee, or completion state always cancels
 *    any previous reminder for that `taskId` before scheduling the updated one.
 * 3. Versioned schedule signature (`"$taskId|$dateEpochDay|$dueTimeFormatted|$reminderOffset"`)
 *    distinguishes between a reminder that already fired for a previous time/offset and a newly
 *    updated schedule for the same task.
 * 4. Realistic Android reliability: uses `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()`
 *    is permitted, and safely falls back to `setAndAllowWhileIdle` without crashing if exact
 *    alarm permission is unavailable.
 * 5. Direct Boot safety: never accesses Room ([AppDatabase]) before credential-protected storage
 *    is unlocked (`UserManager.isUserUnlocked`).
 */
object TaskReminderScheduler {

    private const val TAG = "TaskReminderScheduler"
    private const val PREFS_NAME = "family_task_reminder_scheduler_prefs"
    private const val KEY_SCHEDULED_TASK_IDS = "scheduled_task_ids"
    private const val KEY_ACTIVE_SIGNATURE_PREFIX = "active_sig_"
    private const val KEY_FIRED_SIGNATURE_PREFIX = "fired_sig_"
    private const val KEY_CACHED_MEMBER_ID = "cached_member_id"
    private const val KEY_CACHED_AUTH_UID = "cached_auth_uid"
    private const val KEY_CACHED_MEMBER_NAME = "cached_member_name"

    const val ACTION_TRIGGER_TASK_REMINDER = "com.example.familytasks.ACTION_TRIGGER_TASK_REMINDER"
    const val EXTRA_TASK_ID = "extra_task_id"
    const val EXTRA_TASK_TITLE = "extra_task_title"
    const val EXTRA_TASK_DUE_TIME = "extra_task_due_time"
    const val EXTRA_TASK_REMINDER_OFFSET = "extra_task_reminder_offset"
    const val EXTRA_TASK_POINTS = "extra_task_points"
    const val EXTRA_ASSIGNEE_ID = "extra_assignee_id"
    const val EXTRA_ASSIGNEE_NAME = "extra_assignee_name"
    const val EXTRA_SCHEDULE_SIGNATURE = "extra_schedule_signature"

    fun pendingIntentRequestCode(taskId: String): Int {
        return 310_000 + abs("task_reminder_pi_${taskId.trim()}".hashCode() % 60_000)
    }

    fun notificationIdForTask(taskId: String): Int {
        return 410_000 + abs("task_reminder_notif_${taskId.trim()}".hashCode() % 60_000)
    }

    /**
     * Returns true only when credential-protected storage is unlocked and Room SQLite database
     * can be safely opened (prevents crashes during Direct Boot / `LOCKED_BOOT_COMPLETED`).
     */
    fun isCredentialProtectedStorageAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = context.getSystemService(UserManager::class.java)
            if (userManager != null && !userManager.isUserUnlocked) {
                return false
            }
        }
        return true
    }

    fun getScheduledTaskIds(context: Context): Set<String> {
        if (!isCredentialProtectedStorageAvailable(context)) return emptySet()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SCHEDULED_TASK_IDS, emptySet())?.toSet().orEmpty()
    }

    fun getActiveScheduledSignature(context: Context, taskId: String): String? {
        if (!isCredentialProtectedStorageAvailable(context)) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_SIGNATURE_PREFIX + taskId, null)
    }

    fun isSignatureAlreadyFired(context: Context, scheduleSignature: String): Boolean {
        if (scheduleSignature.isBlank() || !isCredentialProtectedStorageAvailable(context)) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRED_SIGNATURE_PREFIX + scheduleSignature, false)
    }

    fun markSignatureFired(context: Context, taskId: String, scheduleSignature: String) {
        if (scheduleSignature.isBlank() || !isCredentialProtectedStorageAvailable(context)) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentIds = prefs.getStringSet(KEY_SCHEDULED_TASK_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentIds.remove(taskId)
        prefs.edit()
            .putBoolean(KEY_FIRED_SIGNATURE_PREFIX + scheduleSignature, true)
            .putStringSet(KEY_SCHEDULED_TASK_IDS, currentIds)
            .remove(KEY_ACTIVE_SIGNATURE_PREFIX + taskId)
            .apply()
    }

    /**
     * Checks whether a `PendingIntent` for [taskId] is currently registered in `AlarmManager`
     * or tracked as an active scheduled reminder.
     */
    fun isTaskReminderPendingInAlarmManager(context: Context, taskId: String): Boolean {
        val cleanId = taskId.trim()
        if (cleanId.isEmpty()) return false
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_TASK_REMINDER
        }
        val existingPi = PendingIntent.getBroadcast(
            context,
            pendingIntentRequestCode(cleanId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        return existingPi != null && cleanId in getScheduledTaskIds(context)
    }

    /**
     * Cancels any scheduled `AlarmManager` `PendingIntent` for [taskId] and optionally clears
     * any active notification in the status bar.
     */
    fun cancelReminderForTask(
        context: Context,
        taskId: String,
        clearActiveNotification: Boolean = true
    ): Boolean {
        val cleanId = taskId.trim()
        if (cleanId.isEmpty()) return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val requestCode = pendingIntentRequestCode(cleanId)
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_TASK_REMINDER
        }

        var cancelledExisting = false
        val existingPi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existingPi != null) {
            runCatching { alarmManager?.cancel(existingPi) }
            runCatching { existingPi.cancel() }
            cancelledExisting = true
        }

        val fallbackPi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (fallbackPi != null) {
            runCatching { alarmManager?.cancel(fallbackPi) }
            runCatching { fallbackPi.cancel() }
        }

        if (clearActiveNotification) {
            NotificationHelper.cancelTaskReminderNotification(context, notificationIdForTask(cleanId))
        }

        if (isCredentialProtectedStorageAvailable(context)) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentIds = prefs.getStringSet(KEY_SCHEDULED_TASK_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            val wasTracked = currentIds.remove(cleanId)
            prefs.edit()
                .putStringSet(KEY_SCHEDULED_TASK_IDS, currentIds)
                .remove(KEY_ACTIVE_SIGNATURE_PREFIX + cleanId)
                .apply()
            if (wasTracked) cancelledExisting = true
        }

        return cancelledExisting
    }

    /**
     * Schedules or updates a local reminder for [task] if it is assigned to the current device's
     * member, has an execution time and reminder offset, is not completed, and its versioned
     * schedule signature has not already fired.
     *
     * Always cancels any previous schedule for [task.id] first so time edits never leave duplicate alarms.
     */
    fun scheduleOrUpdateTaskReminder(
        context: Context,
        task: FamilyTask,
        assigneeName: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val cleanId = task.id.trim()
        if (cleanId.isEmpty()) return false

        // If task is completed or has no active reminder configured, cancel any existing reminder
        if (!task.hasActiveReminder) {
            cancelReminderForTask(context, cleanId, clearActiveNotification = true)
            return false
        }

        val signature = task.reminderScheduleSignature()
        val previousSignature = getActiveScheduledSignature(context, cleanId)

        // If the schedule changed from a previous signature, cancel the previous PendingIntent & notification
        if (previousSignature != null && previousSignature != signature) {
            cancelReminderForTask(context, cleanId, clearActiveNotification = true)
        } else {
            // Even for the same signature, cancel the existing PendingIntent before re-setting
            cancelReminderForTask(context, cleanId, clearActiveNotification = false)
        }

        // If this exact schedule version already fired, do not schedule it again
        if (isSignatureAlreadyFired(context, signature)) {
            return false
        }

        val triggerAtMillis = task.computeReminderTriggerEpochMillis(zoneId) ?: return false

        // Do not schedule reminders whose trigger time is more than 60 seconds in the past
        if (triggerAtMillis < nowMillis - 60_000L) {
            return false
        }

        val effectiveTriggerMillis = maxOf(triggerAtMillis, nowMillis + 500L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val requestCode = pendingIntentRequestCode(cleanId)

        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_TASK_REMINDER
            putExtra(EXTRA_TASK_ID, cleanId)
            putExtra(EXTRA_TASK_TITLE, task.title)
            putExtra(EXTRA_TASK_DUE_TIME, task.dueTimeFormatted)
            putExtra(EXTRA_TASK_REMINDER_OFFSET, task.reminderOffset.name)
            putExtra(EXTRA_TASK_POINTS, task.points)
            putExtra(EXTRA_ASSIGNEE_ID, task.assignedToMemberId)
            putExtra(EXTRA_ASSIGNEE_NAME, assigneeName)
            putExtra(EXTRA_SCHEDULE_SIGNATURE, signature)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val scheduledSucceeded = runCatching {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
            } else {
                true
            }

            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    effectiveTriggerMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    effectiveTriggerMillis,
                    pendingIntent
                )
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "Fallback to inexact alarm for task $cleanId: ${e.message}")
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    effectiveTriggerMillis,
                    pendingIntent
                )
                true
            }.getOrDefault(false)
        }

        if (scheduledSucceeded && isCredentialProtectedStorageAvailable(context)) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentIds = prefs.getStringSet(KEY_SCHEDULED_TASK_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            currentIds.add(cleanId)
            prefs.edit()
                .putStringSet(KEY_SCHEDULED_TASK_IDS, currentIds)
                .putString(KEY_ACTIVE_SIGNATURE_PREFIX + cleanId, signature)
                .apply()
        }

        return scheduledSucceeded
    }

    /**
     * Reconciles all task reminders on the current device for the active member:
     * - Cancels any previously scheduled reminder whose task was deleted, completed,
     *   had its reminder disabled, or was reassigned to a different family member.
     * - Schedules/updates all active reminders assigned to [currentMemberId] or [currentAuthUid].
     */
    fun syncTaskRemindersForCurrentDevice(
        context: Context,
        tasks: List<FamilyTask>,
        currentMemberId: String,
        currentAuthUid: String = "",
        currentMemberName: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ) {
        if (!isCredentialProtectedStorageAvailable(context)) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val resolvedMemberId = currentMemberId.ifBlank { prefs.getString(KEY_CACHED_MEMBER_ID, "").orEmpty() }
        val resolvedAuthUid = currentAuthUid.ifBlank { prefs.getString(KEY_CACHED_AUTH_UID, "").orEmpty() }
        val resolvedMemberName = currentMemberName.ifBlank { prefs.getString(KEY_CACHED_MEMBER_NAME, "").orEmpty() }

        if (resolvedMemberId.isNotBlank() || resolvedAuthUid.isNotBlank()) {
            prefs.edit()
                .putString(KEY_CACHED_MEMBER_ID, resolvedMemberId)
                .putString(KEY_CACHED_AUTH_UID, resolvedAuthUid)
                .putString(KEY_CACHED_MEMBER_NAME, resolvedMemberName)
                .apply()
        }

        val tasksForThisDevice = tasks.filter { task ->
            val assigned = task.assignedToMemberId.trim()
            assigned.isNotEmpty() && (
                (resolvedMemberId.isNotBlank() && assigned == resolvedMemberId) ||
                    (resolvedAuthUid.isNotBlank() && assigned == resolvedAuthUid)
                )
        }

        val activeTaskIdsForThisDevice = tasksForThisDevice
            .filter { it.hasActiveReminder }
            .map { it.id.trim() }
            .toSet()

        // Cancel any previously scheduled task reminder that is no longer active for this device
        val previouslyScheduledIds = getScheduledTaskIds(context)
        for (oldTaskId in previouslyScheduledIds) {
            if (oldTaskId !in activeTaskIdsForThisDevice) {
                cancelReminderForTask(context, oldTaskId, clearActiveNotification = true)
            }
        }

        // Schedule or update each task assigned to this device
        for (task in tasksForThisDevice) {
            scheduleOrUpdateTaskReminder(
                context = context,
                task = task,
                assigneeName = resolvedMemberName,
                nowMillis = nowMillis,
                zoneId = zoneId
            )
        }
    }

    /**
     * Safely restores scheduled task reminders from Room after device boot (`BOOT_COMPLETED`),
     * app update (`MY_PACKAGE_REPLACED`), or time/timezone change.
     *
     * Strictly verifies that credential-protected storage is unlocked (`UserManager.isUserUnlocked`)
     * before accessing Room so Direct Boot (`LOCKED_BOOT_COMPLETED`) never fails.
     */
    suspend fun restoreTaskRemindersFromRoomIfUnlocked(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (!isCredentialProtectedStorageAvailable(context)) {
            Log.i(TAG, "Skipping Room task reminder restoration: device is still in Direct Boot mode")
            return false
        }
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedMemberId = prefs.getString(KEY_CACHED_MEMBER_ID, "").orEmpty()
            val cachedAuthUid = prefs.getString(KEY_CACHED_AUTH_UID, "").orEmpty()
                .ifBlank { FcmTokenManager.getCachedUid(context).orEmpty() }
            val cachedMemberName = prefs.getString(KEY_CACHED_MEMBER_NAME, "").orEmpty()

            if (cachedMemberId.isBlank() && cachedAuthUid.isBlank()) {
                return@runCatching false
            }

            val roomTasks = AppDatabase.getInstance(context).taskDao().getAllTasksOnce()
            val domainTasks = roomTasks.map { it.toFamilyTask() }
            syncTaskRemindersForCurrentDevice(
                context = context,
                tasks = domainTasks,
                currentMemberId = cachedMemberId,
                currentAuthUid = cachedAuthUid,
                currentMemberName = cachedMemberName,
                nowMillis = nowMillis,
                zoneId = zoneId
            )
            true
        }.onFailure { e ->
            Log.w(TAG, "Failed to restore task reminders from Room: ${e.message}")
        }.getOrDefault(false)
    }

    fun clearAllForTesting(context: Context) {
        val scheduled = getScheduledTaskIds(context)
        for (taskId in scheduled) {
            cancelReminderForTask(context, taskId, clearActiveNotification = true)
        }
        if (isCredentialProtectedStorageAvailable(context)) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
