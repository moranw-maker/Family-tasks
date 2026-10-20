package com.example.notifications

import android.util.Log
import com.example.data.FamilyRepository
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
 *    `/users/{uid}/fcm_tokens/{tokenId}`.
 * 2. Incoming cross-device push notifications (`onMessageReceived`) for:
 *    - A. Parent assigns a task to a child or other family member (`TASK_ASSIGNED`)
 *    - B. Child submits a task for parent approval (`TASK_SUBMITTED_FOR_APPROVAL`)
 *    - C. Parent approves a completed task (`TASK_APPROVED`)
 *    - D. Task is reassigned (`TASK_REASSIGNED`)
 * 3. Recipient verification and strict event-key deduplication so the sender never self-notifies
 *    and duplicate deliveries are ignored.
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
                FcmTokenManager.registerTokenInFirestore(
                    db = db,
                    uid = currentUser.uid,
                    token = token
                )
            }.onFailure { e ->
                Log.w(TAG, "onNewToken Firestore registration deferred until sign-in: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val eventType = data["eventType"] ?: data["type"] ?: FcmTokenManager.EVENT_TASK_ASSIGNED
        val familyId = data["familyId"].orEmpty()
        val taskId = data["taskId"].orEmpty()
        val targetUid = data["targetUid"].orEmpty()
        val targetRole = data["targetRole"].orEmpty()
        val actorUid = data["actorUid"].orEmpty()
        val eventKey = data["eventKey"]
            ?.takeIf { it.isNotBlank() }
            ?: remoteMessage.messageId
            ?: "$eventType:$taskId:${data["updatedAt"] ?: System.currentTimeMillis()}"

        // Verify current authenticated user is the intended recipient (never notify the actor who triggered the write)
        val signedInUid = runCatching {
            if (FirebaseApp.getApps(applicationContext).isNotEmpty()) {
                FirebaseAuth.getInstance().currentUser?.uid
            } else null
        }.getOrNull()

        if (!signedInUid.isNullOrBlank()) {
            if (actorUid.isNotBlank() && actorUid == signedInUid) {
                // Sender must not receive their own push notification
                return
            }
            if (targetUid.isNotBlank() && targetUid != signedInUid) {
                // Message was targeted at a different user account
                return
            }
        }

        // Deduplicate by eventKey so FCM + Firestore snapshot or retry never shows twice
        if (!FcmTokenManager.markEventDispatchedIfNew(eventKey)) {
            return
        }

        val resolvedTitle = data["title"]
            ?.takeIf { it.isNotBlank() }
            ?: notification?.title
            ?: defaultTitleForEvent(eventType)

        val resolvedBody = data["body"]
            ?.takeIf { it.isNotBlank() }
            ?: notification?.body
            ?: ""

        if (resolvedTitle.isBlank() && resolvedBody.isBlank()) return

        // Display genuine system notification via NotificationHelper
        NotificationHelper.showIncomingFcmNotification(
            context = applicationContext,
            title = resolvedTitle,
            message = resolvedBody,
            eventKey = eventKey,
            taskId = taskId
        )

        // Emit to in-app stream so notification history stays in sync
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
    }

    private fun defaultTitleForEvent(eventType: String): String {
        return when (eventType) {
            FcmTokenManager.EVENT_TASK_ASSIGNED -> "משימה חדשה שובצה עבורך 📋"
            FcmTokenManager.EVENT_TASK_SUBMITTED_FOR_APPROVAL -> "משימה ממתינה לאישור הורה ⏳"
            FcmTokenManager.EVENT_TASK_APPROVED -> "המשימה שלך אושרה! 🎉"
            FcmTokenManager.EVENT_TASK_REASSIGNED -> "משימה הועברה אליך 🔄"
            else -> "עדכון משימות משפחתיות 🏡"
        }
    }

    companion object {
        private const val TAG = "FamilyMessagingService"
    }
}
