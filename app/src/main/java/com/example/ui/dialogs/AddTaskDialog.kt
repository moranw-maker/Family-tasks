package com.example.ui.dialogs

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.R
import com.example.model.*
import com.example.ui.components.TimeFormatUtils
import com.example.ui.components.TimePickerField
import com.example.ui.components.VelvetCrystal3DAssetIcon
import com.example.ui.theme.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskDialog(
    members: List<FamilyMember>,
    isHebrew: Boolean,
    taskToEdit: FamilyTask? = null,
    initialDate: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onSaveTask: (FamilyTask) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(taskToEdit?.title ?: "") }
    var description by remember { mutableStateOf(taskToEdit?.description ?: "") }
    var selectedMemberId by remember { mutableStateOf(taskToEdit?.assignedToMemberId ?: (members.firstOrNull()?.id ?: "")) }
    var selectedCategory by remember { mutableStateOf(taskToEdit?.category ?: TaskCategory.CLEANING) }
    var selectedPriority by remember { mutableStateOf(taskToEdit?.priority ?: TaskPriority.MEDIUM) }
    var selectedRecurrence by remember { mutableStateOf(taskToEdit?.recurrence ?: TaskRecurrence.DAILY) }
    var hasDueTime by remember { mutableStateOf(taskToEdit?.hasDueTime ?: false) }
    var dueTime by remember { mutableStateOf(TimeFormatUtils.normalize24HourString(taskToEdit?.dueTimeFormatted, "18:00")) }
    var selectedReminderOffset by remember {
        mutableStateOf(taskToEdit?.reminderOffset ?: TaskReminderOffset.NONE)
    }
    var points by remember { mutableIntStateOf(taskToEdit?.points ?: 15) }
    var selectedTaskDate by remember {
        mutableStateOf(
            taskToEdit?.let { LocalDate.ofEpochDay(it.dateEpochDay) } ?: initialDate
        )
    }
    var notificationPermissionDeniedNotice by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionDeniedNotice = !granted
    }

    val scrollState = rememberScrollState()
    val safeDismiss = {
        onDismiss()
    }

    Dialog(
        onDismissRequest = safeDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp)
                .testTag("add_task_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(scrollState)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (taskToEdit != null) {
                            if (isHebrew) "עריכת משימה משפחתית" else "Edit Household Task"
                        } else {
                            if (isHebrew) "הוספת משימה משפחתית חדשה" else "Add New Household Chore"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoogleBlue
                        )
                    )

                    IconButton(onClick = safeDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isHebrew) "שם המשימה (לדוגמה: סידור חדר)" else "Task Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task_title_input")
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Smart Suggestions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("סידור ילקוט", "שטיפת כלים", "טיול לכלב", "סדר בארון").forEach { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GoogleBlueLight,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    title = suggestion
                                    description = if (isHebrew) "משימה מומלצת לארגון הבית" else "Suggested household task"
                                }
                        ) {
                            Text(
                                text = "✨ $suggestion",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = GoogleBlueDark
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Description Input
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(if (isHebrew) "הסבר או הערות (אופציונלי)" else "Description (optional)") },
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task_description_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Assign to Member
                Text(
                    text = if (isHebrew) "למי מיועדת המשימה?" else "Assign To:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(members) { member ->
                        val isSelected = member.id == selectedMemberId
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { selectedMemberId = member.id }
                                .testTag("assign_member_chip_${member.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(text = member.avatarEmoji, fontSize = 14.sp)
                                Text(
                                    text = member.name,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Category Selection
                Text(
                    text = if (isHebrew) "קטגוריה:" else "Category:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(TaskCategory.values()) { cat ->
                        val isSelected = cat == selectedCategory
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) BorderStroke(1.5.dp, GoogleBlue) else null,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedCategory = cat }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(text = cat.defaultEmoji, fontSize = 14.sp)
                                Text(
                                    text = cat.getDisplayName(isHebrew),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) GoogleBlueDark else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Date Selection (Quick Chips + Calendar DatePicker button)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isHebrew) "תאריך ביצוע:" else "Execution Date:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    TextButton(
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    selectedTaskDate = LocalDate.of(year, month + 1, dayOfMonth)
                                },
                                selectedTaskDate.year,
                                selectedTaskDate.monthValue - 1,
                                selectedTaskDate.dayOfMonth
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("task_pick_custom_date_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = GoogleBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isHebrew)
                                "בחר תאריך (${selectedTaskDate.dayOfMonth}/${selectedTaskDate.monthValue})"
                            else
                                "Pick Date (${selectedTaskDate.dayOfMonth}/${selectedTaskDate.monthValue})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = GoogleBlue
                            )
                        )
                    }
                }

                val today = remember { LocalDate.now() }
                val dateOptions = remember(initialDate, today, selectedTaskDate, isHebrew) {
                    buildList {
                        add(today to (if (isHebrew) "היום" else "Today"))
                        add(today.plusDays(1) to (if (isHebrew) "מחר" else "Tomorrow"))
                        add(today.plusDays(2) to (if (isHebrew) "עוד יומיים" else "In 2 days"))
                        if (selectedTaskDate != today && selectedTaskDate != today.plusDays(1) && selectedTaskDate != today.plusDays(2)) {
                            add(selectedTaskDate to "${selectedTaskDate.dayOfMonth}/${selectedTaskDate.monthValue}")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dateOptions.forEach { (candidateDate, label) ->
                        val isSelected = selectedTaskDate == candidateDate
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedTaskDate = candidateDate }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Optional Execution Time & Reminder Section (Velvet Crystal Card)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        1.dp,
                        if (hasDueTime) GoogleBlue.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task_execution_time_section")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                VelvetCrystal3DAssetIcon(
                                    drawableRes = R.drawable.ic_velvet_due_clock,
                                    size = 24.dp
                                )
                                Column {
                                    Text(
                                        text = if (isHebrew) "הגדר שעת ביצוע ותזכורת" else "Set Execution Time & Reminder",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = if (hasDueTime) {
                                            if (isHebrew) "שעת יעד פעילה: $dueTime" else "Active due time: $dueTime"
                                        } else {
                                            if (isHebrew) "אופציונלי — ללא שעה ספציפית" else "Optional — no specific time"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                            Switch(
                                checked = hasDueTime,
                                onCheckedChange = { enabled ->
                                    hasDueTime = enabled
                                    if (!enabled) {
                                        selectedReminderOffset = TaskReminderOffset.NONE
                                    } else if (selectedReminderOffset == TaskReminderOffset.NONE) {
                                        selectedReminderOffset = TaskReminderOffset.AT_TIME
                                    }
                                },
                                modifier = Modifier.testTag("task_has_due_time_switch")
                            )
                        }

                        AnimatedVisibility(visible = hasDueTime) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TimePickerField(
                                    label = if (isHebrew) "שעת ביצוע" else "Execution Time",
                                    time = dueTime,
                                    onTimeSelected = { dueTime = it },
                                    isHebrew = isHebrew,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("task_due_time_picker")
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    VelvetCrystal3DAssetIcon(
                                        drawableRes = R.drawable.ic_velvet_reminder_bell,
                                        size = 18.dp
                                    )
                                    Text(
                                        text = if (isHebrew) "תזכורת לפני המשימה:" else "Task Reminder:",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("task_reminder_offset_row")
                                ) {
                                    items(TaskReminderOffset.values()) { offset ->
                                        val isSelected = selectedReminderOffset == offset
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSelected) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
                                            ),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    selectedReminderOffset = offset
                                                    if (offset.isEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                        val hasNotifPerm = ContextCompat.checkSelfPermission(
                                                            context,
                                                            Manifest.permission.POST_NOTIFICATIONS
                                                        ) == PackageManager.PERMISSION_GRANTED
                                                        if (!hasNotifPerm) {
                                                            notificationPermissionLauncher.launch(
                                                                Manifest.permission.POST_NOTIFICATIONS
                                                            )
                                                        }
                                                    }
                                                }
                                                .testTag("task_reminder_chip_${offset.name}")
                                        ) {
                                            Text(
                                                text = offset.getShortLabel(isHebrew),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                                ),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                if (notificationPermissionDeniedNotice) {
                                    Text(
                                        text = if (isHebrew)
                                            "הרשאת התראות כבויה במכשיר זה — התזכורת תישמר ותופעל במכשיר המשויך אם ההרשאה פעילה בו."
                                        else
                                            "Notifications are disabled on this device — the reminder will still sync to the assigned member's device.",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Points Selection Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VelvetCrystal3DAssetIcon(
                            drawableRes = R.drawable.ic_velvet_points_star,
                            size = 20.dp
                        )
                        Text(
                            text = if (isHebrew) "נקודות פרס:" else "Reward Points:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 15, 20, 30).forEach { pt ->
                            val isPtSelected = points == pt
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isPtSelected) GoogleYellowDark else GoogleYellowLight,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { points = pt }
                            ) {
                                Text(
                                    text = "+$pt",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPtSelected) Color.White else GoogleYellowDark
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Recurrence Selection
                Text(
                    text = if (isHebrew) "תדירות משימה חוזרת 🔁:" else "Recurrence 🔁:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("task_recurrence_selector")
                ) {
                    items(TaskRecurrence.values()) { rec ->
                        val isSelected = rec == selectedRecurrence
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedRecurrence = rec }
                                .testTag("task_recurrence_chip_${rec.name}")
                        ) {
                            Text(
                                text = rec.getDisplayName(isHebrew),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Submit Button (Velvet Blue-Purple Gradient)
                val submitShape = RoundedCornerShape(28.dp)
                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            val normalized24h = TimeFormatUtils.normalize24HourString(dueTime, "18:00")
                            val (preservedEpochDay, _) = TimeFormatUtils.combineDateAnd24HourTimeToEpochMillis(
                                dateEpochDay = selectedTaskDate.toEpochDay(),
                                time24h = normalized24h
                            )
                            val effectiveReminderOffset = if (hasDueTime) {
                                selectedReminderOffset
                            } else {
                                TaskReminderOffset.NONE
                            }
                            val taskResult = if (taskToEdit != null) {
                                taskToEdit.copy(
                                    title = title.trim(),
                                    description = description.trim(),
                                    category = selectedCategory,
                                    assignedToMemberId = selectedMemberId,
                                    dateEpochDay = preservedEpochDay,
                                    dueTimeFormatted = normalized24h,
                                    hasDueTime = hasDueTime,
                                    reminderOffset = effectiveReminderOffset,
                                    priority = selectedPriority,
                                    points = points,
                                    recurrence = selectedRecurrence
                                )
                            } else {
                                FamilyTask(
                                    id = "task_${System.currentTimeMillis()}",
                                    title = title.trim(),
                                    description = description.trim(),
                                    category = selectedCategory,
                                    assignedToMemberId = selectedMemberId,
                                    dateEpochDay = preservedEpochDay,
                                    dueTimeFormatted = normalized24h,
                                    hasDueTime = hasDueTime,
                                    reminderOffset = effectiveReminderOffset,
                                    priority = selectedPriority,
                                    points = points,
                                    recurrence = selectedRecurrence,
                                    isCompleted = false
                                )
                            }
                            onSaveTask(taskResult)
                        }
                    },
                    shape = submitShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .shadow(6.dp, submitShape, spotColor = VibrantActionBlue.copy(alpha = 0.45f))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF4F46E5), Color(0xFF2563EB), Color(0xFF6366F1))
                            ),
                            submitShape
                        )
                        .testTag("save_task_btn")
                ) {
                    Text(
                        text = if (taskToEdit != null) {
                            if (isHebrew) "שמור שינויים במשימה" else "Save Task Changes"
                        } else {
                            if (isHebrew) "שמור ושבץ משימה" else "Save & Assign Chore"
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

