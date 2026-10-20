package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.FamilyAlarm
import com.example.model.SchoolDay

/**
 * BroadcastReceiver that executes scheduled local FamilyTasks alarms, plays the configured
 * system or custom alarm sound with volume control, triggers vibration, posts an alarm
 * notification with Snooze (נודניק) and Dismiss (כבה) actions, and schedules the next recurring day.
 */
class FamilyAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val ALARM_CHANNEL_ID = "family_tasks_alarm_clock_channel"
        private var activeMediaPlayer: MediaPlayer? = null
        private var activeRingtone: Ringtone? = null

        fun stopActiveRinging(context: Context) {
            runCatching {
                activeMediaPlayer?.let { player ->
                    if (player.isPlaying) player.stop()
                    player.release()
                }
            }
            activeMediaPlayer = null

            runCatching {
                activeRingtone?.let { ringtone ->
                    if (ringtone.isPlaying) ringtone.stop()
                }
            }
            activeRingtone = null

            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.cancel()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val alarmId = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID).orEmpty()
        if (alarmId.isBlank()) return

        when (action) {
            FamilyAlarmScheduler.ACTION_DISMISS_FAMILY_ALARM -> {
                stopActiveRinging(context)
                dismissNotification(context, alarmId)
            }

            FamilyAlarmScheduler.ACTION_SNOOZE_FAMILY_ALARM -> {
                stopActiveRinging(context)
                dismissNotification(context, alarmId)
                val title = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE) ?: "שעון מעורר משפחתי"
                val soundType = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE) ?: "SYSTEM_ALARM"
                val customUri = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI).orEmpty()
                val vibrate = intent.getBooleanExtra(FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE, true)
                val snoozeMin = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5)
                val volume = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, 85)
                FamilyAlarmScheduler.scheduleSnoozeAlarm(
                    context = context,
                    alarmId = alarmId,
                    title = title,
                    soundType = soundType,
                    customSoundUri = customUri,
                    vibrateEnabled = vibrate,
                    snoozeMinutes = snoozeMin,
                    volumePercent = volume
                )
            }

            FamilyAlarmScheduler.ACTION_TRIGGER_FAMILY_ALARM -> {
                val title = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE) ?: "שעון מעורר משפחתי"
                val hour = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, 7)
                val minute = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, 0)
                val soundType = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE) ?: "SYSTEM_ALARM"
                val customUri = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI).orEmpty()
                val vibrate = intent.getBooleanExtra(FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE, true)
                val snoozeMin = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5)
                val volumePercent = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, 85)
                val daysCsv = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_DAYS_CSV).orEmpty()
                val isSnooze = intent.getBooleanExtra(FamilyAlarmScheduler.EXTRA_IS_SNOOZE_TRIGGER, false)

                // Play sound and vibration
                playAlarmAudioAndVibrate(
                    context = context,
                    soundType = soundType,
                    customUriString = customUri,
                    vibrateEnabled = vibrate,
                    volumePercent = volumePercent
                )

                // Show high-priority notification with Snooze and Dismiss actions
                postAlarmNotification(
                    context = context,
                    alarmId = alarmId,
                    title = title,
                    hour = hour,
                    minute = minute,
                    soundType = soundType,
                    customUri = customUri,
                    vibrateEnabled = vibrate,
                    snoozeMinutes = snoozeMin,
                    volumePercent = volumePercent
                )

                // Schedule next recurring day if not a one-shot snooze
                if (!isSnooze) {
                    val days = daysCsv.split(",")
                        .mapNotNull { token -> runCatching { SchoolDay.valueOf(token.trim()) }.getOrNull() }
                    if (days.isNotEmpty()) {
                        val recurringAlarm = FamilyAlarm(
                            id = alarmId,
                            title = title,
                            hour = hour,
                            minute = minute,
                            daysOfWeek = days,
                            isEnabled = true,
                            soundType = soundType,
                            customSoundUri = customUri,
                            vibrateEnabled = vibrate,
                            snoozeDurationMinutes = snoozeMin,
                            volumePercent = volumePercent
                        )
                        FamilyAlarmScheduler.scheduleLocalAlarm(
                            context = context,
                            alarm = recurringAlarm,
                            nowMillis = System.currentTimeMillis() + 60_000L
                        )
                    }
                }
            }
        }
    }

    private fun playAlarmAudioAndVibrate(
        context: Context,
        soundType: String,
        customUriString: String,
        vibrateEnabled: Boolean,
        volumePercent: Int
    ) {
        stopActiveRinging(context)

        val resolvedUri: Uri = when {
            soundType == "CUSTOM_URI" && customUriString.isNotBlank() -> {
                runCatching { Uri.parse(customUriString) }.getOrNull()
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            soundType == "SYSTEM_NOTIFICATION" -> {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            soundType == "SYSTEM_RINGTONE" -> {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            else -> {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
        }

        val clampedVolume = (volumePercent.coerceIn(10, 100) / 100f)

        // Attempt to adjust STREAM_ALARM volume if permitted by device policy
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                if (maxVol > 0) {
                    val targetVol = (maxVol * clampedVolume).toInt().coerceIn(1, maxVol)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
                }
            }
        }

        // Play audio using MediaPlayer on STREAM_ALARM with fallback to Ringtone
        runCatching {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, resolvedUri)
                isLooping = true
                setVolume(clampedVolume, clampedVolume)
                prepare()
                start()
            }
            activeMediaPlayer = player
        }.onFailure {
            runCatching {
                val ringtone = RingtoneManager.getRingtone(context, resolvedUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = true
                    ringtone?.volume = clampedVolume
                }
                ringtone?.play()
                activeRingtone = ringtone
            }
        }

        if (vibrateEnabled) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator?.hasVibrator() == true) {
                    val pattern = longArrayOf(0, 600, 400, 600, 400, 800)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(pattern, 0)
                    }
                }
            }
        }
    }

    private fun postAlarmNotification(
        context: Context,
        alarmId: String,
        title: String,
        hour: Int,
        minute: Int,
        soundType: String,
        customUri: String,
        vibrateEnabled: Boolean,
        snoozeMinutes: Int,
        volumePercent: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "שעון מעורר משפחתי (Family Alarms)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "התראות שעון מעורר משפחתי והשכמה לבית הספר"
                enableVibration(vibrateEnabled)
            }
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            alarmId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, FamilyAlarmReceiver::class.java).apply {
            action = FamilyAlarmScheduler.ACTION_SNOOZE_FAMILY_ALARM
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE, title)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE, soundType)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI, customUri)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE, vibrateEnabled)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, snoozeMinutes)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, volumePercent)
        }
        val snoozePi = PendingIntent.getBroadcast(
            context,
            (alarmId.hashCode() * 31 + 2) and 0x7FFFFFFF,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(context, FamilyAlarmReceiver::class.java).apply {
            action = FamilyAlarmScheduler.ACTION_DISMISS_FAMILY_ALARM
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }
        val dismissPi = PendingIntent.getBroadcast(
            context,
            (alarmId.hashCode() * 31 + 3) and 0x7FFFFFFF,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏰ $title ($timeFormatted)")
            .setContentText("שעון מעורר משפחתי פעיל • לחצו לנודניק ($snoozeMinutes דק׳) או לכיבוי")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPi)
            .addAction(0, "נודניק ($snoozeMinutes דק׳)", snoozePi)
            .addAction(0, "כבה שעון", dismissPi)
            .build()

        nm.notify(notificationIdForAlarm(alarmId), notification)
    }

    private fun dismissNotification(context: Context, alarmId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(notificationIdForAlarm(alarmId))
    }

    private fun notificationIdForAlarm(alarmId: String): Int {
        return 7000 + (alarmId.hashCode() and 0x0FFF)
    }
}
