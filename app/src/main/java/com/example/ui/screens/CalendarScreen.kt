package com.example.ui.screens

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.CalendarViewMode
import java.time.LocalDate
import java.time.YearMonth
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
    onAddNewTask: () -> Unit
) {
    var displayMonth by remember { mutableStateOf(YearMonth.from(selectedDate)) }
    val today = LocalDate.now()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddNewTask,
                containerColor = GoogleBlue,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("calendar_add_task_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isHebrew) "משימה לתאריך" else "Add Task",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // View Mode Selector & Today Button Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Segmented Pills for Month / Week / Day
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        CalendarModePill(
                            title = if (isHebrew) "חודשי" else "Month",
                            isSelected = calendarMode == CalendarViewMode.MONTH,
                            onClick = { onModeChange(CalendarViewMode.MONTH) },
                            testTag = "mode_month"
                        )
                        CalendarModePill(
                            title = if (isHebrew) "שבועי" else "Week",
                            isSelected = calendarMode == CalendarViewMode.WEEK,
                            onClick = { onModeChange(CalendarViewMode.WEEK) },
                            testTag = "mode_week"
                        )
                        CalendarModePill(
                            title = if (isHebrew) "יומי" else "Day",
                            isSelected = calendarMode == CalendarViewMode.DAY,
                            onClick = { onModeChange(CalendarViewMode.DAY) },
                            testTag = "mode_day"
                        )
                    }
                }

                // Jump to Today Button
                FilledTonalButton(
                    onClick = {
                        onDateSelect(today)
                        displayMonth = YearMonth.from(today)
                    },
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("jump_today_btn")
                ) {
                    Text(
                        text = if (isHebrew) "היום" else "Today",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            // Month Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val locale = if (isHebrew) Locale("he") else Locale.ENGLISH
                val monthName = displayMonth.month.getDisplayName(TextStyle.FULL, locale)
                val yearNumber = displayMonth.year

                Text(
                    text = "$monthName $yearNumber",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { displayMonth = displayMonth.minusMonths(1) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isHebrew) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                            contentDescription = "Previous Month"
                        )
                    }

                    IconButton(
                        onClick = { displayMonth = displayMonth.plusMonths(1) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (isHebrew) Icons.Default.ChevronLeft else Icons.Default.ChevronRight,
                            contentDescription = "Next Month"
                        )
                    }
                }
            }

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
                        onDateSelect = onDateSelect,
                        onToggleTask = onToggleTask,
                        onEditTask = onEditTask,
                        onDeleteTask = onDeleteTask,
                        onSendNudge = onSendNudge
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
                        onDateSelect = onDateSelect,
                        onToggleTask = onToggleTask,
                        onEditTask = onEditTask,
                        onDeleteTask = onDeleteTask,
                        onSendNudge = onSendNudge
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
                        onToggleTask = onToggleTask,
                        onEditTask = onEditTask,
                        onDeleteTask = onDeleteTask,
                        onSendNudge = onSendNudge
                    )
                }
            }
        }
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
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) GoogleBlue else Color.Transparent,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
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
    onDateSelect: (LocalDate) -> Unit,
    onToggleTask: (String) -> Unit,
    onEditTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onSendNudge: (FamilyTask) -> Unit
) {
    val daysOfWeekLabels = if (firstDayOfWeek == FirstDayOfWeek.SUNDAY) {
        if (isHebrew) listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
        else listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    } else {
        if (isHebrew) listOf("ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳", "א׳")
        else listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Day Names Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            daysOfWeekLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Days Grid Calculation
        val firstDayOfMonth = displayMonth.atDay(1)
        val dayOfWeekIndex = if (firstDayOfWeek == FirstDayOfWeek.SUNDAY) {
            firstDayOfMonth.dayOfWeek.value % 7 // Sunday = 0
        } else {
            firstDayOfMonth.dayOfWeek.value - 1 // Monday = 0
        }
        val daysInMonth = displayMonth.lengthOfMonth()
        val totalCells = ((dayOfWeekIndex + daysInMonth + 6) / 7) * 7

        // Calendar Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .height(290.dp)
        ) {
            items(totalCells) { cellIndex ->
                val dayNumber = cellIndex - dayOfWeekIndex + 1
                if (dayNumber in 1..daysInMonth) {
                    val date = displayMonth.atDay(dayNumber)
                    val isSelected = date == selectedDate
                    val isToday = date == today
                    val dayTasks = tasks.filter { it.dateEpochDay == date.toEpochDay() }

                    MonthDayCell(
                        date = date,
                        isSelected = isSelected,
                        isToday = isToday,
                        tasks = dayTasks,
                        members = members,
                        onClick = { onDateSelect(date) }
                    )
                } else {
                    Box(modifier = Modifier.size(38.dp))
                }
            }
        }

        HorizontalDivider(color = OutlineLight.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))

        // Selected Date Details & Chores
        val selectedDateTasks = tasks.filter { it.dateEpochDay == selectedDate.toEpochDay() }
        val locale = if (isHebrew) Locale("he") else Locale.ENGLISH
        val selectedDateFormatted = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale) +
                ", " + selectedDate.dayOfMonth + " " + selectedDate.month.getDisplayName(TextStyle.SHORT, locale)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
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
                        color = GoogleBlue
                    )
                )

                Text(
                    text = "${selectedDateTasks.size} " + if (isHebrew) "משימות" else "tasks",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (selectedDateTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isHebrew) "אין משימות מתוזמנות ליום זה 🌱" else "No chores scheduled for this date 🌱",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(selectedDateTasks) { task ->
                        val assignedMember = members.find { it.id == task.assignedToMemberId }
                        TaskCardItem(
                            task = task,
                            assignedMember = assignedMember,
                            currentMember = currentMember,
                            isHebrew = isHebrew,
                            onToggle = { onToggleTask(task.id) },
                            onEdit = { onEditTask(task) },
                            onDelete = { onDeleteTask(task.id) },
                            onSendNudge = { onSendNudge(task) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonthDayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    tasks: List<FamilyTask>,
    members: List<FamilyMember>,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) GoogleBlueLight else Color.Transparent,
        border = if (isToday) androidx.compose.foundation.BorderStroke(1.5.dp, GoogleBlue) else null,
        modifier = Modifier
            .padding(1.5.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Day number
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isToday) GoogleBlue else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = date.dayOfMonth.toString(),
                    fontSize = 11.sp,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }

            // Compact Task Badges (Up to 2 chips in small readable font)
            if (tasks.isNotEmpty()) {
                tasks.take(2).forEach { task ->
                    val member = members.find { it.id == task.assignedToMemberId }
                    val pillColor = if (task.isCompleted) GoogleGreenLight else Color(member?.colorHex ?: 0xFF1A73E8).copy(alpha = 0.2f)
                    val textColor = if (task.isCompleted) GoogleGreenDark else Color(member?.colorHex ?: 0xFF1A73E8)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(pillColor)
                            .padding(horizontal = 2.dp, vertical = 0.5.dp)
                    ) {
                        Text(
                            text = (if (task.isCompleted) "✓ " else "") + task.title,
                            fontSize = 7.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = textColor,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                if (tasks.size > 2) {
                    Text(
                        text = "+${tasks.size - 2}",
                        fontSize = 7.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    onDateSelect: (LocalDate) -> Unit,
    onToggleTask: (String) -> Unit,
    onEditTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onSendNudge: (FamilyTask) -> Unit
) {
    // 7 days of the selected date's week
    val dayOffset = if (firstDayOfWeek == FirstDayOfWeek.SUNDAY) {
        selectedDate.dayOfWeek.value % 7
    } else {
        selectedDate.dayOfWeek.value - 1
    }
    val weekStart = selectedDate.minusDays(dayOffset.toLong())
    val weekDays = (0..6).map { weekStart.plusDays(it.toLong()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Week Days Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            weekDays.forEach { date ->
                val isSelected = date == selectedDate
                val isToday = date == today
                val locale = if (isHebrew) Locale("he") else Locale.ENGLISH
                val dayLetter = date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale)

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) GoogleBlue else if (isToday) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onDateSelect(date) }
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = dayLetter,
                            fontSize = 11.sp,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = date.dayOfMonth.toString(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Week's selected day tasks list
        val dayTasks = tasks.filter { it.dateEpochDay == selectedDate.toEpochDay() }
        val locale = if (isHebrew) Locale("he") else Locale.ENGLISH
        val title = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + ", " + selectedDate.dayOfMonth + " " + selectedDate.month.getDisplayName(TextStyle.SHORT, locale)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = GoogleBlue
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (dayTasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isHebrew) "אין משימות מתוזמנות ליום זה" else "No chores on this day",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(dayTasks) { task ->
                        val assignedMember = members.find { it.id == task.assignedToMemberId }
                        TaskCardItem(
                            task = task,
                            assignedMember = assignedMember,
                            currentMember = currentMember,
                            isHebrew = isHebrew,
                            onToggle = { onToggleTask(task.id) },
                            onEdit = { onEditTask(task) },
                            onDelete = { onDeleteTask(task.id) },
                            onSendNudge = { onSendNudge(task) }
                        )
                    }
                }
            }
        }
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
    onToggleTask: (String) -> Unit,
    onEditTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onSendNudge: (FamilyTask) -> Unit
) {
    val dayTasks = tasks.filter { it.dateEpochDay == selectedDate.toEpochDay() }
    val schoolDay = when (selectedDate.dayOfWeek.value) {
        7 -> SchoolDay.SUNDAY
        1 -> SchoolDay.MONDAY
        2 -> SchoolDay.TUESDAY
        3 -> SchoolDay.WEDNESDAY
        4 -> SchoolDay.THURSDAY
        5 -> SchoolDay.FRIDAY
        else -> null
    }
    val dayPeriods = if (schoolDay != null) periods.filter { it.day == schoolDay } else emptyList()

    val locale = if (isHebrew) Locale("he") else Locale.ENGLISH
    val dayTitle = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + ", " + selectedDate.dayOfMonth + " " + selectedDate.month.getDisplayName(TextStyle.FULL, locale)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        Text(
            text = dayTitle,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = GoogleBlue
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            contentPadding = PaddingValues(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Section: Kid School Periods for this day
            if (dayPeriods.isNotEmpty()) {
                item {
                    Text(
                        text = if (isHebrew) "🏫 שיעורים ומערכת שעות היום" else "🏫 School Schedule Today",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                items(dayPeriods) { period ->
                    val kid = members.find { it.id == period.childId }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(period.colorHex).copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(period.colorHex).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = kid?.avatarEmoji ?: "🎒", fontSize = 22.sp)

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${period.subjectName} (${period.startTime} - ${period.endTime})",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
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
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Section: Household Tasks for this day
            item {
                Text(
                    text = if (isHebrew) "📋 משימות ומטלות לביצוע" else "📋 Household Chores",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            if (dayTasks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isHebrew) "אין מטלות בית לתאריך זה" else "No household tasks for this date",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                }
            } else {
                items(dayTasks) { task ->
                    val assignedMember = members.find { it.id == task.assignedToMemberId }
                    TaskCardItem(
                        task = task,
                        assignedMember = assignedMember,
                        currentMember = currentMember,
                        isHebrew = isHebrew,
                        onToggle = { onToggleTask(task.id) },
                        onEdit = { onEditTask(task) },
                        onDelete = { onDeleteTask(task.id) },
                        onSendNudge = { onSendNudge(task) }
                    )
                }
            }
        }
    }
}
