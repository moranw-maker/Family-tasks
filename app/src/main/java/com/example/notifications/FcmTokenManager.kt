package com.example.notifications

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.FamilyRepository
import com.example.model.UserRole
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Collections

/**
 * Represents a validated incoming cross-device push notification payload delivered via FCM
 * (or server-committed Firestore event).
 */
data class IncomingPushNotificationPayload(
    val eventKey: String,
    val eventType: String,
    val familyId: String,
    val taskId: String,
    val targetUid: String,
    val targetRole: String,
    val actorUid: String,
    val title: String,
    val body: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Manages FCM registration tokens per authenticated user UID and device in the configured
 * named Firestore database (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`).
 *
 * Security & Reliability guarantees:
 * 1. Associates each device's FCM token with the authenticated user's UID at `/users/{uid}/fcm_tokens/{tokenId}`.
 * 2. Supports multiple Android devices per user via deterministic SHA-256 `tokenId` document keys.
 * 3. Handles token refresh (`onNewToken`), login/session restore sync, logout token removal,
 *    and invalid/unregistered token cleanup.
 * 4. Deduplicates notification `eventKey`s across FCM and Firestore listeners so a user never
 *    receives duplicate notifications.
 */
object FcmTokenManager {

    private const val TAG = "FamilyTasksFcm"
    private const val PREFS_NAME = "family_tasks_fcm_prefs"
    private const val KEY_CACHED_FCM_TOKEN = "cached_fcm_token"
    private const val KEY_LAST_SYNCED_UID = "last_synced_uid"
    private const val KEY_LAST_SYNCED_FAMILY_ID = "last_synced_family_id"
    private const val KEY_LAST_SYNCED_ROLE = "last_synced_role"
    private const val KEY_DISPATCHED_EVENT_KEYS = "dispatched_event_keys_v1"

    const val EVENT_TASK_ASSIGNED = "TASK_ASSIGNED"
    const val EVENT_OPEN_TASK_CREATED = "OPEN_TASK_CREATED"
    const val EVENT_TASK_SUBMITTED_FOR_APPROVAL = "TASK_SUBMITTED_FOR_APPROVAL"
    const val EVENT_TASK_APPROVED = "TASK_APPROVED"
    const val EVENT_TASK_REASSIGNED = "TASK_REASSIGNED"
    const val EVENT_ALARM_SYNC_REQUIRED = "ALARM_SYNC_REQUIRED"
    const val EVENT_TASK_SYNC_REQUIRED = "TASK_SYNC_REQUIRED"

    private val fcmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedAppContext: Context? = null

    private val dispatchedEventKeys: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    private val _incomingPushEvents = MutableSharedFlow<IncomingPushNotificationPayload>(extraBufferCapacity = 16)
    val incomingPushEvents: SharedFlow<IncomingPushNotificationPayload> = _incomingPushEvents.asSharedFlow()

    private fun rememberContext(context: Context?): Context? {
        if (context != null) {
            cachedAppContext = context.applicationContext ?: context
        }
        return cachedAppContext
    }

    /**
     * Computes a deterministic 32-character SHA-256 hex ID for an FCM registration token.
     * Ensures multiple devices per user coexist under `/users/{uid}/fcm_tokens/{tokenId}`
     * without overwriting each other.
     */
    fun computeTokenId(rawToken: String): String {
        val clean = rawToken.trim()
        if (clean.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Builds the Firestore payload map for `/users/{uid}/fcm_tokens/{tokenId}`.
     */
    fun buildTokenFirestorePayload(
        uid: String,
        token: String,
        familyId: String = "",
        role: UserRole = UserRole.CHILD,
        nowMillis: Long = System.currentTimeMillis()
    ): Map<String, Any> {
        val tokenId = computeTokenId(token)
        val deviceModel = runCatching {
            "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        }.getOrDefault("Android Device")

        return mapOf(
            "uid" to uid,
            "tokenId" to tokenId,
            "token" to token.trim(),
            "familyId" to familyId,
            "role" to role.toFirestoreValue(),
            "platform" to "ANDROID",
            "deviceModel" to deviceModel,
            "appVersion" to BuildConfig.VERSION_NAME,
            "isActive" to true,
            "updatedAt" to nowMillis
        )
    }

    /**
     * Normalizes event keys across local Firestore snapshot listeners and `onFamilyTaskWritten` FCM payloads.
     * For example:
     * - `open_task:{taskId}:{recipientUid}` and `open_task:{taskId}` -> `open_task:{taskId}`
     * - `assign:{taskId}:{assigneeUid}:{recipientUid}` -> `assign:{taskId}:{assigneeUid}`
     * - `approve:{taskId}:{recipientUid}` -> `approve:{taskId}`
     * - `submit:{taskId}:{stamp}:{recipientUid}` -> `submit:{taskId}:{stamp}`
     * - `reassign:{taskId}:{assigneeUid}:{stamp}:{recipientUid}` -> `reassign:{taskId}:{assigneeUid}:{stamp}`
     */
    fun canonicalizeEventKey(eventKey: String): String {
        val clean = eventKey.trim()
        if (clean.isEmpty()) return ""
        val parts = clean.split(":")
        val prefix = parts.firstOrNull()?.lowercase().orEmpty()
        return when {
            (prefix == "open_task" || prefix == "open_task_created") && parts.size >= 2 ->
                "open_task:${parts[1]}"
            prefix == "approve" && parts.size >= 2 ->
                "approve:${parts[1]}"
            prefix == "assign" && parts.size >= 3 ->
                "assign:${parts[1]}:${parts[2]}"
            prefix == "submit" && parts.size >= 3 ->
                "submit:${parts[1]}:${parts[2]}"
            prefix == "reassign" && parts.size >= 4 ->
                "reassign:${parts[1]}:${parts[2]}:${parts[3]}"
            parts.size >= 4 && parts[parts.size - 1] == parts[parts.size - 2] ->
                parts.dropLast(1).joinToString(":")
            else -> clean
        }
    }

    private fun loadPersistedDispatchedKeysLocked(ctx: Context?) {
        if (ctx == null) return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DISPATCHED_EVENT_KEYS, null).orEmpty()
            if (raw.isNotBlank()) {
                raw.split("\n").forEach { item ->
                    val k = item.trim()
                    if (k.isNotEmpty()) {
                        dispatchedEventKeys.add(k)
                    }
                }
            }
        }
    }

    private fun persistDispatchedKeysLocked(ctx: Context?) {
        if (ctx == null) return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val serialized = dispatchedEventKeys.toList().takeLast(500).joinToString("\n")
            prefs.edit().putString(KEY_DISPATCHED_EVENT_KEYS, serialized).commit()
        }
    }

    /**
     * Deduplicates notification events by [eventKey] across both in-memory state (foreground)
     * and persistent `SharedPreferences` (background / closed app / cold restart).
     * Returns `true` the first time [eventKey] (or its canonical form) is seen, and `false` on any duplicate.
     */
    @JvmOverloads
    fun markEventDispatchedIfNew(eventKey: String, context: Context? = null): Boolean {
        val clean = eventKey.trim()
        if (clean.isEmpty()) return true
        val ctx = rememberContext(context)
        val canonical = canonicalizeEventKey(clean)
        synchronized(dispatchedEventKeys) {
            loadPersistedDispatchedKeysLocked(ctx)
            if (dispatchedEventKeys.contains(clean) || dispatchedEventKeys.contains(canonical)) {
                return false
            }
            while (dispatchedEventKeys.size >= 500) {
                val iterator = dispatchedEventKeys.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                } else {
                    break
                }
            }
            dispatchedEventKeys.add(clean)
            dispatchedEventKeys.add(canonical)
            persistDispatchedKeysLocked(ctx)
            return true
        }
    }

    @JvmOverloads
    fun clearEventKey(eventKey: String, context: Context? = null) {
        val clean = eventKey.trim()
        val ctx = rememberContext(context)
        val canonical = canonicalizeEventKey(clean)
        synchronized(dispatchedEventKeys) {
            loadPersistedDispatchedKeysLocked(ctx)
            dispatchedEventKeys.remove(clean)
            dispatchedEventKeys.remove(canonical)
            persistDispatchedKeysLocked(ctx)
        }
    }

    /**
     * Clears only the in-memory set while keeping SharedPreferences intact — used in tests to
     * simulate process death / app restart after a background FCM notification.
     */
    fun clearInMemoryDispatchedEventsOnlyForTesting() {
        synchronized(dispatchedEventKeys) {
            dispatchedEventKeys.clear()
        }
    }

    @JvmOverloads
    fun clearAllDispatchedEventsForTesting(context: Context? = null) {
        val ctx = rememberContext(context)
        synchronized(dispatchedEventKeys) {
            dispatchedEventKeys.clear()
            if (ctx != null) {
                runCatching {
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_DISPATCHED_EVENT_KEYS)
                        .commit()
                }
            }
        }
    }

    fun emitIncomingPushEvent(payload: IncomingPushNotificationPayload) {
        _incomingPushEvents.tryEmit(payload)
    }

    fun cacheTokenLocally(
        context: Context?,
        token: String,
        uid: String? = null,
        familyId: String? = null,
        role: UserRole? = null
    ) {
        if (context == null || token.isBlank()) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(KEY_CACHED_FCM_TOKEN, token.trim())
                if (!uid.isNullOrBlank()) {
                    putString(KEY_LAST_SYNCED_UID, uid.trim())
                }
                if (!familyId.isNullOrBlank()) {
                    putString(KEY_LAST_SYNCED_FAMILY_ID, familyId.trim())
                }
                if (role != null) {
                    putString(KEY_LAST_SYNCED_ROLE, role.toFirestoreValue())
                }
                apply()
            }
        }
    }

    fun cacheSessionMetadata(
        context: Context?,
        uid: String,
        familyId: String,
        role: UserRole
    ) {
        if (context == null) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (uid.isNotBlank()) putString(KEY_LAST_SYNCED_UID, uid.trim())
                if (familyId.isNotBlank()) putString(KEY_LAST_SYNCED_FAMILY_ID, familyId.trim())
                putString(KEY_LAST_SYNCED_ROLE, role.toFirestoreValue())
                apply()
            }
        }
    }

    fun getCachedToken(context: Context?): String? {
        if (context == null) return null
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_CACHED_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun getCachedUid(context: Context?): String? {
        if (context == null) return null
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_LAST_SYNCED_UID, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun getCachedFamilyId(context: Context?): String? {
        if (context == null) return null
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_LAST_SYNCED_FAMILY_ID, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun getCachedRole(context: Context?): String? {
        if (context == null) return null
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_LAST_SYNCED_ROLE, null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Persists a specific [token] for [uid] into the named Firestore database under
     * `/users/{uid}/fcm_tokens/{tokenId}`.
     */
    suspend fun registerTokenInFirestore(
        db: FirebaseFirestore,
        uid: String,
        token: String,
        familyId: String = "",
        role: UserRole = UserRole.CHILD
    ): Result<String> {
        val cleanUid = uid.trim()
        val cleanToken = token.trim()
        if (cleanUid.isEmpty() || cleanToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("UID and FCM token must not be blank"))
        }
        val tokenId = computeTokenId(cleanToken)
        return try {
            val payload = buildTokenFirestorePayload(
                uid = cleanUid,
                token = cleanToken,
                familyId = familyId,
                role = role
            )
            db.collection("users").document(cleanUid)
                .collection("fcm_tokens").document(tokenId)
                .set(payload, SetOptions.merge())
                .await()
            Result.success(tokenId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register FCM token in Firestore for uid=$cleanUid: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Removes a specific device's FCM token from `/users/{uid}/fcm_tokens/{tokenId}` on logout
     * or when FCM reports the token as invalid/unregistered.
     */
    suspend fun unregisterTokenFromFirestore(
        db: FirebaseFirestore,
        uid: String,
        token: String
    ): Result<Unit> {
        val cleanUid = uid.trim()
        val tokenId = computeTokenId(token)
        if (cleanUid.isEmpty() || tokenId.isEmpty()) {
            return Result.success(Unit)
        }
        return try {
            db.collection("users").document(cleanUid)
                .collection("fcm_tokens").document(tokenId)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete FCM token on logout/cleanup: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Synchronizes the current Android device's FCM token with the named Firestore database
     * for the currently authenticated user.
     */
    fun syncCurrentDeviceTokenAsync(
        context: Context? = null,
        dbOverride: FirebaseFirestore? = null,
        uidOverride: String? = null,
        familyId: String = "",
        role: UserRole = UserRole.CHILD
    ) {
        fcmScope.launch {
            runCatching {
                val appReady = FirebaseApp.getApps(context ?: return@launch).isNotEmpty()
                if (!appReady) return@launch

                val uid = uidOverride?.takeIf { it.isNotBlank() }
                    ?: FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@launch

                val db = dbOverride ?: FirebaseFirestore.getInstance(
                    FirebaseApp.getInstance(),
                    FamilyRepository.NAMED_FIRESTORE_DATABASE_ID
                )

                val token = runCatching {
                    FirebaseMessaging.getInstance().token.await()
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: getCachedToken(context)
                    ?: return@launch

                cacheTokenLocally(context, token, uid, familyId, role)
                registerTokenInFirestore(
                    db = db,
                    uid = uid,
                    token = token,
                    familyId = familyId,
                    role = role
                )
            }
        }
    }

    /**
     * Removes the current device's FCM token from Firestore before signing out of FirebaseAuth
     * so a logged-out device no longer receives notifications for the previous user.
     */
    fun unregisterCurrentDeviceTokenAsync(
        context: Context? = null,
        dbOverride: FirebaseFirestore? = null,
        uidOverride: String? = null
    ) {
        val cachedToken = getCachedToken(context)
        val resolvedUid = uidOverride?.takeIf { it.isNotBlank() }
            ?: runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
            ?: getCachedUid(context)

        runCatching {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.remove(KEY_CACHED_FCM_TOKEN)
                ?.remove(KEY_LAST_SYNCED_UID)
                ?.remove(KEY_LAST_SYNCED_FAMILY_ID)
                ?.remove(KEY_LAST_SYNCED_ROLE)
                ?.apply()
        }

        if (resolvedUid.isNullOrBlank()) return

        fcmScope.launch {
            runCatching {
                val db = dbOverride ?: FirebaseFirestore.getInstance(
                    FirebaseApp.getInstance(),
                    FamilyRepository.NAMED_FIRESTORE_DATABASE_ID
                )
                val token = cachedToken ?: runCatching {
                    FirebaseMessaging.getInstance().token.await()
                }.getOrNull()

                if (!token.isNullOrBlank()) {
                    unregisterTokenFromFirestore(db, resolvedUid, token)
                }
            }
        }
    }
}
