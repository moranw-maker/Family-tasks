package com.example.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.model.FamilyAlarm
import com.example.model.SchoolDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class LocalAlarmScheduleResult(
    val isScheduled: Boolean,
    val isExact: Boolean,
    val nextTriggerAtMillis: Long,
    val statusCode: String, // "SCHEDULED_LOCALLY", "EXACT_PERMISSION_NEEDED", "SCHEDULE_ERROR", "DISABLED"
    val scheduleMethodUsed: String = "",
    val errorMessage: String = ""
)

/**
 * Real-time diagnostic state for the end-to-end 15-second alarm test ("בדיקת צלצול בעוד 15 שניות")
 * and local alarm pipeline verification.
 *
 * Tracks the full chain:
 * AlarmManager -> PendingIntent -> FamilyAlarmReceiver -> FamilyAlarmRingingService -> AlarmAudioController -> Audible ringtone
 */
data class AlarmDiagnosticState(
    val isTestActive: Boolean = false,
    val testAlarmId: String = "",
    val scheduledAtMillis: Long = 0L,
    val triggerAtMillis: Long = 0L,
    val alarmScheduled: Boolean = false,
    val scheduleMethodUsed: String = "",
    val exactAlarmPermissionGranted: Boolean = false,
    val receiverTriggered: Boolean = false,
    val receiverTriggeredAtMillis: Long = 0L,
    val ringingServiceStarted: Boolean = false,
    val ringingServiceStartedAtMillis: Long = 0L,
    val audioPlaybackStarted: Boolean = false,
    val audioPlaybackMethod: String = "",
    val errorCause: String? = null
) {
    val isScheduled: Boolean
        get() = alarmScheduled
    val schedulingMethod: String
        get() = scheduleMethodUsed
    val audioEngineDetails: String
        get() = audioPlaybackMethod
    val errorMessage: String
        get() = errorCause.orEmpty()
}

/**
 * Local Android AlarmManager scheduler for FamilyTasks alarms.
 *
 * - Enforces strict target-device routing:
 *   - Parent-created alarm for a child rings ONLY on that child's device.
 *   - Child-created alarm for self rings ONLY on that child's device.
 *   - Parent-created alarm for self rings ONLY on that parent's device.
 * - Schedules real Android alarms on the target member's device via [AlarmManager.setAlarmClock] /
 *   [AlarmManager.setExactAndAllowWhileIdle].
 * - Maintains an in-process exact-time coroutine watchdog that guarantees punctual delivery via
 *   [PendingIntent.send] while the application process is alive even if OEM battery policies or
 *   missing exact-alarm permissions throttle background broadcasts.
 * - Persists active device alarms locally so [FamilyAlarmBootReceiver] restores them after device reboot.
 */
object FamilyAlarmScheduler {

    private const val PREFS_NAME = "family_tasks_local_alarms_prefs"
    private const val KEY_PERSISTED_ALARMS = "persisted_alarms_json"
    private const val KEY_DELETED_ALARM_IDS = "deleted_alarm_ids_csv"
    private const val KEY_CURRENT_MEMBER_ID = "current_member_id"

    const val DIAGNOSTIC_ALARM_ID = "diag_alarm_15s"
    const val DIAGNOSTIC_15S_ALARM_ID = DIAGNOSTIC_ALARM_ID

    const val ACTION_TRIGGER_FAMILY_ALARM = "com.example.action.TRIGGER_FAMILY_ALARM"
    const val ACTION_SNOOZE_FAMILY_ALARM = "com.example.action.SNOOZE_FAMILY_ALARM"
    const val ACTION_DISMISS_FAMILY_ALARM = "com.example.action.DISMISS_FAMILY_ALARM"

    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_ALARM_TITLE = "extra_alarm_title"
    const val EXTRA_ALARM_HOUR = "extra_alarm_hour"
    const val EXTRA_ALARM_MINUTE = "extra_alarm_minute"
    const val EXTRA_ALARM_SOUND_TYPE = "extra_alarm_sound_type"
    const val EXTRA_ALARM_CUSTOM_URI = "extra_alarm_custom_uri"
    const val EXTRA_ALARM_VIBRATE = "extra_alarm_vibrate"
    const val EXTRA_ALARM_SNOOZE_MINUTES = "extra_alarm_snooze_minutes"
    const val EXTRA_ALARM_VOLUME_PERCENT = "extra_alarm_volume_percent"
    const val EXTRA_ALARM_DAYS_CSV = "extra_alarm_days_csv"
    const val EXTRA_IS_SNOOZE_TRIGGER = "extra_is_snooze_trigger"
    const val EXTRA_IS_DIAGNOSTIC_TEST = "extra_is_diagnostic_test"

    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchdogLock = Any()
    private val activeWatchdogJobs = mutableMapOf<String, Job>()

    private val _diagnosticState = MutableStateFlow(AlarmDiagnosticState())
    val diagnosticState: StateFlow<AlarmDiagnosticState> = _diagnosticState.asStateFlow()

    /**
     * Checks whether [alarm] should be scheduled locally on the device belonging to
     * [currentMemberId] / [currentAuthUid].
     *
     * Rules:
     * 1. Disabled alarms are never scheduled.
     * 2. If the alarm specifies an assigned member (`assignedToMemberId` / `assignedMemberId`),
     *    it is scheduled ONLY if this device belongs to that assigned member.
     * 3. If the alarm has no explicit assigned member, it is scheduled only on its creator's device.
     */
    fun shouldScheduleOnDevice(
        alarm: FamilyAlarm,
        currentMemberId: String,
        currentAuthUid: String = "",
        currentMemberName: String = "",
        isChildDevice: Boolean = false
    ): Boolean {
        if (!alarm.isEnabled || alarm.isDeleted || alarm.scheduleStatus == FamilyAlarm.STATUS_DELETED) return false
        if (currentMemberId.isBlank() && currentAuthUid.isBlank()) return false

        val targetId = alarm.effectiveAssignedMemberId
        if (targetId.isBlank() && alarm.createdByMemberId.isBlank()) {
            return true
        }
        return alarm.isTargetedAtDeviceMember(
            memberId = currentMemberId,
            authUid = currentAuthUid,
            currentMemberName = currentMemberName,
            isChildDevice = isChildDevice
        )
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val channelsEnabled = runCatching {
            NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
        }.getOrDefault(true)
        return runtimeGranted && channelsEnabled
    }

