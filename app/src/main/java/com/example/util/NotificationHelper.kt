package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.notifications.FamilySoundManager
import com.example.notifications.FcmTokenManager
import com.example.notifications.SoundUsageType
import kotlin.math.abs

/**
 * Displays genuine incoming Firebase Cloud Messaging (FCM) push notifications,
 * scheduled task reminders, and family alerts on Android.
 *
 * Implements Android 8+ (`NotificationChannel`) sound management:
 * - Because Android 8+ locks a `NotificationChannel`'s sound after creation, each [SoundUsageType]
 *   tracks its current sound signature in `SharedPreferences`.
 * - When the sound has NOT changed, the existing channel is reused untouched (preserving user OS settings).
 * - When the user selects a new sound in FamilyTasks, ONLY that category's previous channel ID is
 *   deleted and replaced with a single updated channel ID (preventing channel accumulation).
 * - If a custom user-picked sound uses a private `file://` URI (which SystemUI cannot read directly),
 *   the channel uses `setSound(null, null)` and [FamilySoundManager.playNotificationSoundOneShot]
 *   plays the verified local file on `USAGE_NOTIFICATION` (or falls back to the default sound if deleted).
 */
object NotificationHelper {

    const val CHANNEL_TASKS_ID = "family_tasks_channel"
    private const val CHANNEL_TASKS_NAME = "התראות משימות משפחתיות (FamilyTasks)"
    private const val CHANNEL_TASKS_DESC = "התראות בזמן אמת על שיבוץ משימות, בקשות לאישור הורה ואישור נקודות"

    const val CHANNEL_INVITATIONS_ID = "family_invitations_channel"
    private const val CHANNEL_INVITATIONS_NAME = "הזמנות ועדכוני משפחה"
    private const val CHANNEL_INVITATIONS_DESC = "עדכונים על הצטרפות חברי משפחה"

    const val CHANNEL_TASK_REMINDERS_ID = "family_task_scheduled_reminders"
    private const val CHANNEL_TASK_REMINDERS_NAME = "תזכורות מועד ביצוע משימות"
    private const val CHANNEL_TASK_REMINDERS_DESC = "תזכורות מתוזמנות מראש לביצוע משימות משפחתיות במועד שנקבע"

    private const val CHANNEL_PREFS_NAME = "family_notification_channel_sound_prefs"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val audioAttrs = buildNotificationAudioAttributes()

            val taskSoundUri = resolveChannelCompatibleSoundUri(context, SoundUsageType.TASK_CREATED)
            val taskChannel = NotificationChannel(
                CHANNEL_TASKS_ID,
                CHANNEL_TASKS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_TASKS_DESC
                enableVibration(true)
                enableLights(true)
                if (taskSoundUri != null) {
                    setSound(taskSoundUri, audioAttrs)
                }
            }
            notificationManager.createNotificationChannel(taskChannel)

            val inviteSoundUri = resolveChannelCompatibleSoundUri(context, SoundUsageType.FAMILY_GENERAL)
            val inviteChannel = NotificationChannel(
                CHANNEL_INVITATIONS_ID,
                CHANNEL_INVITATIONS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_INVITATIONS_DESC
                enableVibration(true)
                enableLights(true)
                if (inviteSoundUri != null) {
                    setSound(inviteSoundUri, audioAttrs)
                }
            }
            notificationManager.createNotificationChannel(inviteChannel)

