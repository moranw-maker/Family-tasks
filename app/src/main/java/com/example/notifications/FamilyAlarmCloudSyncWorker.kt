package com.example.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.R
import com.example.data.FamilyRepository
import com.example.model.FamilyAlarm
import com.example.model.SchoolDay
import com.example.model.UserRole
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Result of a background Firestore-to-AlarmManager reconciliation pass.
 */
data class AlarmCloudReconciliationResult(
    val success: Boolean,
    val familyId: String = "",
    val uid: String = "",
    val scheduledCount: Int = 0,
    val cancelledCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Persistent [CoroutineWorker] executed by Android [WorkManager] to reconcile remote parent-created,
 * updated, enabled, disabled, or deleted alarms on the child's device even after the app is swiped
 * away from Recents and the FCM service lifecycle ends.
 *
 * Delegates all reconciliation logic to [FamilyAlarmCloudSyncWorker.reconcileAlarmsFromFirestore]
 * without duplicating business logic, retries with exponential backoff on transient network or
 * Firestore failures, and marks the `eventKey` as completed ONLY after reconciliation and
 * parent-facing acknowledgement succeed.
 */
class AlarmSyncCoroutineWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val eventKey = inputData.getString(FamilyAlarmCloudSyncWorker.KEY_EVENT_KEY).orEmpty().trim()
        val hintFamilyId = inputData.getString(FamilyAlarmCloudSyncWorker.KEY_FAMILY_ID).orEmpty().trim()
        val hintAlarmId = inputData.getString(FamilyAlarmCloudSyncWorker.KEY_ALARM_ID).orEmpty().trim()
        val hintAction = inputData.getString(FamilyAlarmCloudSyncWorker.KEY_ACTION).orEmpty().trim()
        val reason = inputData.getString(FamilyAlarmCloudSyncWorker.KEY_REASON)
            ?.takeIf { it.isNotBlank() }
            ?: "work_manager_fcm_sync"

        if (eventKey.isNotEmpty() && FamilyAlarmCloudSyncWorker.isSyncEventCompleted(applicationContext, eventKey)) {
            Log.i(TAG, "Skipping already-completed alarm sync work for eventKey=$eventKey")
            return Result.success()
        }

        val outcome = FamilyAlarmCloudSyncWorker.reconcileAlarmsFromFirestore(
            context = applicationContext,
            hintFamilyId = hintFamilyId,
            hintAlarmId = hintAlarmId,
            hintAction = hintAction,
            reason = "$reason(attempt=${runAttemptCount + 1})"
        )

        return if (outcome.success) {
            if (eventKey.isNotEmpty()) {
                FamilyAlarmCloudSyncWorker.markSyncEventCompleted(applicationContext, eventKey)
                FcmTokenManager.markEventDispatchedIfNew(eventKey)
            }
            Result.success()
        } else {
            Log.w(
                TAG,
                "Alarm sync worker attempt ${runAttemptCount + 1} failed: ${outcome.errorMessage}; " +
                    "will retry with backoff"
            )
            if (runAttemptCount < FamilyAlarmCloudSyncWorker.MAX_WORKER_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "family_alarm_background_sync_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm?.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "סנכרון שעונים מעוררים",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "סנכרון שעונים מעוררים משפחתיים ברקע"
                    setSound(null, null)
                    enableVibration(false)
                }
                nm?.createNotificationChannel(channel)
            }
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("מסנכרן שעונים מעוררים...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return ForegroundInfo(7909, notification)
    }

    companion object {
        private const val TAG = "AlarmSyncWorker"
    }
}

/**
 * Authoritative, idempotent background alarm reconciler for FamilyTasks.
 *
 * Triggered by:
 * 1. High-priority data-only FCM message (`ALARM_SYNC_REQUIRED`) in [FamilyMessagingService], which
 *    enqueues [AlarmSyncCoroutineWorker] as persistent work in Android [WorkManager] so execution
 *    survives when the app is closed from Recents.
 * 2. FCM token rotation (`onNewToken`).
 * 3. App startup and foreground return (`ON_RESUME`) as a backup reconciliation mechanism.
 * 4. Device reboot, time change, or exact-alarm permission grant ([FamilyAlarmBootReceiver]).
 *
 * Security & Reliability guarantees:
 * - Never trusts alarm details inside the FCM payload; always fetches authoritative alarm documents
 *   from the named Firestore database (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`).
 * - Serialized with [syncMutex] so duplicate or out-of-order FCM messages are processed idempotently.
 * - Never marks an `eventKey` as completed before Firestore fetch, local `AlarmManager` scheduling,
 *   and parent-facing acknowledgement succeed.
 * - Never cancels or wipes already-scheduled local alarms if the device is offline or Firestore fails.
 */
