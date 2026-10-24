package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.components.FamilyEmptyState
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilyMemberAvatar
import com.example.ui.components.FamilyPrimaryButton
import com.example.ui.components.FamilySectionHeader
import com.example.ui.components.FamilyStatusChip
import com.example.ui.components.SoftGlassScreenBackground
import com.example.ui.components.TimeFormatUtils
import com.example.ui.components.VelvetSearchContainer
import com.example.ui.components.velvetNeumorphicSheen
import com.example.ui.dialogs.TaskDetailDialog
import com.example.ui.theme.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun formatDashboardGreeting(name: String, isHebrew: Boolean, hour: Int = LocalTime.now().hour): String {
    return if (isHebrew) {
        val timeGreeting = when (hour) {
            in 5..11 -> "בוקר טוב"
            in 12..16 -> "צהריים טובים"
            in 17..21 -> "ערב טוב"
            else -> "לילה טוב"
        }
        "$timeGreeting, $name"
    } else {
        val timeGreeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        "$timeGreeting, $name"
    }
}

private fun formatHebrewDashboardDate(date: LocalDate, isHebrew: Boolean): String {
    if (!isHebrew) {
        return date.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy"))
    }
    val dayHe = when (date.dayOfWeek) {
        DayOfWeek.SUNDAY -> "יום ראשון"
        DayOfWeek.MONDAY -> "יום שני"
        DayOfWeek.TUESDAY -> "יום שלישי"
        DayOfWeek.WEDNESDAY -> "יום רביעי"
        DayOfWeek.THURSDAY -> "יום חמישי"
        DayOfWeek.FRIDAY -> "יום שישי"
        DayOfWeek.SATURDAY -> "יום שבת"
    }
    val monthHe = when (date.monthValue) {
        1 -> "ינואר"
        2 -> "פברואר"
        3 -> "מרץ"
        4 -> "אפריל"
        5 -> "מאי"
        6 -> "יוני"
        7 -> "יולי"
        8 -> "אוגוסט"
        9 -> "ספטמבר"
        10 -> "אוקטובר"
        11 -> "נונובמבר".removePrefix("נו")
        12 -> "דצמבר"
        else -> ""
    }
    return "$dayHe, ${date.dayOfMonth} ב$monthHe ${date.year}"
}

