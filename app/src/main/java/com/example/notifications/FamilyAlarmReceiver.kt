package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.example.model.FamilyAlarm
import com.example.model.SchoolDay
import java.util.concurrent.ConcurrentHashMap

/**
 * BroadcastReceiver that handles exact/inexact local alarm triggers from [FamilyAlarmScheduler]
 * as well as Snooze ("נודניק") and Dismiss ("כיבוי") actions.
 *
 * Hands off continuous alarm audio & vibration playback to [FamilyAlarmRingingService]
 * so the sound continues reliably until dismissed, snoozed, or safety-timed-out.
 */
class FamilyAlarmReceiver : BroadcastReceiver() {

    companion object {
        private val recentTriggerMap = ConcurrentHashMap<String, Long>()

        fun wasRecentlyTriggered(alarmId: String, withinMillis: Long = 20_000L): Boolean {
            if (alarmId.isBlank()) return false
            val last = recentTriggerMap[alarmId] ?: return false
            return (System.currentTimeMillis() - last) in 0..withinMillis
        }

        fun clearRecentTrigger(alarmId: String) {
            if (alarmId.isNotBlank()) {
                recentTriggerMap.remove(alarmId)
            }
        }

        fun stopActiveRinging(context: Context? = null) {
            if (context != null) {
                FamilyAlarmRingingService.dismissActiveAlarm(context)
            } else {
                AlarmAudioController.stopAlarmRinging(null)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val rawAlarmId = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID)
            ?.takeIf { it.isNotBlank() }
            ?: intent.data?.lastPathSegment.orEmpty()

        when (action) {
            FamilyAlarmScheduler.ACTION_DISMISS_FAMILY_ALARM -> {
                clearRecentTrigger(rawAlarmId)
                FamilyAlarmRingingService.dismissActiveAlarm(context, rawAlarmId)
            }

            FamilyAlarmScheduler.ACTION_SNOOZE_FAMILY_ALARM -> {
                clearRecentTrigger(rawAlarmId)
                val persisted = FamilyAlarmScheduler.loadPersistedAlarms(context)
                    .find { it.id == rawAlarmId }
                val title = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE)
                    ?: persisted?.title
                    ?: "שעון מעורר משפחתי"
                val soundType = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE)
                    ?: persisted?.soundType
                    ?: AlarmAudioController.SOUND_MELODY_ENERGETIC
                val customUri = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI)
                    ?: persisted?.customSoundUri.orEmpty()
                val vibrate = intent.getBooleanExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE,
                    persisted?.vibrateEnabled ?: true
                )
                val snoozeMin = intent.getIntExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES,
                    persisted?.snoozeDurationMinutes ?: 5
                )
                val volume = intent.getIntExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT,
                    persisted?.volumePercent ?: 85
                )
                FamilyAlarmRingingService.dismissActiveAlarm(context, rawAlarmId)
                FamilyAlarmScheduler.scheduleSnoozeAlarm(
                    context = context,
                    alarmId = rawAlarmId.ifBlank { "snooze_alarm" },
                    title = title,
                    soundType = soundType,
                    customSoundUri = customUri,
                    vibrateEnabled = vibrate,
                    snoozeMinutes = snoozeMin,
                    volumePercent = volume
                )
            }

            FamilyAlarmScheduler.ACTION_TRIGGER_FAMILY_ALARM -> {
                acquireBriefHandOffWakeLock(context)

                val persisted = FamilyAlarmScheduler.loadPersistedAlarms(context)
                    .find { it.id == rawAlarmId }

                val alarmId = rawAlarmId.ifBlank { persisted?.id ?: "alarm_default" }

                // Deduplicate if both AlarmManager and in-process watchdog fire within 15s while already ringing
                val currentlyRinging = FamilyAlarmRingingService.activeRingingAlarm.value?.alarmId == alarmId
                if (currentlyRinging && wasRecentlyTriggered(alarmId, withinMillis = 15_000L)) {
                    FamilyAlarmScheduler.recordDiagnosticReceiverTriggered(alarmId)
                    return
                }
                recentTriggerMap[alarmId] = System.currentTimeMillis()
                FamilyAlarmScheduler.recordDiagnosticReceiverTriggered(alarmId)

                val title = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: persisted?.title
                    ?: "שעון מעורר משפחתי"
                val hour = intent.getIntExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_HOUR,
                    persisted?.hour ?: 7
                )
                val minute = intent.getIntExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_MINUTE,
                    persisted?.minute ?: 0
                )
                val soundType = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_SOUND_TYPE)
                    ?.takeIf { it.isNotBlank() }
                    ?: persisted?.soundType
                    ?: AlarmAudioController.SOUND_MELODY_ENERGETIC
                val customUri = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_CUSTOM_URI)
                    ?.takeIf { it.isNotBlank() }
                    ?: persisted?.customSoundUri.orEmpty()
                val vibrate = intent.getBooleanExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_VIBRATE,
                    persisted?.vibrateEnabled ?: true
                )
                val snoozeMin = intent.getIntExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES,
                    persisted?.snoozeDurationMinutes ?: 5
                )
                val volumePercent = intent.getIntExtra(
                    FamilyAlarmScheduler.EXTRA_ALARM_VOLUME_PERCENT,
                    persisted?.volumePercent ?: 85
                )
                val daysCsv = intent.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_DAYS_CSV)
                    ?.takeIf { it.isNotBlank() }
                    ?: persisted?.daysOfWeek?.joinToString(",") { it.name }.orEmpty()
                val isSnooze = intent.getBooleanExtra(FamilyAlarmScheduler.EXTRA_IS_SNOOZE_TRIGGER, false)

                FamilyAlarmRingingService.startRingingService(
                    context = context,
                    alarmId = alarmId,
                    title = title,
                    hour = hour,
                    minute = minute,
                    soundType = soundType,
                    customSoundUri = customUri,
                    vibrateEnabled = vibrate,
                    snoozeMinutes = snoozeMin,
                    volumePercent = volumePercent
                )

                // Reschedule the next recurring day's occurrence automatically (except one-shot 15s diagnostic test)
                if (!isSnooze && alarmId.isNotBlank() && alarmId != FamilyAlarmScheduler.DIAGNOSTIC_ALARM_ID) {
                    val days = daysCsv.split(",")
                        .mapNotNull { token ->
                            runCatching { SchoolDay.valueOf(token.trim()) }.getOrNull()
                        }
                    if (days.isNotEmpty()) {
                        val recurringAlarm = (persisted ?: FamilyAlarm(
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
                        )).copy(
                            daysOfWeek = days,
                            isEnabled = true
                        )
                        FamilyAlarmScheduler.scheduleLocalAlarm(
                            context = context,
                            alarm = recurringAlarm,
                            nowMillis = System.currentTimeMillis() + 65_000L
                        )
                    }
                }
            }
        }
    }

    private fun acquireBriefHandOffWakeLock(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FamilyTasks:AlarmReceiverHandoffWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(15_000L)
            }
        }
    }
}