object FamilyAlarmCloudSyncWorker {

    private const val TAG = "FamilyAlarmCloudSync"
    private const val SYNC_PREFS_NAME = "family_alarm_sync_worker_prefs"
    private const val PREF_COMPLETED_EVENT_KEYS = "completed_alarm_sync_event_keys"

    const val KEY_EVENT_KEY = "event_key"
    const val KEY_FAMILY_ID = "family_id"
    const val KEY_ALARM_ID = "alarm_id"
    const val KEY_ACTION = "action"
    const val KEY_REASON = "reason"
    const val UNIQUE_WORK_PREFIX = "family_alarm_cloud_sync"
    const val MAX_WORKER_RETRY_ATTEMPTS = 6

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val completedSyncEventKeys: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Returns `true` ONLY if [eventKey] has already completed a full reconciliation pass.
     */
    fun isSyncEventCompleted(context: Context?, eventKey: String): Boolean {
        val clean = eventKey.trim()
        if (clean.isEmpty()) return false
        synchronized(completedSyncEventKeys) {
            if (clean in completedSyncEventKeys) return true
        }
        if (context == null) return false
        return runCatching {
            val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(PREF_COMPLETED_EVENT_KEYS, emptySet()).orEmpty()
            if (clean in saved) {
                synchronized(completedSyncEventKeys) { completedSyncEventKeys.add(clean) }
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Marks [eventKey] as successfully completed after Firestore reconciliation and local scheduling
     * have finished.
     */
    fun markSyncEventCompleted(context: Context?, eventKey: String) {
        val clean = eventKey.trim()
        if (clean.isEmpty()) return
        synchronized(completedSyncEventKeys) {
            if (completedSyncEventKeys.size >= 300) {
                val it = completedSyncEventKeys.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            completedSyncEventKeys.add(clean)
        }
        if (context == null) return
        runCatching {
            val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            val updated = LinkedHashSet(prefs.getStringSet(PREF_COMPLETED_EVENT_KEYS, emptySet()).orEmpty())
            updated.add(clean)
            while (updated.size > 200) {
                val first = updated.firstOrNull() ?: break
                updated.remove(first)
            }
            prefs.edit().putStringSet(PREF_COMPLETED_EVENT_KEYS, updated).apply()
        }
    }

    fun clearCompletedSyncEventsForTesting(context: Context? = null) {
        synchronized(completedSyncEventKeys) {
            completedSyncEventKeys.clear()
        }
        runCatching {
            context?.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.remove(PREF_COMPLETED_EVENT_KEYS)
                ?.apply()
        }
    }

    /**
     * Enqueues a persistent [WorkManager] job ([AlarmSyncCoroutineWorker]) with network constraints
     * and exponential backoff so that `ALARM_SYNC_REQUIRED` FCM messages complete reliably even when
     * the app has been closed from Recents and the FCM service lifecycle terminates.
     */
    fun enqueuePersistentAlarmSync(
        context: Context,
        eventKey: String = "",
        hintFamilyId: String = "",
        hintAlarmId: String = "",
        hintAction: String = "",
        reason: String = "fcm_push"
    ): Boolean {
        val appCtx = context.applicationContext ?: context
        val inputData = workDataOf(
            KEY_EVENT_KEY to eventKey.trim(),
            KEY_FAMILY_ID to hintFamilyId.trim(),
            KEY_ALARM_ID to hintAlarmId.trim(),
            KEY_ACTION to hintAction.trim(),
            KEY_REASON to reason
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<AlarmSyncCoroutineWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(UNIQUE_WORK_PREFIX)
            .build()

        val uniqueWorkName = if (hintAlarmId.isNotBlank()) {
            "${UNIQUE_WORK_PREFIX}_${hintAlarmId.trim()}"
        } else {
            UNIQUE_WORK_PREFIX
        }

        val enqueuedInWorkManager = runCatching {
            val workManager = runCatching {
                WorkManager.getInstance(appCtx)
            }.getOrElse {
                WorkManager.initialize(appCtx, Configuration.Builder().build())
                WorkManager.getInstance(appCtx)
            }
            workManager.enqueueUniqueWork(
                uniqueWorkName,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            true
        }.onFailure { e ->
            Log.w(TAG, "WorkManager enqueue fallback to coroutine scope: ${e.message}")
        }.getOrDefault(false)

        if (!enqueuedInWorkManager) {
            reconcileAlarmsFromFirestoreAsync(
                context = appCtx,
                hintFamilyId = hintFamilyId,
                hintAlarmId = hintAlarmId,
                hintAction = hintAction,
                reason = reason,
                eventKeyToMarkOnSuccess = eventKey
            )
        }
        return true
    }

    /**
     * Launches an asynchronous reconciliation pass on [workerScope] (used as a backup on app startup
     * and foreground resume).
     */
    fun reconcileAlarmsFromFirestoreAsync(
        context: Context,
        hintFamilyId: String = "",
        hintAlarmId: String = "",
        hintAction: String = "",
        reason: String = "background_sync",
        eventKeyToMarkOnSuccess: String = ""
    ) {
        val appCtx = context.applicationContext ?: context
        workerScope.launch {
            val res = reconcileAlarmsFromFirestore(
                context = appCtx,
                hintFamilyId = hintFamilyId,
                hintAlarmId = hintAlarmId,
                hintAction = hintAction,
                reason = reason
            )
            if (res.success && eventKeyToMarkOnSuccess.isNotBlank()) {
                markSyncEventCompleted(appCtx, eventKeyToMarkOnSuccess)
                FcmTokenManager.markEventDispatchedIfNew(eventKeyToMarkOnSuccess)
            }
        }
    }

    /**
     * Fetches authoritative alarm state from Firestore and reconciles local Android [ android.app.AlarmManager ]
     * schedules idempotently.
     */
    suspend fun reconcileAlarmsFromFirestore(
        context: Context,
        hintFamilyId: String = "",
        hintAlarmId: String = "",
        hintAction: String = "",
        reason: String = "background_sync",
        dbOverride: FirebaseFirestore? = null,
        uidOverride: String? = null
    ): AlarmCloudReconciliationResult = syncMutex.withLock {
        val appCtx = context.applicationContext ?: context
        try {
            val hasFirebaseApp = runCatching { FirebaseApp.getApps(appCtx).isNotEmpty() }.getOrDefault(false)
            if (!hasFirebaseApp && dbOverride == null) {
                FamilyAlarmScheduler.restoreAlarmsOnBoot(appCtx)
                return AlarmCloudReconciliationResult(
                    success = false,
                    errorMessage = "FirebaseApp not initialized; restored persisted local alarms"
                )
            }

            val uid = uidOverride?.takeIf { it.isNotBlank() }
                ?: runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                ?: FcmTokenManager.getCachedUid(appCtx).orEmpty()

            if (uid.isBlank()) {
                FamilyAlarmScheduler.restoreAlarmsOnBoot(appCtx)
                return AlarmCloudReconciliationResult(
                    success = false,
                    errorMessage = "No authenticated user; restored persisted local alarms"
                )
            }

            val db = dbOverride ?: FirebaseFirestore.getInstance(
                FirebaseApp.getInstance(),
                FamilyRepository.NAMED_FIRESTORE_DATABASE_ID
            )

            // Resolve authoritative familyId and user profile
            val userSnap = runCatching {
                db.collection("users").document(uid).get().await()
            }.getOrNull()

            val resolvedFamilyId = listOf(
                userSnap?.getString("familyId").orEmpty(),
                hintFamilyId.trim(),
                FcmTokenManager.getCachedFamilyId(appCtx).orEmpty()
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            if (resolvedFamilyId.isBlank()) {
                FamilyAlarmScheduler.restoreAlarmsOnBoot(appCtx)
                return AlarmCloudReconciliationResult(
                    success = false,
                    uid = uid,
                    errorMessage = "No active familyId found for uid=$uid"
                )
            }

            val familyRef = db.collection("families").document(resolvedFamilyId)

            // Fetch family doc (for deletedAlarmIds tombstones), member doc (for role/name), and alarms collection
            val familySnap = runCatching { familyRef.get().await() }.getOrNull()
            val memberSnap = runCatching {
                familyRef.collection("members").document(uid).get().await()
            }.getOrNull()

            // Verify the user is a member of this family before applying remote alarm mutations
            if (memberSnap != null && !memberSnap.exists() && userSnap?.getString("familyId") != resolvedFamilyId) {
                Log.w(TAG, "Skipping alarm reconciliation: uid=$uid is not a member of familyId=$resolvedFamilyId")
                return AlarmCloudReconciliationResult(
                    success = false,
                    familyId = resolvedFamilyId,
                    uid = uid,
                    errorMessage = "User is not a member of family $resolvedFamilyId"
                )
            }

            val memberName = memberSnap?.getString("name")
                ?.takeIf { it.isNotBlank() }
                ?: userSnap?.getString("displayName").orEmpty()
            val rawRole = memberSnap?.getString("role")
                ?: userSnap?.getString("role")
                ?: FcmTokenManager.getCachedRole(appCtx)
                ?: UserRole.CHILD.name
            val memberRole = UserRole.fromFirestoreValue(rawRole)
            val isChildDevice = !memberRole.isParentRole

            // Cache resolved family membership for future background wakeups
            FcmTokenManager.cacheSessionMetadata(
                context = appCtx,
                uid = uid,
                familyId = resolvedFamilyId,
                role = memberRole
            )

            val alarmsSnap = familyRef.collection("alarms").get().await()

            val deletedIds = mutableSetOf<String>()
            deletedIds.addAll(FamilyAlarmScheduler.getPersistedDeletedAlarmIds(appCtx))

            val familyTombstones = (familySnap?.get("deletedAlarmIds") as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .orEmpty()
            deletedIds.addAll(familyTombstones)

            if (hintAction.equals("DELETE", ignoreCase = true) && hintAlarmId.isNotBlank()) {
                deletedIds.add(hintAlarmId.trim())
            }

            val remoteAlarms = alarmsSnap.documents.mapNotNull { doc ->
                parseFirestoreAlarmDocument(
                    doc = doc,
                    familyId = resolvedFamilyId,
                    deletedIds = deletedIds
                )
            }
            val remoteIds = remoteAlarms.map { it.id }.toSet()

            // Detect previously persisted remote alarms from this family that were deleted in Firestore
            val previouslyPersisted = FamilyAlarmScheduler.loadPersistedAlarms(appCtx)
            previouslyPersisted.forEach { diskAlarm ->
                val isFromSameFamily = diskAlarm.familyId.isBlank() || diskAlarm.familyId == resolvedFamilyId
                val isPurelyLocalDraft = diskAlarm.familyId.isBlank() &&
                    (diskAlarm.createdByMemberId == uid || diskAlarm.isPersonal)
                if (isFromSameFamily && !isPurelyLocalDraft && diskAlarm.id !in remoteIds) {
                    deletedIds.add(diskAlarm.id)
                }
            }

            // Immediately cancel any deleted alarms in AlarmManager
            val newlyCancelledIds = mutableSetOf<String>()
            deletedIds.forEach { deletedId ->
                FamilyAlarmScheduler.markAlarmDeletedAndCancel(appCtx, deletedId)
                newlyCancelledIds.add(deletedId)
            }

            var transientAckWriteError: String? = null

            // Confirm deleted alarm cancellations on member doc so parent sees target confirmation
            if (newlyCancelledIds.isNotEmpty()) {
                val existingCancelledOnDoc = (memberSnap?.get("cancelledAlarmIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.toSet()
                    .orEmpty()
                val unconfirmedCancelledIds = newlyCancelledIds - existingCancelledOnDoc
                if (unconfirmedCancelledIds.isNotEmpty()) {
                    runCatching {
                        familyRef.collection("members").document(uid)
                            .update(
                                mapOf(
                                    "cancelledAlarmIds" to FieldValue.arrayUnion(*unconfirmedCancelledIds.toTypedArray()),
                                    "lastAlarmSyncAtMillis" to System.currentTimeMillis()
                                )
                            )
                            .await()
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to confirm cancelledAlarmIds in Firestore: ${e.message}")
                        transientAckWriteError = e.message ?: "Failed to confirm cancelledAlarmIds"
                    }
                }
            }

            // Preserve any purely local personal alarms that have not been deleted
            val preservedLocalPersonal = previouslyPersisted.filter { local ->
                local.id !in remoteIds &&
                    local.id !in deletedIds &&
                    !local.isDeleted &&
                    local.scheduleStatus != FamilyAlarm.STATUS_DELETED &&
                    local.familyId.isBlank() &&
                    (local.createdByMemberId == uid || local.isPersonal)
            }

            val mergedAlarms = (remoteAlarms + preservedLocalPersonal)
                .filterNot { it.id in deletedIds || it.isDeleted || it.scheduleStatus == FamilyAlarm.STATUS_DELETED }
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.hour }, { it.minute }))

            val deviceModelLabel = runCatching {
                "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            }.getOrDefault("Android Device")

            FamilyAlarmScheduler.syncAllDeviceAlarms(
                context = appCtx,
                alarms = mergedAlarms,
                currentMemberId = uid,
                currentAuthUid = uid,
                currentMemberName = memberName,
                isChildDevice = isChildDevice,
                deletedAlarmIds = deletedIds
            )

            val scheduleResults = FamilyAlarmScheduler.syncAlarmsForCurrentDevice(
                context = appCtx,
                alarms = mergedAlarms,
                currentMemberId = uid,
                currentAuthUid = uid,
                currentMemberName = memberName,
                isChildDevice = isChildDevice,
                authoritativeRemoteSync = true
            )

            // Write target-device schedule acknowledgement back to Firestore for remote alarms targeted at this device
            for (remoteAlarm in remoteAlarms) {
                if (remoteAlarm.id in deletedIds) continue
                val isTargetedHere = remoteAlarm.isTargetedAtDeviceMember(
                    memberId = uid,
                    authUid = uid,
                    currentMemberName = memberName,
                    isChildDevice = isChildDevice
                )
                if (!isTargetedHere) continue

                val localRes = scheduleResults[remoteAlarm.id]
                val resolvedStatus = when {
                    !remoteAlarm.isEnabled -> FamilyAlarm.STATUS_DISABLED
                    localRes != null -> localRes.statusCode
                    else -> FamilyAlarm.STATUS_SCHEDULED_LOCALLY
                }
                val resolvedNextTrigger = if (!remoteAlarm.isEnabled) {
                    0L
                } else {
                    localRes?.nextTriggerAtMillis ?: FamilyAlarmScheduler.computeNextTriggerAtMillis(remoteAlarm)
                }
                val resolvedError = localRes?.errorMessage.orEmpty()

                val needsAckWrite = remoteAlarm.scheduleStatus != resolvedStatus ||
                    remoteAlarm.acknowledgedByMemberId != uid ||
                    remoteAlarm.nextTriggerAtMillis != resolvedNextTrigger

                if (needsAckWrite) {
                    runCatching {
                        familyRef.collection("alarms").document(remoteAlarm.id)
                            .update(
                                mapOf(
                                    "scheduleStatus" to resolvedStatus,
                                    "acknowledgedByMemberId" to uid,
                                    "acknowledgedByDeviceName" to deviceModelLabel,
                                    "acknowledgedAtMillis" to System.currentTimeMillis(),
                                    "nextTriggerAtMillis" to resolvedNextTrigger,
                                    "lastScheduleError" to resolvedError,
                                    "updatedByUid" to uid,
                                    "updatedAt" to System.currentTimeMillis()
                                )
                            )
                            .await()
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to write alarm ack for ${remoteAlarm.id}: ${e.message}")
                        transientAckWriteError = e.message ?: "Failed to write alarm ack for ${remoteAlarm.id}"
                    }
                }
            }

            val scheduledCount = scheduleResults.values.count { it.isScheduled }

            if (transientAckWriteError != null) {
                return AlarmCloudReconciliationResult(
                    success = false,
                    familyId = resolvedFamilyId,
                    uid = uid,
                    scheduledCount = scheduledCount,
                    cancelledCount = newlyCancelledIds.size,
                    errorMessage = transientAckWriteError
                )
            }

            Log.i(
                TAG,
                "Reconciled family alarms from Firestore (reason=$reason, familyId=$resolvedFamilyId, " +
                    "remote=${remoteAlarms.size}, scheduled=$scheduledCount, deleted=${newlyCancelledIds.size})"
            )

            return AlarmCloudReconciliationResult(
                success = true,
                familyId = resolvedFamilyId,
                uid = uid,
                scheduledCount = scheduledCount,
                cancelledCount = newlyCancelledIds.size
            )
        } catch (e: Exception) {
            Log.w(TAG, "reconcileAlarmsFromFirestore failed (reason=$reason): ${e.message}")
            // Never lose existing local schedules if network/Firestore is unavailable
            runCatching { FamilyAlarmScheduler.restoreAlarmsOnBoot(appCtx) }
            return AlarmCloudReconciliationResult(
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    /**
     * Parses a Firestore document under `/families/{familyId}/alarms/{alarmId}` into a [FamilyAlarm],
     * or returns `null` if the document is marked deleted and records its ID in [deletedIds].
     */
    fun parseFirestoreAlarmDocument(
        doc: DocumentSnapshot,
        familyId: String,
        deletedIds: MutableSet<String>
    ): FamilyAlarm? {
        val isDocDeleted = (doc.getBoolean("isDeleted") ?: false) ||
            doc.getString("scheduleStatus") == FamilyAlarm.STATUS_DELETED
        if (isDocDeleted) {
            deletedIds.add(doc.id)
            return null
        }
        if (doc.id in deletedIds) {
            return null
        }
        val title = doc.getString("title") ?: return null
        val hour = (doc.getLong("hour") ?: 7L).toInt().coerceIn(0, 23)
        val minute = (doc.getLong("minute") ?: 0L).toInt().coerceIn(0, 59)
        val assignedToId = doc.getString("assignedToMemberId")
            ?.takeIf { it.isNotBlank() }
            ?: doc.getString("assignedMemberId").orEmpty()
        val assignedMemberName = doc.getString("assignedMemberName").orEmpty()
        val createdById = doc.getString("createdByMemberId").orEmpty()
        val createdByName = doc.getString("createdByMemberName").orEmpty()
        val createdByRole = doc.getString("createdByRole").orEmpty()
        val rawDays = (doc.get("daysOfWeek") as? List<*>)?.mapNotNull { d ->
            (d as? String)?.let { runCatching { SchoolDay.valueOf(it) }.getOrNull() }
        }?.ifEmpty { SchoolDay.allWeekDays } ?: SchoolDay.allWeekDays
        val isEnabled = doc.getBoolean("isEnabled") ?: true
        val emoji = doc.getString("ringtoneEmoji") ?: "⏰"

        return FamilyAlarm(
            id = doc.id,
            familyId = familyId,
            title = title,
            hour = hour,
            minute = minute,
            assignedMemberId = assignedToId,
            assignedToMemberId = assignedToId,
            assignedMemberName = assignedMemberName,
            daysOfWeek = rawDays,
            isEnabled = isEnabled,
            ringtoneEmoji = emoji,
            createdAtMillis = doc.getLong("createdAtMillis") ?: 0L,
            createdByMemberId = createdById,
            createdByMemberName = createdByName,
            createdByRole = createdByRole,
            isPersonal = doc.getBoolean("isPersonal") ?: (assignedToId.isNotBlank() && assignedToId == createdById),
            soundType = doc.getString("soundType") ?: "MELODY_ENERGETIC",
            customSoundUri = doc.getString("customSoundUri").orEmpty(),
            customSoundTitle = doc.getString("customSoundTitle").orEmpty(),
            vibrateEnabled = doc.getBoolean("vibrateEnabled") ?: true,
            snoozeDurationMinutes = (doc.getLong("snoozeDurationMinutes") ?: 5L).toInt().coerceIn(1, 60),
            volumePercent = (doc.getLong("volumePercent") ?: 85L).toInt().coerceIn(10, 100),
            scheduleStatus = doc.getString("scheduleStatus")
                ?: if (isEnabled) FamilyAlarm.STATUS_SCHEDULED_LOCALLY else FamilyAlarm.STATUS_DISABLED,
            acknowledgedByMemberId = doc.getString("acknowledgedByMemberId").orEmpty(),
            acknowledgedByDeviceName = doc.getString("acknowledgedByDeviceName").orEmpty(),
            acknowledgedAtMillis = doc.getLong("acknowledgedAtMillis") ?: 0L,
            nextTriggerAtMillis = doc.getLong("nextTriggerAtMillis") ?: 0L,
            lastScheduleError = doc.getString("lastScheduleError").orEmpty()
        )
    }
}