@Composable
fun TasksScreen(
    tasks: List<FamilyTask>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    selectedMemberFilter: String?,
    isHebrew: Boolean,
    onFilterChange: (String?) -> Unit,
    onToggleTask: (String) -> Unit,
    onEditTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onSendNudge: (FamilyTask) -> Unit,
    onAddNewTask: () -> Unit,
    onOpenFamilyAdmin: () -> Unit = {},
    onOpenRewards: () -> Unit = {},
    taskSyncStates: Map<String, TaskCompletionSyncState> = emptyMap(),
    taskOperationError: String? = null,
    onApproveTask: (String) -> Unit = {},
    onDismissTaskError: () -> Unit = {},
    periods: List<SchedulePeriod> = emptyList(),
    alarms: List<FamilyAlarm> = emptyList(),
    onNavigateToScreen: (AppScreen) -> Unit = {}
) {
    val glass = LocalSoftGlassColors.current
    var filterTab by remember { mutableIntStateOf(0) } // 0 = All, 1 = Today, 2 = Week, 3 = Completed
    var searchQuery by remember { mutableStateOf("") }
    var selectedTaskForDetail by remember { mutableStateOf<FamilyTask?>(null) }

    val todayDate = remember { LocalDate.now() }
    val todayEpoch = remember(todayDate) { todayDate.toEpochDay() }
    val topChampion = remember(members, currentMember) {
        members.maxByOrNull { it.earnedPoints } ?: currentMember
    }
    val totalPoints = remember(members) {
        members.sumOf { it.earnedPoints }
    }
    val completedThisMonth = remember(tasks) {
        tasks.count { it.isCompleted }
    }

    val todayActiveCount = remember(tasks, todayEpoch) {
        tasks.count { !it.isCompleted && it.dateEpochDay == todayEpoch }
    }
    val awaitingCompletionCount = remember(tasks) {
        tasks.count { !it.isCompleted || !it.approvedByParent }
    }
    val upcomingEventsCount = remember(tasks, todayEpoch) {
        tasks.count { !it.isCompleted && it.dateEpochDay >= todayEpoch }
    }

    val todaySchoolDay = remember(todayDate) {
        SchoolDay.fromJavaDayOfWeek(todayDate.dayOfWeek)
    }
    val todayLessonsCount = remember(periods, todaySchoolDay, currentMember) {
        if (todaySchoolDay == null) {
            0
        } else if (currentMember.isChild) {
            periods.count { it.day == todaySchoolDay && it.childId == currentMember.id }
        } else {
            periods.count { it.day == todaySchoolDay }
        }
    }

    val nextScheduledAlarm = remember(alarms, currentMember, members) {
        alarms
            .filter { alarm ->
                alarm.isEnabled && alarm.isVisibleToMember(currentMember = currentMember, members = members)
            }
            .sortedBy { it.hour * 60 + it.minute }
            .firstOrNull()
    }

    // Filter tasks based on member, status, date, and search query.
    val filteredTasks = tasks.filter { task ->
        val matchesMember = selectedMemberFilter == null || task.assignedToMemberId == selectedMemberFilter
        val matchesSearch = searchQuery.isBlank() ||
            task.title.contains(searchQuery, ignoreCase = true) ||
            task.description.contains(searchQuery, ignoreCase = true)
        val syncState = taskSyncStates[task.id] ?: when {
            task.isCompleted && task.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
            task.isCompleted && !task.approvedByParent -> TaskCompletionSyncState.PENDING_APPROVAL
            else -> TaskCompletionSyncState.IDLE
        }
        val isActiveOrPending = !task.isCompleted ||
            !task.approvedByParent ||
            syncState == TaskCompletionSyncState.SAVING ||
            syncState == TaskCompletionSyncState.PENDING_APPROVAL ||
            syncState == TaskCompletionSyncState.FAILED
        val matchesTab = when (filterTab) {
            0 -> isActiveOrPending
            1 -> isActiveOrPending && task.dateEpochDay == todayEpoch
            2 -> isActiveOrPending && task.dateEpochDay <= todayEpoch + 7
            3 -> task.isCompleted
            else -> true
        }
        matchesMember && matchesSearch && matchesTab
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { innerPadding ->
        SoftGlassScreenBackground(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = innerPadding.calculateBottomPadding() + 92.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("tasks_screen")
            ) {
                // 1. Luxury Soft Neumorphism Hero Greeting + Current Date + Family Profile Banner
                item {
                    FamilyGlassCard(
                        cornerRadius = 26.dp,
                        isTintedBlue = true,
                        elevation = 8.dp,
                        onClick = if (currentMember.isParent) onOpenFamilyAdmin else null,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("our_family_banner_card")
                    ) {
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
                                // Stacked 3D FamilyMemberAvatars
                                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                                    members.take(4).forEach { m ->
                                        FamilyMemberAvatar(
                                            member = m,
                                            size = 38.dp,
                                            isHighlighted = m.id == currentMember.id
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = formatDashboardGreeting(currentMember.name, isHebrew),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glass.textPrimary
                                        )
                                    )
                                    Text(
                                        text = formatHebrewDashboardDate(todayDate, isHebrew),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = glass.primaryAccent,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    FamilyStatusChip(
                                        emoji = "👨‍👩‍👧‍👦",
                                        label = if (isHebrew)
                                            "המשפחה שלנו • ${members.size} חברים פעילים"
                                        else
                                            "Our Family • ${members.size} active members",
                                        containerColor = glass.cardBackground.copy(alpha = 0.88f),
                                        contentColor = glass.textSecondary
                                    )
                                }
                            }

                            if (currentMember.isParent) {
                                val manageShape = RoundedCornerShape(14.dp)
                                Surface(
                                    shape = manageShape,
                                    color = Color.Transparent,
                                    border = BorderStroke(0.95.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .shadow(
                                            elevation = 4.dp,
                                            shape = manageShape,
                                            spotColor = glass.primaryAccent.copy(alpha = 0.25f)
                                        )
                                        .clip(manageShape)
                                        .background(
                                            Brush.verticalGradient(
                                                colors = if (glass.isDark) {
                                                    listOf(Color(0xFF243456), Color(0xFF18233C))
                                                } else {
                                                    listOf(Color.White, Color(0xFFEEF4FE))
                                                }
                                            ),
                                            manageShape
                                        )
                                        .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = if (isHebrew) "ניהול" else "Manage",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = glass.primaryAccent
                                            )
                                        )
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                            contentDescription = null,
                                            tint = glass.primaryAccent,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Quick Screen Navigation Shortcuts Row ("לוח שנה", "מערכת שעות", "שעון מעורר")
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DashboardQuickShortcutChip(
                            emoji = "📅",
                            label = if (isHebrew) "לוח שנה" else "Calendar",
                            onClick = { onNavigateToScreen(AppScreen.CALENDAR) },
                            modifier = Modifier.testTag("quick_action_calendar")
                        )
                        DashboardQuickShortcutChip(
                            emoji = "📚",
                            label = if (isHebrew) "מערכת שעות" else "Timetable",
                            onClick = { onNavigateToScreen(AppScreen.TIMETABLE) },
                            modifier = Modifier.testTag("quick_action_timetable")
                        )
                        DashboardQuickShortcutChip(
                            emoji = "⏰",
                            label = if (isHebrew) "שעון מעורר" else "Alarms",
                            onClick = { onNavigateToScreen(AppScreen.ALARMS) },
                            modifier = Modifier.testTag("quick_action_alarms")
                        )
                    }
                }

                // 3. Luxury Soft Neumorphism Bento Dashboard Summary Cards (Points + Completed + Live Family Overview)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Total Points Velvet Neumorphic Card
                            FamilyGlassCard(
                                cornerRadius = 22.dp,
                                accentColor = GoogleYellow,
                                elevation = 7.dp,
                                onClick = onOpenRewards,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 84.dp)
                                    .testTag("stat_card_points")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.Center) {
                                        Text(
                                            text = "$totalPoints",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (glass.isDark) glass.warningText else PastelYellowDark
                                            )
                                        )
                                        Text(
                                            text = if (isHebrew) "נקודות לפרסים" else "Reward Points",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = if (glass.isDark) glass.textSecondary else PastelYellowDark.copy(alpha = 0.85f),
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                    val trophyShape = RoundedCornerShape(14.dp)
                                    Surface(
                                        shape = trophyShape,
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, (if (glass.isDark) glass.warningText else PastelYellowAccent).copy(alpha = 0.40f)),
                                        modifier = Modifier
                                            .size(42.dp)
                                            .shadow(
                                                elevation = 4.dp,
                                                shape = trophyShape,
                                                spotColor = GoogleYellow.copy(alpha = 0.35f)
                                            )
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = if (glass.isDark) {
                                                        listOf(
                                                            glass.warningText.copy(alpha = 0.32f),
                                                            glass.warningText.copy(alpha = 0.16f)
                                                        )
                                                    } else {
                                                        listOf(
                                                            Color.White,
                                                            PastelYellowBorder.copy(alpha = 0.55f)
                                                        )
                                                    }
                                                ),
                                                trophyShape
                                            )
                                            .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 14.dp, accentColor = GoogleYellow)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.EmojiEvents,
                                                contentDescription = null,
                                                tint = if (glass.isDark) glass.warningText else PastelYellowDark,
                                                modifier = Modifier.size(21.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Completed Chores Velvet Neumorphic Card
                            FamilyGlassCard(
                                cornerRadius = 22.dp,
                                accentColor = GoogleGreen,
                                elevation = 7.dp,
                                onClick = { filterTab = 3 },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 84.dp)
                                    .testTag("stat_card_completed")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.Center) {
                                        Text(
                                            text = "$completedThisMonth",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (glass.isDark) glass.successText else PastelGreenDark
                                            )
                                        )
                                        Text(
                                            text = if (isHebrew) "הושלמו החודש" else "Done This Month",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = if (glass.isDark) glass.textSecondary else PastelGreenDark.copy(alpha = 0.85f),
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                    val doneShape = RoundedCornerShape(14.dp)
                                    Surface(
                                        shape = doneShape,
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, (if (glass.isDark) glass.successText else PastelGreenAccent).copy(alpha = 0.40f)),
                                        modifier = Modifier
                                            .size(42.dp)
                                            .shadow(
                                                elevation = 4.dp,
                                                shape = doneShape,
                                                spotColor = GoogleGreen.copy(alpha = 0.35f)
                                            )
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = if (glass.isDark) {
                                                        listOf(
                                                            glass.successText.copy(alpha = 0.32f),
                                                            glass.successText.copy(alpha = 0.16f)
                                                        )
                                                    } else {
                                                        listOf(
                                                            Color.White,
                                                            PastelGreenBorder.copy(alpha = 0.55f)
                                                        )
                                                    }
                                                ),
                                                doneShape
                                            )
                                            .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 14.dp, accentColor = GoogleGreen)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.TaskAlt,
                                                contentDescription = null,
                                                tint = if (glass.isDark) glass.successText else PastelGreenDark,
                                                modifier = Modifier.size(21.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Compact 4-Mini-Metric Overview Strip (Today's Tasks, Next Alarm, School Lessons, Upcoming Events)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MiniOverviewMetricCard(
                                emoji = "📋",
                                valueText = "$todayActiveCount / $awaitingCompletionCount",
                                label = if (isHebrew) "היום / ממתינות" else "Today / Open",
                                onClick = { filterTab = 1 },
                                modifier = Modifier.weight(1f)
                            )
                            MiniOverviewMetricCard(
                                emoji = "⏰",
                                valueText = nextScheduledAlarm?.formattedTime24h ?: (if (isHebrew) "אין פעיל" else "None"),
                                label = if (isHebrew) "השעון הבא" else "Next Alarm",
                                onClick = { onNavigateToScreen(AppScreen.ALARMS) },
                                modifier = Modifier.weight(1f)
                            )
                            MiniOverviewMetricCard(
                                emoji = "🏫",
                                valueText = if (todayLessonsCount > 0) "$todayLessonsCount" else "—",
                                label = if (isHebrew) "שיעורים היום" else "Lessons Today",
                                onClick = { onNavigateToScreen(AppScreen.TIMETABLE) },
                                modifier = Modifier.weight(1f)
                            )
                            MiniOverviewMetricCard(
                                emoji = "📅",
                                valueText = "$upcomingEventsCount",
                                label = if (isHebrew) "בלוח השנה" else "In Calendar",
                                onClick = { onNavigateToScreen(AppScreen.CALENDAR) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 4. Velvet Neumorphic Points Leader Card
                item {
                    FamilyGlassCard(
                        cornerRadius = 20.dp,
                        accentColor = SoftLavender,
                        elevation = 6.dp,
                        onClick = onOpenRewards,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("achievement_champion_card")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                val crownShape = RoundedCornerShape(12.dp)
                                Surface(
                                    shape = crownShape,
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, SoftLavender.copy(alpha = 0.42f)),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .shadow(
                                            elevation = 4.dp,
                                            shape = crownShape,
                                            spotColor = SoftLavender.copy(alpha = 0.35f)
                                        )
                                        .background(
                                            Brush.verticalGradient(
                                                colors = if (glass.isDark) {
                                                    listOf(
                                                        SoftLavender.copy(alpha = 0.36f),
                                                        SoftLavender.copy(alpha = 0.18f)
                                                    )
                                                } else {
                                                    listOf(
                                                        Color.White,
                                                        SoftLavender.copy(alpha = 0.22f)
                                                    )
                                                }
                                            ),
                                            crownShape
                                        )
                                        .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 12.dp, accentColor = SoftLavender)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.WorkspacePremium,
                                            contentDescription = null,
                                            tint = if (glass.isDark) Color(0xFFDDD6FE) else PastelPurpleDark,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (isHebrew) "מוביל הנקודות:" else "Points Leader:",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (glass.isDark) Color(0xFFDDD6FE) else PastelPurpleDark
                                    )
                                )
                                Text(
                                    text = "${topChampion.avatarEmoji} ${topChampion.name}",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        color = glass.textPrimary,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                            }

                            FamilyStatusChip(
                                emoji = "⭐",
                                label = "${topChampion.earnedPoints} ${if (isHebrew) "נק׳" else "pts"}",
                                containerColor = SoftLavender.copy(alpha = if (glass.isDark) 0.35f else 0.18f),
                                contentColor = if (glass.isDark) Color(0xFFF5F3FF) else PastelPurpleDark
                            )
                        }
                    }
                }

                // 5. Role-Appropriate Velvet Section Header + Primary Button
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        FamilySectionHeader(
                            title = if (isHebrew) "משימות המשפחה" else "Family Tasks",
                            subtitle = if (isHebrew) "מעקב ביצועים ואישור משימות בזמן אמת" else "Real-time family chore tracking",
                            modifier = Modifier.weight(1f)
                        )

                        if (currentMember.isParent) {
                            Spacer(modifier = Modifier.width(8.dp))
                            FamilyPrimaryButton(
                                text = if (isHebrew) "הוסף משימה" else "Add Task",
                                leadingIcon = Icons.Default.Add,
                                onClick = onAddNewTask,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                                modifier = Modifier.testTag("add_task_primary_button")
                            )
                        }
                    }
                }

                // 6. Luxury Soft Neumorphism Filter Tabs, Recessed Search Bar & Member Chips
                item {
                    FamilyGlassCard(
                        cornerRadius = 24.dp,
                        elevation = 6.dp,
                        contentPadding = PaddingValues(13.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterPillChip(
                                    label = if (isHebrew) "הכל" else "All",
                                    isSelected = filterTab == 0,
                                    onClick = { filterTab = 0 }
                                )
                                FilterPillChip(
                                    label = if (isHebrew) "היום" else "Today",
                                    isSelected = filterTab == 1,
                                    onClick = { filterTab = 1 }
                                )
                                FilterPillChip(
                                    label = if (isHebrew) "השבוע" else "Week",
                                    isSelected = filterTab == 2,
                                    onClick = { filterTab = 2 }
                                )
                                FilterPillChip(
                                    label = if (isHebrew) "הושלמו" else "Done",
                                    isSelected = filterTab == 3,
                                    onClick = { filterTab = 3 }
                                )
                            }

                            VelvetSearchContainer(
                                cornerRadius = 16.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            text = if (isHebrew) "חיפוש משימות..." else "Search tasks...",
                                            style = MaterialTheme.typography.bodySmall.copy(color = glass.textMuted)
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = glass.primaryAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        if (searchQuery.isNotBlank()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Clear",
                                                    tint = glass.textSecondary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedBorderColor = glass.primaryAccent.copy(alpha = 0.55f),
                                        unfocusedBorderColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("task_search_field")
                                )
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    val isAllSelected = selectedMemberFilter == null
                                    val chipShape = RoundedCornerShape(14.dp)
                                    Surface(
                                        shape = chipShape,
                                        color = Color.Transparent,
                                        border = BorderStroke(
                                            0.9.dp,
                                            if (isAllSelected) glass.primaryAccent else glass.cardBorder
                                        ),
                                        modifier = Modifier
                                            .shadow(
                                                elevation = if (isAllSelected) 4.dp else 2.dp,
                                                shape = chipShape,
                                                spotColor = if (isAllSelected) glass.primaryAccent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.15f)
                                            )
                                            .clip(chipShape)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = if (isAllSelected) {
                                                        if (glass.isDark) {
                                                            listOf(Color(0xFF60A5FA), Color(0xFF3B82F6))
                                                        } else {
                                                            listOf(Color(0xFF3B82F6), Color(0xFF2563EB))
                                                        }
                                                    } else if (glass.isDark) {
                                                        listOf(Color(0xFF1F2B44), Color(0xFF151F32))
                                                    } else {
                                                        listOf(Color.White, Color(0xFFF1F5FD))
                                                    }
                                                ),
                                                chipShape
                                            )
                                            .clickable { onFilterChange(null) }
                                            .testTag("filter_member_all")
                                    ) {
                                        Text(
                                            text = if (isHebrew) "כל החברים" else "All Members",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (isAllSelected) {
                                                    Color.White
                                                } else {
                                                    glass.textSecondary
                                                }
                                            ),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        )
                                    }
                                }

                                items(members) { m ->
                                    val isSelected = selectedMemberFilter == m.id
                                    val chipShape = RoundedCornerShape(14.dp)
                                    Surface(
                                        shape = chipShape,
                                        color = Color.Transparent,
                                        border = BorderStroke(
                                            if (isSelected) 1.3.dp else 0.9.dp,
                                            if (isSelected) glass.primaryAccent else glass.cardBorder
                                        ),
                                        modifier = Modifier
                                            .shadow(
                                                elevation = if (isSelected) 4.dp else 2.dp,
                                                shape = chipShape,
                                                spotColor = if (isSelected) glass.primaryAccent.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.15f)
                                            )
                                            .clip(chipShape)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = if (isSelected) {
                                                        if (glass.isDark) {
                                                            listOf(Color(0xFF263B6A), Color(0xFF1B2B4E))
                                                        } else {
                                                            listOf(Color.White, Color(0xFFDFEBFE))
                                                        }
                                                    } else if (glass.isDark) {
                                                        listOf(Color(0xFF1F2B44), Color(0xFF151F32))
                                                    } else {
                                                        listOf(Color.White, Color(0xFFF1F5FD))
                                                    }
                                                ),
                                                chipShape
                                            )
                                            .clickable { onFilterChange(m.id) }
                                            .testTag("filter_member_${m.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            FamilyMemberAvatar(
                                                member = m,
                                                size = 22.dp,
                                                isHighlighted = isSelected
                                            )
                                            Text(
                                                text = m.name,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                                    color = if (isSelected) glass.primaryAccent else glass.textPrimary
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 7. Error Banner when a task write or approval fails
                if (!taskOperationError.isNullOrBlank()) {
                    item {
                        FamilyGlassCard(
                            cornerRadius = 18.dp,
                            accentColor = glass.dangerText,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("task_operation_error_banner")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = glass.dangerText,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = if (isHebrew) "שגיאה בשמירת המשימה בענן" else "Cloud Task Sync Error",
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = glass.dangerText
                                            )
                                        )
                                        Text(
                                            text = taskOperationError,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = glass.textPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = onDismissTaskError,
                                    modifier = Modifier.testTag("dismiss_task_error_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss error",
                                        tint = glass.dangerText
                                    )
                                }
                            }
                        }
                    }
                }

                // 8. Tasks List
                if (filteredTasks.isEmpty()) {
                    item {
                        FamilyEmptyState(
                            emoji = if (filterTab == 3) "🏆" else "✨",
                            title = if (filterTab == 3) {
                                if (isHebrew) "אין משימות שהושלמו בסינון זה" else "No completed tasks in this view"
                            } else {
                                if (isHebrew) "אין משימות ממתינות! הכל נקי ומסודר" else "All tasks are done!"
                            },
                            subtitle = if (isHebrew) {
                                "כל המשימות המשפחתיות מסונכרנות בזמן אמת"
                            } else {
                                "All family tasks are synced in real time"
                            }
                        )
                    }
                } else {
                    items(filteredTasks, key = { it.id }) { task ->
                        val assignedMember = members.find { it.id == task.assignedToMemberId }
                        val syncState = taskSyncStates[task.id] ?: when {
                            task.isCompleted && task.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
                            task.isCompleted && !task.approvedByParent -> TaskCompletionSyncState.PENDING_APPROVAL
                            else -> TaskCompletionSyncState.IDLE
                        }
                        TaskItemCardModern(
                            task = task,
                            assignedMember = assignedMember,
                            currentMember = currentMember,
                            syncState = syncState,
                            isHebrew = isHebrew,
                            onToggle = { onToggleTask(task.id) },
                            onApprove = { onApproveTask(task.id) },
                            onEdit = { onEditTask(task) },
                            onDelete = { onDeleteTask(task.id) },
                            onSendNudge = { onSendNudge(task) },
                            onCardClick = { selectedTaskForDetail = task }
                        )
                    }
                }
            }
        }
    }

    // Modal Task Details Bottom Sheet / Dialog
    selectedTaskForDetail?.let { activeTask ->
        val latestTask = tasks.find { it.id == activeTask.id } ?: activeTask
        val activeAssignee = members.find { it.id == latestTask.assignedToMemberId }
        val activeSyncState = taskSyncStates[latestTask.id] ?: when {
            latestTask.isCompleted && latestTask.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
            latestTask.isCompleted && !latestTask.approvedByParent -> TaskCompletionSyncState.PENDING_APPROVAL
            else -> TaskCompletionSyncState.IDLE
        }
        TaskDetailDialog(
            task = latestTask,
            assignedMember = activeAssignee,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = { selectedTaskForDetail = null },
            onToggle = { onToggleTask(latestTask.id) },
            onEdit = { onEditTask(latestTask) },
            onDelete = { onDeleteTask(latestTask.id) },
            onSendNudge = { onSendNudge(latestTask) },
            syncState = activeSyncState,
            onApprove = { onApproveTask(latestTask.id) }
        )
    }
}