    @Volatile
    var fullScreenPermissionOverrideForTesting: Boolean? = null

    fun canUseFullScreenIntent(context: Context): Boolean {
        fullScreenPermissionOverrideForTesting?.let { return it }
        val appCtx = context.applicationContext ?: context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return true
            runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
        } else {
            true
        }
    }

    fun openFullScreenIntentSettings(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val launchedWithPkg = runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${appCtx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appCtx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launchedWithPkg) return

            val launchedGeneral = runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appCtx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launchedGeneral) return
        }
        openNotificationSettings(appCtx)
    }

    fun openNotificationSettings(context: Context) {
        val appCtx = context.applicationContext ?: context
        runCatching {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appCtx.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appCtx.startActivity(intent)
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
        } else {
            true
        }
    }

    fun openExactAlarmPermissionSettings(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val launchedWithPackage = runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${appCtx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appCtx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launchedWithPackage) return

            val launchedGeneral = runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appCtx.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launchedGeneral) return
        }
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${appCtx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appCtx.startActivity(intent)
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    /**
     * Computes the next future epoch milliseconds matching the alarm's [FamilyAlarm.hour],
     * [FamilyAlarm.minute], and [FamilyAlarm.daysOfWeek].
     */
    fun computeNextTriggerAtMillis(
        alarm: FamilyAlarm,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val allowedCalendarDays = alarm.daysOfWeek.map { schoolDayToCalendarDay(it) }.toSet()
        // Note: Legacy alarms stored before Saturday was added to SchoolDay had 6 days (Sun..Fri).
        // Treat 6+ days without Saturday or empty days as all 7 days so alarms work on Saturday too.
        val effectiveAllowedDays = if (allowedCalendarDays.size >= 6 && Calendar.SATURDAY !in allowedCalendarDays) {
            allowedCalendarDays + Calendar.SATURDAY
        } else {
            allowedCalendarDays
        }

        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, alarm.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, alarm.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Search up to 8 days ahead for the next matching day of week in the future
        for (offset in 0..7) {
            val candidate = (cal.clone() as Calendar).apply {
                if (offset > 0) {
                    add(Calendar.DAY_OF_YEAR, offset)
                }
            }
            val dayOfWeek = candidate.get(Calendar.DAY_OF_WEEK)
            val matchesDay = effectiveAllowedDays.isEmpty() || dayOfWeek in effectiveAllowedDays
            if (matchesDay && candidate.timeInMillis > nowMillis + 1_000L) {
                return candidate.timeInMillis
            }
        }

        // Fallback: tomorrow at the specified hour/minute
        return cal.apply {
            if (timeInMillis <= nowMillis + 1_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }.timeInMillis
    }

    /**
     * Formats a human-readable summary of when the alarm will next ring relative to [nowMillis].
     */
    fun formatNextTriggerSummary(
        triggerAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        isHebrew: Boolean = true
    ): String {
        if (triggerAtMillis <= 0L) return ""
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val trigCal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val timeStr = String.format(
            Locale.US,
            "%02d:%02d",
            trigCal.get(Calendar.HOUR_OF_DAY),
            trigCal.get(Calendar.MINUTE)
        )
        val sameDay = nowCal.get(Calendar.YEAR) == trigCal.get(Calendar.YEAR) &&
            nowCal.get(Calendar.DAY_OF_YEAR) == trigCal.get(Calendar.DAY_OF_YEAR)
        val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val isTomorrow = tomorrowCal.get(Calendar.YEAR) == trigCal.get(Calendar.YEAR) &&
            tomorrowCal.get(Calendar.DAY_OF_YEAR) == trigCal.get(Calendar.DAY_OF_YEAR)

        val diffMinutes = ((triggerAtMillis - nowMillis).coerceAtLeast(0L) + 59_999L) / 60_000L
        val relativeText = when {
            diffMinutes <= 1L -> if (isHebrew) "בעוד דקה" else "in 1 min"
            diffMinutes < 60L -> if (isHebrew) "בעוד $diffMinutes דק׳" else "in ${diffMinutes}m"
            else -> {
                val hrs = diffMinutes / 60L
                val mins = diffMinutes % 60L
                if (mins == 0L) {
                    if (isHebrew) "בעוד $hrs שעות" else "in ${hrs}h"
                } else {
                    if (isHebrew) "בעוד $hrs ש׳ ו-$mins דק׳" else "in ${hrs}h ${mins}m"
                }
            }
        }

        return when {
            sameDay -> if (isHebrew) "הצלצול הבא: היום ב-$timeStr ($relativeText)" else "Next ring: Today at $timeStr ($relativeText)"
            isTomorrow -> if (isHebrew) "הצלצול הבא: מחר ב-$timeStr ($relativeText)" else "Next ring: Tomorrow at $timeStr ($relativeText)"
            else -> if (isHebrew) "הצלצול הבא: $timeStr ($relativeText)" else "Next ring: $timeStr ($relativeText)"
        }
    }

    /**
     * Registers a [FamilyAlarm] in Android's [AlarmManager] on this device.
     */
    fun scheduleLocalAlarm(
        context: Context,
        alarm: FamilyAlarm,
        nowMillis: Long = System.currentTimeMillis()
    ): LocalAlarmScheduleResult {
        val appCtx = context.applicationContext ?: context
        if (alarm.isDeleted || alarm.scheduleStatus == FamilyAlarm.STATUS_DELETED) {
            markAlarmDeletedAndCancel(appCtx, alarm.id)
            return LocalAlarmScheduleResult(
                isScheduled = false,
                isExact = false,
                nextTriggerAtMillis = 0L,
                statusCode = FamilyAlarm.STATUS_DELETED
            )
        }
        if (!alarm.isEnabled) {
            cancelLocalAlarm(appCtx, alarm.id)
            return LocalAlarmScheduleResult(
                isScheduled = false,
                isExact = false,
                nextTriggerAtMillis = 0L,
                statusCode = FamilyAlarm.STATUS_DISABLED
            )
        }

        val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return LocalAlarmScheduleResult(
                isScheduled = false,
                isExact = false,
                nextTriggerAtMillis = 0L,
                statusCode = FamilyAlarm.STATUS_SCHEDULE_ERROR,
                errorMessage = "AlarmManager service unavailable"
            )

        val triggerAtMillis = computeNextTriggerAtMillis(alarm, nowMillis)
        val pendingIntent = buildTriggerPendingIntent(appCtx, alarm, isSnooze = false)
        val canExact = canScheduleExactAlarms(appCtx)

        val (scheduledSuccessfully, scheduledExact, methodUsed, lastErr) = registerWithAlarmManager(
            context = appCtx,
            alarmManager = alarmManager,
            alarmId = alarm.id,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            requestCodeSalt = 10,
            alarmTitle = alarm.title,
            alarmHour = alarm.hour,
            alarmMinute = alarm.minute,
            snoozeMinutes = alarm.snoozeDurationMinutes
        )

        if (scheduledSuccessfully) {
            clearDeletedAlarmId(appCtx, alarm.id)
            upsertPersistedAlarmForBootRestore(appCtx, alarm.copy(nextTriggerAtMillis = triggerAtMillis))
            armInProcessWatchdog(
                context = appCtx,
                alarm = alarm,
                triggerAtMillis = triggerAtMillis,
                pendingIntent = pendingIntent,
                isSnooze = false
            )
        }

        val statusCode = when {
            !scheduledSuccessfully -> FamilyAlarm.STATUS_SCHEDULE_ERROR
            scheduledExact || canExact -> FamilyAlarm.STATUS_SCHEDULED_LOCALLY
            else -> FamilyAlarm.STATUS_EXACT_PERMISSION_NEEDED
        }

        return LocalAlarmScheduleResult(
            isScheduled = scheduledSuccessfully,
            isExact = scheduledExact,
            nextTriggerAtMillis = if (scheduledSuccessfully) triggerAtMillis else 0L,
            statusCode = statusCode,
            scheduleMethodUsed = methodUsed,
            errorMessage = if (scheduledSuccessfully) "" else lastErr
        )
    }

    /**
     * Schedules a REAL Android [AlarmManager] diagnostic alarm for 15 seconds in the future.
     *
     * Does NOT directly play a sound — exercises the complete pipeline:
     * AlarmManager -> PendingIntent -> FamilyAlarmReceiver -> FamilyAlarmRingingService -> AlarmAudioController
     */
    fun scheduleDiagnosticAlarmIn15Seconds(
        context: Context,
        soundType: String = AlarmAudioController.SOUND_MELODY_ENERGETIC,
        volumePercent: Int = 90
    ): AlarmDiagnosticState {
        val appCtx = context.applicationContext ?: context
        FamilyAlarmReceiver.clearRecentTrigger(DIAGNOSTIC_ALARM_ID)
        cancelLocalAlarm(appCtx, DIAGNOSTIC_ALARM_ID)

        val now = System.currentTimeMillis()
        val triggerAtMillis = now + 15_000L
        val trigCal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val canExact = canScheduleExactAlarms(appCtx)

        val diagAlarm = FamilyAlarm(
            id = DIAGNOSTIC_ALARM_ID,
            title = "בדיקת צלצול אבחון (15 שניות)",
            hour = trigCal.get(Calendar.HOUR_OF_DAY),
            minute = trigCal.get(Calendar.MINUTE),
            daysOfWeek = SchoolDay.allWeekDays,
            isEnabled = true,
            soundType = soundType,
            vibrateEnabled = true,
            snoozeDurationMinutes = 5,
            volumePercent = volumePercent.coerceIn(10, 100),
            isPersonal = true
        )

        val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            val failedState = AlarmDiagnosticState(
                isTestActive = false,
                testAlarmId = DIAGNOSTIC_ALARM_ID,
                scheduledAtMillis = now,
                triggerAtMillis = triggerAtMillis,
                alarmScheduled = false,
                exactAlarmPermissionGranted = canExact,
                errorCause = "שירות AlarmManager אינו זמין במכשיר"
            )
            _diagnosticState.value = failedState
            return failedState
        }

        val pendingIntent = buildTriggerPendingIntent(appCtx, diagAlarm, isSnooze = false)
        val (scheduledSuccessfully, _, methodUsed, err) = registerWithAlarmManager(
            context = appCtx,
            alarmManager = alarmManager,
            alarmId = DIAGNOSTIC_ALARM_ID,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            requestCodeSalt = 12,
            alarmTitle = diagAlarm.title,
            alarmHour = diagAlarm.hour,
            alarmMinute = diagAlarm.minute,
            snoozeMinutes = diagAlarm.snoozeDurationMinutes
        )

        if (scheduledSuccessfully) {
            armInProcessWatchdog(
                context = appCtx,
                alarm = diagAlarm,
                triggerAtMillis = triggerAtMillis,
                pendingIntent = pendingIntent,
                isSnooze = false
            )
        }

        val newState = AlarmDiagnosticState(
            isTestActive = scheduledSuccessfully,
            testAlarmId = DIAGNOSTIC_ALARM_ID,
            scheduledAtMillis = now,
            triggerAtMillis = triggerAtMillis,
            alarmScheduled = scheduledSuccessfully,
            scheduleMethodUsed = methodUsed,
            exactAlarmPermissionGranted = canExact,
            receiverTriggered = false,
            ringingServiceStarted = false,
            audioPlaybackStarted = false,
            errorCause = if (scheduledSuccessfully) null else (err.ifBlank { "כשל בתזמון AlarmManager" })
        )
        _diagnosticState.value = newState
        return newState
    }

    fun recordDiagnosticReceiverTriggered(alarmId: String) {
        val current = _diagnosticState.value
        if (alarmId == DIAGNOSTIC_ALARM_ID || current.isTestActive || current.testAlarmId == alarmId) {
            _diagnosticState.value = current.copy(
                testAlarmId = alarmId.ifBlank { current.testAlarmId },
                receiverTriggered = true,
                receiverTriggeredAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun recordDiagnosticServiceStarted(alarmId: String) {
        val current = _diagnosticState.value
        if (alarmId == DIAGNOSTIC_ALARM_ID || current.isTestActive || current.testAlarmId == alarmId || current.receiverTriggered) {
            _diagnosticState.value = current.copy(
                testAlarmId = alarmId.ifBlank { current.testAlarmId },
                ringingServiceStarted = true,
                ringingServiceStartedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun recordDiagnosticAudioStarted(method: String) {
        val current = _diagnosticState.value
        _diagnosticState.value = current.copy(
            isTestActive = false,
            audioPlaybackStarted = true,
            audioPlaybackMethod = method,
            errorCause = null
        )
    }

    fun recordDiagnosticError(cause: String) {
        val current = _diagnosticState.value
        _diagnosticState.value = current.copy(
            isTestActive = false,
            errorCause = cause
        )
    }

    fun scheduleDiagnostic15SecondAlarm(
        context: Context,
        currentMemberName: String = "",
        soundType: String = AlarmAudioController.SOUND_MELODY_ENERGETIC,
        volumePercent: Int = 90
    ): LocalAlarmScheduleResult {
        val state = scheduleDiagnosticAlarmIn15Seconds(
            context = context,
            soundType = soundType,
            volumePercent = volumePercent
        )
        return LocalAlarmScheduleResult(
            isScheduled = state.alarmScheduled,
            isExact = state.exactAlarmPermissionGranted,
            nextTriggerAtMillis = state.triggerAtMillis,
            statusCode = if (state.alarmScheduled) FamilyAlarm.STATUS_SCHEDULED_LOCALLY else FamilyAlarm.STATUS_SCHEDULE_ERROR,
            scheduleMethodUsed = state.scheduleMethodUsed,
            errorMessage = state.errorCause.orEmpty()
        )
    }

    fun resetDiagnosticState(context: Context) {
        val appCtx = context.applicationContext ?: context
        cancelLocalAlarm(appCtx, DIAGNOSTIC_ALARM_ID)
        _diagnosticState.value = AlarmDiagnosticState(
            exactAlarmPermissionGranted = canScheduleExactAlarms(appCtx)
        )
    }

    fun isAlarmLocallyScheduled(context: Context, alarmId: String): Boolean {
        val appCtx = context.applicationContext ?: context
        return runCatching {
            val intent = Intent(appCtx, FamilyAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FAMILY_ALARM
                data = Uri.parse("familytasks://alarm/$alarmId")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val existingPi = PendingIntent.getBroadcast(
                appCtx,
                requestCodeForAlarm(alarmId, 0),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            existingPi != null
        }.getOrDefault(false)
    }

    fun isSnoozeLocallyScheduled(context: Context, alarmId: String): Boolean {
        val appCtx = context.applicationContext ?: context
        return runCatching {
            val snoozeIntent = Intent(appCtx, FamilyAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FAMILY_ALARM
                data = Uri.parse("familytasks://alarm_snooze/$alarmId")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val existingPi = PendingIntent.getBroadcast(
                appCtx,
                requestCodeForAlarm(alarmId, 1),
                snoozeIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            existingPi != null
        }.getOrDefault(false)
    }

    private data class AlarmRegistrationOutcome(
        val scheduledSuccessfully: Boolean,
        val scheduledExact: Boolean,
        val methodUsed: String,
        val errorMessage: String
    )

    private fun registerWithAlarmManager(
        context: Context,
        alarmManager: AlarmManager,
        alarmId: String,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        requestCodeSalt: Int,
        alarmTitle: String = "בוקר טוב!",
        alarmHour: Int = 7,
        alarmMinute: Int = 0,
        snoozeMinutes: Int = 5
    ): AlarmRegistrationOutcome {
        val showIntent = FamilyAlarmRingingActivity.createLaunchIntent(
            context = context,
            alarmId = alarmId,
            title = alarmTitle,
            hour = alarmHour,
            minute = alarmMinute,
            snoozeMinutes = snoozeMinutes
        )
        val showPendingIntent = buildActivityPendingIntentWithBal(
            context = context,
            requestCode = requestCodeForAlarm(alarmId, requestCodeSalt),
            intent = showIntent,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var lastError = ""

        // 1. Primary path: setAlarmClock (wakes Doze, grants FGS & full-screen exemption)
        try {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
            alarmManager.setAlarmClock(clockInfo, pendingIntent)
            return AlarmRegistrationOutcome(
                scheduledSuccessfully = true,
                scheduledExact = true,
                methodUsed = "AlarmManager.setAlarmClock",
                errorMessage = ""
            )
        } catch (se: SecurityException) {
            lastError = se.localizedMessage ?: "SecurityException in setAlarmClock"
        } catch (t: Throwable) {
            lastError = t.localizedMessage ?: "Error in setAlarmClock"
        }

        // 2. Secondary path: setExactAndAllowWhileIdle
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            return AlarmRegistrationOutcome(
                scheduledSuccessfully = true,
                scheduledExact = true,
                methodUsed = "AlarmManager.setExactAndAllowWhileIdle",
                errorMessage = ""
            )
        } catch (se: SecurityException) {
            lastError = se.localizedMessage ?: "SecurityException in setExactAndAllowWhileIdle"
        } catch (t: Throwable) {
            lastError = t.localizedMessage ?: "Error in setExactAndAllowWhileIdle"
        }

        // 3. Fallback when exact alarm permission is denied on Android 12+: allowWhileIdle first so Doze wakes up
        return try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            AlarmRegistrationOutcome(
                scheduledSuccessfully = true,
                scheduledExact = false,
                methodUsed = "AlarmManager.setAndAllowWhileIdle",
                errorMessage = ""
            )
        } catch (_: Throwable) {
            try {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, 1_000L, pendingIntent)
                AlarmRegistrationOutcome(
                    scheduledSuccessfully = true,
                    scheduledExact = false,
                    methodUsed = "AlarmManager.setWindow (inexact fallback)",
                    errorMessage = ""
                )
            } catch (err: Throwable) {
                AlarmRegistrationOutcome(
                    scheduledSuccessfully = false,
                    scheduledExact = false,
                    methodUsed = "FAILED",
                    errorMessage = err.localizedMessage ?: lastError.ifBlank { "Unknown AlarmManager failure" }
                )
            }
        }
    }

    /**
     * Arms an in-process coroutine watchdog for [alarm].
     * If Android's [AlarmManager] delivery is delayed past `triggerAtMillis + 1_200L` (for example,
     * when `SCHEDULE_EXACT_ALARM` is not yet granted or OEM battery saver throttles broadcasts),
     * dispatches the real [pendingIntent] via [PendingIntent.send] so the full broadcast receiver ->
     * foreground service -> audio pipeline executes punctually.
     */
    private fun armInProcessWatchdog(
        context: Context,
        alarm: FamilyAlarm,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        isSnooze: Boolean
    ) {
        val appCtx = context.applicationContext ?: context
        val watchdogKey = if (isSnooze) "${alarm.id}:snooze" else alarm.id
        synchronized(watchdogLock) {
            activeWatchdogJobs.remove(watchdogKey)?.cancel()
            val waitMillis = (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            // Only arm in-process timer for alarms within the next 24 hours
            if (waitMillis <= 24 * 3_600_000L) {
                val job = watchdogScope.launch {
                    delay(waitMillis + 900L)
                    if (!isActive) return@launch
                    if (!FamilyAlarmReceiver.wasRecentlyTriggered(alarm.id, withinMillis = 20_000L)) {
                        val sentViaPendingIntent = runCatching {
                            pendingIntent.send()
                            true
                        }.getOrDefault(false)

                        if (!sentViaPendingIntent) {
                            val fallbackIntent = buildTriggerBroadcastIntent(appCtx, alarm, isSnooze)
                            FamilyAlarmReceiver().onReceive(appCtx, fallbackIntent)
                        }
                    }
                }
                activeWatchdogJobs[watchdogKey] = job
            }
        }
    }

    /**
     * Schedules a one-shot snooze alarm after [snoozeMinutes] minutes.
     */
    fun scheduleSnoozeAlarm(
        context: Context,
        alarmId: String,
        title: String,
        soundType: String,
        customSoundUri: String,
        vibrateEnabled: Boolean,
        snoozeMinutes: Int,
        volumePercent: Int
    ): Long {
        val appCtx = context.applicationContext ?: context
        val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return 0L
        val safeMinutes = snoozeMinutes.coerceIn(1, 60)
        val triggerAtMillis = System.currentTimeMillis() + safeMinutes * 60_000L
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val tempAlarm = FamilyAlarm(
            id = alarmId,
            title = title,
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            soundType = soundType,
            customSoundUri = customSoundUri,
            vibrateEnabled = vibrateEnabled,
            snoozeDurationMinutes = safeMinutes,
            volumePercent = volumePercent
        )
        val pendingIntent = buildTriggerPendingIntent(appCtx, tempAlarm, isSnooze = true)
        val (scheduled, _, _, _) = registerWithAlarmManager(
            context = appCtx,
            alarmManager = alarmManager,
            alarmId = alarmId,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            requestCodeSalt = 11,
            alarmTitle = title,
            alarmHour = tempAlarm.hour,
            alarmMinute = tempAlarm.minute,
            snoozeMinutes = safeMinutes
        )
        if (scheduled) {
            armInProcessWatchdog(
                context = appCtx,
                alarm = tempAlarm,
                triggerAtMillis = triggerAtMillis,
                pendingIntent = pendingIntent,
                isSnooze = true
            )
        }
        return triggerAtMillis
    }

    /**
     * Cancels only a pending snooze alarm for [alarmId] without cancelling the recurring alarm
     * schedule or removing the alarm from persisted boot-restore storage.
     */
    fun cancelPendingSnoozeOnly(context: Context, alarmId: String) {
        if (alarmId.isBlank()) return
        val appCtx = context.applicationContext ?: context
        synchronized(watchdogLock) {
            activeWatchdogJobs.remove("$alarmId:snooze")?.cancel()
        }
        val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            val snoozeIntent = Intent(appCtx, FamilyAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FAMILY_ALARM
                data = Uri.parse("familytasks://alarm_snooze/$alarmId")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val snoozePi = PendingIntent.getBroadcast(
                appCtx,
                requestCodeForAlarm(alarmId, 1),
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(snoozePi)
            snoozePi.cancel()
        }
    }

    /**
     * Cancels a previously scheduled local alarm, any pending snooze, and any active ringing for [alarmId],
     * and removes it from persisted local boot-restore storage.
     */
    fun cancelLocalAlarm(context: Context, alarmId: String) {
        if (alarmId.isBlank()) return
        val appCtx = context.applicationContext ?: context
        synchronized(watchdogLock) {
            activeWatchdogJobs.remove(alarmId)?.cancel()
            activeWatchdogJobs.remove("$alarmId:snooze")?.cancel()
        }
        FamilyAlarmReceiver.clearRecentTrigger(alarmId)
        if (FamilyAlarmRingingService.activeRingingAlarm.value?.alarmId == alarmId) {
            FamilyAlarmRingingService.dismissActiveAlarm(appCtx, alarmId)
        }
        removePersistedAlarmFromBootRestore(appCtx, alarmId)

        val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            val intent = Intent(appCtx, FamilyAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FAMILY_ALARM
                data = Uri.parse("familytasks://alarm/$alarmId")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val pi = PendingIntent.getBroadcast(
                appCtx,
                requestCodeForAlarm(alarmId, 0),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
            pi.cancel()

            val snoozeIntent = Intent(appCtx, FamilyAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FAMILY_ALARM
                data = Uri.parse("familytasks://alarm_snooze/$alarmId")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val snoozePi = PendingIntent.getBroadcast(
                appCtx,
                requestCodeForAlarm(alarmId, 1),
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(snoozePi)
            snoozePi.cancel()
        }
    }

    /**
     * Creates an Activity [PendingIntent] with Android 14+ (API 34+) Background Activity Launch (BAL)
     * privileges enabled so full-screen / lock-screen alarm activities can start reliably.
     */
    fun buildActivityPendingIntentWithBal(
        context: Context,
        requestCode: Int,
        intent: Intent,
        flags: Int
    ): PendingIntent {
        val optionsBundle = buildBalCreatorActivityOptionsBundle()
        return if (optionsBundle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            runCatching {
                PendingIntent.getActivity(context, requestCode, intent, flags, optionsBundle)
            }.getOrElse {
                PendingIntent.getActivity(context, requestCode, intent, flags)
            }
        } else {
            PendingIntent.getActivity(context, requestCode, intent, flags)
        }
    }

    fun buildBalCreatorActivityOptionsBundle(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            val options = android.app.ActivityOptions.makeBasic()
            // Creator option ONLY (never set sender mode here, as Android 15/16 rejects sender mode on getActivity):
            // API 36+: MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS = 3, API 34-35: MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1
            val method = android.app.ActivityOptions::class.java.getMethod(
                "setPendingIntentCreatorBackgroundActivityStartMode",
                Int::class.javaPrimitiveType
            )
            val modeToUse = if (Build.VERSION.SDK_INT >= 36) 3 else 1
            val applied = runCatching {
                method.invoke(options, modeToUse)
                true
            }.getOrDefault(false)
            if (!applied && modeToUse != 1) {
                runCatching { method.invoke(options, 1) }
            }
            options.toBundle()
        }.getOrNull()
    }

    fun buildBalSenderActivityOptionsBundle(): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            val options = android.app.ActivityOptions.makeBasic()
            val method = android.app.ActivityOptions::class.java.getMethod(
                "setPendingIntentBackgroundActivityStartMode",
                Int::class.javaPrimitiveType
            )
            val modeToUse = if (Build.VERSION.SDK_INT >= 36) 3 else 1
            val applied = runCatching {
                method.invoke(options, modeToUse)
                true
            }.getOrDefault(false)
            if (!applied && modeToUse != 1) {
                runCatching { method.invoke(options, 1) }
            }
            options.toBundle()
        }.getOrNull()
    }

    /**
     * Marks [alarmId] as deleted in local persistence and cancels its AlarmManager PendingIntent,
     * any pending snooze PendingIntent, and active ringing if [alarmId] is currently ringing.
     */
    fun markAlarmDeletedAndCancel(context: Context, alarmId: String) {
        if (alarmId.isBlank()) return
        val appCtx = context.applicationContext ?: context
        recordDeletedAlarmId(appCtx, alarmId)
        cancelLocalAlarm(appCtx, alarmId)
    }

    fun getPersistedDeletedAlarmIds(context: Context): Set<String> {
        val appCtx = context.applicationContext ?: context
        return runCatching {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_DELETED_ALARM_IDS, "")
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun isAlarmTombstoned(context: Context, alarmId: String): Boolean {
        if (alarmId.isBlank()) return false
        return alarmId in getPersistedDeletedAlarmIds(context)
    }

    fun clearDeletedAlarmId(context: Context, alarmId: String) {
        if (alarmId.isBlank()) return
        val appCtx = context.applicationContext ?: context
        runCatching {
            val current = getPersistedDeletedAlarmIds(appCtx).toMutableSet()
            if (current.remove(alarmId)) {
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DELETED_ALARM_IDS, current.toList().takeLast(200).joinToString(","))
                    .commit()
            }
        }
    }

    private fun recordDeletedAlarmId(context: Context, alarmId: String) {
        if (alarmId.isBlank()) return
        val appCtx = context.applicationContext ?: context
        runCatching {
            val current = getPersistedDeletedAlarmIds(appCtx).toMutableSet()
            current.add(alarmId)
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DELETED_ALARM_IDS, current.toList().takeLast(200).joinToString(","))
                .commit()
        }
    }

    fun syncAllDeviceAlarms(
        context: Context,
        alarms: List<FamilyAlarm>,
        currentMemberId: String,
        currentAuthUid: String = "",
        currentMemberName: String = "",
        isChildDevice: Boolean = false,
        deletedAlarmIds: Set<String> = emptySet()
    ): Set<String> {
        val appCtx = context.applicationContext ?: context
        val previousIds = loadPersistedAlarms(appCtx).map { it.id }.toSet()
        deletedAlarmIds.forEach { id ->
            if (id.isNotBlank() && id != DIAGNOSTIC_ALARM_ID) {
                markAlarmDeletedAndCancel(appCtx, id)
            }
        }
        syncAlarmsForCurrentDevice(
            context = appCtx,
            alarms = alarms,
            currentMemberId = currentMemberId,
            currentAuthUid = currentAuthUid,
            currentMemberName = currentMemberName,
            isChildDevice = isChildDevice,
            authoritativeRemoteSync = deletedAlarmIds.isNotEmpty()
        )
        val remainingIds = loadPersistedAlarms(appCtx).map { it.id }.toSet()
        return (previousIds - remainingIds) + deletedAlarmIds
    }

    /**
     * Synchronizes all alarms for the current device member:
     * - Registers enabled alarms targeted at `currentMemberId` / `currentAuthUid` in [AlarmManager].
     * - Cancels alarms that were disabled, deleted, or assigned to a different member.
     * - Never cancels an active diagnostic test alarm ([DIAGNOSTIC_ALARM_ID]).
     * - Persists the active alarms list locally so [FamilyAlarmBootReceiver] can restore them on reboot.
     * - Returns a map of `alarmId -> LocalAlarmScheduleResult` for alarms that belong to this device.
     */
    fun syncAlarmsForCurrentDevice(
        context: Context,
        alarms: List<FamilyAlarm>,
        currentMemberId: String,
        currentAuthUid: String = "",
        currentMemberName: String = "",
        isChildDevice: Boolean = false,
        authoritativeRemoteSync: Boolean = false
    ): Map<String, LocalAlarmScheduleResult> {
        val appCtx = context.applicationContext ?: context
        val deletedIds = getPersistedDeletedAlarmIds(appCtx)
        val activeAlarms = alarms.filter { !it.isDeleted && it.scheduleStatus != FamilyAlarm.STATUS_DELETED && it.id !in deletedIds }
        val previousAlarms = loadPersistedAlarms(appCtx)
        val currentIds = activeAlarms.map { it.id }.toSet()
        val isStarterDemoOnlyList = !authoritativeRemoteSync &&
            currentIds.isNotEmpty() &&
            currentIds.all { it == "alarm_wakeup" || it == "alarm_school_bus" }

        // Cancel any removed alarms (never cancel the diagnostic test alarm, and never wipe persisted user alarms
        // when startup runs before Firestore has loaded the real family alarms)
        if (!isStarterDemoOnlyList) {
            previousAlarms.forEach { old ->
                if (old.id !in currentIds && old.id != DIAGNOSTIC_ALARM_ID) {
                    if (authoritativeRemoteSync) {
                        markAlarmDeletedAndCancel(appCtx, old.id)
                    } else {
                        cancelLocalAlarm(appCtx, old.id)
                    }
                }
            }
        }

        // Also cancel any explicitly tombstoned alarms passed in `alarms`
        alarms.forEach { alarm ->
            if ((alarm.isDeleted || alarm.scheduleStatus == FamilyAlarm.STATUS_DELETED) && alarm.id != DIAGNOSTIC_ALARM_ID) {
                markAlarmDeletedAndCancel(appCtx, alarm.id)
            }
        }

        val results = mutableMapOf<String, LocalAlarmScheduleResult>()
        val alarmsForThisDevice = mutableListOf<FamilyAlarm>()

        activeAlarms.forEach { alarm ->
            if (shouldScheduleOnDevice(
                    alarm = alarm,
                    currentMemberId = currentMemberId,
                    currentAuthUid = currentAuthUid,
                    currentMemberName = currentMemberName,
                    isChildDevice = isChildDevice
                )
            ) {
                val res = scheduleLocalAlarm(appCtx, alarm)
                results[alarm.id] = res
                if (res.isScheduled) {
                    alarmsForThisDevice.add(alarm.copy(nextTriggerAtMillis = res.nextTriggerAtMillis))
                }
            } else if (alarm.id != DIAGNOSTIC_ALARM_ID) {
                cancelLocalAlarm(appCtx, alarm.id)
            }
        }

        // Preserve and reschedule any user-created persisted alarms during non-authoritative starter-only syncs
        if (isStarterDemoOnlyList) {
            previousAlarms.forEach { persistedAlarm ->
                if (persistedAlarm.id !in currentIds &&
                    persistedAlarm.id !in deletedIds &&
                    persistedAlarm.id != DIAGNOSTIC_ALARM_ID &&
                    persistedAlarm.isEnabled
                ) {
                    val res = scheduleLocalAlarm(appCtx, persistedAlarm)
                    results[persistedAlarm.id] = res
                    if (res.isScheduled) {
                        alarmsForThisDevice.add(persistedAlarm.copy(nextTriggerAtMillis = res.nextTriggerAtMillis))
                    }
                }
            }
        }

        val effectiveMemberId = currentAuthUid.ifBlank { currentMemberId }
        persistAlarmsForBootRestore(appCtx, alarmsForThisDevice, effectiveMemberId)
        return results
    }

    /**
     * Restores all persisted alarms after device reboot or exact-alarm permission grant.
     */
    fun restoreAlarmsOnBoot(context: Context): Int {
        val appCtx = context.applicationContext ?: context
        val deletedIds = getPersistedDeletedAlarmIds(appCtx)
        val persisted = loadPersistedAlarms(appCtx).filter { it.id !in deletedIds && !it.isDeleted }
        var restoredCount = 0
        persisted.forEach { alarm ->
            if (alarm.isEnabled) {
                val res = scheduleLocalAlarm(appCtx, alarm)
                if (res.isScheduled) {
                    restoredCount++
                }
            }
        }
        return restoredCount
    }

    internal fun buildTriggerBroadcastIntent(
        context: Context,
        alarm: FamilyAlarm,
        isSnooze: Boolean
    ): Intent {
        val schemePath = if (isSnooze) "familytasks://alarm_snooze/${alarm.id}" else "familytasks://alarm/${alarm.id}"
        return Intent(context, FamilyAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_FAMILY_ALARM
            data = Uri.parse(schemePath)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra(EXTRA_ALARM_TITLE, alarm.title)
            putExtra(EXTRA_ALARM_HOUR, alarm.hour)
            putExtra(EXTRA_ALARM_MINUTE, alarm.minute)
            putExtra(EXTRA_ALARM_SOUND_TYPE, alarm.soundType)
            putExtra(EXTRA_ALARM_CUSTOM_URI, alarm.customSoundUri)
            putExtra(EXTRA_ALARM_VIBRATE, alarm.vibrateEnabled)
            putExtra(EXTRA_ALARM_SNOOZE_MINUTES, alarm.snoozeDurationMinutes)
            putExtra(EXTRA_ALARM_VOLUME_PERCENT, alarm.volumePercent)
            putExtra(EXTRA_ALARM_DAYS_CSV, alarm.daysOfWeek.joinToString(",") { it.name })
            putExtra(EXTRA_IS_SNOOZE_TRIGGER, isSnooze)
        }
    }

    private fun buildTriggerPendingIntent(
        context: Context,
        alarm: FamilyAlarm,
        isSnooze: Boolean
    ): PendingIntent {
        val intent = buildTriggerBroadcastIntent(context, alarm, isSnooze)
        return PendingIntent.getBroadcast(
            context,
            requestCodeForAlarm(alarm.id, if (isSnooze) 1 else 0),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCodeForAlarm(alarmId: String, salt: Int): Int {
        return (alarmId.hashCode() * 31 + salt) and 0x7FFFFFFF
    }

    private fun schoolDayToCalendarDay(day: SchoolDay): Int = when (day) {
        SchoolDay.SUNDAY -> Calendar.SUNDAY
        SchoolDay.MONDAY -> Calendar.MONDAY
        SchoolDay.TUESDAY -> Calendar.TUESDAY
        SchoolDay.WEDNESDAY -> Calendar.WEDNESDAY
        SchoolDay.THURSDAY -> Calendar.THURSDAY
        SchoolDay.FRIDAY -> Calendar.FRIDAY
        SchoolDay.SATURDAY -> Calendar.SATURDAY
    }

    private fun persistAlarmsForBootRestore(
        context: Context,
        alarms: List<FamilyAlarm>,
        currentMemberId: String
    ) {
        runCatching {
            val arr = JSONArray()
            alarms.forEach { alarm ->
                val obj = JSONObject().apply {
                    put("id", alarm.id)
                    put("familyId", alarm.familyId)
                    put("title", alarm.title)
                    put("hour", alarm.hour)
                    put("minute", alarm.minute)
                    put("assignedMemberId", alarm.effectiveAssignedMemberId)
                    put("assignedToMemberId", alarm.effectiveAssignedMemberId)
                    put("assignedMemberName", alarm.assignedMemberName)
                    put("createdByMemberId", alarm.createdByMemberId)
                    put("createdByMemberName", alarm.createdByMemberName)
                    put("createdByRole", alarm.createdByRole)
                    put("daysOfWeek", alarm.daysOfWeek.joinToString(",") { it.name })
                    put("isEnabled", alarm.isEnabled)
                    put("ringtoneEmoji", alarm.ringtoneEmoji)
                    put("soundType", alarm.soundType)
                    put("customSoundUri", alarm.customSoundUri)
                    put("customSoundTitle", alarm.customSoundTitle)
                    put("vibrateEnabled", alarm.vibrateEnabled)
                    put("snoozeDurationMinutes", alarm.snoozeDurationMinutes)
                    put("volumePercent", alarm.volumePercent)
                    put("isPersonal", alarm.isPersonal)
                }
                arr.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PERSISTED_ALARMS, arr.toString())
                .putString(KEY_CURRENT_MEMBER_ID, currentMemberId)
                .commit()
        }
    }

    private fun upsertPersistedAlarmForBootRestore(context: Context, alarm: FamilyAlarm) {
        if (alarm.id == DIAGNOSTIC_ALARM_ID) return
        val appCtx = context.applicationContext ?: context
        val existing = loadPersistedAlarms(appCtx).filterNot { it.id == alarm.id }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentMemberId = prefs.getString(KEY_CURRENT_MEMBER_ID, "").orEmpty()
        persistAlarmsForBootRestore(appCtx, existing + alarm, currentMemberId)
    }

    private fun removePersistedAlarmFromBootRestore(context: Context, alarmId: String) {
        if (alarmId.isBlank()) return
        val appCtx = context.applicationContext ?: context
        val existing = loadPersistedAlarms(appCtx)
        if (existing.any { it.id == alarmId }) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentMemberId = prefs.getString(KEY_CURRENT_MEMBER_ID, "").orEmpty()
            persistAlarmsForBootRestore(appCtx, existing.filterNot { it.id == alarmId }, currentMemberId)
        }
    }

    fun loadPersistedAlarms(context: Context): List<FamilyAlarm> {
        return runCatching {
            val deletedIds = getPersistedDeletedAlarmIds(context)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PERSISTED_ALARMS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            val list = mutableListOf<FamilyAlarm>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank() || id in deletedIds) continue
                val daysCsv = obj.optString("daysOfWeek", "")
                val days = daysCsv.split(",")
                    .mapNotNull { token ->
                        runCatching { SchoolDay.valueOf(token.trim()) }.getOrNull()
                    }
                    .ifEmpty { SchoolDay.allWeekDays }
                val assignedId = obj.optString("assignedToMemberId", "").ifBlank {
                    obj.optString("assignedMemberId", "")
                }
                list.add(
                    FamilyAlarm(
                        id = id,
                        familyId = obj.optString("familyId"),
                        title = obj.optString("title", "שעון מעורר"),
                        hour = obj.optInt("hour", 7),
                        minute = obj.optInt("minute", 0),
                        assignedMemberId = assignedId,
                        assignedToMemberId = assignedId,
                        assignedMemberName = obj.optString("assignedMemberName", ""),
                        createdByMemberId = obj.optString("createdByMemberId", ""),
                        createdByMemberName = obj.optString("createdByMemberName", ""),
                        createdByRole = obj.optString("createdByRole", ""),
                        daysOfWeek = days,
                        isEnabled = obj.optBoolean("isEnabled", true),
                        ringtoneEmoji = obj.optString("ringtoneEmoji", "⏰"),
                        soundType = obj.optString("soundType", AlarmAudioController.SOUND_MELODY_ENERGETIC),
                        customSoundUri = obj.optString("customSoundUri", ""),
                        customSoundTitle = obj.optString("customSoundTitle", ""),
                        vibrateEnabled = obj.optBoolean("vibrateEnabled", true),
                        snoozeDurationMinutes = obj.optInt("snoozeDurationMinutes", 5),
                        volumePercent = obj.optInt("volumePercent", 85),
                        isPersonal = obj.optBoolean("isPersonal", false)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }
}
