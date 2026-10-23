package com.example.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores locally scheduled FamilyTasks alarms when the device reboots or when
 * exact-alarm permission state changes on Android 12+, and performs an asynchronous
 * Firestore alarm reconciliation pass to catch up on any parent-created/updated/deleted
 * alarms that occurred while the device was powered off or offline.
 */
class FamilyAlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == "android.intent.action.TIME_SET" ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            // 1. Immediately restore locally persisted alarms so offline boot works without network
            FamilyAlarmScheduler.restoreAlarmsOnBoot(context)

            // 2. Also reconcile with authoritative Firestore state in the background when online
            val pendingResult = runCatching { goAsync() }.getOrNull()
            val appCtx = context.applicationContext ?: context
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    FamilyAlarmCloudSyncWorker.reconcileAlarmsFromFirestore(
                        context = appCtx,
                        reason = "boot_or_system_event:$action"
                    )
                } finally {
                    runCatching { pendingResult?.finish() }
                }
            }
        }
    }
}