@Composable
private fun DashboardQuickShortcutChip(
    emoji: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false
) {
    val glass = LocalSoftGlassColors.current
    val chipShape = RoundedCornerShape(16.dp)
    val surfaceGradient = Brush.verticalGradient(
        colors = if (isPrimary) {
            listOf(Color(0xFF3B82F6), Color(0xFF2563EB))
        } else if (glass.isDark) {
            listOf(Color(0xFF22304C), Color(0xFF162035))
        } else {
            listOf(Color.White, Color(0xFFF0F5FE))
        }
    )

    Surface(
        shape = chipShape,
        color = Color.Transparent,
        border = BorderStroke(
            0.95.dp,
            Brush.verticalGradient(
                colors = if (glass.isDark) {
                    listOf(glass.primaryAccent.copy(alpha = 0.42f), glass.cardBorder)
                } else {
                    listOf(Color.White, glass.primaryAccent.copy(alpha = 0.26f))
                }
            )
        ),
        modifier = modifier
            .shadow(
                elevation = 5.dp,
                shape = chipShape,
                ambientColor = if (glass.isDark) Color.Black.copy(alpha = 0.7f) else Color(0xFF1E3A8A).copy(alpha = 0.14f),
                spotColor = if (glass.isDark) Color.Black.copy(alpha = 0.8f) else Color(0xFF1E3A8A).copy(alpha = 0.22f)
            )
            .clip(chipShape)
            .background(surfaceGradient, chipShape)
            .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 16.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (isPrimary) {
                    Color.White.copy(alpha = 0.22f)
                } else {
                    glass.primaryAccentSoftBg
                },
                border = BorderStroke(0.7.dp, glass.primaryAccent.copy(alpha = 0.25f)),
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = emoji, fontSize = 13.sp)
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isPrimary) {
                        Color.White
                    } else {
                        glass.textPrimary
                    }
                )
            )
        }
    }
}

