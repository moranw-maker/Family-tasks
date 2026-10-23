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

        const val ALARM_CHANNEL_ID = "family_alarm_ringing_fullscreen_v5"
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
            val appCtx = context.applicationContext ?: context
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

            // Ensure high-priority alarm notification channel exists BEFORE posting foreground notification.
            // IMPORTANT: Do NOT call wakeUpScreenForAlarm() before startForeground()!
            // If ACQUIRE_CAUSES_WAKEUP turns PowerManager.isInteractive() to true before startForeground()
            // posts the fullScreenIntent notification, Android SystemUI treats the device as already interactive
            // and demotes the fullScreenIntent to a heads-up banner instead of launching FamilyAlarmRingingActivity.
            ensureStaticAlarmNotificationChannel(appCtx)

            // Start audio playback immediately so there is zero latency even before service binding completes
            AlarmAudioController.startAlarmRinging(
                context = appCtx,
                soundType = soundType,
                customSoundUri = customSoundUri,
                volumePercent = volumePercent,
                vibrateEnabled = vibrateEnabled
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
            }.getOrElse { err ->
                FamilyAlarmScheduler.recordDiagnosticError(
                    "startForegroundService fallback: ${err.localizedMessage ?: err.javaClass.simpleName}"
                )
                false
            }

            if (startedForeground) {
                FamilyAlarmScheduler.recordDiagnosticServiceStarted(alarmId)
            } else {
                // Fallback if foreground service couldn't start: post full-screen / heads-up notification directly
                runCatching {
                    val immediateNotification = buildStaticAlarmNotification(
                        context = appCtx,
                        alarmId = alarmId,
                        title = title,
                        timeFormatted = timeFormatted,
                        hour = hour,
                        minute = minute,
                        soundType = soundType,
                        customUri = customSoundUri,
                        vibrateEnabled = vibrateEnabled,
                        snoozeMinutes = snoozeMinutes,
                        volumePercent = volumePercent
                    )
                    val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(FOREGROUND_NOTIFICATION_ID, immediateNotification)
                }
            }

            // Only launch the activity directly here if FamilyTasks itself is currently visible in the foreground.
            // When the app is in the background or screen is locked, startForeground()'s fullScreenIntent
            // launches FamilyAlarmRingingActivity via SystemUI without triggering a BAL block.
            if (isAppProcessInForeground(appCtx)) {
                launchRingingActivitySafely(
                    context = appCtx,
                    alarmId = alarmId,
                    title = title,
                    hour = hour,
                    minute = minute,
                    snoozeMinutes = snoozeMinutes
                )
            }
        }

        internal fun isAppProcessInForeground(context: Context): Boolean {
            return runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return false
                val myPid = android.os.Process.myPid()
                val proc = am.runningAppProcesses?.firstOrNull { it.pid == myPid } ?: return false
                proc.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }.getOrDefault(false)
        }

        internal fun launchRingingActivitySafely(
            context: Context,
            alarmId: String,
            title: String,
            hour: Int,
            minute: Int,
            snoozeMinutes: Int
        ) {
            val appCtx = context.applicationContext ?: context
            val activityIntent = FamilyAlarmRingingActivity.createLaunchIntent(
                context = appCtx,
                alarmId = alarmId,
                title = title,
                hour = hour,
                minute = minute,
                snoozeMinutes = snoozeMinutes
            )
            val balSenderBundle = FamilyAlarmScheduler.buildBalSenderActivityOptionsBundle()

            // 1. Fire BAL-enabled Activity PendingIntent first (carries creator + sender BAL privileges)
            val sentViaPendingIntent = runCatching {
                val pi = FamilyAlarmScheduler.buildActivityPendingIntentWithBal(
                    context = appCtx,
                    requestCode = (alarmId.hashCode() * 31 + 2) and 0x7FFFFFFF,
                    intent = activityIntent,
                    flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (balSenderBundle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pi.send(appCtx, 0, null, null, null, null, balSenderBundle)
                } else {
                    pi.send()
                }
                true
            }.getOrDefault(false)

            // 2. Fallback to direct startActivity if PendingIntent.send failed
            if (!sentViaPendingIntent) {
                runCatching {
                    if (balSenderBundle != null) {
                        appCtx.startActivity(activityIntent, balSenderBundle)
                    } else {
                        appCtx.startActivity(activityIntent)
                    }
                }
            }
        }

        internal fun wakeUpScreenForAlarm(context: Context) {
            runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                @Suppress("DEPRECATION")
                val screenWakeFlags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE
                val wl = pm.newWakeLock(screenWakeFlags, "FamilyTasks:AlarmScreenWakeUpLock")
                wl.setReferenceCounted(false)
                wl.acquire(20_000L)
            }
        }

        fun dismissActiveAlarm(context: Context, alarmId: String = "", cancelPendingSnooze: Boolean = true) {
            val appCtx = context.applicationContext ?: context
            val current = _activeRingingAlarm.value
            val resolvedId = alarmId.ifBlank { current?.alarmId.orEmpty() }
            _activeRingingAlarm.value = null
            FamilyAlarmReceiver.recordAlarmDismissed(resolvedId)
            if (cancelPendingSnooze && resolvedId.isNotBlank()) {
                FamilyAlarmScheduler.cancelPendingSnoozeOnly(appCtx, resolvedId)
            }
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
            val appCtx = context.applicationContext ?: context
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
            dismissActiveAlarm(appCtx, current.alarmId, cancelPendingSnooze = false)
            return nextSnoozeMillis
        }

        private fun ensureStaticAlarmNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    ALARM_CHANNEL_ID,
                    "שעון מעורר משפחתי (Family Alarms)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "התראות צלצול פעיל ומסך מלא של שעון מעורר משפחתי"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0L, 500L, 300L, 500L)
                    enableLights(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                    // Silent channel sound (null) while keeping IMPORTANCE_HIGH + vibration so AlarmAudioController
                    // plays the user's selected ringtone cleanly without double-playing or Uri.EMPTY errors.
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }

        internal fun buildStaticAlarmNotification(
            context: Context,
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
            ensureStaticAlarmNotificationChannel(context)
            val fullScreenActivityIntent = FamilyAlarmRingingActivity.createLaunchIntent(
                context = context,
                alarmId = alarmId,
                title = title,
                hour = hour,
                minute = minute,
                snoozeMinutes = snoozeMinutes
            )
            val fullScreenPendingIntent = FamilyAlarmScheduler.buildActivityPendingIntentWithBal(
                context = context,
                requestCode = (alarmId.hashCode() * 31 + 2) and 0x7FFFFFFF,
                intent = fullScreenActivityIntent,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                (alarmId.hashCode() * 31 + 3) and 0x7FFFFFFF,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val dismissIntent = Intent(context, FamilyAlarmReceiver::class.java).apply {
                action = FamilyAlarmScheduler.ACTION_DISMISS_FAMILY_ALARM
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                (alarmId.hashCode() * 31 + 4) and 0x7FFFFFFF,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val displayLabel = FamilyAlarmRingingActivity.resolveAlarmDisplayLabel(title)
            return NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ $timeFormatted • $displayLabel")
                .setContentText("☀️ הגיע הזמן להתחיל את היום • לחצו לכיבוי או נודניק")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setAutoCancel(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(fullScreenPendingIntent)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .addAction(
                    R.drawable.ic_notification,
                    "נודניק (עוד ${snoozeMinutes} דק׳)",
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
                val title = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: "בוקר טוב!"
                val hour = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, 7)
                val minute = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, 0)
                val soundType = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE) ?: AlarmAudioController.SOUND_MELODY_ENERGETIC
                val customUri = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI).orEmpty()
                val vibrate = intent.getBooleanExtra(FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE, true)
                val snoozeMin = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5)
                val volumePercent = intent.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT, 85)
                val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

                FamilyAlarmScheduler.recordDiagnosticServiceStarted(alarmId)

                val prevRinging = _activeRingingAlarm.value
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

                // 1. Acquire CPU PARTIAL_WAKE_LOCK first (does not alter PowerManager.isInteractive())
                acquireBoundedWakeLock()
                ensureStaticAlarmNotificationChannel(this)
                val notification = buildStaticAlarmNotification(
                    context = this,
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

                // 2. Immediately promote to Foreground Service with fullScreenIntent BEFORE waking the screen
                //    so Android SystemUI sees isInteractive == false (or Keyguard active) and launches fullScreenIntent!
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

                // 3. Now that startForeground() has been called, re-assert audio focus and active playback
                if (prevRinging == null || prevRinging.alarmId != alarmId ||
                    (System.currentTimeMillis() - prevRinging.startedAtMillis) > 2_500L
                ) {
                    AlarmAudioController.startAlarmRinging(
                        context = this,
                        soundType = soundType,
                        customSoundUri = customUri,
                        volumePercent = volumePercent,
                        vibrateEnabled = vibrate
                    )
                } else {
                    AlarmAudioController.ensureAlarmAudioPlayingAndFocused(
                        context = this,
                        soundType = soundType,
                        customSoundUri = customUri,
                        volumePercent = volumePercent,
                        vibrateEnabled = vibrate
                    )
                }

                // 4. Wake the screen AFTER startForeground() has posted the fullScreenIntent notification,
                //    and launch the activity directly if the app process is in the foreground.
                if (isAppProcessInForeground(this)) {
                    launchRingingActivitySafely(
                        context = this,
                        alarmId = alarmId,
                        title = title,
                        hour = hour,
                        minute = minute,
                        snoozeMinutes = snoozeMin
                    )
                }
                serviceScope.launch {
                    delay(180L)
                    wakeUpScreenForAlarm(this@FamilyAlarmRingingService)
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
}
