package com.example.notifications

import android.content.Context
import android.util.Log
import com.example.data.FamilyRepository
import com.example.model.UserRole
import com.example.util.NotificationHelper
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Genuine Firebase Cloud Messaging (FCM) receiver service for FamilyTasks.
 *
 * Handles:
 * 1. Automatic token rotation (`onNewToken`) — persists the new token in the named Firestore
 *    database (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`) under
 *    `/users/{uid}/fcm_tokens/{tokenId}` and enqueues a persistent alarm reconciliation pass.
 * 2. Incoming high-priority data-only alarm synchronization events (`ALARM_SYNC_REQUIRED`) when a
 *    parent creates, updates, enables, disables, or deletes a child's alarm while the child's app
 *    is closed or in the background. Enqueues persistent work via Android `WorkManager`
 *    ([AlarmSyncCoroutineWorker]) so synchronization is not tied to the short `FirebaseMessagingService`
 *    lifecycle and automatically retries with exponential backoff on transient network/Firestore failures.
 * 3. Incoming cross-device task push notifications (`onMessageReceived`) for:
 *    - A. Parent assigns a task to a child or other family member (`TASK_ASSIGNED`)
 *    - B. Child submits a task for parent approval (`TASK_SUBMITTED_FOR_APPROVAL`)
 *    - C. Parent approves a completed task (`TASK_APPROVED`)
 *    - D. Task is reassigned (`TASK_REASSIGNED`)
 * 4. Recipient verification and strict completion-based deduplication so `ALARM_SYNC_REQUIRED`
 *    events are only marked completed after reconciliation succeeds.
 */
class FamilyMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (token.isBlank()) return
        FcmTokenManager.cacheTokenLocally(applicationContext, token)

        serviceScope.launch {
            runCatching {
                if (FirebaseApp.getApps(applicationContext).isEmpty()) return@launch
                val currentUser = FirebaseAuth.getInstance().currentUser ?: return@launch
                val db = FirebaseFirestore.getInstance(
                    FirebaseApp.getInstance(),
                    FamilyRepository.NAMED_FIRESTORE_DATABASE_ID
                )
                val cachedFamilyId = FcmTokenManager.getCachedFamilyId(applicationContext).orEmpty()
                val cachedRole = FcmTokenManager.getCachedRole(applicationContext)
                    ?.let { UserRole.fromFirestoreValue(it) }
                    ?: UserRole.CHILD
                FcmTokenManager.registerTokenInFirestore(
                    db = db,
                    uid = currentUser.uid,
                    token = token,
                    familyId = cachedFamilyId,
                    role = cachedRole
                )
                // Enqueue persistent alarm reconciliation on token refresh
                FamilyAlarmCloudSyncWorker.enqueuePersistentAlarmSync(
                    context = applicationContext,
                    hintFamilyId = cachedFamilyId,
                    reason = "fcm_token_refresh"
                )
            }.onFailure { e ->
                Log.w(TAG, "onNewToken Firestore registration deferred until sign-in: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        handleRemoteMessagePayload(
            context = applicationContext,
            data = remoteMessage.data,
            messageId = remoteMessage.messageId,
            notificationTitle = remoteMessage.notification?.title,
            notificationBody = remoteMessage.notification?.body,
            scope = serviceScope
        )
    }

    companion object {
        private const val TAG = "FamilyMessagingService"

        /**
         * Processes an incoming FCM data payload for either data-only background alarm synchronization
         * (`ALARM_SYNC_REQUIRED`) or family task notifications. Extracted for deterministic unit testing.
         */
        @Suppress("UNUSED_PARAMETER")
        fun handleRemoteMessagePayload(
            context: Context,
            data: Map<String, String>,
            messageId: String? = null,
            notificationTitle: String? = null,
            notificationBody: String? = null,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            signedInUidOverride: String? = null,
            onAlarmReconcileRequested: ((familyId: String, alarmId: String, action: String) -> Unit)? = null,
            onTaskReminderReconcileRequested: ((familyId: String, taskId: String, operation: String) -> Unit)? = null
        ): Boolean {
            val appCtx = context.applicationContext ?: context
            val eventType = data["eventType"] ?: data["type"] ?: FcmTokenManager.EVENT_TASK_ASSIGNED
            val familyId = data["familyId"].orEmpty()
            val taskId = data["taskId"].orEmpty()
            val alarmId = data["alarmId"].orEmpty()
            val alarmAction = data["action"].orEmpty()
            val taskOperation = data["operation"].orEmpty().ifBlank { alarmAction }
            val targetUid = data["targetUid"].orEmpty()
            val targetRole = data["targetRole"].orEmpty()
            val actorUid = data["actorUid"].orEmpty()
            val entityId = alarmId.ifBlank { taskId }
            val eventKey = data["eventKey"]
                ?.takeIf { it.isNotBlank() }
                ?: when {
                    eventType == FcmTokenManager.EVENT_OPEN_TASK_CREATED && taskId.isNotBlank() ->
                        "open_task:$taskId"
                    !messageId.isNullOrBlank() ->
                        messageId
                    else ->
                        "$eventType:$entityId:${data["updatedAt"] ?: System.currentTimeMillis()}"
                }

            // Verify current authenticated user is the intended recipient
            val signedInUid = signedInUidOverride ?: runCatching {
                if (FirebaseApp.getApps(appCtx).isNotEmpty()) {
                    FirebaseAuth.getInstance().currentUser?.uid
                } else null
            }.getOrNull() ?: FcmTokenManager.getCachedUid(appCtx)

            if (!signedInUid.isNullOrBlank()) {
                val isAlarmDelete = eventType == FcmTokenManager.EVENT_ALARM_SYNC_REQUIRED &&
                    alarmAction.equals("DELETE", ignoreCase = true)
                val isTaskSyncEvent = eventType == FcmTokenManager.EVENT_TASK_SYNC_REQUIRED
                if (actorUid.isNotBlank() && actorUid == signedInUid && !isAlarmDelete && !isTaskSyncEvent) {
                    // Sender must not process their own visible push banner event
                    return false
                }
                if (targetUid.isNotBlank() && targetUid != signedInUid) {
                    // Message was targeted at a different user account
                    return false
                }
            }

            // Handle data-only background alarm synchronization push via persistent WorkManager
            if (eventType == FcmTokenManager.EVENT_ALARM_SYNC_REQUIRED) {
                // Only skip if this exact eventKey has ALREADY completed reconciliation
                if (FamilyAlarmCloudSyncWorker.isSyncEventCompleted(appCtx, eventKey)) {
                    Log.i(TAG, "Ignoring already-completed ALARM_SYNC_REQUIRED eventKey=$eventKey")
                    return false
                }

                Log.i(
                    TAG,
                    "Enqueuing persistent WorkManager alarm sync for ALARM_SYNC_REQUIRED " +
                        "(familyId=$familyId, alarmId=$alarmId, action=$alarmAction, eventKey=$eventKey)"
                )

                // Always enqueue persistent WorkManager work so synchronization survives FCM service termination.
                // Note: Do NOT mark eventKey as completed here before the sync actually finishes!
                FamilyAlarmCloudSyncWorker.enqueuePersistentAlarmSync(
                    context = appCtx,
                    eventKey = eventKey,
                    hintFamilyId = familyId,
                    hintAlarmId = alarmId,
                    hintAction = alarmAction,
                    reason = "fcm_push:${alarmAction.ifBlank { "SYNC" }}"
                )

                if (onAlarmReconcileRequested != null) {
                    onAlarmReconcileRequested(familyId, alarmId, alarmAction)
                    // In deterministic unit tests supplying a synchronous callback, mark completed after callback succeeds
                    FamilyAlarmCloudSyncWorker.markSyncEventCompleted(appCtx, eventKey)
                }
                return true
            }

            // Handle silent data-only task reminder synchronization (create/edit/time-change/reassign/complete/delete)
            // without showing any duplicate OS notification.
            if (eventType == FcmTokenManager.EVENT_TASK_SYNC_REQUIRED) {
                val normalizedOp = taskOperation.uppercase()
                if (taskId.isNotBlank()) {
                    when (normalizedOp) {
                        "DELETE", "CANCEL", "COMPLETE" -> {
                            TaskReminderScheduler.cancelReminderForTask(
                                context = appCtx,
                                taskId = taskId,
                                clearActiveNotification = true
                            )
                        }
                        "UPSERT", "CREATE", "UPDATE" -> {
                            val epochDay = data["dateEpochDay"]?.toLongOrNull()
                            val dueTime = data["dueTimeFormatted"].orEmpty()
                            val offset = com.example.model.TaskReminderOffset.fromRaw(data["reminderOffset"])
                            val hasDueTime = (data["hasDueTime"]?.toBooleanStrictOrNull() ?: false) ||
                                offset != com.example.model.TaskReminderOffset.NONE
                            val isCompleted = data["isCompleted"]?.toBooleanStrictOrNull() ?: false
                            val assignedTo = data["assignedToMemberId"].orEmpty().ifBlank { targetUid }
                            if (epochDay != null && dueTime.isNotBlank()) {
                                val payloadTask = com.example.model.FamilyTask(
                                    id = taskId,
                                    title = data["taskTitle"].orEmpty().ifBlank { "משימה משפחתית" },
                                    assignedToMemberId = assignedTo,
                                    dateEpochDay = epochDay,
                                    dueTimeFormatted = dueTime,
                                    hasDueTime = hasDueTime,
                                    reminderOffset = offset,
                                    reminderEnabled = hasDueTime && offset != com.example.model.TaskReminderOffset.NONE,
                                    isCompleted = isCompleted
                                )
                                TaskReminderScheduler.scheduleOrUpdateTaskReminder(
                                    context = appCtx,
                                    task = payloadTask
                                )
                            }
                        }
                    }
                }
                TaskReminderSyncWorker.enqueueTaskReminderSync(
                    context = appCtx,
                    hintFamilyId = familyId,
                    hintTaskId = taskId,
                    hintOperation = taskOperation,
                    reason = "fcm_task_sync:${taskOperation.ifBlank { "UPSERT" }}"
                )
                onTaskReminderReconcileRequested?.invoke(familyId, taskId, taskOperation)
                return true
            }

            // For task assignment / reassignment / approval events, also trigger background reminder sync
            // so any attached execution-time reminder is scheduled or cancelled even when the app is closed.
            if (
                eventType == FcmTokenManager.EVENT_TASK_ASSIGNED ||
                eventType == FcmTokenManager.EVENT_TASK_REASSIGNED ||
                eventType == FcmTokenManager.EVENT_TASK_APPROVED ||
                eventType == FcmTokenManager.EVENT_TASK_SUBMITTED_FOR_APPROVAL
            ) {
                TaskReminderSyncWorker.enqueueTaskReminderSync(
                    context = appCtx,
                    hintFamilyId = familyId,
                    hintTaskId = taskId,
                    hintOperation = "UPSERT",
                    reason = "fcm_event:$eventType"
                )
                onTaskReminderReconcileRequested?.invoke(familyId, taskId, "UPSERT")
            }

            // Deduplicate task notification events by eventKey (both in-memory and SharedPreferences)
            // so FCM + Firestore snapshot never shows twice whether the app is open or closed
            if (!FcmTokenManager.markEventDispatchedIfNew(eventKey, appCtx)) {
                return false
            }

            val resolvedTitle = data["title"]
                ?.takeIf { it.isNotBlank() }
                ?: notificationTitle
                ?: defaultTitleForEvent(eventType)

            val resolvedBody = data["body"]
                ?.takeIf { it.isNotBlank() }
                ?: notificationBody
                ?: ""

            if (resolvedTitle.isBlank() && resolvedBody.isBlank()) return false

            // When the app is in the background or closed, display the system Heads-Up notification.
            // When the app is open in the foreground, suppress the floating system Heads-Up notification
            // so the user sees only the styled in-app FamilyTasks banner (with single sound playback).
            if (!FcmTokenManager.isAppInForeground) {
                NotificationHelper.showIncomingFcmNotification(
                    context = appCtx,
                    title = resolvedTitle,
                    message = resolvedBody,
                    eventKey = eventKey,
                    taskId = taskId,
                    eventType = eventType
                )
            } else if (FcmTokenManager.markSoundPlayedIfNew(eventKey)) {
                val usageType = NotificationHelper.resolveSoundUsageForEvent(eventType)
                val uri = FamilySoundManager.resolvePlayableUriOrFallback(appCtx, usageType)
                val cfg = FamilySoundManager.getSoundConfigForUsage(appCtx, usageType)
                FcmTokenManager.recordForegroundSoundPlayed(uri.toString())
                FamilySoundManager.playNotificationSoundOneShot(
                    context = appCtx,
                    uri = uri,
                    volumePercent = cfg.volumePercent
                )
            }

            // Emit to in-app stream so notification history (and foreground banner + sound) stays in sync
            FcmTokenManager.emitIncomingPushEvent(
                IncomingPushNotificationPayload(
                    eventKey = eventKey,
                    eventType = eventType,
                    familyId = familyId,
                    taskId = taskId,
                    targetUid = targetUid,
                    targetRole = targetRole,
                    actorUid = actorUid,
                    title = resolvedTitle,
                    body = resolvedBody
                )
            )
            return true
        }

        private fun defaultTitleForEvent(eventType: String): String {
            return when (eventType) {
                FcmTokenManager.EVENT_TASK_ASSIGNED -> "משימה חדשה שובצה עבורך 📋"
                FcmTokenManager.EVENT_OPEN_TASK_CREATED -> "📢 משימה פתוחה להתנדבות!"
                FcmTokenManager.EVENT_TASK_SUBMITTED_FOR_APPROVAL -> "משימה ממתינה לאישור הורה ⏳"
                FcmTokenManager.EVENT_TASK_APPROVED -> "המשימה שלך אושרה! 🎉"
                FcmTokenManager.EVENT_TASK_REASSIGNED -> "משימה הועברה אליך 🔄"
                else -> "עדכון משימות משפחתיות 🏡"
            }
        }
    }
}
