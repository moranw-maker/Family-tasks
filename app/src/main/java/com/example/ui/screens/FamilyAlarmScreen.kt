package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.text.format.DateFormat
import com.example.model.DeletedAlarmSyncNotice
import com.example.model.FamilyAlarm
import com.example.model.FamilyMember
import com.example.model.SchoolDay
import com.example.model.UserRole
import com.example.notifications.ActiveRingingAlarmState
import com.example.notifications.AlarmAudioController
import com.example.notifications.AlarmDiagnosticState
import com.example.notifications.FamilyAlarmReceiver
import com.example.notifications.FamilyAlarmRingingService
import com.example.notifications.FamilyAlarmScheduler
import com.example.ui.components.FamilyTimeSelectionDialog
import com.example.ui.components.ScreenHelpBanner
import com.example.ui.components.TimeFormatUtils
import com.example.ui.components.TimePickerField
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.GoogleBlueDark
import com.example.ui.theme.GoogleBlueLight
import com.example.ui.theme.OutlineLight
import com.example.ui.theme.PastelGreenBackground
import com.example.ui.theme.PastelGreenDark
import com.example.ui.theme.GoogleRed
import com.example.ui.theme.GoogleRedDark
import com.example.ui.theme.GoogleRedLight
import com.example.ui.theme.PastelYellowBackground
import com.example.ui.theme.PastelYellowBorder
import com.example.ui.theme.PastelYellowDark
import com.example.ui.theme.VibrantActionBlue
import java.util.Calendar

/**
 * Standalone Family Alarm Clock screen ("שעון מעורר").
 *
 * Features:
 * - Separate bottom navigation tab for both Parent and Child roles.
 * - Role-aware ownership & editing permissions:
 *   - Parent-created alarm for child: visible to child (read-only), editable by parent, rings only on child's device.
 *   - Child-created alarm for self: editable by both child and parent, rings only on child's device.
 *   - Parent-created alarm for self: editable only by parent, rings only on parent's device.
 * - Live in-app ringing banner with alarm time, label, "נודניק" (Snooze), and "כיבוי" (Dismiss).
 * - Audible sound preview and volume preview for built-in melodies, system tones, and custom audio files.
 * - Hebrew confirmation dialog before deleting an alarm.
 */
