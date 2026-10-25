package com.example.ui.dialogs

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.R
import com.example.model.*
import com.example.ui.components.FamilyTimeSelectionDialog
import com.example.ui.components.TimeFormatUtils
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
    var selectedMemberId by remember {
        mutableStateOf(taskToEdit?.assignedToMemberId ?: (members.firstOrNull()?.id ?: ""))
    }
    var selectedCategory by remember { mutableStateOf(taskToEdit?.category ?: TaskCategory.CLEANING) }
    var selectedPriority by remember { mutableStateOf(taskToEdit?.priority ?: TaskPriority.MEDIUM) }
    var selectedRecurrence by remember { mutableStateOf(taskToEdit?.recurrence ?: TaskRecurrence.DAILY) }
    var hasDueTime by remember { mutableStateOf(taskToEdit?.hasDueTime ?: false) }
    var dueTime by remember {
        mutableStateOf(TimeFormatUtils.normalize24HourString(taskToEdit?.dueTimeFormatted, "18:00"))
    }
    var selectedReminderOffset by remember {
        mutableStateOf(taskToEdit?.reminderOffset ?: TaskReminderOffset.NONE)
    }
    var points by remember { mutableIntStateOf(taskToEdit?.points ?: 15) }
    var selectedTaskDate by remember {
        mutableStateOf(
            taskToEdit?.let { LocalDate.ofEpochDay(it.dateEpochDay) } ?: initialDate
        )
    }
    var showMoreOptions by remember {
        mutableStateOf(taskToEdit != null && taskToEdit.description.isNotBlank())
    }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var notificationPermissionDeniedNotice by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionDeniedNotice = !granted
    }

    val scrollState = rememberScrollState()
    val today = remember { LocalDate.now() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .heightIn(max = 640.dp)
                    .testTag("add_task_dialog")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 1. FIXED COMPACT HEADER (inspired by TaskDetailBottomSheet)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = GoogleBlueLight,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = selectedCategory.defaultEmoji,
                                        fontSize = 18.sp
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = if (taskToEdit != null) {
                                        if (isHebrew) "עריכת משימה" else "Edit Task"
                                    } else {
                                        if (isHebrew) "משימה חדשה" else "New Task"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Text(
                                    text = if (isHebrew) {
                                        "${selectedCategory.getDisplayName(true)} • +$points נק׳ • ${selectedRecurrence.getDisplayName(true)}"
                                    } else {
                                        "${selectedCategory.getDisplayName(false)} • +$points pts • ${selectedRecurrence.getDisplayName(false)}"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("close_add_task_dialog_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = if (isHebrew) "סגור" else "Close",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        thickness = 0.8.dp
                    )

                    // 2. SCROLLABLE COMPACT BODY
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // PRIMARY ROW 1: Task Name at the top
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = {
                                Text(
                                    text = if (isHebrew) "שם המשימה (לדוגמה: סידור חדר)" else "Task Name",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("task_title_input")
                        )

                        // PRIMARY ROW 2: Single-Row Family Member Selection (no clipping)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isHebrew) "שיוך:" else "To:",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("assign_member_row")
                            ) {
                                items(members, key = { it.id }) { member ->
                                    val isSelected = member.id == selectedMemberId
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) GoogleBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable { selectedMemberId = member.id }
                                            .testTag("assign_member_chip_${member.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(text = member.avatarEmoji, fontSize = 13.sp)
                                            Text(
                                                text = member.name,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                                ),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // PRIMARY ROW 3: Single-Row Execution Date & 24h Time Selection
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("task_execution_time_section")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Date + Time in a single horizontal row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    VelvetCrystal3DAssetIcon(
                                        drawableRes = R.drawable.ic_velvet_due_clock,
                                        size = 18.dp
                                    )

                                    // Compact Date Chips + Custom Date Picker in horizontal row
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        item {
                                            val isTodaySelected = selectedTaskDate == today
                                            CompactPillChip(
                                                label = if (isHebrew) "היום" else "Today",
                                                isSelected = isTodaySelected,
                                                onClick = { selectedTaskDate = today },
                                                modifier = Modifier.testTag("date_today_chip")
                                            )
                                        }
                                        item {
                                            val tomorrow = today.plusDays(1)
                                            val isTomorrowSelected = selectedTaskDate == tomorrow
                                            CompactPillChip(
                                                label = if (isHebrew) "מחר" else "Tomorrow",
                                                isSelected = isTomorrowSelected,
                                                onClick = { selectedTaskDate = tomorrow },
                                                modifier = Modifier.testTag("date_tomorrow_chip")
                                            )
                                        }
                                        item {
                                            val isCustomDate = selectedTaskDate != today && selectedTaskDate != today.plusDays(1)
                                            Surface(
                                                shape = RoundedCornerShape(11.dp),
                                                color = if (isCustomDate) GoogleBlue else MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isCustomDate) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
                                                ),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(11.dp))
                                                    .clickable {
                                                        DatePickerDialog(
                                                            context,
                                                            { _, year, month, dayOfMonth ->
                                                                selectedTaskDate = LocalDate.of(year, month + 1, dayOfMonth)
                                                            },
                                                            selectedTaskDate.year,
                                                            selectedTaskDate.monthValue - 1,
                                                            selectedTaskDate.dayOfMonth
                                                        ).show()
                                                    }
                                                    .testTag("task_pick_custom_date_btn")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CalendarMonth,
                                                        contentDescription = null,
                                                        tint = if (isCustomDate) Color.White else GoogleBlue,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Text(
                                                        text = "${selectedTaskDate.dayOfMonth}/${selectedTaskDate.monthValue}",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isCustomDate) Color.White else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Compact 24h Execution Time Pill + Toggle on the same row
                                    Surface(
                                        shape = RoundedCornerShape(11.dp),
                                        color = if (hasDueTime) GoogleBlueLight else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            if (hasDueTime) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(11.dp))
                                            .clickable {
                                                if (!hasDueTime) {
                                                    hasDueTime = true
                                                    if (selectedReminderOffset == TaskReminderOffset.NONE) {
                                                        selectedReminderOffset = TaskReminderOffset.AT_TIME
                                                    }
                                                }
                                                showTimePickerDialog = true
                                            }
                                            .testTag("task_due_time_picker")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AccessTime,
                                                contentDescription = null,
                                                tint = if (hasDueTime) GoogleBlueDark else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = if (hasDueTime) dueTime else (if (isHebrew) "שעה" else "Time"),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = if (hasDueTime) GoogleBlueDark else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                        }
                                    }

                                    // Compact toggle pill to enable/disable execution time quickly
                                    Surface(
                                        shape = CircleShape,
                                        color = if (hasDueTime) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                val nextState = !hasDueTime
                                                hasDueTime = nextState
                                                if (!nextState) {
                                                    selectedReminderOffset = TaskReminderOffset.NONE
                                                } else if (selectedReminderOffset == TaskReminderOffset.NONE) {
                                                    selectedReminderOffset = TaskReminderOffset.AT_TIME
                                                }
                                            }
                                            .testTag("task_has_due_time_switch")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = if (hasDueTime) "✓" else "+",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (hasDueTime) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    thickness = 0.6.dp
                                )

                                // PRIMARY ROW 4: Single-Row Reminder Selection (always visible, 1 tap)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    VelvetCrystal3DAssetIcon(
                                        drawableRes = R.drawable.ic_velvet_reminder_bell,
                                        size = 18.dp
                                    )
                                    Text(
                                        text = if (isHebrew) "תזכורת:" else "Alert:",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("task_reminder_offset_row")
                                    ) {
                                        items(TaskReminderOffset.values()) { offset ->
                                            val isSelected = if (!hasDueTime) {
                                                offset == TaskReminderOffset.NONE
                                            } else {
                                                selectedReminderOffset == offset
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(11.dp),
                                                color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isSelected) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
                                                ),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(11.dp))
                                                    .clickable {
                                                        selectedReminderOffset = offset
                                                        if (offset == TaskReminderOffset.NONE) {
                                                            // Keep hasDueTime as-is, only turn off reminder
                                                        } else {
                                                            if (!hasDueTime) {
                                                                hasDueTime = true
                                                            }
                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                                                    }
                                                    .testTag("task_reminder_chip_${offset.name}")
                                            ) {
                                                Text(
                                                    text = offset.getShortLabel(isHebrew),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                                        fontSize = 11.sp
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (notificationPermissionDeniedNotice) {
                                    Text(
                                        text = if (isHebrew)
                                            "הרשאת התראות כבויה במכשיר זה — התזכורת תסונכרן למכשיר המשויך."
                                        else
                                            "Notifications disabled locally — reminder will still sync to assignee's device.",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                            }
                        }

                        // EXPANDABLE "MORE OPTIONS" ("אפשרויות נוספות") HEADER BUTTON
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(
                                1.dp,
                                if (showMoreOptions) GoogleBlue.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { showMoreOptions = !showMoreOptions }
                                .testTag("expand_more_options_button")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = GoogleBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isHebrew) "אפשרויות נוספות" else "More Options",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    Text(
                                        text = if (isHebrew) {
                                            "(${selectedCategory.getDisplayName(true)} • +$points • ${selectedRecurrence.getDisplayName(true)})"
                                        } else {
                                            "(${selectedCategory.getDisplayName(false)} • +$points • ${selectedRecurrence.getDisplayName(false)})"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = if (showMoreOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // EXPANDABLE CONTENT: Quick Suggestions, Category, Points, Recurrence, Description
                        AnimatedVisibility(
                            visible = showMoreOptions,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("more_options_container")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // 1. Auto-Suggestions (Horizontal scrollable row, no clipping)
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        val suggestions = listOf("סידור ילקוט", "שטיפת כלים", "טיול לכלב", "סדר בארון")
                                        itemsIndexed(suggestions) { index, suggestion ->
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = GoogleBlueLight,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        title = suggestion
                                                        if (description.isBlank()) {
                                                            description = if (isHebrew) "משימה מומלצת לארגון הבית" else "Suggested household task"
                                                        }
                                                    }
                                                    .testTag("suggestion_chip_$index")
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

                                    // 2. Category Selection (Single row with horizontal scroll)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (isHebrew) "קטגוריה:" else "Category:",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            items(TaskCategory.values()) { cat ->
                                                val isSelected = cat == selectedCategory
                                                Surface(
                                                    shape = RoundedCornerShape(11.dp),
                                                    color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isSelected) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
                                                    ),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(11.dp))
                                                        .clickable { selectedCategory = cat }
                                                        .testTag("category_chip_${cat.name}")
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(text = cat.defaultEmoji, fontSize = 12.sp)
                                                        Text(
                                                            text = cat.getDisplayName(isHebrew),
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                color = if (isSelected) GoogleBlueDark else MaterialTheme.colorScheme.onSurface
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 3. Reward Points Row (Single row)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            VelvetCrystal3DAssetIcon(
                                                drawableRes = R.drawable.ic_velvet_points_star,
                                                size = 18.dp
                                            )
                                            Text(
                                                text = if (isHebrew) "נקודות פרס:" else "Points:",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            listOf(10, 15, 20, 30).forEach { pt ->
                                                val isPtSelected = points == pt
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isPtSelected) GoogleBlue else MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isPtSelected) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
                                                    ),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable { points = pt }
                                                        .testTag("points_chip_$pt")
                                                ) {
                                                    Text(
                                                        text = "+$pt",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isPtSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                                        ),
                                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 4. Recurrence Selection (Single row, preserves editing recurring tasks)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = if (isHebrew) "תדירות:" else "Repeat:",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            contentPadding = PaddingValues(horizontal = 2.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("task_recurrence_selector")
                                        ) {
                                            items(TaskRecurrence.values()) { rec ->
                                                val isSelected = rec == selectedRecurrence
                                                Surface(
                                                    shape = RoundedCornerShape(11.dp),
                                                    color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isSelected) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
                                                    ),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(11.dp))
                                                        .clickable { selectedRecurrence = rec }
                                                        .testTag("task_recurrence_chip_${rec.name}")
                                                ) {
                                                    Text(
                                                        text = rec.getDisplayName(isHebrew),
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                                        ),
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 5. Compact Notes / Description Input
                                    OutlinedTextField(
                                        value = description,
                                        onValueChange = { description = it },
                                        placeholder = {
                                            Text(
                                                text = if (isHebrew) "הערות או פירוט נוסף (אופציונלי)" else "Notes (optional)",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                        },
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        shape = RoundedCornerShape(12.dp),
                                        maxLines = 2,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("task_description_input")
                                    )
                                }
                            }
                        }
                    }

                    // 3. FIXED FOOTER OUTSIDE SCROLL AREA: "שמור משימה" ALWAYS ACCESSIBLE
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        thickness = 0.8.dp
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        val submitShape = RoundedCornerShape(22.dp)
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
                                .height(46.dp)
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
                                    if (isHebrew) "שמור משימה" else "Save Task"
                                },
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTimePickerDialog) {
        FamilyTimeSelectionDialog(
            initialTime = dueTime,
            title = if (isHebrew) "שעת ביצוע" else "Execution Time",
            isHebrew = isHebrew,
            onDismiss = { showTimePickerDialog = false },
            onConfirm = { chosenTime ->
                dueTime = TimeFormatUtils.normalize24HourString(chosenTime, dueTime)
                hasDueTime = true
                if (selectedReminderOffset == TaskReminderOffset.NONE) {
                    selectedReminderOffset = TaskReminderOffset.AT_TIME
                }
                showTimePickerDialog = false
            }
        )
    }
}

@Composable
private fun CompactPillChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isSelected) GoogleBlue else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}
