package com.example.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.FamilyRepository
import com.example.data.local.AppDatabase
import com.example.model.FamilyTask
import com.example.model.TaskCategory
import com.example.model.TaskPriority
import com.example.model.TaskRecurrence
import com.example.model.TaskReminderOffset
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Background `WorkManager` worker and reconciliation coordinator that synchronizes task
 * reminders from Firestore when the app is closed or in the background (triggered by
 * silent `TASK_SYNC_REQUIRED` FCM messages, task assignment/reassignment FCM events, or boot).
 */
class TaskReminderSyncCoroutineWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val hintFamilyId = inputData.getString(TaskReminderSyncWorker.KEY_HINT_FAMILY_ID).orEmpty()
        val hintTaskId = inputData.getString(TaskReminderSyncSyncKeys.KEY_HINT_TASK_ID).orEmpty()
        val hintOperation = inputData.getString(TaskReminderSyncSyncKeys.KEY_HINT_OPERATION).orEmpty()
        val reason = inputData.getString(TaskReminderSyncWorker.KEY_REASON).orEmpty()

        return try {
            val reconciled = TaskReminderSyncWorker.reconcileTaskRemindersFromFirestore(
                context = applicationContext,
                hintFamilyId = hintFamilyId,
                hintTaskId = hintTaskId,
                hintOperation = hintOperation,
                reason = reason.ifBlank { "work_manager" }
            )
            if (reconciled || runAttemptCount >= 3) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TaskReminderSyncWorker.TAG, "TaskReminderSyncCoroutineWorker retry: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

private object TaskReminderSyncSyncKeys {
    const val KEY_HINT_TASK_ID = "hint_task_id"
    const val KEY_HINT_OPERATION = "hint_operation"
}

object TaskReminderSyncWorker {

    const val TAG = "TaskReminderSyncWorker"
    private const val UNIQUE_WORK_NAME_PREFIX = "family_task_reminder_sync_"
    const val KEY_HINT_FAMILY_ID = "hint_family_id"
    const val KEY_REASON = "sync_reason"

    fun enqueueTaskReminderSync(
        context: Context,
        hintFamilyId: String = "",
        hintTaskId: String = "",
        hintOperation: String = "",
        reason: String = "fcm_task_sync"
    ) {
        val appCtx = context.applicationContext ?: context

        // If this is an explicit DELETE operation with a known taskId, immediately cancel the local PendingIntent
        // even before Firestore round-trip finishes so a due reminder cannot fire in the meantime!
        if (hintTaskId.isNotBlank() && hintOperation.equals("DELETE", ignoreCase = true)) {
            TaskReminderScheduler.cancelReminderForTask(
                context = appCtx,
                taskId = hintTaskId,
                clearActiveNotification = true
            )
        }

        runCatching {
            val workData = workDataOf(
                KEY_HINT_FAMILY_ID to hintFamilyId,
                TaskReminderSyncSyncKeys.KEY_HINT_TASK_ID to hintTaskId,
                TaskReminderSyncSyncKeys.KEY_HINT_OPERATION to hintOperation,
                KEY_REASON to reason
            )
            val requestBuilder = OneTimeWorkRequestBuilder<TaskReminderSyncCoroutineWorker>()
                .setInputData(workData)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)

            runCatching {
                requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            val uniqueWorkName = if (hintTaskId.isNotBlank()) {
                "$UNIQUE_WORK_NAME_PREFIX$hintTaskId"
            } else {
                "${UNIQUE_WORK_NAME_PREFIX}all"
            }

            WorkManager.getInstance(appCtx).enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                requestBuilder.build()
            )
        }.onFailure { e ->
            Log.w(TAG, "WorkManager enqueue skipped/failed in environment: ${e.message}")
        }
    }

    /**
     * Fetches the authoritative task list for the user's family from the named Firestore database,
     * updates local Room storage (when credential-protected storage is unlocked), and reconciles
     * all local `AlarmManager` task reminders via [TaskReminderScheduler].
     */
    suspend fun reconcileTaskRemindersFromFirestore(
        context: Context,
        hintFamilyId: String = "",
        hintTaskId: String = "",
        hintOperation: String = "",
        reason: String = "manual"
    ): Boolean {
        val appCtx = context.applicationContext ?: context

        if (hintTaskId.isNotBlank() && hintOperation.equals("DELETE", ignoreCase = true)) {
            TaskReminderScheduler.cancelReminderForTask(appCtx, hintTaskId, clearActiveNotification = true)
            if (TaskReminderScheduler.isCredentialProtectedStorageAvailable(appCtx)) {
                runCatching {
                    AppDatabase.getInstance(appCtx).taskDao().deleteTaskById(hintTaskId)
                }
            }
        }

        // First restore from Room if available so local changes are immediately reflected
        TaskReminderScheduler.restoreTaskRemindersFromRoomIfUnlocked(appCtx)

        if (FirebaseApp.getApps(appCtx).isEmpty()) return false
        val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
        val uid = currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: FcmTokenManager.getCachedUid(appCtx).orEmpty()
        if (uid.isBlank()) return false

        val familyId = hintFamilyId.takeIf { it.isNotBlank() }
            ?: FcmTokenManager.getCachedFamilyId(appCtx).orEmpty()
        if (familyId.isBlank()) return false

        return try {
            val db = FirebaseFirestore.getInstance(
                FirebaseApp.getInstance(),
                FamilyRepository.NAMED_FIRESTORE_DATABASE_ID
            )
            val snapshot = db.collection("families")
                .document(familyId)
                .collection("tasks")
                .get()
                .await()

            val remoteTasks = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                parseFirestoreTaskForReminderSync(doc.id, data, familyId)
            }

            if (TaskReminderScheduler.isCredentialProtectedStorageAvailable(appCtx)) {
                runCatching {
                    val dao = AppDatabase.getInstance(appCtx).taskDao()
                    dao.deleteTasksByFamily(familyId)
                    dao.upsertTasks(remoteTasks.map { it.toCoreTask(familyId) })
                }
            }

            TaskReminderScheduler.syncTaskRemindersForCurrentDevice(
                context = appCtx,
                tasks = remoteTasks,
                currentMemberId = uid,
                currentAuthUid = uid
            )
            Log.i(TAG, "Reconciled ${remoteTasks.size} tasks from Firestore (reason=$reason)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "reconcileTaskRemindersFromFirestore failed (reason=$reason): ${e.message}")
            false
        }
    }

    internal fun parseFirestoreTaskForReminderSync(
        docId: String,
        data: Map<String, Any?>,
        fallbackFamilyId: String
    ): FamilyTask? {
        val title = (data["title"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["task_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return null
        val assignedTo = (data["assignedUid"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assignedToMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assignedMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assigned_member_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: ""
        val epochDay = ((data["dateEpochDay"] as? Number)?.toLong())
            ?: ((data["due_date_epoch_day"] as? Number)?.toLong()?.takeIf { it != 0L })
            ?: (System.currentTimeMillis() / 86_400_000L)
        val dueTime = (data["dueTimeFormatted"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["due_time"] as? String)?.takeIf { it.isNotBlank() }
            ?: "16:00"
        val reminderOffsetRaw = (data["reminderOffset"] as? String)
            ?: (data["reminder_offset"] as? String)
            ?: TaskReminderOffset.NONE.name
        val parsedOffset = TaskReminderOffset.fromRaw(reminderOffsetRaw)
        val explicitHasDueTime = (data["hasDueTime"] as? Boolean)
            ?: (data["has_due_time"] as? Boolean)
            ?: (data["hasDueTime"] as? String)?.toBooleanStrictOrNull()
            ?: (data["has_due_time"] as? String)?.toBooleanStrictOrNull()
        val hasDueTime = (explicitHasDueTime ?: false) || parsedOffset != TaskReminderOffset.NONE
        val completed = (data["isCompleted"] as? Boolean)
            ?: (data["is_completed"] as? Boolean)
            ?: false
        val approvedByParent = (data["approvedByParent"] as? Boolean)
            ?: (data["approved_by_parent"] as? Boolean)
            ?: false
        val points = ((data["points"] as? Number)?.toInt())
            ?: ((data["pointsValue"] as? Number)?.toInt())
            ?: 10
        val categoryStr = (data["category"] as? String) ?: TaskCategory.OTHER.name
        val priorityStr = (data["priority"] as? String) ?: TaskPriority.MEDIUM.name
        val recurrenceStr = (data["recurrence"] as? String) ?: TaskRecurrence.ONCE.name
        val resolvedFamilyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["family_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: fallbackFamilyId

        return FamilyTask(
            id = docId,
            familyId = resolvedFamilyId,
            title = title,
            description = (data["description"] as? String).orEmpty(),
            category = runCatching { TaskCategory.valueOf(categoryStr) }.getOrDefault(TaskCategory.OTHER),
            assignedToMemberId = assignedTo,
            dateEpochDay = epochDay,
            dueTimeFormatted = dueTime,
            hasDueTime = hasDueTime,
            reminderOffset = parsedOffset,
            reminderEnabled = hasDueTime && parsedOffset != TaskReminderOffset.NONE,
            isCompleted = completed,
            approvedByParent = approvedByParent,
            priority = runCatching { TaskPriority.valueOf(priorityStr) }.getOrDefault(TaskPriority.MEDIUM),
            recurrence = runCatching { TaskRecurrence.valueOf(recurrenceStr) }.getOrDefault(TaskRecurrence.ONCE),
            points = points
        )
    }
}
