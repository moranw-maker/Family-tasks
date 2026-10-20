package com.example.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActiveRingingAlarmState(
    val alarmId: String,
    val title: String,
    val timeFormatted: String,
    val hour: Int,
    val minute: Int,
    val soundType: String,
    val customSoundUri: String,
    val vibrateEnabled: Boolean,
    val snoozeMinutes: Int,
    val volumePercent: Int,
    val startedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Foreground Service responsible for keeping the alarm sound & vibration alive
 * until the user taps Dismiss ("כיבוי"), Snooze ("נודניק"), or the 5-minute safety timeout expires.
 */
class FamilyAlarmRingingService : Service() {

    companion object {
        const val ACTION_START_RINGING = "com.example.action.START_ALARM_RINGING_SERVICE"
        const val ACTION_STOP_RINGING = "com.example.action.STOP_ALARM_RINGING_SERVICE"
        const val ACTION_SNOOZE_RINGING = "com.example.action.SNOOZE_ALARM_RINGING_SERVICE"

        private const val ALARM_CHANNEL_ID = "family_alarm_clock_ringing_v2"
        private const val FOREGROUND_NOTIFICATION_ID = 7901
        private const val SAFETY_TIMEOUT_MILLIS = 5 * 60_000L // 5 minutes safety timeout

        private val _activeRingingAlarm = MutableStateFlow<ActiveRingingAlarmState?>(null)
        val activeRingingAlarm: StateFlow<ActiveRingingAlarmState?> = _activeRingingAlarm.asStateFlow()

        fun startRingingService(
            context: Context,
            alarmId: String,
            title: String,
            hour: Int,
            minute: Int,
            soundType: String,
            customSoundUri: String,
            vibrateEnabled: Boolean,
            snoozeMinutes: Int,
            volumePercent: Int
        ) {
            val appCtx = context.applicationContext
            val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            _activeRingingAlarm.value = ActiveRingingAlarmState(
                alarmId = alarmId,
                title = title,
                timeFormatted = timeFormatted,
                hour = hour,
                minute = minute,
                soundType = soundType,
                customSoundUri = customSoundUri,
                vibrateEnabled = vibrateEnabled,
                snoozeMinutes = snoozeMinutes,
                volumePercent = volumePercent
            )

            val serviceIntent = Intent(appCtx, FamilyAlarmRingingService::class.java).apply {
                action = ACTION_START_RINGING
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE, title)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, hour)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, minute)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE, soundType)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI, customSoundUri)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE, vibrateEnabled)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, snoozeMinutes)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, volumePercent)
            }

            val startedForeground = runCatching {
                ContextCompat.startForegroundService(appCtx, serviceIntent)
                true
            }.getOrDefault(false)

            if (!startedForeground) {
                // Fallback if OS blocks foreground service start in a specific state
                AlarmAudioController.startAlarmRinging(
                    context = appCtx,
                    soundType = soundType,
                    customSoundUri = customSoundUri,
                    volumePercent = volumePercent,
                    vibrateEnabled = vibrateEnabled
                )
            }
        }

        fun dismissActiveAlarm(context: Context, alarmId: String = "") {
            val appCtx = context.applicationContext
            val current = _activeRingingAlarm.value
            val resolvedId = alarmId.ifBlank { current?.alarmId.orEmpty() }
            _activeRingingAlarm.value = null
            AlarmAudioController.stopAlarmRinging(appCtx)

            val notificationManager = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(FOREGROUND_NOTIFICATION_ID)
            if (resolvedId.isNotBlank()) {
                notificationManager?.cancel((resolvedId.hashCode() and 0x7FFFFFFF))
            }

            runCatching {
                appCtx.stopService(Intent(appCtx, FamilyAlarmRingingService::class.java))
            }
        }

        fun snoozeActiveAlarm(context: Context): Long {
            val appCtx = context.applicationContext
            val current = _activeRingingAlarm.value ?: return 0L
            val nextSnoozeMillis = FamilyAlarmScheduler.scheduleSnoozeAlarm(
                context = appCtx,
                alarmId = current.alarmId,
                title = current.title,
                soundType = current.soundType,
                customSoundUri = current.customSoundUri,
                vibrateEnabled = current.vibrateEnabled,
                snoozeMinutes = current.snoozeMinutes,
                volumePercent = current.volumePercent
            )
            dismissActiveAlarm(appCtx, current.alarmId)
            return nextSnoozeMillis
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeoutJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RINGING -> {
                val alarmId = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID).orEmpty()
                dismissActiveAlarm(this, alarmId)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE_RINGING -> {
                snoozeActiveAlarm(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_RINGING -> {
                val alarmId = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID).orEmpty().ifBlank { "alarm_default" }
                val title = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE) ?: "שעון מעורר משפחתי"
                val hour = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, 7)
                val minute = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, 0)
                val soundType = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE) ?: AlarmAudioController.SOUND_MELODY_ENERGETIC
                val customUri = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI).orEmpty()
                val vibrate = intent.getBooleanExtra(FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE, true)
                val snoozeMin = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5)
                val volumePercent = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, 85)
                val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

                _activeRingingAlarm.value = ActiveRingingAlarmState(
                    alarmId = alarmId,
                    title = title,
                    timeFormatted = timeFormatted,
                    hour = hour,
                    minute = minute,
                    soundType = soundType,
                    customSoundUri = customUri,
                    vibrateEnabled = vibrate,
                    snoozeMinutes = snoozeMin,
                    volumePercent = volumePercent
                )

                acquireBoundedWakeLock()
                ensureAlarmNotificationChannel()
                val notification = buildForegroundAlarmNotification(
                    alarmId = alarmId,
                    title = title,
                    timeFormatted = timeFormatted,
                    hour = hour,
                    minute = minute,
                    soundType = soundType,
                    customUri = customUri,
                    vibrateEnabled = vibrate,
                    snoozeMinutes = snoozeMin,
                    volumePercent = volumePercent
                )

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            FOREGROUND_NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
                    }
                }.onFailure {
                    runCatching { startForeground(FOREGROUND_NOTIFICATION_ID, notification) }
                }

                AlarmAudioController.startAlarmRinging(
                    context = this,
                    soundType = soundType,
                    customSoundUri = customUri,
                    volumePercent = volumePercent,
                    vibrateEnabled = vibrate
                )

                // Attempt to launch the lock-screen / full-screen ringing UI
                runCatching {
                    val activityIntent = FamilyAlarmRingingActivity.createLaunchIntent(
                        context = this,
                        alarmId = alarmId,
                        title = title,
                        hour = hour,
                        minute = minute,
                        snoozeMinutes = snoozeMin
                    )
                    startActivity(activityIntent)
                }

                timeoutJob?.cancel()
                timeoutJob = serviceScope.launch {
                    delay(SAFETY_TIMEOUT_MILLIS)
                    dismissActiveAlarm(this@FamilyAlarmRingingService, alarmId)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        AlarmAudioController.stopAlarmRinging(this)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireBoundedWakeLock() {
        runCatching {
            releaseWakeLock()
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FamilyTasks:AlarmRingingServiceWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(SAFETY_TIMEOUT_MILLIS)
            }
            wakeLock = wl
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { wl ->
                if (wl.isHeld) wl.release()
            }
            wakeLock = null
        }
    }

    private fun ensureAlarmNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "שעון מעורר משפחתי (Family Alarms)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "התראות צלצול פעיל של שעון מעורר משפחתי"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                // Audio is played directly by AlarmAudioController with USAGE_ALARM
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundAlarmNotification(
        alarmId: String,
        title: String,
        timeFormatted: String,
        hour: Int,
        minute: Int,
        soundType: String,
        customUri: String,
        vibrateEnabled: Boolean,
        snoozeMinutes: Int,
        volumePercent: Int
    ): Notification {
        val fullScreenActivityIntent = FamilyAlarmRingingActivity.createLaunchIntent(
            context = this,
            alarmId = alarmId,
            title = title,
            hour = hour,
            minute = minute,
            snoozeMinutes = snoozeMinutes
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            (alarmId.hashCode() * 31 + 2) and 0x7FFFFFFF,
            fullScreenActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, FamilyAlarmReceiver::class.java).apply {
            action = FamilyAlarmScheduler.ACTION_SNOOZE_FAMILY_ALARM
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE, title)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE, soundType)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI, customUri)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE, vibrateEnabled)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, snoozeMinutes)
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, volumePercent)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            this,
            (alarmId.hashCode() * 31 + 3) and 0x7FFFFFFF,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, FamilyAlarmReceiver::class.java).apply {
            action = FamilyAlarmScheduler.ACTION_DISMISS_FAMILY_ALARM
            putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            (alarmId.hashCode() * 31 + 4) and 0x7FFFFFFF,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏰ $timeFormatted • $title")
            .setContentText("שעון מעורר מצלצל עכשיו • בחרו נודניק או כיבוי")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(
                R.drawable.ic_notification,
                "נודניק (${snoozeMinutes} דק׳)",
                snoozePendingIntent
            )
            .addAction(
                R.drawable.ic_notification,
                "כיבוי",
                dismissPendingIntent
            )
            .build()
    }
}
