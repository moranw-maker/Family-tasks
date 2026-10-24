package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlin.math.abs

/**
 * Displays genuine incoming Firebase Cloud Messaging (FCM) push notifications and
 * server-verified real-time cross-device task events on Android.
 *
 * Does NOT fire local simulated alerts when a button is tapped on the same device.
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

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val taskChannel = NotificationChannel(
                CHANNEL_TASKS_ID,
                CHANNEL_TASKS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_TASKS_DESC
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(taskChannel)

            val inviteChannel = NotificationChannel(
                CHANNEL_INVITATIONS_ID,
                CHANNEL_INVITATIONS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_INVITATIONS_DESC
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(inviteChannel)

            val reminderChannel = NotificationChannel(
                CHANNEL_TASK_REMINDERS_ID,
                CHANNEL_TASK_REMINDERS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_TASK_REMINDERS_DESC
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(reminderChannel)
        }
    }

    /**
     * Displays a scheduled task due-time reminder notification for the assigned family member.
     */
    fun showTaskScheduledReminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        dueTimeFormatted: String,
        reminderOffsetLabelHe: String,
        assigneeName: String = "",
        points: Int = 0,
        notificationId: Int
    ) {
        val namePrefix = if (assigneeName.isNotBlank()) "$assigneeName • " else ""
        val title = "תזכורת למשימה: $taskTitle"
        val pointsSuffix = if (points > 0) " • +$points נק'" else ""
        val body = "${namePrefix}מועד ביצוע: $dueTimeFormatted ($reminderOffsetLabelHe)$pointsSuffix"

        showPushNotification(
            context = context,
            channelId = CHANNEL_TASK_REMINDERS_ID,
            title = title,
            message = body,
            bigText = body,
            notificationId = notificationId,
            taskId = taskId
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
     * from [eventKey] to prevent duplicate OS notifications.
     */
    fun showIncomingFcmNotification(
        context: Context,
        title: String,
        message: String,
        eventKey: String,
        taskId: String = ""
    ) {
        val deterministicId = 20_000 + abs(eventKey.ifBlank { "$title:$message" }.hashCode() % 70_000)
        showPushNotification(
            context = context,
            channelId = CHANNEL_TASKS_ID,
            title = title,
            message = message,
            bigText = message,
            notificationId = deterministicId,
            taskId = taskId
        )
    }

    fun showPushNotification(
        context: Context,
        channelId: String = CHANNEL_TASKS_ID,
        title: String,
        message: String,
        bigText: String? = null,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
        taskId: String = ""
    ) {
        createNotificationChannels(context)

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

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText ?: message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (channelId == CHANNEL_TASK_REMINDERS_ID) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Handled gracefully if permission is revoked at runtime
        }
    }
}
