package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.notifications.FamilyAlarmReceiver
import com.example.notifications.FamilyAlarmScheduler
import com.example.ui.components.TimePickerField
import com.example.ui.theme.*
import java.util.Calendar

private enum class TimetableSubSection {
    SCHEDULE,
    ALARMS
}

@Composable
fun TimetableScreen(
    periods: List<SchedulePeriod>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    selectedChildId: String,
    timetableViewMode: TimetableViewMode,
    isHebrew: Boolean,
    familyAlarms: List<FamilyAlarm> = emptyList(),
    onSelectChild: (String) -> Unit,
    onViewModeChange: (TimetableViewMode) -> Unit,
    onAddPeriod: () -> Unit,
    onEditPeriod: (SchedulePeriod) -> Unit,
    onDeletePeriod: (String) -> Unit,
    onTestAlert: (SchedulePeriod, String) -> Unit,
    onSaveAlarm: (FamilyAlarm) -> Unit = {},
    onToggleAlarm: (String) -> Unit = {},
    onDeleteAlarm: (String) -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val kids = members.filter { it.role == UserRole.CHILD }
    var selectedDay by remember { mutableStateOf(SchoolDay.SUNDAY) }
    var activeSection by remember { mutableStateOf(TimetableSubSection.SCHEDULE) }
    var alarmToEdit by remember { mutableStateOf<FamilyAlarm?>(null) }
    var showAddAlarmDialog by remember { mutableStateOf(false) }

    val activeKid = members.find { it.id == selectedChildId } ?: kids.firstOrNull() ?: members.first()
    val kidPeriods = periods.filter { it.childId == activeKid.id }
    val dayPeriods = kidPeriods
        .filter { it.day == selectedDay }
        .sortedBy { it.periodIndex }

    Scaffold(
        floatingActionButton = {
            if (activeSection == TimetableSubSection.ALARMS) {
                Surface(
                    onClick = {
                        alarmToEdit = null
                        showAddAlarmDialog = true
                    },
                    shape = RoundedCornerShape(24.dp),
                    color = VibrantActionBlue,
                    contentColor = Color.White,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .height(46.dp)
                        .testTag("add_alarm_fab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.AlarmAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = if (isHebrew) "שעון מעורר חדש" else "Add Alarm",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (currentMember.isParent) {
                Surface(
                    onClick = onAddPeriod,
                    shape = RoundedCornerShape(24.dp),
                    color = VibrantActionBlue,
                    contentColor = Color.White,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .height(46.dp)
                        .testTag("add_period_fab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = if (isHebrew) "הוסף שיעור" else "Add Period",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
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
        ) {
            // Quick Screen Help Guide
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                com.example.ui.components.ScreenHelpBanner(
                    title = if (isHebrew) "מערכת שעות ושעון מעורר משפחתי" else "School Schedule & Family Alarms",
                    tips = if (isHebrew) listOf(
                        "בחירת תלמיד/ה: בחרו בלשוניות למעלה בין הילדים לצפייה במערכת האישית (א׳-ו׳).",
                        "מעבר תצוגות: צפייה יומית, טבלה שבועית או שעון מעורר משפחתי משותף.",
                        "שעון מעורר: הגדירו שעוני השכמה והסעה לבית הספר וסנכרנו לשעון המכשיר.",
                        "התראות שיעור: כל שיעור שולח התראת הכנה לפני תחילתו."
                    ) else listOf(
                        "Select Child: Tap child chips on top to view their schedule (Sun-Fri).",
                        "Views: Toggle between Daily view, Weekly grid, and Family Alarm Clock.",
                        "Family Alarms: Set wake-up & school alarms and sync to your device clock.",
                        "Alerts: Receive notifications prior to school periods."
                    ),
                    isHebrew = isHebrew
                )
            }

            // Top Switcher: Daily / Weekly Grid / Family Alarm Clock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        TimetableViewPill(
                            title = if (isHebrew) "יומי" else "Daily",
                            isSelected = activeSection == TimetableSubSection.SCHEDULE &&
                                timetableViewMode == TimetableViewMode.DAILY_LIST,
                            onClick = {
                                activeSection = TimetableSubSection.SCHEDULE
                                onViewModeChange(TimetableViewMode.DAILY_LIST)
                            }
                        )
                        TimetableViewPill(
                            title = if (isHebrew) "טבלה שבועית" else "Weekly Grid",
                            isSelected = activeSection == TimetableSubSection.SCHEDULE &&
                                timetableViewMode == TimetableViewMode.WEEKLY_TABLE,
                            onClick = {
                                activeSection = TimetableSubSection.SCHEDULE
                                onViewModeChange(TimetableViewMode.WEEKLY_TABLE)
                            }
                        )
                        TimetableViewPill(
                            title = if (isHebrew) "⏰ שעון מעורר" else "⏰ Alarms",
                            isSelected = activeSection == TimetableSubSection.ALARMS,
                            onClick = {
                                activeSection = TimetableSubSection.ALARMS
                            }
                        )
                    }
                }

                if (currentMember.isAdmin && activeSection == TimetableSubSection.SCHEDULE) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = GoogleBlueLight
                    ) {
                        Text(
                            text = if (isHebrew) "עריכת הורה ✏️" else "Parent Edit ✏️",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = GoogleBlueDark,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (activeSection == TimetableSubSection.ALARMS) {
                FamilyAlarmsSection(
                    alarms = familyAlarms,
                    members = members,
                    currentMember = currentMember,
                    isHebrew = isHebrew,
                    onToggleAlarm = onToggleAlarm,
                    onEditAlarm = { alarm ->
                        alarmToEdit = alarm
                        showAddAlarmDialog = true
                    },
                    onDeleteAlarm = onDeleteAlarm,
                    onSetSystemAlarm = { alarm ->
                        setAndroidSystemAlarm(context, alarm, isHebrew, onShowToast)
                    },
                    onShowToast = onShowToast
                )
            } else {
                // Kids Tabs Row
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(kids) { kid ->
                        val isSelected = kid.id == activeKid.id
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { onSelectChild(kid.id) }
                                .testTag("child_tab_${kid.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(text = kid.avatarEmoji, fontSize = 16.sp)
                                Text(
                                    text = kid.name + (if (kid.schoolGrade != null) " (${kid.schoolGrade})" else ""),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (timetableViewMode == TimetableViewMode.WEEKLY_TABLE) {
                    WeeklyTimetableTableView(
                        kidPeriods = kidPeriods,
                        childName = activeKid.name,
                        isAdmin = currentMember.isAdmin,
                        isHebrew = isHebrew,
                        onEditPeriod = onEditPeriod,
                        onDeletePeriod = onDeletePeriod,
                        onTestAlert = onTestAlert
                    )
                } else {
                    // Days of the Week Tabs (Sunday through Friday)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SchoolDay.values().forEach { day ->
                            val isDaySelected = day == selectedDay
                            val dayPeriodsCount = kidPeriods.count { it.day == day }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isDaySelected) GoogleBlueLight else MaterialTheme.colorScheme.surface,
                                border = if (isDaySelected) BorderStroke(1.5.dp, GoogleBlue) else BorderStroke(0.5.dp, OutlineLight),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { selectedDay = day }
                                    .testTag("day_tab_${day.name}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (isHebrew) day.shortHe else day.getDisplayName(false).take(3),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isDaySelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isDaySelected) GoogleBlueDark else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        maxLines = 1
                                    )
                                    if (dayPeriodsCount > 0) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "$dayPeriodsCount",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDaySelected) GoogleBlueDark else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (dayPeriods.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "🎒", fontSize = 48.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (isHebrew)
                                        "אין שיעורים במערכת ליום ${selectedDay.getDisplayName(isHebrew)}"
                                    else
                                        "No periods on ${selectedDay.getDisplayName(isHebrew)}",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                if (currentMember.isParent) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (isHebrew) "לחצו על + למטה להוספת שיעור" else "Tap + below to add a period",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 110.dp, top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(dayPeriods, key = { it.id }) { period ->
                                PeriodCardItem(
                                    period = period,
                                    childName = activeKid.name,
                                    isAdmin = currentMember.isAdmin,
                                    isHebrew = isHebrew,
                                    onEdit = { onEditPeriod(period) },
                                    onDelete = { onDeletePeriod(period.id) },
                                    onTestAlert = { onTestAlert(period, activeKid.name) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddAlarmDialog) {
        AddOrEditFamilyAlarmDialog(
            alarmToEdit = alarmToEdit,
            members = members,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = {
                showAddAlarmDialog = false
                alarmToEdit = null
            },
            onSave = { savedAlarm, alsoSetSystemClock ->
                onSaveAlarm(savedAlarm)
                val isRemoteChildTarget = savedAlarm.assignedMemberId.isNotBlank() &&
                    savedAlarm.assignedMemberId != currentMember.id
                if (alsoSetSystemClock && !isRemoteChildTarget) {
                    setAndroidSystemAlarm(context, savedAlarm, isHebrew, onShowToast)
                } else {
                    val toastMsg = if (isRemoteChildTarget) {
                        if (isHebrew) {
                            "השעון המעורר נשמר בענן • ממתין לאישור ותזמון אוטומטי במכשיר של ${savedAlarm.assignedMemberName} ⏳"
                        } else {
                            "Alarm synced to cloud • Pending automatic scheduling on ${savedAlarm.assignedMemberName}'s device ⏳"
                        }
                    } else {
                        if (isHebrew) {
                            "השעון המעורר תוזמן במכשיר זה לשעה ${savedAlarm.formattedTime24h} ⏰"
                        } else {
                            "Alarm scheduled locally on this device for ${savedAlarm.formattedTime24h} ⏰"
                        }
                    }
                    onShowToast(toastMsg)
                }
                showAddAlarmDialog = false
                alarmToEdit = null
            }
        )
    }
}

private fun setAndroidSystemAlarm(
    context: android.content.Context,
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

@Composable
private fun FamilyAlarmsSection(
    alarms: List<FamilyAlarm>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onToggleAlarm: (String) -> Unit,
    onEditAlarm: (FamilyAlarm) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onSetSystemAlarm: (FamilyAlarm) -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    val hasExactPermission = remember { FamilyAlarmScheduler.canScheduleExactAlarms(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .testTag("family_alarms_section")
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = GoogleBlueLight.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                                text = if (isHebrew) "שעונים מעוררים ותזכורות בוקר" else "Family Wake-Up & School Alarms",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlueDark
                                )
                            )
                            Text(
                                text = if (isHebrew)
                                    "תזמון מקומי מדויק ב-Android + סנכרון שעוני הורה למכשיר הילד/ה עם אישור מכשיר"
                                else
                                    "Local Android AlarmManager scheduling + parent-to-child cloud sync with device confirmation",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            FamilyAlarmReceiver.stopActiveRinging(context)
                            onShowToast(
                                if (isHebrew) "כל צלצול פעיל הופסק" else "Stopped active alarm ringing"
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("stop_active_alarm_ringing_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlarmOff,
                            contentDescription = null,
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
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (alarms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "⏰", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isHebrew) "עדיין אין שעונים מעוררים מוגדרים" else "No family alarms configured yet",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isHebrew) "לחצו על 'שעון מעורר חדש' למטה להוספה" else "Tap 'Add Alarm' below to create one",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(alarms, key = { it.id }) { alarm ->
                    val assignedMember = members.find { it.id == alarm.assignedMemberId }
                    FamilyAlarmCard(
                        alarm = alarm,
                        assignedMember = assignedMember,
                        currentMember = currentMember,
                        isHebrew = isHebrew,
                        onToggle = { onToggleAlarm(alarm.id) },
                        onEdit = { onEditAlarm(alarm) },
                        onDelete = { onDeleteAlarm(alarm.id) },
                        onSetSystemAlarm = { onSetSystemAlarm(alarm) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FamilyAlarmCard(
    alarm: FamilyAlarm,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetSystemAlarm: () -> Unit
) {
    val isPendingTargetDevice = alarm.isEnabled && (
        alarm.scheduleStatus == "PENDING_TARGET_DEVICE" ||
            (alarm.assignedMemberId.isNotBlank() &&
                alarm.assignedMemberId != currentMember.id &&
                alarm.acknowledgedByMemberId != alarm.assignedMemberId)
    )
    val isExactPermNeeded = alarm.isEnabled && alarm.scheduleStatus == "EXACT_PERMISSION_NEEDED"

    val (statusLabel, statusBg, statusFg) = when {
        !alarm.isEnabled -> Triple(
            if (isHebrew) "כבוי" else "Disabled",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        isPendingTargetDevice -> Triple(
            if (isHebrew) {
                "⏳ ממתין לסנכרון במכשיר של ${assignedMember?.name ?: alarm.assignedMemberName.ifBlank { "הילד/ה" }}"
            } else {
                "⏳ Pending target device confirmation (${assignedMember?.name ?: alarm.assignedMemberName})"
            },
            PastelYellowBackground,
            PastelYellowDark
        )
        isExactPermNeeded -> Triple(
            if (isHebrew) "⚠️ מתוזמן (מומלץ לאשר הרשאת דיוק)" else "⚠️ Scheduled (exact permission recommended)",
            PastelYellowBackground,
            PastelYellowDark
        )
        else -> Triple(
            if (isHebrew) {
                val dev = alarm.acknowledgedByDeviceName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "✓ מתוזמן במכשיר$dev"
            } else {
                val dev = alarm.acknowledgedByDeviceName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "✓ Scheduled on device$dev"
            },
            PastelGreenBackground,
            PastelGreenDark
        )
    }

    val soundLabel = when (alarm.soundType) {
        "CUSTOM_URI" -> alarm.customSoundTitle.ifBlank { if (isHebrew) "שמע מותאם אישית 🎵" else "Custom Audio 🎵" }
        "SYSTEM_NOTIFICATION" -> if (isHebrew) "צליל התראה 🔔" else "Notification Sound 🔔"
        "SYSTEM_RINGTONE" -> if (isHebrew) "צלצול טלפון 📞" else "Ringtone 📞"
        else -> if (isHebrew) "צליל שעון מעורר ⏰" else "System Alarm ⏰"
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
            .clickable { onEdit() }
            .testTag("family_alarm_card_${alarm.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (alarm.isEnabled) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = alarm.ringtoneEmoji, fontSize = 22.sp)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = alarm.formattedTime24h,
                            style = MaterialTheme.typography.headlineSmall.copy(
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.testTag("toggle_alarm_${alarm.id}")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Device Acknowledgement & Scheduling Status Pill
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val daysText = if (alarm.daysOfWeek.size >= 6) {
                    if (isHebrew) "כל ימי הלימודים (א׳-ו׳)" else "School days (Sun-Fri)"
                } else {
                    alarm.daysOfWeek.joinToString(", ") {
                        if (isHebrew) it.shortHe else it.getDisplayName(false).take(3)
                    }
                }
                val memberBadge = when {
                    alarm.isPersonal && alarm.assignedMemberId == currentMember.id ->
                        if (isHebrew) "שעון אישי (${currentMember.name})" else "Personal (${currentMember.name})"
                    assignedMember != null -> "${assignedMember.avatarEmoji} ${assignedMember.name}"
                    alarm.assignedMemberName.isNotBlank() -> alarm.assignedMemberName
                    else -> if (isHebrew) "כל המשפחה 👨‍👩‍👧‍👦" else "Whole Family 👨‍👩‍👧‍👦"
                }

                Text(
                    text = "$memberBadge • $daysText",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSetSystemAlarm,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("set_device_alarm_${alarm.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlarmOn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isHebrew) "שעון מערכת" else "System Clock",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("delete_alarm_${alarm.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = if (isHebrew) "מחיקה" else "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddOrEditFamilyAlarmDialog(
    alarmToEdit: FamilyAlarm?,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSave: (FamilyAlarm, Boolean) -> Unit
) {
    val context = LocalContext.current

    var title by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.title ?: if (isHebrew) "השכמה לבית הספר ☀️" else "School Wake-Up ☀️")
    }
    var timeFormatted by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.formattedTime24h ?: "07:00")
    }
    var selectedMemberId by remember(alarmToEdit) {
        mutableStateOf(
            alarmToEdit?.assignedMemberId
                ?: if (!currentMember.isParent) currentMember.id else ""
        )
    }
    var isPersonalAlarm by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.isPersonal ?: !currentMember.isParent)
    }
    var selectedDays by remember(alarmToEdit) {
        mutableStateOf(
            alarmToEdit?.daysOfWeek?.toSet() ?: setOf(
                SchoolDay.SUNDAY,
                SchoolDay.MONDAY,
                SchoolDay.TUESDAY,
                SchoolDay.WEDNESDAY,
                SchoolDay.THURSDAY,
                SchoolDay.FRIDAY
            )
        )
    }
    var soundType by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.soundType ?: "SYSTEM_ALARM")
    }
    var customSoundUri by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.customSoundUri.orEmpty())
    }
    var customSoundTitle by remember(alarmToEdit) {
        mutableStateOf(alarmToEdit?.customSoundTitle.orEmpty())
    }
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

    val customAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            customSoundUri = uri.toString()
            customSoundTitle = uri.lastPathSegment?.substringAfterLast('/') ?: "Custom Audio"
            soundType = "CUSTOM_URI"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = if (alarmToEdit == null) {
                    if (isHebrew) "שעון מעורר משפחתי / אישי חדש ⏰" else "New Family / Personal Alarm ⏰"
                } else {
                    if (isHebrew) "עריכת שעון מעורר ⏰" else "Edit Family Alarm ⏰"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isHebrew) "תיאור השעון המעורר" else "Alarm Label") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alarm_title_input")
                )

                TimePickerField(
                    label = if (isHebrew) "שעת צלצול (24 שעות)" else "Alarm Time (24h)",
                    time = timeFormatted,
                    isHebrew = isHebrew,
                    onTimeSelected = { timeFormatted = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (isHebrew) "ימים בשבוע:" else "Days of Week:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SchoolDay.values().forEach { day ->
                        val isSelected = day in selectedDays
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedDays = if (isSelected && selectedDays.size > 1) {
                                        selectedDays - day
                                    } else {
                                        selectedDays + day
                                    }
                                }
                        ) {
                            Text(
                                text = if (isHebrew) day.shortHe else day.getDisplayName(false).take(2),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Text(
                    text = if (isHebrew) "שיוך (אישי / ילד / כל המשפחה):" else "Target Member:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        val isPersonalSelf = isPersonalAlarm && selectedMemberId == currentMember.id
                        FilterChip(
                            selected = isPersonalSelf,
                            onClick = {
                                isPersonalAlarm = true
                                selectedMemberId = currentMember.id
                            },
                            label = { Text(if (isHebrew) "שעון אישי שלי" else "My Personal Alarm") },
                            modifier = Modifier.testTag("alarm_assign_personal")
                        )
                    }
                    if (currentMember.isParent) {
                        item {
                            val isAll = !isPersonalAlarm && selectedMemberId.isBlank()
                            FilterChip(
                                selected = isAll,
                                onClick = {
                                    isPersonalAlarm = false
                                    selectedMemberId = ""
                                },
                                label = { Text(if (isHebrew) "כל המשפחה" else "Whole Family") },
                                modifier = Modifier.testTag("alarm_assign_all")
                            )
                        }
                        items(members) { member ->
                            val isSelected = !isPersonalAlarm && selectedMemberId == member.id
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    isPersonalAlarm = false
                                    selectedMemberId = member.id
                                },
                                label = { Text("${member.avatarEmoji} ${member.name}") },
                                modifier = Modifier.testTag("alarm_assign_${member.id}")
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Sound Type & Custom Audio Picker
                Text(
                    text = if (isHebrew) "צליל שעון מעורר:" else "Alarm Sound:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = soundType == "SYSTEM_ALARM",
                            onClick = { soundType = "SYSTEM_ALARM" },
                            label = { Text(if (isHebrew) "צליל שעון ⏰" else "Alarm ⏰") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = soundType == "SYSTEM_NOTIFICATION",
                            onClick = { soundType = "SYSTEM_NOTIFICATION" },
                            label = { Text(if (isHebrew) "התראה 🔔" else "Alert 🔔") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = soundType == "SYSTEM_RINGTONE",
                            onClick = { soundType = "SYSTEM_RINGTONE" },
                            label = { Text(if (isHebrew) "צלצול 📞" else "Ringtone 📞") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = soundType == "CUSTOM_URI",
                            onClick = { customAudioLauncher.launch(arrayOf("audio/*")) },
                            label = {
                                Text(
                                    if (soundType == "CUSTOM_URI" && customSoundTitle.isNotBlank()) {
                                        "🎵 $customSoundTitle"
                                    } else {
                                        if (isHebrew) "קובץ שמע מותאם 🎵" else "Custom Audio 🎵"
                                    }
                                )
                            },
                            modifier = Modifier.testTag("alarm_custom_sound_chip")
                        )
                    }
                }

                // Volume Slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isHebrew) "עוצמת שמע:" else "Alarm Volume:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${volumePercent.toInt()}%",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = GoogleBlue
                            )
                        )
                    }
                    Slider(
                        value = volumePercent,
                        onValueChange = { volumePercent = it },
                        valueRange = 10f..100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("alarm_volume_slider")
                    )
                }

                // Snooze Duration Selector & Vibration Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isHebrew) "זמן נודניק (דק׳):" else "Snooze (min):",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15).forEach { mins ->
                            FilterChip(
                                selected = snoozeDurationMinutes == mins,
                                onClick = { snoozeDurationMinutes = mins },
                                label = { Text("$mins") },
                                modifier = Modifier.testTag("alarm_snooze_${mins}")
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isHebrew) "רטט בעת צלצול" else "Vibrate on alarm",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Switch(
                        checked = vibrateEnabled,
                        onCheckedChange = { vibrateEnabled = it },
                        modifier = Modifier.testTag("alarm_vibrate_switch")
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { alsoSetDeviceClock = !alsoSetDeviceClock }
                ) {
                    Checkbox(
                        checked = alsoSetDeviceClock,
                        onCheckedChange = { alsoSetDeviceClock = it }
                    )
                    Text(
                        text = if (isHebrew) "פתח גם את אפליקציית השעון המובנית במכשיר" else "Also open built-in Android Clock app",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parts = timeFormatted.split(":")
                    val hr = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                    val min = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val assignedMember = members.find { it.id == selectedMemberId }
                    val alarm = FamilyAlarm(
                        id = alarmToEdit?.id ?: "alarm_${System.currentTimeMillis()}",
                        title = title.trim().ifBlank { if (isHebrew) "שעון מעורר" else "Alarm" },
                        hour = hr,
                        minute = min,
                        assignedMemberId = selectedMemberId,
                        assignedMemberName = assignedMember?.name.orEmpty(),
                        daysOfWeek = SchoolDay.values().filter { it in selectedDays },
                        isEnabled = true,
                        createdByMemberId = currentMember.id,
                        createdByMemberName = currentMember.name,
                        isPersonal = isPersonalAlarm,
                        soundType = soundType,
                        customSoundUri = customSoundUri,
                        customSoundTitle = customSoundTitle,
                        vibrateEnabled = vibrateEnabled,
                        snoozeDurationMinutes = snoozeDurationMinutes,
                        volumePercent = volumePercent.toInt().coerceIn(10, 100)
                    )
                    onSave(alarm, alsoSetDeviceClock)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("save_alarm_confirm_button")
            ) {
                Text(if (isHebrew) "שמור ותזמן" else "Save & Schedule", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isHebrew) "ביטול" else "Cancel")
            }
        }
    )
}

@Composable
fun TimetableViewPill(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) GoogleBlue else Color.Transparent,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun WeeklyTimetableTableView(
    kidPeriods: List<SchedulePeriod>,
    childName: String,
    isAdmin: Boolean,
    isHebrew: Boolean,
    onEditPeriod: (SchedulePeriod) -> Unit,
    onDeletePeriod: (String) -> Unit,
    onTestAlert: (SchedulePeriod, String) -> Unit
) {
    val schoolDays = SchoolDay.values().toList()
    val maxPeriod = maxOf(6, kidPeriods.maxOfOrNull { it.periodIndex } ?: 6)
    val periodIndices = (1..maxPeriod).toList()
    val horizontalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text(
            text = if (isHebrew) "לוח שעות שבועי (א׳-ו׳) - $childName" else "Weekly Timetable (Sun-Fri) - $childName",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = GoogleBlue)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Horizontally scrollable table matrix
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            border = BorderStroke(1.dp, OutlineLight),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .horizontalScroll(horizontalScrollState)
            ) {
                // Header Row (Days of the week)
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isHebrew) "שעה" else "#",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    schoolDays.forEach { day ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = GoogleBlueLight,
                            modifier = Modifier
                                .width(110.dp)
                                .padding(horizontal = 2.dp)
                        ) {
                            Text(
                                text = day.getDisplayName(isHebrew),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlueDark
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = OutlineLight.copy(alpha = 0.5f))

                // Rows for Period 1 to maxPeriod
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(periodIndices) { periodIndex ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            // Period Index column
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$periodIndex",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleBlue
                                    )
                                )
                            }

                            // Day cells
                            schoolDays.forEach { day ->
                                val period = kidPeriods.find { it.day == day && it.periodIndex == periodIndex }

                                Box(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .height(72.dp)
                                        .padding(2.dp)
                                ) {
                                    if (period != null) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(period.colorHex).copy(alpha = 0.15f),
                                            border = BorderStroke(1.dp, Color(period.colorHex).copy(alpha = 0.4f)),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    if (isAdmin) onEditPeriod(period)
                                                }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(4.dp),
                                                verticalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = period.subjectName,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(period.colorHex)
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isAdmin) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "Edit",
                                                            tint = Color(period.colorHex),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = "${period.startTime}-${period.endTime}",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 8.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                )

                                                if (period.roomNumber.isNotBlank()) {
                                                    Text(
                                                        text = period.roomNumber,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 8.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            modifier = Modifier.fillMaxSize()
                                        ) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PeriodCardItem(
    period: SchedulePeriod,
    childName: String,
    isAdmin: Boolean,
    isHebrew: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestAlert: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Period number circle badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(period.colorHex).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${period.periodIndex}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(period.colorHex)
                    )
                )
            }

            // Subject & Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = period.subjectName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${period.startTime} - ${period.endTime}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (period.roomNumber.isNotBlank()) {
                        Text(
                            text = "📍 " + period.roomNumber,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    if (period.teacherName.isNotBlank()) {
                        Text(
                            text = "👤 " + period.teacherName,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }
            }

            // Actions: Edit, Test Notification, Delete
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onTestAlert,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("test_alert_period_${period.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Test Notification",
                        tint = GoogleBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isAdmin) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("edit_period_${period.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = GoogleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
