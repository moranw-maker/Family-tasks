package com.example.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.example.MainActivity
import com.example.model.FamilyAlarm
import com.example.model.SchoolDay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class LocalAlarmScheduleResult(
    val isScheduled: Boolean,
    val isExact: Boolean,
    val nextTriggerAtMillis: Long,
    val statusCode: String // "SCHEDULED_LOCALLY", "EXACT_PERMISSION_NEEDED", "DISABLED"
)

/**
 * Local Android AlarmManager scheduler for FamilyTasks alarms.
 *
 * - Supports personal alarms and parent-created alarms assigned to children.
 * - Schedules real Android alarms on the target member's device via [AlarmManager].
 * - Respects Android 12+ (API 31+) exact-alarm permissions ([AlarmManager.canScheduleExactAlarms]).
 * - Persists active device alarms locally so [FamilyAlarmBootReceiver] restores them after device reboot.
 */
object FamilyAlarmScheduler {

    private const val PREFS_NAME = "family_tasks_local_alarms_prefs"
    private const val KEY_PERSISTED_ALARMS = "persisted_alarms_json"
    private const val KEY_CURRENT_MEMBER_ID = "current_member_id"

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

    /**
     * Checks whether this alarm is targeted at the current device's active member.
     * - If `assignedMemberId` is blank ("Whole Family"), every family member's device schedules it.
     * - If `assignedMemberId` matches `currentMemberId`, this device is the target device and schedules it.
     * - If `isPersonal` and created by `currentMemberId`, this device schedules it.
     * - Otherwise (e.g., parent created an alarm specifically for a child's `assignedMemberId`),
     *   the parent's device does NOT schedule it locally; it waits for the child's device to schedule and acknowledge it.
     */
    fun shouldScheduleOnDevice(alarm: FamilyAlarm, currentMemberId: String): Boolean {
        if (!alarm.isEnabled) return false
        if (currentMemberId.isBlank()) return true
        if (alarm.assignedMemberId.isBlank()) return true
        if (alarm.assignedMemberId == currentMemberId) return true
        if (alarm.isPersonal && alarm.createdByMemberId == currentMemberId) return true
        return false
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
    }