            val reminderSoundUri = resolveChannelCompatibleSoundUri(context, SoundUsageType.TASK_REMINDER)
            val reminderChannel = NotificationChannel(
                CHANNEL_TASK_REMINDERS_ID,
                CHANNEL_TASK_REMINDERS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_TASK_REMINDERS_DESC
                enableVibration(true)
                enableLights(true)
                if (reminderSoundUri != null) {
                    setSound(reminderSoundUri, audioAttrs)
                }
            }
            notificationManager.createNotificationChannel(reminderChannel)
        }
    }

    private fun buildNotificationAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun baseChannelPrefixForUsage(usageType: SoundUsageType): String = when (usageType) {
        SoundUsageType.TASK_REMINDER -> CHANNEL_TASK_REMINDERS_ID
        SoundUsageType.FAMILY_GENERAL -> CHANNEL_INVITATIONS_ID
        SoundUsageType.TASK_CREATED -> CHANNEL_TASKS_ID
        SoundUsageType.TASK_UPDATED -> "family_tasks_updated_channel"
        SoundUsageType.TASK_COMPLETED -> "family_tasks_completed_channel"
        SoundUsageType.TASK_DELETED -> "family_tasks_deleted_channel"
        SoundUsageType.ALARM_DEFAULT -> CHANNEL_TASKS_ID
    }

    private fun channelTitleForUsage(usageType: SoundUsageType): String = when (usageType) {
        SoundUsageType.TASK_REMINDER -> CHANNEL_TASK_REMINDERS_NAME
        SoundUsageType.FAMILY_GENERAL -> CHANNEL_INVITATIONS_NAME
        SoundUsageType.TASK_CREATED -> CHANNEL_TASKS_NAME
        SoundUsageType.TASK_UPDATED -> "עדכוני משימות משפחתיות"
        SoundUsageType.TASK_COMPLETED -> "השלמת משימות ונקודות"
        SoundUsageType.TASK_DELETED -> "מחיקת וביטול משימות"
        SoundUsageType.ALARM_DEFAULT -> CHANNEL_TASKS_NAME
    }

    private fun channelDescForUsage(usageType: SoundUsageType): String = when (usageType) {
        SoundUsageType.TASK_REMINDER -> CHANNEL_TASK_REMINDERS_DESC
        SoundUsageType.FAMILY_GENERAL -> CHANNEL_INVITATIONS_DESC
        else -> CHANNEL_TASKS_DESC
    }

    private fun resolveChannelCompatibleSoundUri(
        context: Context,
        usageType: SoundUsageType,
        overrideSoundType: String = "",
        overrideCustomUri: String = ""
    ): Uri? {
        val config = if (overrideSoundType.isNotBlank() || overrideCustomUri.isNotBlank()) {
            com.example.notifications.SelectedSoundConfig(
                soundType = overrideSoundType.ifBlank { com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI },
                customSoundUri = overrideCustomUri
            )
        } else {
            FamilySoundManager.getSoundConfigForUsage(context, usageType)
        }

        val playableUri = FamilySoundManager.resolvePlayableUriOrFallback(
            context = context,
            soundType = config.soundType,
            customSoundUri = config.customSoundUri,
            fallbackUsage = usageType
        ) ?: return null

        // SystemUI can read android.resource:// and content:// URIs directly, but NOT app-private file:// URIs
        val scheme = playableUri.scheme?.lowercase()
        return if (scheme == "android.resource" || scheme == "content") {
            playableUri
        } else {
            null
        }
    }

    /**
     * Ensures an Android 8+ [NotificationChannel] exists for [usageType] with its current sound.
     * If the sound changed since the channel was created, deletes ONLY the single previous channel
     * for [usageType] and creates a rotated channel ID so the new sound takes effect immediately
     * without accumulating stale channels.
     */
    fun ensureChannelForSoundUsage(
        context: Context,
        usageType: SoundUsageType,
        overrideSoundType: String = "",
        overrideCustomUri: String = ""
    ): String {
        val baseId = baseChannelPrefixForUsage(usageType)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return baseId
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return baseId

        val config = if (overrideSoundType.isNotBlank() || overrideCustomUri.isNotBlank()) {
            com.example.notifications.SelectedSoundConfig(
                soundType = overrideSoundType.ifBlank { com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI },
                customSoundUri = overrideCustomUri
            )
        } else {
            FamilySoundManager.getSoundConfigForUsage(context, usageType)
        }

        val playableUri = FamilySoundManager.resolvePlayableUriOrFallback(
            context = context,
            soundType = config.soundType,
            customSoundUri = config.customSoundUri,
            fallbackUsage = usageType
        )
        val isPrivateFileUri = playableUri?.scheme?.equals("file", ignoreCase = true) == true
        val channelSoundUri = if (isPrivateFileUri) null else playableUri
        val soundSignature = if (isPrivateFileUri) {
            "private_file_silent_channel"
        } else {
            channelSoundUri?.toString() ?: "default"
        }

        val prefs = context.getSharedPreferences(CHANNEL_PREFS_NAME, Context.MODE_PRIVATE)
        val keySig = "sig_${usageType.prefKey}"
        val keyChannelId = "cid_${usageType.prefKey}"

        val previousSig = prefs.getString(keySig, null)
        val previousChannelId = prefs.getString(keyChannelId, null)

        // Reuse existing channel when sound signature has not changed
        if (previousSig == soundSignature && !previousChannelId.isNullOrBlank()) {
            val existing = nm.getNotificationChannel(previousChannelId)
            if (existing != null) {
                return previousChannelId
            }
        }

        val defaultItem = FamilySoundManager.findLibrarySoundById(context, usageType.defaultSoundId)
        val defaultUriStr = defaultItem?.buildResourceUri(context) ?: "default"

        val targetChannelId = if (soundSignature == defaultUriStr && previousChannelId == null) {
            baseId
        } else {
            val hashSuffix = abs(soundSignature.hashCode() % 100_000)
            "${baseId}_v_$hashSuffix"
        }

        // Delete ONLY the single previous rotated channel for this usageType (never delete active targetChannelId)
        if (!previousChannelId.isNullOrBlank() && previousChannelId != targetChannelId) {
            runCatching { nm.deleteNotificationChannel(previousChannelId) }
        }

        val channel = NotificationChannel(
            targetChannelId,
            channelTitleForUsage(usageType),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = channelDescForUsage(usageType)
            enableVibration(true)
            enableLights(true)
            if (channelSoundUri != null) {
                setSound(channelSoundUri, buildNotificationAudioAttributes())
            } else {
                setSound(null, null)
            }
        }
        nm.createNotificationChannel(channel)

        prefs.edit()
            .putString(keySig, soundSignature)
            .putString(keyChannelId, targetChannelId)
            .apply()

        return targetChannelId
    }

    /**
     * Maps an incoming FCM or task event type string to the corresponding [SoundUsageType].
     */
    fun resolveSoundUsageForEvent(eventType: String): SoundUsageType {
        return when (eventType.uppercase()) {
            FcmTokenManager.EVENT_TASK_ASSIGNED, "TASK_CREATED", "CREATE" -> SoundUsageType.TASK_CREATED
            FcmTokenManager.EVENT_TASK_REASSIGNED, "TASK_UPDATED", "UPDATE" -> SoundUsageType.TASK_UPDATED
            FcmTokenManager.EVENT_TASK_APPROVED,
            FcmTokenManager.EVENT_TASK_SUBMITTED_FOR_APPROVAL,
            "TASK_COMPLETED",
            "COMPLETE" -> SoundUsageType.TASK_COMPLETED
            "TASK_DELETED", "DELETE", "CANCEL" -> SoundUsageType.TASK_DELETED
            "TASK_REMINDER" -> SoundUsageType.TASK_REMINDER
            else -> SoundUsageType.FAMILY_GENERAL
        }
    }

    /**
     * Displays a scheduled task due-time reminder notification for the assigned family member,
     * using either the task's custom reminder sound (if specified) or the configured
     * [SoundUsageType.TASK_REMINDER] sound.
     */
    fun showTaskScheduledReminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        dueTimeFormatted: String,
        reminderOffsetLabelHe: String,
        assigneeName: String = "",
        points: Int = 0,
        notificationId: Int,
        customSoundType: String = "",
        customSoundUri: String = ""
    ) {
        val namePrefix = if (assigneeName.isNotBlank()) "$assigneeName • " else ""
        val title = "תזכורת למשימה: $taskTitle"
        val pointsSuffix = if (points > 0) " • +$points נק'" else ""
        val body = "${namePrefix}מועד ביצוע: $dueTimeFormatted ($reminderOffsetLabelHe)$pointsSuffix"

        val activeChannelId = ensureChannelForSoundUsage(
            context = context,
            usageType = SoundUsageType.TASK_REMINDER,
            overrideSoundType = customSoundType,
            overrideCustomUri = customSoundUri
        )

        showPushNotification(
            context = context,
            channelId = activeChannelId,
            title = title,
            message = body,
            bigText = body,
            notificationId = notificationId,
            taskId = taskId,
            soundUsageType = SoundUsageType.TASK_REMINDER,
            overrideSoundType = customSoundType,
            overrideCustomUri = customSoundUri
        )
    }

    /**
     * Cancels any active OS notification for a task reminder when the task is completed,
     * rescheduled, reassigned, or deleted.
     */
    fun cancelTaskReminderNotification(context: Context, notificationId: Int) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }

    /**
     * Displays a genuine incoming cross-device FCM notification (when the app is in the
     * foreground, background, or closed) using a deterministic notification ID derived
     * from [eventKey] and the user's configured sound for [eventType].
     */
    fun showIncomingFcmNotification(
        context: Context,
        title: String,
        message: String,
        eventKey: String,
        taskId: String = "",
        eventType: String = FcmTokenManager.EVENT_TASK_ASSIGNED
    ) {
        val deterministicId = 20_000 + abs(eventKey.ifBlank { "$title:$message" }.hashCode() % 70_000)
        val usageType = resolveSoundUsageForEvent(eventType)
        val activeChannelId = ensureChannelForSoundUsage(context, usageType)

        showPushNotification(
            context = context,
            channelId = activeChannelId,
            title = title,
            message = message,
            bigText = message,
            notificationId = deterministicId,
            taskId = taskId,
            soundUsageType = usageType
        )
    }

    fun showPushNotification(
        context: Context,
        channelId: String = CHANNEL_TASKS_ID,
        title: String,
        message: String,
        bigText: String? = null,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
        taskId: String = "",
        soundUsageType: SoundUsageType? = null,
        overrideSoundType: String = "",
        overrideCustomUri: String = ""
    ) {
        createNotificationChannels(context)

        val effectiveUsage = soundUsageType ?: when {
            channelId.startsWith(CHANNEL_TASK_REMINDERS_ID) -> SoundUsageType.TASK_REMINDER
            channelId.startsWith(CHANNEL_INVITATIONS_ID) -> SoundUsageType.FAMILY_GENERAL
            else -> SoundUsageType.TASK_CREATED
        }

        val config = if (overrideSoundType.isNotBlank() || overrideCustomUri.isNotBlank()) {
            com.example.notifications.SelectedSoundConfig(
                soundType = overrideSoundType.ifBlank { com.example.notifications.AlarmAudioController.SOUND_CUSTOM_URI },
                customSoundUri = overrideCustomUri,
                volumePercent = FamilySoundManager.getSoundConfigForUsage(context, effectiveUsage).volumePercent
            )
        } else {
            FamilySoundManager.getSoundConfigForUsage(context, effectiveUsage)
        }

        val playableUri = FamilySoundManager.resolvePlayableUriOrFallback(
            context = context,
            soundType = config.soundType,
            customSoundUri = config.customSoundUri,
            fallbackUsage = effectiveUsage
        )
        val isPrivateFileUri = playableUri?.scheme?.equals("file", ignoreCase = true) == true

        // If a custom private file:// audio was picked, play it safely on USAGE_NOTIFICATION
        if (isPrivateFileUri && playableUri != null) {
            FamilySoundManager.playNotificationSoundOneShot(context, playableUri, config.volumePercent)
        }

        // Check POST_NOTIFICATIONS permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (taskId.isNotBlank()) {
                putExtra("fcm_task_id", taskId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isReminder = effectiveUsage == SoundUsageType.TASK_REMINDER ||
            channelId.startsWith(CHANNEL_TASK_REMINDERS_ID)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText ?: message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isReminder) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (!isPrivateFileUri && playableUri != null) {
            builder.setSound(playableUri)
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
        } else if (isPrivateFileUri) {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Handled gracefully if permission is revoked at runtime
        }
    }
}
