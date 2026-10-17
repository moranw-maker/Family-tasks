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

object NotificationHelper {

    const val CHANNEL_INVITATIONS_ID = "family_invitations_channel"
    private const val CHANNEL_INVITATIONS_NAME = "Family Invitations & Alerts"
    private const val CHANNEL_INVITATIONS_DESC = "Push notifications for new family member invitations and alerts"

    const val CHANNEL_TASKS_ID = "family_tasks_channel"
    private const val CHANNEL_TASKS_NAME = "Family Tasks & Reminders"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // Channel for Invitations (High Importance - Heads Up Banner + Sound)
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

            // Channel for Tasks & General Alerts
            val taskChannel = NotificationChannel(
                CHANNEL_TASKS_ID,
                CHANNEL_TASKS_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(taskChannel)
        }
    }

    fun showInvitationNotification(
        context: Context,
        inviteeName: String,
        familyName: String,
        inviteCode: String,
        isHebrew: Boolean = true
    ) {
        createNotificationChannels(context)

        val title = if (isHebrew) {
            "הזמנה חדשה למשפחת $familyName! 💌"
        } else {
            "New Invitation to $familyName! 💌"
        }

        val message = if (isHebrew) {
            "נשלחה הזמנה עבור $inviteeName. קוד הצטרפות: $inviteCode"
        } else {
            "Invitation sent for $inviteeName. Household invite code: $inviteCode"
        }

        val bigText = if (isHebrew) {
            "הזמנה רשמית נשלחה עבור $inviteeName להצטרף ל-$familyName.\nהמוזמן יכול להצטרף כעת ישירות באמצעות קוד ההזמנה: $inviteCode"
        } else {
            "An official invitation was sent for $inviteeName to join $familyName.\nThey can join immediately using the invite code: $inviteCode"
        }

        showPushNotification(
            context = context,
            channelId = CHANNEL_INVITATIONS_ID,
            title = title,
            message = message,
            bigText = bigText,
            notificationId = 1001 + (System.currentTimeMillis() % 1000).toInt()
        )
    }

    fun showPushNotification(
        context: Context,
        channelId: String = CHANNEL_INVITATIONS_ID,
        title: String,
        message: String,
        bigText: String? = null,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt()
    ) {
        createNotificationChannels(context)

        // Check POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // If permission is not yet granted, return gracefully
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Handled gracefully if permission revoked at runtime
        }
    }
}
