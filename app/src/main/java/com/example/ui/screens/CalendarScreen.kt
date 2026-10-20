package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.components.TimeFormatUtils
import com.example.ui.dialogs.TaskDetailBottomSheet
import com.example.ui.theme.*
import com.example.viewmodel.CalendarViewMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(
    tasks: List<FamilyTask>,
    periods: List<SchedulePeriod>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    calendarMode: CalendarViewMode,
    selectedDate: LocalDate,
    firstDayOfWeek: FirstDayOfWeek,
    isHebrew: Boolean,
    onModeChange: (CalendarViewMode) -> Unit,
    onDateSelect: (LocalDate) -> Unit,
    onToggleTask: (String) -> Unit,
    onEditTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onSendNudge: (FamilyTask) -> Unit,
    onAddNewTask: () -> Unit,
    taskSyncStates: Map<String, TaskCompletionSyncState> = emptyMap(),
    onApproveTask: (String) -> Unit = {}
) {
    var displayMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }
    var selectedTaskForSheet by remember { mutableStateOf<FamilyTask?>(null) }
    val today = remember { LocalDate.now(ZoneId.systemDefault()) }

    // Keep displayMonth synced if selectedDate jumps to a different month externally
    LaunchedEffect(selectedDate) {
        val targetMonth = YearMonth.from(selectedDate)
        if (targetMonth != displayMonth) {
            displayMonth = targetMonth
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Compact Top Calendar Header: Month Navigation + View Mode Selector + Today Shortcut
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val locale = if (isHebrew) Locale.forLanguageTag("he") else Locale.ENGLISH
                        val monthName = displayMonth.month.getDisplayName(TextStyle.FULL, locale)
                        val yearNumber = displayMonth.year

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val prev = displayMonth.minusMonths(1)
                                    displayMonth = prev
                                    val clampedDay = selectedDate.dayOfMonth.coerceAtMost(prev.lengthOfMonth())
                                    onDateSelect(prev.atDay(clampedDay))
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("prev_month_btn")
                            ) {
                                Icon(
                                    imageVector = if (isHebrew) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                                    contentDescription = if (isHebrew) "חודש קודם" else "Previous Month",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = "$monthName $yearNumber",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .testTag("calendar_month_title")
                            )

                            IconButton(
                                onClick = {
                                    val next = displayMonth.plusMonths(1)
                                    displayMonth = next
                                    val clampedDay = selectedDate.dayOfMonth.coerceAtMost(next.lengthOfMonth())
                                    onDateSelect(next.atDay(clampedDay))
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("next_month_btn")
                            ) {
                                Icon(
                                    imageVector = if (isHebrew) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                                    contentDescription = if (isHebrew) "חודש הבא" else "Next Month",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Today shortcut
                            FilledTonalButton(
                                onClick = {
                                    displayMonth = YearMonth.from(today)
                                    onDateSelect(today)
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("jump_today_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Today,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isHebrew) "היום" else "Today",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            // Compact Segmented Pills for Month / Week / Day
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(modifier = Modifier.padding(2.dp)) {
                                    CalendarModePill(
                                        title = if (isHebrew) "חודש" else "M",
                                        isSelected = calendarMode == CalendarViewMode.MONTH,
                                        onClick = { onModeChange(CalendarViewMode.MONTH) },
                                        testTag = "mode_month"
                                    )
                                    CalendarModePill(
                                        title = if (isHebrew) "שבוע" else "W",
                                        isSelected = calendarMode == CalendarViewMode.WEEK,
                                        onClick = { onModeChange(CalendarViewMode.WEEK) },
                                        testTag = "mode_week"
                                    )
                                    CalendarModePill(
                                        title = if (isHebrew) "יום" else "D",
                                        isSelected = calendarMode == CalendarViewMode.DAY,
                                        onClick = { onModeChange(CalendarViewMode.DAY) },
                                        testTag = "mode_day"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

            when (calendarMode) {
                CalendarViewMode.MONTH -> {
                    MonthCalendarView(
                        displayMonth = displayMonth,
                        selectedDate = selectedDate,
                        today = today,
                        tasks = tasks,
                        members = members,
                        currentMember = currentMember,
                        firstDayOfWeek = firstDayOfWeek,
                        isHebrew = isHebrew,
                        taskSyncStates = taskSyncStates,
                        onDateSelect = onDateSelect,
                        onTaskClick = { clickedTask ->
                            onDateSelect(LocalDate.ofEpochDay(clickedTask.dateEpochDay))
                            selectedTaskForSheet = clickedTask
                        },
                        onToggleTask = onToggleTask,
                        onApproveTask = onApproveTask,
                        onAddNewTask = onAddNewTask
                    )
                }
                CalendarViewMode.WEEK -> {
                    WeekCalendarView(
                        selectedDate = selectedDate,
                        today = today,
                        tasks = tasks,
                        members = members,
                        currentMember = currentMember,
                        firstDayOfWeek = firstDayOfWeek,
                        isHebrew = isHebrew,
                        taskSyncStates = taskSyncStates,
                        onDateSelect = onDateSelect,
                        onTaskClick = { selectedTaskForSheet = it },
                        onToggleTask = onToggleTask,
                        onApproveTask = onApproveTask,
                        onAddNewTask = onAddNewTask
                    )
                }
                CalendarViewMode.DAY -> {
                    DayCalendarView(
                        selectedDate = selectedDate,
                        tasks = tasks,
                        periods = periods,
                        members = members,
                        currentMember = currentMember,
                        isHebrew = isHebrew,
                        taskSyncStates = taskSyncStates,
                        onTaskClick = { selectedTaskForSheet = it },
                        onToggleTask = onToggleTask,
                        onApproveTask = onApproveTask,
                        onAddNewTask = onAddNewTask
                    )
                }
            }
        }
    }

    // Polished Material 3 Modal Bottom Sheet when any task is tapped in a cell or in the agenda
    selectedTaskForSheet?.let { activeTask ->
        val latestTask = tasks.find { it.id == activeTask.id } ?: activeTask
        val assignedMember = members.find { it.id == latestTask.assignedToMemberId }
        val syncState = taskSyncStates[latestTask.id] ?: when {
            latestTask.isCompleted && latestTask.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
            latestTask.isCompleted && !latestTask.approvedByParent -> TaskCompletionSyncState.PENDING_APPROVAL
            else -> TaskCompletionSyncState.IDLE
        }

        TaskDetailBottomSheet(
            task = latestTask,
            assignedMember = assignedMember,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = { selectedTaskForSheet = null },
            onToggle = { onToggleTask(latestTask.id) },
            onEdit = {
                selectedTaskForSheet = null
                onEditTask(latestTask)
            },
            onDelete = {
                onDeleteTask(latestTask.id)
                selectedTaskForSheet = null
            },
            onSendNudge = { onSendNudge(latestTask) },
            syncState = syncState,
            onApprove = { onApproveTask(latestTask.id) }
        )
    }
}

@Composable
fun CalendarModePill(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            ),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun MonthCalendarView(
    displayMonth: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    tasks: List<FamilyTask>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    firstDayOfWeek: FirstDayOfWeek,
    isHebrew: Boolean,
    taskSyncStates: Map<String, TaskCompletionSyncState>,
    onDateSelect: (LocalDate) -> Unit,
    onTaskClick: (FamilyTask) -> Unit,
    onToggleTask: (String) -> Unit,
    onApproveTask: (String) -> Unit,
    onAddNewTask: () -> Unit = {}
) {
    val daysOfWeekLabels = if (firstDayOfWeek == FirstDayOfWeek.SUNDAY) {
        if (isHebrew) listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
        else listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    } else {
        if (isHebrew) listOf("ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳", "א׳")
        else listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    }

    val firstDayOfMonth = displayMonth.atDay(1)
    val dayOfWeekIndex = if (firstDayOfWeek == FirstDayOfWeek.SUNDAY) {
        firstDayOfMonth.dayOfWeek.value % 7 // Sunday = 0
    } else {
        firstDayOfMonth.dayOfWeek.value - 1 // Monday = 0
    }
    val daysInMonth = displayMonth.lengthOfMonth()
    val totalCells = ((dayOfWeekIndex + daysInMonth + 6) / 7) * 7
    val rowCount = (totalCells / 7).coerceAtLeast(5)

    // Pre-group and sort each day's tasks by 24-hour scheduled time
    val tasksByEpochDay = remember(tasks) {
        tasks.groupBy { it.dateEpochDay }
            .mapValues { (_, list) ->
                list.sortedBy { TimeFormatUtils.toMinutesSinceMidnight(it.dueTimeFormatted) }
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactHeight = maxHeight < 640.dp
        val maxLabelsPerCell = if (isCompactHeight) 2 else 3
        val cellHeight = if (isCompactHeight) 56.dp else 64.dp
        val gridHeight = cellHeight * rowCount + 6.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Upper Portion: Google Calendar-Inspired Month Grid
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Weekday Header Strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        daysOfWeekLabels.forEach { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Month Cells Grid
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        userScrollEnabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .height(gridHeight)
                            .testTag("calendar_month_grid")
                    ) {
                        items(totalCells) { cellIndex ->
                            val dayNumber = cellIndex - dayOfWeekIndex + 1
                            if (dayNumber in 1..daysInMonth) {
                                val date = displayMonth.atDay(dayNumber)
                                val isSelected = date == selectedDate
                                val isToday = date == today
                                val dayTasks = tasksByEpochDay[date.toEpochDay()].orEmpty()

                                MonthDayCell(
                                    date = date,
                                    isSelected = isSelected,
                                    isToday = isToday,
                                    tasks = dayTasks,
                                    isHebrew = isHebrew,
                                    maxVisibleTasks = maxLabelsPerCell,
                                    cellHeight = cellHeight,
                                    onDateClick = { onDateSelect(date) },
                                    onTaskStripClick = { task ->
                                        onDateSelect(date)
                                        onTaskClick(task)
                                    }
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .height(cellHeight)
                                        .padding(1.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))

            // Lower Portion: Dedicated Selected-Day Agenda Section directly below calendar
            val selectedDateTasks = tasksByEpochDay[selectedDate.toEpochDay()].orEmpty()
            SelectedDayAgendaSection(
                selectedDate = selectedDate,
                today = today,
                tasks = selectedDateTasks,
                members = members,
                currentMember = currentMember,
                isHebrew = isHebrew,
                taskSyncStates = taskSyncStates,
                onTaskClick = onTaskClick,
                onToggleTask = onToggleTask,
                onApproveTask = onApproveTask,
                onAddNewTask = onAddNewTask,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
fun MonthDayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    tasks: List<FamilyTask>,
    isHebrew: Boolean,
    maxVisibleTasks: Int = 2,
    cellHeight: androidx.compose.ui.unit.Dp = 62.dp,
    onDateClick: () -> Unit,
    onTaskStripClick: (FamilyTask) -> Unit
) {
    val cellBgColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        isToday -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surface
    }
    val cellBorderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cellBgColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 0.6.dp, cellBorderColor),
        modifier = Modifier
            .padding(1.dp)
            .height(cellHeight)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onDateClick() }
            .testTag("calendar_day_cell_${date}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Day number header
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    fontSize = 10.sp,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            Spacer(modifier = Modifier.height(1.dp))

            // Compact Google Calendar-style colored event strips directly inside the cell
            if (tasks.isNotEmpty()) {
                val hasOverflow = tasks.size > maxVisibleTasks
                val visibleCount = if (hasOverflow) (maxVisibleTasks - 1).coerceAtLeast(1) else tasks.size

                tasks.take(visibleCount).forEach { task ->
                    val (stripBg, stripText) = taskStripColors(task)
                    val statusPrefix = when {
                        task.isCompleted && task.approvedByParent -> "✓ "
                        task.isCompleted && !task.approvedByParent -> "⏳ "
                        else -> ""
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(stripBg)
                            .clickable { onTaskStripClick(task) }
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                            .testTag("calendar_cell_task_${task.id}")
                    ) {
                        Text(
                            text = "$statusPrefix${task.title}",
                            fontSize = 8.sp,
                            lineHeight = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = stripText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (hasOverflow) {
                    val extraCount = tasks.size - visibleCount
                    Text(
                        text = if (isHebrew) "+$extraCount עוד" else "+$extraCount more",
                        fontSize = 7.5.sp,
                        lineHeight = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.testTag("calendar_cell_more_${date}")
                    )
                }
            }
        }
    }
}

/**
 * Subtle, soft-tactile category/status color pairs for Calendar event strips.
 */
@Composable
private fun taskStripColors(task: FamilyTask): Pair<Color, Color> {
    return when {
        task.isCompleted && task.approvedByParent ->
            PastelGreenBackground to PastelGreenDark
        task.isCompleted && !task.approvedByParent ->
            PastelYellowBackground to PastelYellowDark
        else -> when (task.category) {
            TaskCategory.HOMEWORK -> SoftLavenderLight to SoftLavenderDark
            TaskCategory.CLEANING -> SoftTealLight to SoftTealDark
            TaskCategory.GROCERIES -> WarmCoralLight to WarmCoralDark
            TaskCategory.DISHES -> PastelGreenBackground to PastelGreenDark
            TaskCategory.PETS -> PastelYellowBackground to PastelYellowDark
            TaskCategory.BEDROOM -> GoogleBlueLight to GoogleBlueDark
            else -> GoogleBlueLight to GoogleBlueDark
        }
    }
}

@Composable
private fun SelectedDayAgendaSection(
    selectedDate: LocalDate,
    today: LocalDate,
    tasks: List<FamilyTask>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    taskSyncStates: Map<String, TaskCompletionSyncState>,
    onTaskClick: (FamilyTask) -> Unit,
    onToggleTask: (String) -> Unit,
    onApproveTask: (String) -> Unit,
    onAddNewTask: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val locale = if (isHebrew) Locale.forLanguageTag("he") else Locale.ENGLISH
    val dayPrefix = when (selectedDate) {
        today -> if (isHebrew) "היום • " else "Today • "
        today.plusDays(1) -> if (isHebrew) "מחר • " else "Tomorrow • "
        today.minusDays(1) -> if (isHebrew) "אתמול • " else "Yesterday • "
        else -> ""
    }
    val selectedDateFormatted = dayPrefix +
        selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale) +
        ", " + selectedDate.dayOfMonth + " " +
        selectedDate.month.getDisplayName(TextStyle.SHORT, locale)

    Column(
        modifier = modifier
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("calendar_selected_day_agenda")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = selectedDateFormatted,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("calendar_selected_date_header")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (isHebrew) "${tasks.size} משימות" else "${tasks.size} tasks",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (currentMember.isParent) {
                    Button(
                        onClick = onAddNewTask,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("calendar_add_task_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isHebrew) "משימה לתאריך" else "Add Task",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (tasks.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("calendar_agenda_empty_state")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.EventAvailable,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isHebrew)
                            "אין משימות מתוזמנות לתאריך זה"
                        else
                            "No tasks scheduled for this date",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(tasks, key = { it.id }) { task ->
                    val assignedMember = members.find { it.id == task.assignedToMemberId }
                    val syncState = taskSyncStates[task.id] ?: when {
                        task.isCompleted && task.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
                        task.isCompleted && !task.approvedByParent -> TaskCompletionSyncState.PENDING_APPROVAL
                        else -> TaskCompletionSyncState.IDLE
                    }

                    CalendarAgendaTaskRow(
                        task = task,
                        assignedMember = assignedMember,
                        currentMember = currentMember,
                        syncState = syncState,
                        isHebrew = isHebrew,
                        onRowClick = { onTaskClick(task) },
                        onToggle = { onToggleTask(task.id) },
                        onApprove = { onApproveTask(task.id) }
                    )
                }
            }
        }
    }
}

/**
 * Compact, crisp soft-tactile agenda row showing 24-hour scheduled time, title, assignee,
 * and completion/approval status. Tapping opens the Modal Bottom Sheet.
 */
@Composable
fun CalendarAgendaTaskRow(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember,
    syncState: TaskCompletionSyncState,
    isHebrew: Boolean,
    onRowClick: () -> Unit,
    onToggle: () -> Unit,
    onApprove: () -> Unit
) {
    val time24h = TimeFormatUtils.normalize24HourString(task.dueTimeFormatted, "16:00")
    val isPendingApproval = task.isCompleted && !task.approvedByParent
    val isApproved = task.isCompleted && task.approvedByParent
    val isSaving = syncState == TaskCompletionSyncState.SAVING
    val canToggle = currentMember.isParent || task.assignedToMemberId == currentMember.id

    val (stripBg, stripAccent) = taskStripColors(task)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isApproved -> PastelGreenBorder
                isPendingApproval -> PastelYellowBorder
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
            }
        ),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onRowClick() }
            .testTag("calendar_agenda_task_${task.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: 24-hour time pill + category accent bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = stripBg,
                    modifier = Modifier.widthIn(min = 52.dp)
                ) {
                    Text(
                        text = time24h,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = stripAccent,
                            fontSize = 12.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isApproved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (isApproved) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    val statusText = when {
                        isSaving -> if (isHebrew) "שומר..." else "Saving..."
                        isApproved -> if (isHebrew) "אושר ✓ (+${task.points})" else "Approved ✓ (+${task.points})"
                        isPendingApproval -> if (isHebrew) "ממתין לאישור הורה ⏳" else "Pending approval ⏳"
                        else -> "+${task.points} ${if (isHebrew) "נק'" else "pts"}"
                    }
                    val assigneeText = assignedMember?.let { "${it.avatarEmoji} ${it.name}" }
                        ?: if (isHebrew) "חבר משפחה" else "Member"

                    Text(
                        text = "$assigneeText • $statusText",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = when {
                                isApproved -> PastelGreenDark
                                isPendingApproval -> PastelYellowDark
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right: Role-aware quick action (Parent approve button or completion checkbox)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (currentMember.isParent && isPendingApproval && !isSaving) {
                    FilledTonalButton(
                        onClick = onApprove,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = PastelGreenBackground,
                            contentColor = PastelGreenDark
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("calendar_approve_${task.id}")
                    ) {
                        Text(
                            text = if (isHebrew) "אשר ✓" else "Approve ✓",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (canToggle) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isApproved -> GoogleGreen
                                    isPendingApproval -> GoogleYellowDark
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                width = 1.8.dp,
                                color = when {
                                    isApproved -> GoogleGreen
                                    isPendingApproval -> GoogleYellowDark
                                    else -> MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape
                            )
                            .clickable(enabled = !isSaving) { onToggle() }
                            .testTag("calendar_toggle_${task.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (task.isCompleted) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeekCalendarView(
    selectedDate: LocalDate,
    today: LocalDate,
    tasks: List<FamilyTask>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    firstDayOfWeek: FirstDayOfWeek,
    isHebrew: Boolean,
    taskSyncStates: Map<String, TaskCompletionSyncState>,
    onDateSelect: (LocalDate) -> Unit,
    onTaskClick: (FamilyTask) -> Unit,
    onToggleTask: (String) -> Unit,
    onApproveTask: (String) -> Unit,
    onAddNewTask: () -> Unit = {}
) {
    val dayOffset = if (firstDayOfWeek == FirstDayOfWeek.SUNDAY) {
        selectedDate.dayOfWeek.value % 7
    } else {
        selectedDate.dayOfWeek.value - 1
    }
    val weekStart = selectedDate.minusDays(dayOffset.toLong())
    val weekDays = (0..6).map { weekStart.plusDays(it.toLong()) }

    val sortedDayTasks = remember(tasks, selectedDate) {
        tasks.filter { it.dateEpochDay == selectedDate.toEpochDay() }
            .sortedBy { TimeFormatUtils.toMinutesSinceMidnight(it.dueTimeFormatted) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            weekDays.forEach { date ->
                val isSelected = date == selectedDate
                val isToday = date == today
                val locale = if (isHebrew) Locale.forLanguageTag("he") else Locale.ENGLISH
                val dayLetter = date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isToday -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onDateSelect(date) }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = dayLetter,
                            fontSize = 11.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = date.dayOfMonth.toString(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

        SelectedDayAgendaSection(
            selectedDate = selectedDate,
            today = today,
            tasks = sortedDayTasks,
            members = members,
            currentMember = currentMember,
            isHebrew = isHebrew,
            taskSyncStates = taskSyncStates,
            onTaskClick = onTaskClick,
            onToggleTask = onToggleTask,
            onApproveTask = onApproveTask,
            onAddNewTask = onAddNewTask,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
fun DayCalendarView(
    selectedDate: LocalDate,
    tasks: List<FamilyTask>,
    periods: List<SchedulePeriod>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    taskSyncStates: Map<String, TaskCompletionSyncState>,
    onTaskClick: (FamilyTask) -> Unit,
    onToggleTask: (String) -> Unit,
    onApproveTask: (String) -> Unit,
    onAddNewTask: () -> Unit = {}
) {
    val dayTasks = remember(tasks, selectedDate) {
        tasks.filter { it.dateEpochDay == selectedDate.toEpochDay() }
            .sortedBy { TimeFormatUtils.toMinutesSinceMidnight(it.dueTimeFormatted) }
    }
    val schoolDay = when (selectedDate.dayOfWeek.value) {
        7 -> SchoolDay.SUNDAY
        1 -> SchoolDay.MONDAY
        2 -> SchoolDay.TUESDAY
        3 -> SchoolDay.WEDNESDAY
        4 -> SchoolDay.THURSDAY
        5 -> SchoolDay.FRIDAY
        else -> null
    }
    val dayPeriods = if (schoolDay != null) {
        periods.filter { it.day == schoolDay }
            .sortedBy { TimeFormatUtils.toMinutesSinceMidnight(it.startTime) }
    } else emptyList()

    val locale = if (isHebrew) Locale.forLanguageTag("he") else Locale.ENGLISH
    val dayTitle = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale) +
        ", " + selectedDate.dayOfMonth + " " +
        selectedDate.month.getDisplayName(TextStyle.FULL, locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = dayTitle,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f)
            )

            if (currentMember.isParent) {
                Button(
                    onClick = onAddNewTask,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .testTag("calendar_add_task_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isHebrew) "משימה לתאריך" else "Add Task",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            contentPadding = PaddingValues(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (dayPeriods.isNotEmpty()) {
                item {
                    Text(
                        text = if (isHebrew) "🏫 מערכת שעות היום" else "🏫 School Schedule Today",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                items(dayPeriods, key = { it.id }) { period ->
                    val kid = members.find { it.id == period.childId }
                    val start24 = TimeFormatUtils.normalize24HourString(period.startTime, "08:00")
                    val end24 = TimeFormatUtils.normalize24HourString(period.endTime, "08:45")
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SoftLavenderLight,
                        border = BorderStroke(1.dp, SoftLavenderBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = kid?.avatarEmoji ?: "🎒", fontSize = 20.sp)

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${period.subjectName} ($start24 - $end24)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${kid?.name ?: ""} • ${period.roomNumber} • ${period.teacherName}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            item {
                Text(
                    text = if (isHebrew) "📋 משימות מתוזמנות (לפי שעה)" else "📋 Scheduled Tasks (by time)",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }

            if (dayTasks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.padding(18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isHebrew) "אין משימות מתוזמנות לתאריך זה" else "No tasks scheduled for this date",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                }
            } else {
                items(dayTasks, key = { it.id }) { task ->
                    val assignedMember = members.find { it.id == task.assignedToMemberId }
                    val syncState = taskSyncStates[task.id] ?: when {
                        task.isCompleted && task.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
                        task.isCompleted && !task.approvedByParent -> TaskCompletionSyncState.PENDING_APPROVAL
                        else -> TaskCompletionSyncState.IDLE
                    }
                    CalendarAgendaTaskRow(
                        task = task,
                        assignedMember = assignedMember,
                        currentMember = currentMember,
                        syncState = syncState,
                        isHebrew = isHebrew,
                        onRowClick = { onTaskClick(task) },
                        onToggle = { onToggleTask(task.id) },
                        onApprove = { onApproveTask(task.id) }
                    )
                }
            }
        }
    }
}