    fun openExactAlarmPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
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
            val matchesDay = allowedCalendarDays.isEmpty() || dayOfWeek in allowedCalendarDays
            if (matchesDay && candidate.timeInMillis > nowMillis + 2_000L) {
                return candidate.timeInMillis
            }
        }

        // Fallback: tomorrow at the specified hour/minute
        return cal.apply {
            if (timeInMillis <= nowMillis + 2_000L) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }.timeInMillis
    }

    /**
     * Registers a [FamilyAlarm] in Android's [AlarmManager] on this device.
     */
    fun scheduleLocalAlarm(
        context: Context,
        alarm: FamilyAlarm,
        nowMillis: Long = System.currentTimeMillis()
    ): LocalAlarmScheduleResult {
        if (!alarm.isEnabled) {
            cancelLocalAlarm(context, alarm.id)
            return LocalAlarmScheduleResult(
                isScheduled = false,
                isExact = false,
                nextTriggerAtMillis = 0L,
                statusCode = "DISABLED"
            )
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return LocalAlarmScheduleResult(
                isScheduled = false,
                isExact = false,
                nextTriggerAtMillis = 0L,
                statusCode = "PENDING_TARGET_DEVICE"
            )

        val triggerAtMillis = computeNextTriggerAtMillis(alarm, nowMillis)
        val pendingIntent = buildTriggerPendingIntent(context, alarm, isSnooze = false)
        val canExact = canScheduleExactAlarms(context)

        var scheduledExact = false
        val scheduledSuccessfully = runCatching {
            if (canExact) {
                val showIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val showPendingIntent = PendingIntent.getActivity(
                    context,
                    requestCodeForAlarm(alarm.id, 10),
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                try {
                    val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
                    alarmManager.setAlarmClock(clockInfo, pendingIntent)
                    scheduledExact = true
                } catch (_: SecurityException) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    scheduledExact = true
                }
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                scheduledExact = false
            }
            true
        }.getOrElse {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                scheduledExact = false
                true
            }.getOrDefault(false)
        }

        val statusCode = when {
            !scheduledSuccessfully -> "PENDING_TARGET_DEVICE"
            scheduledExact -> "SCHEDULED_LOCALLY"
            else -> "EXACT_PERMISSION_NEEDED"
        }

        return LocalAlarmScheduleResult(
            isScheduled = scheduledSuccessfully,
            isExact = scheduledExact,
            nextTriggerAtMillis = if (scheduledSuccessfully) triggerAtMillis else 0L,
            statusCode = statusCode
        )
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
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return 0L
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
        val pendingIntent = buildTriggerPendingIntent(context, tempAlarm, isSnooze = true)
        runCatching {
            if (canScheduleExactAlarms(context)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        }
        return triggerAtMillis
    }

    /**
     * Cancels a previously scheduled local alarm and any pending snooze for [alarmId].
     */
    fun cancelLocalAlarm(context: Context, alarmId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            val intent = Intent(context, FamilyAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FAMILY_ALARM
                data = Uri.parse("familytasks://alarm/$alarmId")
            }
            val pi = PendingIntent.getBroadcast(
                context,
                requestCodeForAlarm(alarmId, 0),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)

            val snoozeIntent = Intent(context, FamilyAlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_FAMILY_ALARM
                data = Uri.parse("familytasks://alarm_snooze/$alarmId")
            }
            val snoozePi = PendingIntent.getBroadcast(
                context,
                requestCodeForAlarm(alarmId, 1),
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(snoozePi)
        }
    }

    /**
     * Synchronizes all alarms for the current device member:
     * - Registers enabled alarms assigned to `currentMemberId` (or whole family) in [AlarmManager].
     * - Cancels alarms that were disabled, deleted, or reassigned to another member.
     * - Persists the active alarms list locally so [FamilyAlarmBootReceiver] can restore them on reboot.
     * - Returns a map of `alarmId -> LocalAlarmScheduleResult` for alarms that belong to this device.
     */
    fun syncAlarmsForCurrentDevice(
        context: Context,
        alarms: List<FamilyAlarm>,
        currentMemberId: String
    ): Map<String, LocalAlarmScheduleResult> {
        val previousAlarms = loadPersistedAlarms(context)
        val currentIds = alarms.map { it.id }.toSet()

        // Cancel any removed alarms
        previousAlarms.forEach { old ->
            if (old.id !in currentIds) {
                cancelLocalAlarm(context, old.id)
            }
        }

        val results = mutableMapOf<String, LocalAlarmScheduleResult>()
        val alarmsForThisDevice = mutableListOf<FamilyAlarm>()

        alarms.forEach { alarm ->
            if (shouldScheduleOnDevice(alarm, currentMemberId)) {
                val res = scheduleLocalAlarm(context, alarm)
                results[alarm.id] = res
                if (res.isScheduled) {
                    alarmsForThisDevice.add(alarm.copy(nextTriggerAtMillis = res.nextTriggerAtMillis))
                }
            } else {
                cancelLocalAlarm(context, alarm.id)
            }
        }

        persistAlarmsForBootRestore(context, alarmsForThisDevice, currentMemberId)
        return results
    }

    /**
     * Restores all persisted alarms after device reboot or exact-alarm permission grant.
     */
    fun restoreAlarmsOnBoot(context: Context): Int {
        val persisted = loadPersistedAlarms(context)
        var restoredCount = 0
        persisted.forEach { alarm ->
            if (alarm.isEnabled) {
                val res = scheduleLocalAlarm(context, alarm)
                if (res.isScheduled) {
                    restoredCount++
                }
            }
        }
        return restoredCount
    }

    private fun buildTriggerPendingIntent(
        context: Context,
        alarm: FamilyAlarm,
        isSnooze: Boolean
    ): PendingIntent {
        val schemePath = if (isSnooze) "familytasks://alarm_snooze/${alarm.id}" else "familytasks://alarm/${alarm.id}"
        val intent = Intent(context, FamilyAlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_FAMILY_ALARM
            data = Uri.parse(schemePath)
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
                    put("assignedMemberId", alarm.assignedMemberId)
                    put("assignedMemberName", alarm.assignedMemberName)
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
                .apply()
        }
    }

    fun loadPersistedAlarms(context: Context): List<FamilyAlarm> {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_PERSISTED_ALARMS, null) ?: return emptyList()
            val arr = JSONArray(raw)
            val list = mutableListOf<FamilyAlarm>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val daysCsv = obj.optString("daysOfWeek", "")
                val days = daysCsv.split(",")
                    .mapNotNull { token ->
                        runCatching { SchoolDay.valueOf(token.trim()) }.getOrNull()
                    }
                    .ifEmpty {
                        listOf(
                            SchoolDay.SUNDAY,
                            SchoolDay.MONDAY,
                            SchoolDay.TUESDAY,
                            SchoolDay.WEDNESDAY,
                            SchoolDay.THURSDAY,
                            SchoolDay.FRIDAY
                        )
                    }
                list.add(
                    FamilyAlarm(
                        id = obj.optString("id"),
                        familyId = obj.optString("familyId"),
                        title = obj.optString("title", "שעון מעורר"),
                        hour = obj.optInt("hour", 7),
                        minute = obj.optInt("minute", 0),
                        assignedMemberId = obj.optString("assignedMemberId", ""),
                        assignedMemberName = obj.optString("assignedMemberName", ""),
                        daysOfWeek = days,
                        isEnabled = obj.optBoolean("isEnabled", true),
                        ringtoneEmoji = obj.optString("ringtoneEmoji", "⏰"),
                        soundType = obj.optString("soundType", "SYSTEM_ALARM"),
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