@Composable
private fun MiniOverviewMetricCard(
    emoji: String,
    valueText: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalSoftGlassColors.current
    FamilyGlassCard(
        cornerRadius = 18.dp,
        elevation = 5.dp,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val iconShape = RoundedCornerShape(10.dp)
            Surface(
                shape = iconShape,
                color = Color.Transparent,
                border = BorderStroke(0.8.dp, glass.primaryAccent.copy(alpha = 0.28f)),
                modifier = Modifier
                    .size(30.dp)
                    .shadow(
                        elevation = 2.dp,
                        shape = iconShape,
                        spotColor = glass.primaryAccent.copy(alpha = 0.22f)
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = if (glass.isDark) {
                                listOf(Color(0xFF253759), glass.primaryAccentSoftBg)
                            } else {
                                listOf(Color.White, glass.primaryAccentSoftBg)
                            }
                        ),
                        iconShape
                    )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = emoji, fontSize = 14.sp)
                }
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = glass.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TaskItemCardModern(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember = FamilyMember(id = "", name = "", role = UserRole.PARENT, colorHex = 0xFF2B6CB0),
    syncState: TaskCompletionSyncState = TaskCompletionSyncState.IDLE,
    isHebrew: Boolean,
    onToggle: () -> Unit,
    onApprove: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSendNudge: () -> Unit = {},
    onCardClick: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val isPendingApproval = task.isCompleted && !task.approvedByParent
    val isFullyApproved = task.isCompleted && task.approvedByParent
    val isSaving = syncState == TaskCompletionSyncState.SAVING
    val isFailed = syncState == TaskCompletionSyncState.FAILED
    val canToggle = currentMember.isParent ||
        task.assignedToMemberId == currentMember.id ||
        task.assignedToMemberId.isBlank()

    var showContextMenu by remember { mutableStateOf(false) }

    val checkBgColor by animateColorAsState(
        targetValue = when {
            isSaving -> glass.primaryAccent.copy(alpha = 0.22f)
            isFailed -> glass.dangerBg
            isFullyApproved -> GoogleGreen
            isPendingApproval -> GoogleYellow
            else -> glass.primaryAccentSoftBg.copy(alpha = if (glass.isDark) 0.50f else 0.65f)
        },
        animationSpec = spring(),
        label = "checkBg"
    )

    val cardAccentColor = when {
        isFailed -> glass.dangerText
        isSaving -> glass.primaryAccent
        isFullyApproved -> GoogleGreen
        isPendingApproval -> GoogleYellow
        else -> null
    }

    val normalizedTime = remember(task.dueTimeFormatted) {
        TimeFormatUtils.normalize24HourString(task.dueTimeFormatted)
    }

    FamilyGlassCard(
        cornerRadius = 22.dp,
        isSelected = isSaving,
        accentColor = cardAccentColor,
        elevation = 6.dp,
        onClick = onCardClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_card_${task.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 3D Luxury Soft Neumorphic Completion Orb
            val orbBorderColor = when {
                isFailed -> glass.dangerText
                isSaving -> glass.primaryAccent
                isFullyApproved -> GoogleGreen
                isPendingApproval -> GoogleYellow
                canToggle -> glass.primaryAccent.copy(alpha = 0.72f)
                else -> glass.cardBorder
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .shadow(
                        elevation = if (task.isCompleted) 5.dp else 3.dp,
                        shape = CircleShape,
                        spotColor = orbBorderColor.copy(alpha = 0.45f)
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = if (task.isCompleted) {
                                listOf(
                                    checkBgColor,
                                    checkBgColor.copy(alpha = 0.85f)
                                )
                            } else if (glass.isDark) {
                                listOf(Color(0xFF243454), checkBgColor)
                            } else {
                                listOf(Color.White, checkBgColor)
                            }
                        ),
                        CircleShape
                    )
                    .border(1.6.dp, orbBorderColor, CircleShape)
                    .clickable(enabled = !isSaving && canToggle) { onToggle() }
                    .testTag("task_toggle_${task.id}"),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isSaving -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = glass.primaryAccent
                        )
                    }
                    task.isCompleted -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    !canToggle -> {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Read Only",
                            tint = glass.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Task Title, Optional Description, and Velvet Status Chips
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (isFullyApproved) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (isFullyApproved) glass.textSecondary else glass.textPrimary
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (task.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = glass.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                val statusLabel = when {
                    isSaving -> if (isHebrew) "שומר בענן..." else "Saving..."
                    isFailed -> if (isHebrew) "שגיאה בשמירה • שוחזר" else "Save failed"
                    isPendingApproval -> if (isHebrew) "נשמר • ממתין לאישור הורה" else "Pending approval"
                    isFullyApproved -> if (isHebrew) "נשמר • הושלם ואושר ✓" else "Saved & Approved ✓"
                    else -> normalizedTime
                }
                val statusTag = when {
                    isSaving -> "task_status_saving_${task.id}"
                    isFailed -> "task_status_failed_${task.id}"
                    isPendingApproval -> "task_status_pending_approval_${task.id}"
                    isFullyApproved -> "task_status_saved_approved_${task.id}"
                    else -> "task_status_idle_${task.id}"
                }
                val statusColor = when {
                    isSaving -> glass.primaryAccent
                    isFailed -> glass.dangerText
                    isPendingApproval -> glass.warningText
                    isFullyApproved -> glass.successText
                    else -> glass.primaryAccent
                }
                val statusBg = when {
                    isSaving -> glass.primaryAccentSoftBg
                    isFailed -> glass.dangerBg
                    isPendingApproval -> glass.warningBg
                    isFullyApproved -> glass.successBg
                    else -> glass.primaryAccentSoftBg.copy(alpha = 0.65f)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    // Points Badge
                    FamilyStatusChip(
                        emoji = "⭐",
                        label = "+${task.points} ${if (isHebrew) "נק׳" else "pts"}",
                        containerColor = if (isFullyApproved) glass.successBg else glass.warningBg,
                        contentColor = if (isFullyApproved) glass.successText else glass.warningText
                    )

                    // Status / Due Time Chip
                    FamilyStatusChip(
                        label = statusLabel,
                        containerColor = statusBg,
                        contentColor = statusColor,
                        modifier = Modifier.testTag(statusTag)
                    )

                    // Recurrence Chip
                    if (task.recurrence.isRecurring) {
                        FamilyStatusChip(
                            emoji = "🔁",
                            label = task.recurrence.getDisplayName(isHebrew),
                            containerColor = glass.primaryAccentSoftBg,
                            contentColor = glass.primaryAccent
                        )
                    }

                    // Assigned Member Chip
                    if (assignedMember != null) {
                        FamilyStatusChip(
                            emoji = assignedMember.avatarEmoji,
                            label = assignedMember.name,
                            containerColor = Color(assignedMember.colorHex).copy(alpha = if (glass.isDark) 0.25f else 0.14f),
                            contentColor = glass.textPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 3D Raised Category Squircle Badge + Contextual Menu for PARENT secondary actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val catShape = RoundedCornerShape(14.dp)
                val catBaseBg = if (glass.isDark) {
                    glass.primaryAccentSoftBg.copy(alpha = 0.65f)
                } else {
                    when (task.category) {
                        TaskCategory.BEDROOM -> GoogleBlueLight
                        TaskCategory.DISHES -> PastelGreenBackground
                        TaskCategory.HOMEWORK -> SoftLavenderLight
                        TaskCategory.CLEANING -> SoftTealLight
                        TaskCategory.GROCERIES -> WarmCoralLight
                        TaskCategory.PETS -> PastelYellowBackground
                        else -> glass.primaryAccentSoftBg
                    }
                }
                Surface(
                    shape = catShape,
                    color = Color.Transparent,
                    border = BorderStroke(0.9.dp, glass.cardBorder),
                    modifier = Modifier
                        .size(42.dp)
                        .shadow(
                            elevation = 3.dp,
                            shape = catShape,
                            spotColor = glass.primaryAccent.copy(alpha = 0.20f)
                        )
                        .background(
                            Brush.verticalGradient(
                                colors = if (glass.isDark) {
                                    listOf(Color(0xFF253555), catBaseBg)
                                } else {
                                    listOf(Color.White, catBaseBg)
                                }
                            ),
                            catShape
                        )
                        .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 14.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = task.category.defaultEmoji, fontSize = 19.sp)
                    }
                }

                if (currentMember.isParent) {
                    Box {
                        IconButton(
                            onClick = { showContextMenu = true },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("task_menu_${task.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = if (isHebrew) "אפשרויות משימה" else "Task Options",
                                tint = glass.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showContextMenu,
                            onDismissRequest = { showContextMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isHebrew) "ערוך משימה" else "Edit Task") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showContextMenu = false
                                    onEdit()
                                }
                            )
                            if (!task.isCompleted) {
                                DropdownMenuItem(
                                    text = { Text(if (isHebrew) "שלח תזכורת" else "Send Reminder") },
                                    leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null) },
                                    onClick = {
                                        showContextMenu = false
                                        onSendNudge()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isHebrew) "מחק משימה" else "Delete Task",
                                        color = glass.dangerText
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = glass.dangerText
                                    )
                                },
                                onClick = {
                                    showContextMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }

        // Parent Approval Action Bar when a child has marked the task completed
        if (currentMember.isParent && isPendingApproval && !isSaving) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = glass.warningBg,
                border = BorderStroke(1.dp, glass.warningText.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isHebrew)
                            "סומן כבוצע ע\"י ${assignedMember?.name ?: "הילד/ה"} וממתין לאישורך"
                        else
                            "Completed by ${assignedMember?.name ?: "child"} — awaiting approval",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = glass.warningText,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FamilyPrimaryButton(
                        text = if (isHebrew) "אשר ביצוע ✓" else "Approve ✓",
                        onClick = onApprove,
                        containerColor = GoogleGreen,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("approve_task_${task.id}")
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FilterPillChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val pillShape = RoundedCornerShape(14.dp)
    val pillBrush = Brush.verticalGradient(
        colors = if (isSelected) {
            if (glass.isDark) {
                listOf(Color(0xFF60A5FA), Color(0xFF3B82F6), Color(0xFF4F46E5))
            } else {
                listOf(Color(0xFF3B82F6), Color(0xFF2563EB), Color(0xFF1D4ED8))
            }
        } else if (glass.isDark) {
            listOf(Color(0xFF1D2840), Color(0xFF141D30))
        } else {
            listOf(Color(0xFFFFFFFF), Color(0xFFEEF3FC))
        }
    )

    Surface(
        shape = pillShape,
        color = Color.Transparent,
        border = BorderStroke(
            0.9.dp,
            if (isSelected) Color.White.copy(alpha = 0.45f) else glass.cardBorder
        ),
        modifier = Modifier
            .weight(1f)
            .shadow(
                elevation = if (isSelected) 5.dp else 2.dp,
                shape = pillShape,
                ambientColor = if (isSelected) glass.primaryAccent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.12f),
                spotColor = if (isSelected) glass.primaryAccent.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.18f)
            )
            .clip(pillShape)
            .background(pillBrush, pillShape)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (isSelected) {
                        Color.White
                    } else {
                        glass.textSecondary
                    },
                    fontWeight = FontWeight.ExtraBold
                )
            )
        }
    }
}


@Composable
fun TaskCardItem(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSendNudge: () -> Unit
) {
    TaskItemCardModern(
        task = task,
        assignedMember = assignedMember,
        currentMember = currentMember,
        isHebrew = isHebrew,
        onToggle = onToggle,
        onEdit = onEdit,
        onDelete = onDelete,
        onSendNudge = onSendNudge,
        onCardClick = onEdit
    )
}