@Composable
fun FamilyAlarmScreen(
    alarms: List<FamilyAlarm>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    deletedAlarmNotices: List<DeletedAlarmSyncNotice> = emptyList(),
    onDismissDeletedNotice: (String) -> Unit = {},
    onSaveAlarm: (FamilyAlarm) -> Unit,
    onToggleAlarm: (String) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onShowToast: (String) -> Unit,
    onNavigateBack: (() -> Unit)? = null
) {
    if (onNavigateBack != null) {
        BackHandler { onNavigateBack() }
    }

    val context = LocalContext.current
    val activeRingingAlarm by FamilyAlarmRingingService.activeRingingAlarm.collectAsState()
    val diagnosticState by FamilyAlarmScheduler.diagnosticState.collectAsState()
    var showDeveloperDiagnostics by remember { mutableStateOf(false) }
    var alarmToEdit by remember { mutableStateOf<FamilyAlarm?>(null) }
    var showAddOrEditDialog by remember { mutableStateOf(false) }
    var alarmPendingDelete by remember { mutableStateOf<FamilyAlarm?>(null) }

    val hasExactPermission = remember(diagnosticState) { FamilyAlarmScheduler.canScheduleExactAlarms(context) }
    val canUseFullScreen = remember(diagnosticState) { FamilyAlarmScheduler.canUseFullScreenIntent(context) }
    val hasNotifications = remember(diagnosticState) { FamilyAlarmScheduler.hasNotificationPermission(context) }
    val isBatteryUnrestricted = remember { FamilyAlarmScheduler.isIgnoringBatteryOptimizations(context) }

    // Ensure any sound preview stops when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            AlarmAudioController.stopPreview(context)
        }
    }

    Scaffold(
        floatingActionButton = {
            Surface(
                onClick = {
                    alarmToEdit = null
                    showAddOrEditDialog = true
                },
                shape = RoundedCornerShape(24.dp),
                color = VibrantActionBlue,
                contentColor = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("add_alarm_fab")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AlarmAdd,
                        contentDescription = if (isHebrew) "שעון מעורר חדש" else "Add Alarm",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isHebrew) "שעון מעורר חדש" else "Add Alarm",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFEBF3FE),
                            Color(0xFFF7F9FD),
                            Color.White
                        )
                    )
                )
                .testTag("family_alarm_screen")
        ) {
            val isCompactScreen = maxWidth < 360.dp
            val horizontalPad = if (isCompactScreen) 10.dp else 14.dp

            LazyColumn(
                contentPadding = PaddingValues(
                    start = horizontalPad,
                    end = horizontalPad,
                    top = 8.dp,
                    bottom = 112.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .testTag("family_alarms_list")
            ) {
                // 1. Live Ringing Alarm Card (when an alarm is actively ringing on this device)
                activeRingingAlarm?.let { ringing ->
                    item(key = "active_ringing_banner") {
                        ActiveRingingAlarmCard(
                            ringing = ringing,
                            isHebrew = isHebrew,
                            onSnooze = {
                                FamilyAlarmRingingService.snoozeActiveAlarm(context)
                                onShowToast(
                                    if (isHebrew) "נודניק הופעל ל-${ringing.snoozeMinutes} דקות 💤"
                                    else "Snoozed for ${ringing.snoozeMinutes} minutes 💤"
                                )
                            },
                            onDismiss = {
                                FamilyAlarmRingingService.dismissActiveAlarm(context, ringing.alarmId)
                                onShowToast(
                                    if (isHebrew) "השעון המעורר כובה ✓"
                                    else "Alarm dismissed ✓"
                                )
                            }
                        )
                    }
                }

                // 2. Quick Help Banner
                item(key = "alarm_help_banner") {
                    ScreenHelpBanner(
                        title = if (isHebrew) "שעון מעורר משפחתי חכם" else "Smart Family Alarm Clock",
                        tips = if (isHebrew) listOf(
                            "שעון הורה לילד: הורה יכול להגדיר שעון מעורר עבור הילד/ה שיצלצל במכשיר הילד בלבד.",
                            "הרשאות עריכה: ילד יכול לערוך או למחוק רק שעונים שהוא יצר בעצמו. שעון שהוגדר ע״י הורה מוגן מפני כיבוי.",
                            "שמע ועוצמה: ניתן לבחור מנגינות השכמה מובנות, צלילי מערכת או קובץ שמע אישי ולבדוק את העוצמה מראש."
                        ) else listOf(
                            "Parent-to-Child Alarms: Parents can schedule wake-up alarms that ring directly on the child's device.",
                            "Permissions: Children can manage their own alarms, while parent-assigned alarms are protected from tampering.",
                            "Sound & Volume Preview: Test built-in melodies, system tones, or custom audio files before saving."
                        ),
                        isHebrew = isHebrew
                    )
                }

                // 3. Header & Device Readiness Card
                item(key = "alarm_header_readiness_card") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = GoogleBlueLight.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Alarm,
                                        contentDescription = null,
                                        tint = GoogleBlueDark,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isHebrew) "שעונים מעוררים והשכמה" else "Family & Personal Alarms",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = GoogleBlueDark
                                            )
                                        )
                                        Text(
                                            text = if (isHebrew) {
                                                if (currentMember.isParent) {
                                                    "מצב הורה: ניהול שעונים אישיים ושעוני השכמה למכשירי הילדים"
                                                } else {
                                                    "מצב ילד/ה (${currentMember.name}): שעונים אישיים ושעוני השכמה מההורים"
                                                }
                                            } else {
                                                if (currentMember.isParent) {
                                                    "Parent mode: Manage personal and child wake-up alarms"
                                                } else {
                                                    "Child mode (${currentMember.name}): Personal & parent-scheduled alarms"
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    TextButton(
                                        onClick = { showDeveloperDiagnostics = !showDeveloperDiagnostics },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 40.dp)
                                            .testTag("developer_alarm_diagnostics_toggle")
                                    ) {
                                        Text(
                                            text = if (isHebrew) "אבחון 🛠️" else "Diag 🛠️",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleBlueDark
                                        )
                                    }

                                    TextButton(
                                        onClick = {
                                            FamilyAlarmReceiver.stopActiveRinging(context)
                                            onShowToast(
                                                if (isHebrew) "כל צלצול פעיל הופסק" else "Stopped active alarm ringing"
                                            )
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .defaultMinSize(minHeight = 40.dp)
                                            .testTag("stop_active_alarm_ringing_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AlarmOff,
                                            contentDescription = if (isHebrew) "השתק" else "Silence",
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isHebrew) "השתק" else "Silence",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (showDeveloperDiagnostics) {
                                Spacer(modifier = Modifier.height(8.dp))
                                DeveloperAlarmDiagnosticsCard(
                                    diagnosticState = diagnosticState,
                                    isHebrew = isHebrew,
                                    currentMemberName = currentMember.name,
                                    onSchedule15sTest = {
                                        val res = FamilyAlarmScheduler.scheduleDiagnostic15SecondAlarm(
                                            context = context,
                                            currentMemberName = currentMember.name
                                        )
                                        if (res.isScheduled) {
                                            onShowToast(
                                                if (isHebrew) "בדיקת צלצול אמיתית נקבעה ב-AlarmManager לעוד 15 שניות ⏱️"
                                                else "Real 15-second AlarmManager test scheduled ⏱️"
                                            )
                                        } else {
                                            onShowToast(
                                                if (isHebrew) "שגיאה בתזמון בדיקת 15 שניות: ${res.errorMessage}"
                                                else "Failed to schedule 15s test: ${res.errorMessage}"
                                            )
                                        }
                                    },
                                    onStopRinging = {
                                        FamilyAlarmReceiver.stopActiveRinging(context)
                                    },
                                    onReset = {
                                        FamilyAlarmScheduler.resetDiagnosticState(context)
                                    }
                                )
                            }

                            if (!hasExactPermission) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = PastelYellowBackground,
                                    border = BorderStroke(1.dp, PastelYellowBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isHebrew)
                                                "לצלצול בדקה המדויקת ב-Android 12+, אשרו הרשאת שעון מדויק"
                                            else
                                                "Allow exact alarm permission for minute-accurate triggers",
                                            style = MaterialTheme.typography.labelSmall.copy(color = PastelYellowDark),
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { FamilyAlarmScheduler.openExactAlarmPermissionSettings(context) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("grant_exact_alarm_permission_button")
                                        ) {
                                            Text(
                                                text = if (isHebrew) "אשר הרשאה" else "Grant",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            if (!canUseFullScreen) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = PastelYellowBackground,
                                    border = BorderStroke(1.dp, PastelYellowBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isHebrew)
                                                "להצגת מסך כיבוי אוטומטי מעל נעילת מסך, אשרו הרשאת התראה במסך מלא"
                                            else
                                                "Allow full-screen alarm display to show dismiss/snooze screen automatically",
                                            style = MaterialTheme.typography.labelSmall.copy(color = PastelYellowDark),
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { FamilyAlarmScheduler.openFullScreenIntentSettings(context) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("grant_fullscreen_alarm_permission_button")
                                        ) {
                                            Text(
                                                text = if (isHebrew) "אשר מסך מלא" else "Allow",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            if (!hasNotifications) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = PastelYellowBackground,
                                    border = BorderStroke(1.dp, PastelYellowBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isHebrew)
                                                "התראות כבויות — הפעילו התראות להצגת כפתורי כיבוי ונודניק מהירים"
                                            else
                                                "Notifications are disabled — enable for quick dismiss/snooze controls",
                                            style = MaterialTheme.typography.labelSmall.copy(color = PastelYellowDark),
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = { FamilyAlarmScheduler.openNotificationSettings(context) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("grant_alarm_notification_permission_button")
                                        ) {
                                            Text(
                                                text = if (isHebrew) "הפעל התראות" else "Enable",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            if (!isBatteryUnrestricted) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isHebrew)
                                            "מומלץ להחריג מחיסכון בסוללה לצלצול אמין כשהמסך כבוי"
                                        else
                                            "Disable battery optimization for reliable lock-screen alarms",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { FamilyAlarmScheduler.openBatteryOptimizationSettings(context) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.testTag("open_battery_optimization_button")
                                    ) {
                                        Text(
                                            text = if (isHebrew) "הגדרות סוללה" else "Battery",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3b. Recently Deleted Alarms Cloud vs Target Device Sync Notices
                items(
                    items = deletedAlarmNotices,
                    key = { "deleted_notice_${it.alarmId}" }
                ) { notice ->
                    DeletedAlarmSyncNoticeCard(
                        notice = notice,
                        isHebrew = isHebrew,
                        onDismiss = { onDismissDeletedNotice(notice.alarmId) }
                    )
                }

                // 4. Alarms List or Empty State
                if (alarms.isEmpty()) {
                    item(key = "empty_alarms_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp, horizontal = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "⏰", fontSize = 52.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (isHebrew) "עדיין אין שעונים מעוררים מוגדרים" else "No family alarms configured yet",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isHebrew)
                                        "לחצו על 'שעון מעורר חדש' למטה ליצירת שעון אישי או שעון לילד/ה"
                                    else
                                        "Tap 'Add Alarm' below to create a personal or child alarm",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                } else {
                    items(alarms, key = { it.id }) { alarm ->
                        val targetId = alarm.effectiveAssignedMemberId
                        val assignedMember = members.find { it.id == targetId }
                        val creatorMember = members.find { it.id == alarm.createdByMemberId }
                        val canModify = alarm.canMemberModify(currentMember)
                        val canDelete = alarm.canMemberDelete(currentMember)

                        FamilyAlarmCard(
                            alarm = alarm,
                            assignedMember = assignedMember,
                            creatorMember = creatorMember,
                            currentMember = currentMember,
                            canModify = canModify,
                            canDelete = canDelete,
                            isHebrew = isHebrew,
                            onToggle = {
                                if (canModify) {
                                    onToggleAlarm(alarm.id)
                                } else {
                                    onShowToast(
                                        if (isHebrew) "שעון זה הוגדר ע״י הורה ואינו ניתן לכיבוי או שינוי מחשבון ילד 🔒"
                                        else "This alarm is managed by a parent and cannot be modified 🔒"
                                    )
                                }
                            },
                            onEdit = {
                                if (canModify) {
                                    alarmToEdit = alarm
                                    showAddOrEditDialog = true
                                } else {
                                    onShowToast(
                                        if (isHebrew) "שעון זה מנוהל ע״י הורה (צפייה בלבד) 🔒"
                                        else "This alarm is managed by a parent (view only) 🔒"
                                    )
                                }
                            },
                            onRequestDelete = {
                                if (canDelete) {
                                    alarmPendingDelete = alarm
                                } else {
                                    onShowToast(
                                        if (isHebrew) "אין הרשאה למחוק שעון מעורר שהוגדר ע״י הורה 🔒"
                                        else "Only a parent can delete this alarm 🔒"
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 5. Hebrew Delete Confirmation Dialog (Requirement 8)
    alarmPendingDelete?.let { alarmToDelete ->
        AlertDialog(
            onDismissRequest = { alarmPendingDelete = null },
            shape = RoundedCornerShape(22.dp),
            title = {
                Text(
                    text = "למחוק את השעון המעורר?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("delete_alarm_dialog_title")
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "האם אתה בטוח שברצונך למחוק את השעון המעורר?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${alarmToDelete.formattedTime24h} • ${alarmToDelete.title}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoogleBlueDark
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = alarmToDelete.id
                        alarmPendingDelete = null
                        onDeleteAlarm(id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD93025),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("delete_alarm_confirm_button")
                ) {
                    Text("מחיקה", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { alarmPendingDelete = null },
                    modifier = Modifier.testTag("delete_alarm_cancel_button")
                ) {
                    Text("ביטול", fontWeight = FontWeight.SemiBold)
                }
            },
            modifier = Modifier.testTag("delete_alarm_confirm_dialog")
        )
    }

    // 6. Add / Edit Alarm Dialog with Sound & Volume Preview
    if (showAddOrEditDialog) {
        AddOrEditFamilyAlarmDialog(
            alarmToEdit = alarmToEdit,
            members = members,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = {
                AlarmAudioController.stopPreview(context)
                showAddOrEditDialog = false
                alarmToEdit = null
            },
            onSave = { savedAlarm, alsoSetSystemClock ->
                AlarmAudioController.stopPreview(context)
                onSaveAlarm(savedAlarm)
                val targetId = savedAlarm.effectiveAssignedMemberId
                val isRemoteChildTarget = targetId.isNotBlank() && targetId != currentMember.id
                if (alsoSetSystemClock && !isRemoteChildTarget) {
                    openAndroidSystemClock(context, savedAlarm, isHebrew, onShowToast)
                } else {
                    val toastMsg = if (isRemoteChildTarget) {
                        if (isHebrew) {
                            "השעון נשמר בענן • יצלצל במכשיר של ${savedAlarm.assignedMemberName} (${savedAlarm.formattedTime24h}) ⏳"
                        } else {
                            "Alarm synced • Will ring on ${savedAlarm.assignedMemberName}'s device (${savedAlarm.formattedTime24h}) ⏳"
                        }
                    } else {
                        if (isHebrew) {
                            "השעון המעורר תוזמן במכשיר זה לשעה ${savedAlarm.formattedTime24h} ⏰"
                        } else {
                            "Alarm scheduled on this device for ${savedAlarm.formattedTime24h} ⏰"
                        }
                    }
                    onShowToast(toastMsg)
                }
                showAddOrEditDialog = false
                alarmToEdit = null
            }
        )
    }
}

@Composable
private fun ActiveRingingAlarmCard(
    ringing: ActiveRingingAlarmState,
    isHebrew: Boolean,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF123262),
        contentColor = Color.White,
        shadowElevation = 8.dp,
        border = BorderStroke(1.5.dp, GoogleBlueLight),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_ringing_alarm_banner")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "⏰", fontSize = 24.sp)
                    }
                    Column {
                        Text(
                            text = ringing.timeFormatted,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = ringing.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = GoogleBlueLight
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onSnooze,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("active_alarm_snooze_button")
                ) {
                    Icon(imageVector = Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "נודניק (${ringing.snoozeMinutes} דק׳)" else "Snooze (${ringing.snoozeMinutes}m)",
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD93025),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("active_alarm_dismiss_button")
                ) {
                    Icon(imageVector = Icons.Default.AlarmOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "כיבוי" else "Dismiss",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DeletedAlarmSyncNoticeCard(
    notice: DeletedAlarmSyncNotice,
    isHebrew: Boolean,
    onDismiss: () -> Unit
) {
    val isPendingRemoteCancel = notice.isRemoteTarget && !notice.isConfirmedCancelledOnTarget
    val bgColor = if (isPendingRemoteCancel) PastelYellowBackground else PastelGreenBackground
    val fgColor = if (isPendingRemoteCancel) PastelYellowDark else PastelGreenDark
    val borderColor = if (isPendingRemoteCancel) PastelYellowBorder else PastelGreenDark.copy(alpha = 0.35f)

    val statusText = if (isPendingRemoteCancel) {
        if (isHebrew) {
            "☁️ השעון (${notice.formattedTime} • ${notice.title}) נמחק מהענן — ממתין לאישור ביטול ממכשיר של ${notice.targetMemberName} (יבוטל אוטומטית כשהמכשיר מחובר)"
        } else {
            "☁️ Deleted in cloud (${notice.formattedTime} • ${notice.title}) — waiting for cancellation confirmation from ${notice.targetMemberName}'s device"
        }
    } else {
        if (isHebrew) {
            if (notice.isRemoteTarget) {
                "✓ השעון (${notice.formattedTime} • ${notice.title}) נמחק מהענן ובוטל סופית במכשיר של ${notice.targetMemberName}"
            } else {
                "✓ השעון (${notice.formattedTime} • ${notice.title}) נמחק ובוטל ממכשיר זה"
            }
        } else {
            if (notice.isRemoteTarget) {
                "✓ Deleted in cloud and confirmed cancelled on ${notice.targetMemberName}'s device (${notice.formattedTime})"
            } else {
                "✓ Alarm deleted and cancelled on this device (${notice.formattedTime})"
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("deleted_alarm_sync_notice_${notice.alarmId}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = fgColor
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("dismiss_deleted_notice_${notice.alarmId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (isHebrew) "סגור הודעה" else "Dismiss notice",
                    tint = fgColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FamilyAlarmCard(
    alarm: FamilyAlarm,
    assignedMember: FamilyMember?,
    creatorMember: FamilyMember?,
    currentMember: FamilyMember,
    canModify: Boolean,
    canDelete: Boolean,
    isHebrew: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val targetId = alarm.effectiveAssignedMemberId
    val isTargetedAtThisDevice = alarm.isTargetedAtDeviceMember(currentMember.id)
    val isRemoteTarget = alarm.isTargetedToAnotherMember() && !isTargetedAtThisDevice
    val isTargetAcknowledged = alarm.isTargetDeviceAcknowledged()
    val isPendingTargetDevice = alarm.isEnabled && isRemoteTarget && !isTargetAcknowledged
    val isExactPermNeeded = alarm.isEnabled && alarm.scheduleStatus == FamilyAlarm.STATUS_EXACT_PERMISSION_NEEDED
    val isScheduleError = alarm.isEnabled && alarm.scheduleStatus == FamilyAlarm.STATUS_SCHEDULE_ERROR

    val targetDisplayName = assignedMember?.name
        ?: alarm.assignedMemberName.takeIf { it.isNotBlank() }
        ?: if (isHebrew) "בן משפחה" else "Family Member"

    val nextTriggerSummary = remember(alarm.hour, alarm.minute, alarm.daysOfWeek, alarm.isEnabled) {
        if (!alarm.isEnabled) ""
        else formatNextTriggerRelativeSummary(alarm, isHebrew)
    }

    val (statusLabel, statusBg, statusFg) = when {
        !alarm.isEnabled -> Triple(
            if (isHebrew) "כבוי" else "Disabled",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        isScheduleError -> Triple(
            if (isHebrew) "⚠️ שגיאת תזמון במכשיר — בדקו הרשאות שעון" else "⚠️ Device schedule error",
            GoogleRedLight,
            GoogleRedDark
        )
        isPendingTargetDevice -> Triple(
            if (isHebrew) {
                "⏳ ממתין לסנכרון במכשיר של $targetDisplayName"
            } else {
                "⏳ Pending confirmation on $targetDisplayName's device"
            },
            PastelYellowBackground,
            PastelYellowDark
        )
        isExactPermNeeded -> Triple(
            if (isHebrew) "⚠️ מתוזמן ($nextTriggerSummary • מומלץ לאשר הרשאת דיוק)" else "⚠️ Scheduled ($nextTriggerSummary)",
            PastelYellowBackground,
            PastelYellowDark
        )
        else -> Triple(
            if (isHebrew) {
                val dev = alarm.acknowledgedByDeviceName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                if (isTargetedAtThisDevice) {
                    val triggerNote = nextTriggerSummary.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
                    "✓ מתוזמן במכשיר זה$triggerNote"
                } else {
                    "✓ סונכרן ומתוזמן במכשיר של $targetDisplayName$dev"
                }
            } else {
                val dev = alarm.acknowledgedByDeviceName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                if (isTargetedAtThisDevice) {
                    "✓ Scheduled on this device ($nextTriggerSummary)"
                } else {
                    "✓ Synced & scheduled on $targetDisplayName's device$dev"
                }
            },
            PastelGreenBackground,
            PastelGreenDark
        )
    }

    val soundLabel = AlarmAudioController.soundDisplayName(
        soundType = alarm.soundType,
        customTitle = alarm.customSoundTitle,
        isHebrew = isHebrew
    )

    val creatorName = creatorMember?.name
        ?: alarm.createdByMemberName.takeIf { it.isNotBlank() }
        ?: if (alarm.createdByRole.equals("PARENT", ignoreCase = true)) {
            if (isHebrew) "הורה" else "Parent"
        } else {
            ""
        }

    val ownershipDescription = when {
        alarm.createdByMemberId == currentMember.id && targetId == currentMember.id ->
            if (isHebrew) "שעון אישי שלי (${currentMember.name})" else "My Personal Alarm (${currentMember.name})"
        alarm.createdByRole.equals("PARENT", ignoreCase = true) && targetId != alarm.createdByMemberId ->
            if (isHebrew) "הוגדר ע״י הורה (${creatorName.ifBlank { "הורה" }}) עבור $targetDisplayName"
            else "Set by Parent ($creatorName) for $targetDisplayName"
        targetId.isNotBlank() ->
            if (isHebrew) "שעון של $targetDisplayName" else "$targetDisplayName's Alarm"
        else ->
            if (isHebrew) "שעון משפחתי" else "Family Alarm"
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (alarm.isEnabled) 3.dp else 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (alarm.isEnabled) GoogleBlue.copy(alpha = 0.35f) else OutlineLight
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canModify) { onEdit() }
            .testTag("family_alarm_card_${alarm.id}")
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompactCard = maxWidth < 330.dp
            val cardPadding = if (isCompactCard) 12.dp else 14.dp

            Column(modifier = Modifier.padding(cardPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isCompactCard) 8.dp else 12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isCompactCard) 40.dp else 46.dp)
                                .clip(CircleShape)
                                .background(
                                    if (alarm.isEnabled) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = alarm.ringtoneEmoji, fontSize = if (isCompactCard) 19.sp else 22.sp)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = alarm.formattedTime24h,
                                style = (if (isCompactCard) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall).copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (alarm.isEnabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            )
                            Text(
                                text = alarm.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (alarm.isEnabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Switch(
                        checked = alarm.isEnabled,
                        enabled = canModify,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier.testTag("toggle_alarm_${alarm.id}")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Device Acknowledgement & Scheduling Status Pill (FlowRow wraps cleanly on narrow screens)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusBg,
                        modifier = Modifier.testTag("alarm_status_badge_${alarm.id}")
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = statusFg
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (!canModify) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.testTag("alarm_parent_locked_badge_${alarm.id}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isHebrew) "מנוהל ע״י הורה" else "Parent Managed",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Audio, Vibration, Snooze & Volume Summary
                Text(
                    text = if (isHebrew) {
                        "$soundLabel • עוצמה ${alarm.volumePercent}% • נודניק ${alarm.snoozeDurationMinutes} דק׳ • ${if (alarm.vibrateEnabled) "רטט פעיל" else "ללא רטט"}"
                    } else {
                        "$soundLabel • Vol ${alarm.volumePercent}% • Snooze ${alarm.snoozeDurationMinutes}m • ${if (alarm.vibrateEnabled) "Vibrate" else "No vibrate"}"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val daysText = when {
                        alarm.daysOfWeek.size >= 7 ->
                            if (isHebrew) "כל ימות השבוע (א׳-ש׳)" else "Every day (Sun-Sat)"
                        alarm.daysOfWeek.size == 6 && SchoolDay.SATURDAY !in alarm.daysOfWeek ->
                            if (isHebrew) "ימי לימודים (א׳-ו׳)" else "School days (Sun-Fri)"
                        else ->
                            alarm.daysOfWeek.joinToString(", ") {
                                if (isHebrew) it.shortHe else it.getDisplayName(false).take(3)
                            }
                    }

                    Text(
                        text = "$ownershipDescription • $daysText",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (canModify || canDelete) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (canModify) {
                                IconButton(
                                    onClick = onEdit,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("edit_alarm_${alarm.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = if (isHebrew) "עריכה" else "Edit",
                                        tint = GoogleBlue,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            if (canDelete) {
                                IconButton(
                                    onClick = onRequestDelete,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("delete_alarm_${alarm.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = if (isHebrew) "מחיקה" else "Delete",
                                        tint = Color(0xFFD93025),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddOrEditFamilyAlarmDialog(
    alarmToEdit: FamilyAlarm?,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSave: (FamilyAlarm, Boolean) -> Unit
) {
    val context = LocalContext.current
    val previewState by AlarmAudioController.previewState.collectAsState()
    val isDevice24Hour = remember(context) {
        runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(true)
    }

    val childMembers = remember(members) { members.filter { it.role == UserRole.CHILD } }

    var title by remember(alarmToEdit) {
        val initialTitle = alarmToEdit?.title?.takeUnless {
            it == "בוקר טוב!" || it == "שעון מעורר" || it == "שעון מעורר ☀️" || it == "Good Morning!"
        }.orEmpty()
        mutableStateOf(initialTitle)
    }
    var timeFormatted by remember(alarmToEdit) {
        val defaultTime = alarmToEdit?.formattedTime24h ?: run {
            val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, 2) }
            String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
        mutableStateOf(defaultTime)
    }
    var showTimePickerModal by remember { mutableStateOf(false) }

    // Default assignment:
    // - Child role: always assigned to self (`currentMember.id`).
    // - Parent role: defaults to editing alarm's target, or personal (`currentMember.id`) if no child selected.
    var selectedMemberId by remember(alarmToEdit) {
        mutableStateOf(
            alarmToEdit?.effectiveAssignedMemberId?.takeIf { it.isNotBlank() }
                ?: currentMember.id
        )
    }
    var isPersonalAlarm by remember(alarmToEdit) {
        mutableStateOf(
            if (!currentMember.isParent) true
            else (alarmToEdit?.effectiveAssignedMemberId ?: currentMember.id) == currentMember.id
        )
    }

    var selectedDays by remember(alarmToEdit) {
        mutableStateOf(
            alarmToEdit?.daysOfWeek?.toSet() ?: SchoolDay.allWeekDays.toSet()
        )
    }
    var soundType by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.soundType ?: AlarmAudioController.SOUND_MELODY_ENERGETIC)
    }
    var customSoundUri by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.customSoundUri.orEmpty())
    }
    var customSoundTitle by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.customSoundTitle.orEmpty())
    }
    var customFileErrorHe by remember { mutableStateOf<String?>(null) }

    var vibrateEnabled by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.vibrateEnabled ?: true)
    }
    var snoozeDurationMinutes by remember(alarmToEdit) {
        mutableIntStateOf(alarmToEdit?.snoozeDurationMinutes ?: 5)
    }
    var volumePercent by remember(alarmToEdit) {
        mutableFloatStateOf((alarmToEdit?.volumePercent ?: 85).toFloat())
    }
    var alsoSetDeviceClock by remember { mutableStateOf(false) }

    val isTargetRemoteChild = currentMember.isParent &&
        selectedMemberId.isNotBlank() &&
        selectedMemberId != currentMember.id

    val customAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val importRes = AlarmAudioController.importAndPersistCustomSoundUri(context, uri)
            importRes.onSuccess { (persistedUriStr, displayName) ->
                customFileErrorHe = null
                customSoundUri = persistedUriStr
                customSoundTitle = displayName
                soundType = AlarmAudioController.SOUND_CUSTOM_URI
                AlarmAudioController.startPreview(
                    context = context,
                    soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                    customSoundUri = persistedUriStr,
                    volumePercent = volumePercent.toInt()
                )
            }.onFailure { err ->
                customFileErrorHe = err.localizedMessage ?: "לא ניתן לטעון את קובץ השמע שנבחר"
            }
        }
    }

    val parsedTime = remember(timeFormatted) {
        val parts = timeFormatted.split(":")
        val hr = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val min = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        Pair(hr, min)
    }

    val periodBadgeText = remember(parsedTime, isDevice24Hour, isHebrew) {
        val hr = parsedTime.first
        if (!isDevice24Hour) {
            if (hr < 12) "AM" else "PM"
        } else {
            if (isHebrew) {
                when (hr) {
                    in 5..11 -> "בוקר"
                    in 12..16 -> "צהריים"
                    in 17..20 -> "ערב"
                    else -> "לילה"
                }
            } else {
                "24h"
            }
        }
    }

    val previewNextTriggerText = remember(timeFormatted, selectedDays, isHebrew, title) {
        val tempAlarm = FamilyAlarm(
            id = "preview",
            title = title.ifBlank { if (isHebrew) "בוקר טוב!" else "Good Morning!" },
            hour = parsedTime.first,
            minute = parsedTime.second,
            daysOfWeek = SchoolDay.allWeekDays.filter { it in selectedDays }.ifEmpty { SchoolDay.allWeekDays }
        )
        formatNextTriggerRelativeSummary(tempAlarm, isHebrew)
    }

    val softCardBorder = BorderStroke(1.dp, Color(0xFFDCE8F8))
    val softCardShape = RoundedCornerShape(16.dp)

    Dialog(
        onDismissRequest = {
            AlarmAudioController.stopPreview(context)
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            val maxDialogHeight = (maxHeight * 0.94f).coerceAtLeast(320.dp)
            val isCompactWidth = maxWidth < 360.dp

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFF4F8FE),
                tonalElevation = 4.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(1.dp, Color(0xFFCCE0FA)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .heightIn(max = maxDialogHeight)
                    .testTag("alarm_editor_dialog")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // =========================================================================
                    // 1. COMPACT FIXED HEADER
                    // =========================================================================
                    Surface(
                        color = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("alarm_editor_header")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (alarmToEdit == null) {
                                    if (isHebrew) "⏰ שעון מעורר חדש" else "⏰ New Alarm"
                                } else {
                                    if (isHebrew) "⏰ עריכת שעון מעורר" else "⏰ Edit Alarm"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF15366B),
                                    fontSize = 18.sp
                                ),
                                modifier = Modifier.testTag("alarm_editor_header_title")
                            )

                            IconButton(
                                onClick = {
                                    AlarmAudioController.stopPreview(context)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("close_alarm_editor_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = if (isHebrew) "סגור" else "Close",
                                    tint = Color(0xFF4A6388),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFDCE8F8), thickness = 1.dp)

                    // =========================================================================
                    // 2. VERTICALLY SCROLLABLE SETTINGS CONTENT AREA (weight(1f, fill = false))
                    // =========================================================================
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = if (isCompactWidth) 10.dp else 14.dp, vertical = 10.dp)
                            .testTag("alarm_editor_scroll_content"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // B. MAIN ALARM TIME CARD (Prominent near the top)
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color.White,
                            border = BorderStroke(1.2.dp, Color(0xFFBDD6FA)),
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { showTimePickerModal = true }
                                .testTag("alarm_editor_time_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                            Text(
                                                text = timeFormatted,
                                                style = MaterialTheme.typography.displaySmall.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color(0xFF12366E),
                                                    fontSize = if (isCompactWidth) 34.sp else 40.sp,
                                                    letterSpacing = (-1).sp
                                                ),
                                                modifier = Modifier.testTag("alarm_editor_time_display")
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFFEAF2FE)
                                        ) {
                                            Text(
                                                text = periodBadgeText,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = GoogleBlueDark,
                                                    fontSize = 11.sp
                                                ),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Color(0xFFE8F1FE),
                                        border = BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.25f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AccessTime,
                                                contentDescription = if (isHebrew) "שינוי שעה" else "Change Time",
                                                tint = GoogleBlueDark,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = if (isHebrew) "בחר שעה" else "Pick Time",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = GoogleBlueDark
                                                )
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Compact Next Scheduled Ringing Time Preview Pill
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = PastelGreenBackground,
                                    border = BorderStroke(1.dp, PastelGreenDark.copy(alpha = 0.28f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("alarm_next_ring_preview")
                                ) {
                                    Text(
                                        text = if (isHebrew) "⏰ יצלצל: $previewNextTriggerText" else "⏰ Next ring: $previewNextTriggerText",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = PastelGreenDark,
                                            fontSize = 11.sp
                                        ),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // C. ALARM ASSIGNMENT ("עבור מי השעון?")
                        Surface(
                            shape = softCardShape,
                            color = Color.White,
                            border = softCardBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (isHebrew) "עבור מי השעון?" else "Who is this alarm for?",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1E3A66)
                                    )
                                )

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        val isPersonalSelf = selectedMemberId == currentMember.id
                                        FilterChip(
                                            selected = isPersonalSelf,
                                            onClick = {
                                                isPersonalAlarm = true
                                                selectedMemberId = currentMember.id
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color(0xFFDCEBFE),
                                                selectedLabelColor = Color(0xFF12366E)
                                            ),
                                            label = {
                                                Text(
                                                    text = if (isHebrew) "השעון שלי" else "My Alarm",
                                                    fontWeight = if (isPersonalSelf) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 12.sp
                                                )
                                            },
                                            modifier = Modifier.testTag("alarm_assign_personal")
                                        )
                                    }
                                    if (currentMember.isParent) {
                                        items(childMembers) { child ->
                                            val isSelected = selectedMemberId == child.id
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    isPersonalAlarm = false
                                                    selectedMemberId = child.id
                                                    if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) {
                                                        soundType = AlarmAudioController.SOUND_MELODY_ENERGETIC
                                                    }
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFFDCEBFE),
                                                    selectedLabelColor = Color(0xFF12366E)
                                                ),
                                                label = {
                                                    Text(
                                                        text = "${child.avatarEmoji} ${child.name}",
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = 12.sp
                                                    )
                                                },
                                                modifier = Modifier.testTag("alarm_assign_${child.id}")
                                            )
                                        }
                                    }
                                }

                                if (isTargetRemoteChild) {
                                    val targetChildName = childMembers.find { it.id == selectedMemberId }?.name ?: ""
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFEAF3FF),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isHebrew)
                                                "📲 יסונכרן ויצלצל במכשיר של $targetChildName בלבד (מוגן מכיבוי ע״י הילד/ה)"
                                            else
                                                "📲 Will sync and ring on $targetChildName's device (protected from child edits)",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = GoogleBlueDark,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp
                                            ),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // D. COMPACT ALARM NAME FIELD ("שם השעון המעורר")
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = {
                                Text(
                                    text = if (isHebrew) "שם השעון המעורר" else "Alarm Name",
                                    fontSize = 12.sp
                                )
                            },
                            placeholder = {
                                Text(
                                    text = if (isHebrew) "השכמה לבית הספר" else "School Wake-Up",
                                    fontSize = 12.sp,
                                    color = Color(0xFF8A9BB5)
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = GoogleBlue,
                                unfocusedBorderColor = Color(0xFFD5E3F7)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("alarm_title_input")
                        )

                        // E. COMPACT DAYS OF THE WEEK SELECTOR
                        Surface(
                            shape = softCardShape,
                            color = Color.White,
                            border = softCardBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "ימים בשבוע" else "Days",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E3A66)
                                        ),
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = {
                                                selectedDays = SchoolDay.allWeekDays
                                                    .filterNot { it == SchoolDay.SATURDAY }
                                                    .toSet()
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                text = if (isHebrew) "א׳-ו׳ (לימודים)" else "Sun-Fri",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                selectedDays = SchoolDay.allWeekDays
                                                    .filterNot { it == SchoolDay.FRIDAY || it == SchoolDay.SATURDAY }
                                                    .toSet()
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                text = if (isHebrew) "א׳-ה׳" else "Sun-Thu",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                selectedDays = SchoolDay.allWeekDays.toSet()
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                text = if (isHebrew) "כל השבוע" else "All Week",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    SchoolDay.allWeekDays.forEach { day ->
                                        val isSelected = day in selectedDays
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) GoogleBlue else Color(0xFFEEF4FC),
                                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFFD5E3F7)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    selectedDays = if (isSelected && selectedDays.size > 1) {
                                                        selectedDays - day
                                                    } else {
                                                        selectedDays + day
                                                    }
                                                }
                                                .testTag("alarm_day_${day.name}")
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (isHebrew) day.shortHe else day.getDisplayName(false).take(2),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) Color.White else Color(0xFF355079)
                                                    ),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // F. COMPACT SOUND, MELODY & VOLUME CARD
                        Surface(
                            shape = softCardShape,
                            color = Color.White,
                            border = softCardBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Header + Play/Stop Preview button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isHebrew) "צליל ומנגינת השכמה" else "Alarm Sound",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1E3A66)
                                            )
                                        )
                                        Text(
                                            text = AlarmAudioController.soundDisplayName(soundType, customSoundTitle, isHebrew),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = GoogleBlueDark,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (previewState.isPlaying) {
                                        FilledTonalButton(
                                            onClick = { AlarmAudioController.stopPreview(context) },
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = GoogleRedLight,
                                                contentColor = GoogleRedDark
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .height(30.dp)
                                                .testTag("alarm_stop_preview_button")
                                        ) {
                                            Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isHebrew) "עצור" else "Stop",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        FilledTonalButton(
                                            onClick = {
                                                AlarmAudioController.startPreview(
                                                    context = context,
                                                    soundType = soundType,
                                                    customSoundUri = customSoundUri,
                                                    volumePercent = volumePercent.toInt()
                                                )
                                            },
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = Color(0xFFE5F0FE),
                                                contentColor = GoogleBlueDark
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier
                                                .height(30.dp)
                                                .testTag("alarm_preview_sound_button")
                                        ) {
                                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isHebrew) "השמע צליל" else "Preview",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Horizontal scrollable sound chips (saves vertical space while keeping 1-tap selection)
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(AlarmAudioController.builtInSoundOptions) { option ->
                                        val isSelected = soundType == option.soundType
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                soundType = option.soundType
                                                customFileErrorHe = null
                                                AlarmAudioController.startPreview(
                                                    context = context,
                                                    soundType = option.soundType,
                                                    customSoundUri = "",
                                                    volumePercent = volumePercent.toInt()
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color(0xFFDCEBFE),
                                                selectedLabelColor = Color(0xFF12366E)
                                            ),
                                            label = {
                                                Text(
                                                    text = "${option.emoji} ${if (isHebrew) option.titleHe else option.titleEn}",
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )
                                            },
                                            modifier = Modifier.testTag("alarm_sound_${option.soundType}")
                                        )
                                    }
                                }

                                // Custom audio file selector (for alarms on this device)
                                if (!isTargetRemoteChild) {
                                    OutlinedButton(
                                        onClick = { customAudioLauncher.launch(arrayOf("audio/*")) },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        border = BorderStroke(
                                            width = if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) 1.5.dp else 1.dp,
                                            color = if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) GoogleBlue else Color(0xFFD5E3F7)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(34.dp)
                                            .testTag("alarm_custom_sound_chip")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = GoogleBlue,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (soundType == AlarmAudioController.SOUND_CUSTOM_URI && customSoundTitle.isNotBlank()) {
                                                if (isHebrew) "קובץ נבחר: $customSoundTitle"
                                                else "Selected: $customSoundTitle"
                                            } else {
                                                if (isHebrew) "בחר קובץ שמע / שיר מהמכשיר 🎵"
                                                else "Choose Custom Audio File 🎵"
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Error Banner if custom audio or preview failed
                                val errorText = customFileErrorHe ?: if (isHebrew) previewState.errorMessageHe else previewState.errorMessageEn
                                if (!errorText.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = GoogleRedLight,
                                        border = BorderStroke(1.dp, GoogleRed.copy(alpha = 0.4f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("alarm_audio_error_banner")
                                    ) {
                                        Text(
                                            text = "⚠️ $errorText",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = GoogleRedDark,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                HorizontalDivider(color = Color(0xFFEEF4FC), thickness = 1.dp)

                                // Compact Volume Slider + Test Volume Button
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isHebrew) "עוצמת שמע (${volumePercent.toInt()}%)" else "Volume (${volumePercent.toInt()}%)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E3A66)
                                        )
                                    )

                                    TextButton(
                                        onClick = {
                                            if (previewState.isPlaying) {
                                                AlarmAudioController.stopPreview(context)
                                            } else {
                                                AlarmAudioController.startPreview(
                                                    context = context,
                                                    soundType = soundType,
                                                    customSoundUri = customSoundUri,
                                                    volumePercent = volumePercent.toInt(),
                                                    durationMillis = 5_000L
                                                )
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .height(26.dp)
                                            .testTag("alarm_test_volume_button")
                                    ) {
                                        Icon(
                                            imageVector = if (previewState.isPlaying) Icons.Default.Stop else Icons.Default.VolumeUp,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (previewState.isPlaying) {
                                                if (isHebrew) "עצור בדיקה" else "Stop"
                                            } else {
                                                if (isHebrew) "בדוק עוצמה" else "Test Volume"
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Slider(
                                    value = volumePercent,
                                    onValueChange = { newVol ->
                                        volumePercent = newVol
                                        AlarmAudioController.updateActivePreviewVolume(context, newVol.toInt())
                                    },
                                    valueRange = 10f..100f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp)
                                        .testTag("alarm_volume_slider")
                                )
                            }
                        }

                        // G. COMPACT SNOOZE, VIBRATION & DEVICE CLOCK CARD
                        Surface(
                            shape = softCardShape,
                            color = Color.White,
                            border = softCardBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Snooze row
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "זמן נודניק (דק׳):" else "Snooze (min):",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E3A66)
                                        ),
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(5, 10, 15).forEach { mins ->
                                            val isSelected = snoozeDurationMinutes == mins
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = { snoozeDurationMinutes = mins },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = Color(0xFFDCEBFE),
                                                    selectedLabelColor = Color(0xFF12366E)
                                                ),
                                                label = {
                                                    Text(
                                                        text = "$mins",
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        fontSize = 12.sp
                                                    )
                                                },
                                                modifier = Modifier.testTag("alarm_snooze_${mins}")
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = Color(0xFFEEF4FC), thickness = 1.dp)

                                // Vibration row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isHebrew) "רטט בעת צלצול" else "Vibrate on alarm",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF1E3A66)
                                        )
                                    )
                                    Switch(
                                        checked = vibrateEnabled,
                                        onCheckedChange = { vibrateEnabled = it },
                                        modifier = Modifier.testTag("alarm_vibrate_switch")
                                    )
                                }

                                if (!isTargetRemoteChild) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { alsoSetDeviceClock = !alsoSetDeviceClock }
                                    ) {
                                        Checkbox(
                                            checked = alsoSetDeviceClock,
                                            onCheckedChange = { alsoSetDeviceClock = it },
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isHebrew) "פתח גם את אפליקציית השעון המובנית במכשיר" else "Also open built-in Android Clock app",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = Color(0xFF4A6388)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // =========================================================================
                    // 3. PERMANENTLY VISIBLE BOTTOM ACTION BAR ("ביטול" & "שמור שעון")
                    // =========================================================================
                    HorizontalDivider(color = Color(0xFFDCE8F8), thickness = 1.dp)

                    Surface(
                        color = Color.White.copy(alpha = 0.96f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("alarm_editor_bottom_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    AlarmAudioController.stopPreview(context)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFBFD4F2)),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("cancel_alarm_editor_button")
                            ) {
                                Text(
                                    text = if (isHebrew) "ביטול" else "Cancel",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF355079)
                                )
                            }

                            Button(
                                onClick = {
                                    val hr = parsedTime.first
                                    val min = parsedTime.second
                                    val effectiveTargetId = if (!currentMember.isParent) {
                                        currentMember.id
                                    } else {
                                        selectedMemberId.ifBlank { currentMember.id }
                                    }
                                    val assignedMember = members.find { it.id == effectiveTargetId }
                                    val preservedCreatorId = alarmToEdit?.createdByMemberId?.takeIf { it.isNotBlank() } ?: currentMember.id
                                    val preservedCreatorName = alarmToEdit?.createdByMemberName?.takeIf { it.isNotBlank() } ?: currentMember.name
                                    val preservedCreatorRole = alarmToEdit?.createdByRole?.takeIf { it.isNotBlank() }
                                        ?: currentMember.role.toFirestoreValue()

                                    val ringtoneEmoji = AlarmAudioController.builtInSoundOptions
                                        .find { it.soundType == soundType }?.emoji ?: "⏰"

                                    val alarm = FamilyAlarm(
                                        id = alarmToEdit?.id ?: "alarm_${System.currentTimeMillis()}",
                                        title = title.trim().ifBlank { if (isHebrew) "בוקר טוב!" else "Good Morning!" },
                                        hour = hr,
                                        minute = min,
                                        assignedMemberId = effectiveTargetId,
                                        assignedToMemberId = effectiveTargetId,
                                        assignedMemberName = assignedMember?.name ?: currentMember.name,
                                        daysOfWeek = SchoolDay.values().filter { it in selectedDays },
                                        isEnabled = true,
                                        ringtoneEmoji = ringtoneEmoji,
                                        createdByMemberId = preservedCreatorId,
                                        createdByMemberName = preservedCreatorName,
                                        createdByRole = preservedCreatorRole,
                                        isPersonal = effectiveTargetId == preservedCreatorId,
                                        soundType = soundType,
                                        customSoundUri = if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) customSoundUri else "",
                                        customSoundTitle = if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) customSoundTitle else "",
                                        vibrateEnabled = vibrateEnabled,
                                        snoozeDurationMinutes = snoozeDurationMinutes,
                                        volumePercent = volumePercent.toInt().coerceIn(10, 100)
                                    )
                                    onSave(alarm, alsoSetDeviceClock)
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GoogleBlue,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1.35f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("save_alarm_confirm_button")
                            ) {
                                Text(
                                    text = if (isHebrew) "שמור שעון" else "Save Alarm",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTimePickerModal) {
        FamilyTimeSelectionDialog(
            initialTime = timeFormatted,
            title = if (isHebrew) "בחירת שעת השכמה" else "Select Alarm Time",
            isHebrew = isHebrew,
            onDismiss = { showTimePickerModal = false },
            onConfirm = { chosenTime ->
                timeFormatted = TimeFormatUtils.normalize24HourString(chosenTime, timeFormatted)
                showTimePickerModal = false
            }
        )
    }
}

private fun openAndroidSystemClock(
    context: Context,
    alarm: FamilyAlarm,
    isHebrew: Boolean,
    onShowToast: (String) -> Unit
) {
    try {
        val calendarDays = ArrayList<Int>()
        alarm.daysOfWeek.forEach { schoolDay ->
            when (schoolDay) {
                SchoolDay.SUNDAY -> calendarDays.add(Calendar.SUNDAY)
                SchoolDay.MONDAY -> calendarDays.add(Calendar.MONDAY)
                SchoolDay.TUESDAY -> calendarDays.add(Calendar.TUESDAY)
                SchoolDay.WEDNESDAY -> calendarDays.add(Calendar.WEDNESDAY)
                SchoolDay.THURSDAY -> calendarDays.add(Calendar.THURSDAY)
                SchoolDay.FRIDAY -> calendarDays.add(Calendar.FRIDAY)
                SchoolDay.SATURDAY -> calendarDays.add(Calendar.SATURDAY)
            }
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, alarm.title)
            putExtra(AlarmClock.EXTRA_VIBRATE, alarm.vibrateEnabled)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (calendarDays.isNotEmpty()) {
                putExtra(AlarmClock.EXTRA_DAYS, calendarDays)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        onShowToast(
            if (isHebrew) "השעון המעורר תוזמן ישירות במערכת FamilyTasks (${alarm.formattedTime24h}) ⏰"
            else "Alarm scheduled directly in FamilyTasks (${alarm.formattedTime24h}) ⏰"
        )
    }
}

private fun formatNextTriggerRelativeSummary(
    alarm: FamilyAlarm,
    isHebrew: Boolean
): String {
    val nowMillis = System.currentTimeMillis()
    val nextMillis = FamilyAlarmScheduler.computeNextTriggerAtMillis(alarm, nowMillis)
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val targetCal = Calendar.getInstance().apply { timeInMillis = nextMillis }

    val diffMinutes = ((nextMillis - nowMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
    val sameDay = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)

    val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = tomorrowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
        tomorrowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)

    val dayNameHe = when (targetCal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> "יום ראשון"
        Calendar.MONDAY -> "יום שני"
        Calendar.TUESDAY -> "יום שלישי"
        Calendar.WEDNESDAY -> "יום רביעי"
        Calendar.THURSDAY -> "יום חמישי"
        Calendar.FRIDAY -> "יום שישי"
        Calendar.SATURDAY -> "יום שבת"
        else -> ""
    }

    return if (isHebrew) {
        when {
            sameDay && diffMinutes <= 60L -> "היום ב-${alarm.formattedTime24h} (בעוד $diffMinutes דק׳)"
            sameDay -> "היום ב-${alarm.formattedTime24h}"
            isTomorrow -> "מחר ($dayNameHe) ב-${alarm.formattedTime24h}"
            else -> "$dayNameHe ב-${alarm.formattedTime24h}"
        }
    } else {
        when {
            sameDay && diffMinutes <= 60L -> "Today at ${alarm.formattedTime24h} (in ${diffMinutes}m)"
            sameDay -> "Today at ${alarm.formattedTime24h}"
            isTomorrow -> "Tomorrow at ${alarm.formattedTime24h}"
            else -> "At ${alarm.formattedTime24h}"
        }
    }
}

@Composable
private fun DeveloperAlarmDiagnosticsCard(
    diagnosticState: AlarmDiagnosticState,
    isHebrew: Boolean,
    currentMemberName: String,
    onSchedule15sTest: () -> Unit,
    onStopRinging: () -> Unit,
    onReset: () -> Unit
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(diagnosticState.isScheduled, diagnosticState.triggerAtMillis) {
        while (diagnosticState.isScheduled && !diagnosticState.receiverTriggered) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(500L)
        }
        nowMillis = System.currentTimeMillis()
    }

    val remainingSeconds = if (diagnosticState.isScheduled && diagnosticState.triggerAtMillis > nowMillis) {
        ((diagnosticState.triggerAtMillis - nowMillis + 999L) / 1000L).coerceAtLeast(0L)
    } else {
        0L
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("developer_alarm_diagnostics_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isHebrew) "כלי אבחון מפתחים (Developer Diagnostics)" else "Developer Alarm Diagnostics",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = GoogleBlueDark
                    )
                )
                if (diagnosticState.isScheduled || diagnosticState.errorMessage.isNotBlank()) {
                    TextButton(
                        onClick = onReset,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = if (isHebrew) "איפוס" else "Reset",
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSchedule15sTest,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoogleBlueDark,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("diagnostic_alarm_15s_button")
                ) {
                    Text(
                        text = "בדיקת צלצול בעוד 15 שניות",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (diagnosticState.audioPlaybackStarted || diagnosticState.ringingServiceStarted) {
                    FilledTonalButton(
                        onClick = onStopRinging,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GoogleRedLight,
                            contentColor = GoogleRedDark
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("diagnostic_stop_ringing_button")
                    ) {
                        Text(
                            text = if (isHebrew) "עצור צלצול" else "Stop",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Diagnostic pipeline checklist
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF3F6FC), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                val permStatusText = if (diagnosticState.exactAlarmPermissionGranted) {
                    "✓ הרשאת התראה מדויקת: מאושרת (${diagnosticState.schedulingMethod.ifBlank { "EXACT" }})"
                } else {
                    "⚠️ הרשאת התראה מדויקת: לא זמינה (שימוש ב-AlarmClock / Window)"
                }
                Text(
                    text = permStatusText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("diag_status_permission")
                )

                val scheduledText = if (diagnosticState.isScheduled) {
                    val countNote = if (!diagnosticState.receiverTriggered && remainingSeconds > 0L) {
                        " (בעוד ${remainingSeconds} שניות)"
                    } else ""
                    "✓ 1. נקבע ב-AlarmManager: כן$countNote"
                } else {
                    "○ 1. נקבע ב-AlarmManager: טרם הופעל"
                }
                Text(
                    text = scheduledText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.isScheduled) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.isScheduled) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("diag_status_scheduled")
                )

                val receiverText = if (diagnosticState.receiverTriggered) {
                    "✓ 2. BroadcastReceiver הופעל (FamilyAlarmReceiver)"
                } else {
                    "○ 2. BroadcastReceiver הופעל: ממתין..."
                }
                Text(
                    text = receiverText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.receiverTriggered) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.receiverTriggered) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("diag_status_receiver")
                )

                val serviceText = if (diagnosticState.ringingServiceStarted) {
                    "✓ 3. שירות צלצול הופעל (FamilyAlarmRingingService Foreground)"
                } else {
                    "○ 3. שירות צלצול הופעל: ממתין..."
                }
                Text(
                    text = serviceText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.ringingServiceStarted) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.ringingServiceStarted) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("diag_status_service")
                )

                val audioText = if (diagnosticState.audioPlaybackStarted) {
                    val engine = diagnosticState.audioEngineDetails.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
                    "✓ 4. ניגון שמע הופעל (USAGE_ALARM)$engine"
                } else {
                    "○ 4. ניגון שמע הופעל: ממתין..."
                }
                Text(
                    text = audioText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.audioPlaybackStarted) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.audioPlaybackStarted) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("diag_status_audio")
                )

                if (diagnosticState.errorMessage.isNotBlank()) {
                    Text(
                        text = "❌ שגיאה: ${diagnosticState.errorMessage}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoogleRedDark
                        ),
                        modifier = Modifier.testTag("diag_status_error")
                    )
                }
            }
        }
    }
}
