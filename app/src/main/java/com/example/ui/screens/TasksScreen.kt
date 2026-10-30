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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.R
import com.example.model.*
import com.example.ui.components.FamilyEmptyState
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilyMemberAvatar
import com.example.ui.components.FamilyPrimaryButton
import com.example.ui.components.FamilySectionHeader
import com.example.ui.components.FamilyStatusChip
import com.example.ui.components.SoftGlassScreenBackground
import com.example.ui.components.TimeFormatUtils
import com.example.ui.components.VelvetCrystal3DAssetIcon
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
        11 -> "נובמבר"
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
    taskProposals: List<TaskCollaborationProposal> = emptyList(),
    onProposeVolunteer: (taskId: String, note: String) -> Unit = { _, _ -> },
    onProposeSwap: (offeredTaskId: String, targetTaskId: String, note: String) -> Unit = { _, _, _ -> },
    onChildRespondProposal: (proposalId: String, approve: Boolean) -> Unit = { _, _ -> },
    onParentDecideProposal: (proposalId: String, approve: Boolean) -> Unit = { _, _ -> },
    onApproveTaskWithSplit: (taskId: String, performerPoints: Int, originalOwnerPoints: Int, bonusPoints: Int) -> Unit = { _, _, _, _ -> },
    tasksViewMode: TasksViewMode = TasksViewMode.STANDARD,
    onToggleTasksViewMode: () -> Unit = {},
    onVolunteerForOpenTask: (taskId: String, note: String) -> Unit = { _, _ -> },
    onCancelTaskProposal: (proposalId: String) -> Unit = {},
    onSelectOpenTaskVolunteer: (proposalId: String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    periods: List<SchedulePeriod> = emptyList(),
    alarms: List<FamilyAlarm> = emptyList(),
    onNavigateToScreen: (AppScreen) -> Unit = {}
) {
    val glass = LocalSoftGlassColors.current
    var filterTab by remember { mutableIntStateOf(0) } // 0 = All, 1 = Today, 2 = Week, 3 = Completed
    var searchQuery by remember { mutableStateOf("") }
    var selectedTaskForDetail by remember { mutableStateOf<FamilyTask?>(null) }
    var targetTaskForSwapDialog by remember { mutableStateOf<FamilyTask?>(null) }
    var taskForParentSplitDialog by remember { mutableStateOf<FamilyTask?>(null) }

    val visibleProposals = remember(taskProposals, currentMember) {
        taskProposals
            .filter { it.isVisibleToMember(currentMember) && it.status != TaskProposalStatus.CANCELLED }
            .sortedWith(
                compareByDescending<TaskCollaborationProposal> { it.isAwaitingActionFromMember(currentMember) }
                    .thenByDescending { it.status.isPending }
                    .thenByDescending { it.updatedAtMillis }
            )
            .take(8)
    }

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

    // Unassigned open tasks available for volunteering
    val openVolunteeringTasks = remember(tasks, searchQuery) {
        tasks.filter { task ->
            task.isUnassignedOpenTask &&
                (searchQuery.isBlank() ||
                    task.title.contains(searchQuery, ignoreCase = true) ||
                    task.description.contains(searchQuery, ignoreCase = true))
        }
    }

    // Filter tasks based on member, status, date, and search query (excluding unassigned open tasks which have their own section).
    val filteredTasks = tasks.filter { task ->
        if (task.isUnassignedOpenTask) return@filter false
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
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = innerPadding.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("tasks_screen")
            ) {
                // 1. Compact Velvet Family Greeting & Non-Overlapping Ordered Avatars Row
                item {
                    FamilyGlassCard(
                        cornerRadius = 24.dp,
                        isTintedBlue = true,
                        elevation = 7.dp,
                        onClick = if (currentMember.isParent) onOpenFamilyAdmin else null,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("our_family_banner_card")
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
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
                                            .testTag("family_management_chip")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = if (isHebrew) "ניהול משפחה" else "Manage",
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

                            // Ordered Family Member Avatars Row (No overlap!)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .testTag("family_avatars_ordered_row"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                members.forEach { m ->
                                    val isCurrentOrFiltered = selectedMemberFilter == m.id ||
                                        (selectedMemberFilter == null && m.id == currentMember.id)
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isCurrentOrFiltered) {
                                            glass.primaryAccent.copy(alpha = if (glass.isDark) 0.24f else 0.12f)
                                        } else {
                                            glass.cardBackground.copy(alpha = 0.72f)
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (isCurrentOrFiltered) glass.primaryAccent.copy(alpha = 0.55f)
                                            else glass.cardBorder
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                onFilterChange(if (selectedMemberFilter == m.id) null else m.id)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            FamilyMemberAvatar(
                                                member = m,
                                                size = 30.dp,
                                                isHighlighted = isCurrentOrFiltered
                                            )
                                            Column {
                                                Text(
                                                    text = m.name,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = glass.textPrimary
                                                    ),
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = "${m.earnedPoints} ${if (isHebrew) "נק׳" else "pts"}",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        color = glass.textSecondary,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Single Consolidated Velvet Summary Strip (Points + Completed/Today — No Duplicate Cards)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Total Points & Leader Card
                        FamilyGlassCard(
                            cornerRadius = 20.dp,
                            accentColor = GoogleYellow,
                            elevation = 6.dp,
                            onClick = onOpenRewards,
                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("stat_card_points")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "$totalPoints",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (glass.isDark) glass.warningText else PastelYellowDark
                                        )
                                    )
                                    Text(
                                        text = if (isHebrew)
                                            "נקודות • מוביל: ${topChampion.name}"
                                        else
                                            "Points • Top: ${topChampion.name}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = glass.textSecondary,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                VelvetCrystal3DAssetIcon(
                                    drawableRes = R.drawable.ic_velvet_points_star,
                                    size = 34.dp
                                )
                            }
                        }

                        // Completed & Today's Active Tasks Card
                        FamilyGlassCard(
                            cornerRadius = 20.dp,
                            accentColor = GoogleGreen,
                            elevation = 6.dp,
                            onClick = { filterTab = if (filterTab == 3) 0 else 3 },
                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("stat_card_completed")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "$completedThisMonth",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (glass.isDark) glass.successText else PastelGreenDark
                                        )
                                    )
                                    Text(
                                        text = if (isHebrew)
                                            "הושלמו • $todayActiveCount להיום"
                                        else
                                            "Done • $todayActiveCount today",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = glass.textSecondary,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                VelvetCrystal3DAssetIcon(
                                    drawableRes = R.drawable.ic_velvet_check_badge,
                                    size = 34.dp
                                )
                            }
                        }
                    }
                }

                // 3. Prominent Velvet Header + Luxury "Add Task" Button with Blue-Purple Gradient & 3D Icon
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        FamilySectionHeader(
                            title = if (isHebrew) "משימות המשפחה" else "Family Tasks",
                            subtitle = if (isHebrew) "מעקב ביצועים ותזכורות בזמן אמת" else "Real-time tasks & reminders",
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(6.dp))
                        val viewToggleShape = RoundedCornerShape(14.dp)
                        Surface(
                            shape = viewToggleShape,
                            color = glass.cardBackground.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clip(viewToggleShape)
                                .clickable { onToggleTasksViewMode() }
                                .testTag("switch_tasks_view_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (tasksViewMode == TasksViewMode.COMPACT) Icons.Default.ViewAgenda else Icons.Default.ViewStream,
                                    contentDescription = if (isHebrew) "החלף תצוגה" else "Switch View",
                                    tint = glass.primaryAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (isHebrew) "החלף תצוגה" else "Switch View",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.primaryAccent
                                    )
                                )
                            }
                        }

                        if (currentMember.isParent) {
                            Spacer(modifier = Modifier.width(8.dp))
                            val addBtnShape = RoundedCornerShape(18.dp)
                            val addBtnBrush = Brush.horizontalGradient(
                                colors = if (glass.isDark) {
                                    listOf(Color(0xFF6366F1), Color(0xFF3B82F6), Color(0xFF7C3AED))
                                } else {
                                    listOf(Color(0xFF4F46E5), Color(0xFF2563EB), Color(0xFF6D28D9))
                                }
                            )
                            Surface(
                                shape = addBtnShape,
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
                                modifier = Modifier
                                    .shadow(
                                        elevation = 8.dp,
                                        shape = addBtnShape,
                                        ambientColor = Color(0xFF4F46E5).copy(alpha = 0.40f),
                                        spotColor = Color(0xFF6D28D9).copy(alpha = 0.55f)
                                    )
                                    .clip(addBtnShape)
                                    .background(addBtnBrush, addBtnShape)
                                    .clickable(onClick = onAddNewTask)
                                    .testTag("add_task_primary_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    VelvetCrystal3DAssetIcon(
                                        drawableRes = R.drawable.ic_velvet_add_plus,
                                        size = 22.dp
                                    )
                                    Text(
                                        text = if (isHebrew) "הוסף משימה" else "Add Task",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Velvet Edition Filter Tabs, Search Bar & Member Filter Chips
                item {
                    FamilyGlassCard(
                        cornerRadius = 22.dp,
                        elevation = 6.dp,
                        contentPadding = PaddingValues(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                VelvetCrystal3DAssetIcon(
                                    drawableRes = R.drawable.ic_velvet_filter,
                                    size = 22.dp
                                )
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
                                            1.dp,
                                            if (isAllSelected) Color.White.copy(alpha = 0.45f) else glass.cardBorder
                                        ),
                                        modifier = Modifier
                                            .shadow(
                                                elevation = if (isAllSelected) 5.dp else 2.dp,
                                                shape = chipShape,
                                                spotColor = if (isAllSelected) Color(0xFF4F46E5).copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.15f)
                                            )
                                            .clip(chipShape)
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = if (isAllSelected) {
                                                        listOf(Color(0xFF4F46E5), Color(0xFF2563EB), Color(0xFF6D28D9))
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
                                            if (isSelected) Color.White.copy(alpha = 0.45f) else glass.cardBorder
                                        ),
                                        modifier = Modifier
                                            .shadow(
                                                elevation = if (isSelected) 5.dp else 2.dp,
                                                shape = chipShape,
                                                spotColor = if (isSelected) Color(0xFF4F46E5).copy(alpha = 0.40f) else Color.Black.copy(alpha = 0.15f)
                                            )
                                            .clip(chipShape)
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = if (isSelected) {
                                                        listOf(Color(0xFF4F46E5), Color(0xFF2563EB), Color(0xFF6D28D9))
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
                                                    color = if (isSelected) Color.White else glass.textPrimary
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Error Banner when a task write or approval fails
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

                // 5.4 Open Tasks for Volunteering ("🙋 משימות פתוחות")
                if (openVolunteeringTasks.isNotEmpty()) {
                    item(key = "open_tasks_volunteering_section") {
                        OpenTasksVolunteeringSection(
                            openTasks = openVolunteeringTasks,
                            taskProposals = taskProposals,
                            currentMember = currentMember,
                            isHebrew = isHebrew,
                            onVolunteer = { task -> onVolunteerForOpenTask(task.id, "") },
                            onCancelVolunteer = { proposalId -> onCancelTaskProposal(proposalId) },
                            onParentSelectVolunteer = { proposalId -> onSelectOpenTaskVolunteer(proposalId) },
                            onEditTask = onEditTask,
                            onDeleteTask = onDeleteTask,
                            onCardClick = { selectedTaskForDetail = it }
                        )
                    }
                }

                // 5.5 Task Collaboration Proposals (Volunteer & Swap Requests)
                if (visibleProposals.isNotEmpty()) {
                    item(key = "task_proposals_section") {
                        TaskCollaborationProposalsCard(
                            proposals = visibleProposals,
                            currentMember = currentMember,
                            isHebrew = isHebrew,
                            onChildRespond = onChildRespondProposal,
                            onParentDecide = onParentDecideProposal,
                            onCancelProposal = onCancelTaskProposal
                        )
                    }
                }

                // 6. Tasks List (Presented Early on Screen)
                if (filteredTasks.isEmpty() && openVolunteeringTasks.isEmpty()) {
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
                        val activeProposalForTask = taskProposals.find {
                            it.status.isPending && (it.primaryTaskId == task.id || it.offeredSwapTaskId == task.id)
                        }
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
                            activeProposal = activeProposalForTask,
                            isCompact = tasksViewMode == TasksViewMode.COMPACT,
                            isHebrew = isHebrew,
                            onToggle = { onToggleTask(task.id) },
                            onApprove = { onApproveTask(task.id) },
                            onOpenSplitApprove = { taskForParentSplitDialog = task },
                            onVolunteer = { onProposeVolunteer(task.id, "") },
                            onProposeSwap = { targetTaskForSwapDialog = task },
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

    // Child Swap Task Selection Dialog
    targetTaskForSwapDialog?.let { targetTask ->
        val myEligibleSwapTasks = remember(tasks, currentMember.id, targetTask.id) {
            tasks.filter {
                it.assignedToMemberId == currentMember.id &&
                    it.id != targetTask.id &&
                    it.isEligibleForChildProposal()
            }
        }
        ProposeTaskSwapDialog(
            targetTask = targetTask,
            targetOwnerName = members.find { it.id == targetTask.assignedToMemberId }?.name.orEmpty(),
            myEligibleTasks = myEligibleSwapTasks,
            isHebrew = isHebrew,
            onConfirmSwap = { offeredTaskId, note ->
                onProposeSwap(offeredTaskId, targetTask.id, note)
                targetTaskForSwapDialog = null
            },
            onDismiss = { targetTaskForSwapDialog = null }
        )
    }

    // Parent Flexible Points Split Approval Dialog
    taskForParentSplitDialog?.let { targetTask ->
        val performerName = members.find { it.id == targetTask.assignedToMemberId }?.name ?: "הילד/ה המבצע/ת"
        val origOwnerName = targetTask.originalAssignedMemberName?.takeIf { it.isNotBlank() }
            ?: targetTask.originalAssignedToMemberId?.let { origId -> members.find { it.id == origId }?.name }
            ?: ""
        ParentTaskPointsSplitDialog(
            task = targetTask,
            performerName = performerName,
            originalOwnerName = origOwnerName,
            isHebrew = isHebrew,
            onConfirmSplit = { performerPts, origPts, bonusPts ->
                onApproveTaskWithSplit(targetTask.id, performerPts, origPts, bonusPts)
                taskForParentSplitDialog = null
            },
            onDismiss = { taskForParentSplitDialog = null }
        )
    }
}

@Composable
private fun TaskItemCardModern(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember = FamilyMember(id = "", name = "", role = UserRole.PARENT, colorHex = 0xFF2B6CB0),
    syncState: TaskCompletionSyncState = TaskCompletionSyncState.IDLE,
    activeProposal: TaskCollaborationProposal? = null,
    isCompact: Boolean = false,
    isHebrew: Boolean,
    onToggle: () -> Unit,
    onApprove: () -> Unit = {},
    onOpenSplitApprove: () -> Unit = {},
    onVolunteer: () -> Unit = {},
    onProposeSwap: () -> Unit = {},
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
    val taskDateFormatted = remember(task.dateEpochDay, isHebrew) {
        val date = LocalDate.ofEpochDay(task.dateEpochDay)
        val today = LocalDate.now()
        when (date) {
            today -> if (isHebrew) "היום" else "Today"
            today.plusDays(1) -> if (isHebrew) "מחר" else "Tomorrow"
            else -> "${date.dayOfMonth}/${date.monthValue}"
        }
    }

    FamilyGlassCard(
        cornerRadius = if (isCompact) 18.dp else 22.dp,
        isSelected = isSaving,
        accentColor = cardAccentColor,
        elevation = if (isCompact) 4.dp else 6.dp,
        onClick = onCardClick,
        contentPadding = PaddingValues(
            horizontal = if (isCompact) 12.dp else 14.dp,
            vertical = if (isCompact) 9.dp else 13.dp
        ),
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
                    task.hasDueTime -> "$taskDateFormatted • $normalizedTime"
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
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = statusBg,
                        modifier = Modifier.testTag(statusTag)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (task.hasDueTime && !task.isCompleted) {
                                VelvetCrystal3DAssetIcon(
                                    drawableRes = R.drawable.ic_velvet_due_clock,
                                    size = 14.dp
                                )
                            }
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            )
                        }
                    }

                    // Active Reminder Badge (with 3D Velvet Bell Icon)
                    if (task.hasDueTime && task.reminderOffset.isEnabled && !task.isCompleted) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SoftLavender.copy(alpha = if (glass.isDark) 0.28f else 0.16f),
                            border = BorderStroke(0.8.dp, SoftLavender.copy(alpha = 0.45f)),
                            modifier = Modifier.testTag("task_reminder_badge_${task.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                VelvetCrystal3DAssetIcon(
                                    drawableRes = R.drawable.ic_velvet_reminder_bell,
                                    size = 14.dp
                                )
                                Text(
                                    text = task.reminderOffset.getShortLabel(isHebrew),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (glass.isDark) Color(0xFFDDD6FE) else PastelPurpleDark
                                    )
                                )
                            }
                        }
                    }

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

                    // Provenance Chip when task was reassigned via Volunteer or Swap
                    if (task.wasTransferredOrSwapped && !task.originalAssignedMemberName.isNullOrBlank()) {
                        FamilyStatusChip(
                            emoji = "🤝",
                            label = if (isHebrew) "במקום ${task.originalAssignedMemberName}" else "For ${task.originalAssignedMemberName}",
                            containerColor = glass.primaryAccentSoftBg,
                            contentColor = glass.primaryAccent
                        )
                    }

                    // Active Collaboration Proposal Status Badge on Task
                    if (activeProposal != null) {
                        FamilyStatusChip(
                            emoji = if (activeProposal.type == TaskProposalType.SWAP_TASKS) "🔁" else "🙋",
                            label = activeProposal.status.getDisplayName(isHebrew),
                            containerColor = glass.warningBg,
                            contentColor = glass.warningText
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

        // Child Collaboration Action Row: Volunteer for Another Child's Task or Propose Swap
        val canChildCollaborate = !currentMember.isParent &&
            task.assignedToMemberId.isNotBlank() &&
            task.assignedToMemberId != currentMember.id &&
            task.isEligibleForChildProposal() &&
            activeProposal == null
        if (canChildCollaborate) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = glass.primaryAccentSoftBg,
                    border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onVolunteer() }
                        .testTag("volunteer_task_btn_${task.id}")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isHebrew) "🙋 אני רוצה לבצע את המשימה" else "🙋 I want to do this task",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.primaryAccent
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SoftLavender.copy(alpha = if (glass.isDark) 0.24f else 0.14f),
                    border = BorderStroke(1.dp, SoftLavender.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onProposeSwap() }
                        .testTag("propose_swap_btn_${task.id}")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isHebrew) "🔁 הצע החלפת משימות" else "🔁 Propose Swap",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = if (glass.isDark) Color(0xFFDDD6FE) else PastelPurpleDark
                            ),
                            maxLines = 1
                        )
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isHebrew)
                                    "סומן כבוצע ע\"י ${assignedMember?.name ?: "הילד/ה"} וממתין לאישורך"
                                else
                                    "Completed by ${assignedMember?.name ?: "child"} — awaiting approval",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = glass.warningText,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            if (task.wasTransferredOrSwapped && !task.originalAssignedMemberName.isNullOrBlank()) {
                                Text(
                                    text = if (isHebrew)
                                        "שויך במקור ל-${task.originalAssignedMemberName} • ניתן לחלק נקודות או להעניק בונוס"
                                    else
                                        "Originally assigned to ${task.originalAssignedMemberName}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = glass.primaryAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (task.wasTransferredOrSwapped) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = glass.primaryAccentSoftBg,
                                    border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onOpenSplitApprove() }
                                        .testTag("split_points_task_${task.id}")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isHebrew) "חלוקת נקודות ⚖️" else "Split ⚖️",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = glass.primaryAccent
                                            )
                                        )
                                    }
                                }
                            }
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
    }
}

@Composable
private fun OpenTasksVolunteeringSection(
    openTasks: List<FamilyTask>,
    taskProposals: List<TaskCollaborationProposal>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onVolunteer: (FamilyTask) -> Unit,
    onCancelVolunteer: (String) -> Unit,
    onParentSelectVolunteer: (String) -> Unit,
    onEditTask: (FamilyTask) -> Unit,
    onDeleteTask: (String) -> Unit,
    onCardClick: (FamilyTask) -> Unit
) {
    val glass = LocalSoftGlassColors.current
    FamilyGlassCard(
        cornerRadius = 22.dp,
        isTintedBlue = true,
        elevation = 5.dp,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("open_tasks_volunteering_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isHebrew) "🙋 משימות פתוחות" else "🙋 Open Tasks",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = glass.textPrimary
                        )
                    )
                    Text(
                        text = if (isHebrew) {
                            "משימות פתוחות להתנדבות ילדי המשפחה באישור הורה"
                        } else {
                            "Open tasks for family children to volunteer"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = glass.textSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
                FamilyStatusChip(
                    emoji = "📢",
                    label = "${openTasks.size} ${if (isHebrew) "פתוחות" else "open"}",
                    containerColor = glass.primaryAccentSoftBg,
                    contentColor = glass.primaryAccent
                )
            }

            openTasks.forEach { task ->
                val openVolunteers = remember(taskProposals, task.id) {
                    taskProposals.filter {
                        it.primaryTaskId == task.id &&
                            it.isOpenTaskVolunteer &&
                            it.status == TaskProposalStatus.PENDING_PARENT_APPROVAL
                    }
                }
                val myPendingVolunteerProposal = remember(openVolunteers, currentMember.id) {
                    openVolunteers.find { it.requesterChildId == currentMember.id }
                }
                val normalizedTime = remember(task.dueTimeFormatted) {
                    TimeFormatUtils.normalize24HourString(task.dueTimeFormatted)
                }
                val taskDateFormatted = remember(task.dateEpochDay, isHebrew) {
                    val date = LocalDate.ofEpochDay(task.dateEpochDay)
                    val today = LocalDate.now()
                    when (date) {
                        today -> if (isHebrew) "היום" else "Today"
                        today.plusDays(1) -> if (isHebrew) "מחר" else "Tomorrow"
                        else -> "${date.dayOfMonth}/${date.monthValue}"
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = glass.cardBackground,
                    border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onCardClick(task) }
                        .testTag("open_task_card_${task.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(text = task.category.defaultEmoji, fontSize = 18.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glass.textPrimary
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (task.description.isNotBlank()) {
                                        Text(
                                            text = task.description,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = glass.textSecondary
                                            ),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            if (currentMember.isParent) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { onEditTask(task) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = if (isHebrew) "ערוך" else "Edit",
                                            tint = glass.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteTask(task.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = if (isHebrew) "מחק" else "Delete",
                                            tint = glass.dangerText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Metadata Chips Row: Points, Date/Time, Volunteers Count
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            FamilyStatusChip(
                                emoji = "⭐",
                                label = "+${task.points} ${if (isHebrew) "נק׳" else "pts"}",
                                containerColor = glass.warningBg,
                                contentColor = glass.warningText
                            )
                            FamilyStatusChip(
                                emoji = "📅",
                                label = if (task.hasDueTime) "$taskDateFormatted • $normalizedTime" else taskDateFormatted,
                                containerColor = glass.primaryAccentSoftBg,
                                contentColor = glass.primaryAccent
                            )
                            FamilyStatusChip(
                                emoji = "🙋",
                                label = if (isHebrew) {
                                    when (openVolunteers.size) {
                                        0 -> "אין מתנדבים עדיין"
                                        1 -> "מתנדב אחד"
                                        else -> "${openVolunteers.size} מתנדבים"
                                    }
                                } else {
                                    "${openVolunteers.size} volunteers"
                                },
                                containerColor = if (openVolunteers.isNotEmpty()) glass.successBg else glass.primaryAccentSoftBg.copy(alpha = 0.6f),
                                contentColor = if (openVolunteers.isNotEmpty()) glass.successText else glass.textSecondary
                            )
                        }

                        // Child Action: Volunteer or Cancel Pending Volunteer Request
                        if (!currentMember.isParent) {
                            if (myPendingVolunteerProposal == null) {
                                FamilyPrimaryButton(
                                    text = if (isHebrew) "🙋 אני מתנדב!" else "🙋 I Volunteer!",
                                    onClick = { onVolunteer(task) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .testTag("volunteer_open_task_btn_${task.id}")
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isHebrew) {
                                            "✓ התנדבת למשימה זו • ממתין לאישור הורה"
                                        } else {
                                            "✓ Volunteered • Awaiting parent approval"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glass.successText
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(
                                        onClick = { onCancelVolunteer(myPendingVolunteerProposal.id) },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, glass.dangerText.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier
                                            .height(32.dp)
                                            .testTag("cancel_open_volunteer_btn_${task.id}")
                                    ) {
                                        Text(
                                            text = if (isHebrew) "בטל התנדבות" else "Cancel",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = glass.dangerText
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Parent Action: View Volunteers and Choose/Approve who receives the task
                        if (currentMember.isParent && openVolunteers.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("parent_open_volunteers_list_${task.id}")
                            ) {
                                Text(
                                    text = if (isHebrew) {
                                        if (openVolunteers.size == 1) "מתנדב/ת למשימה זו (בחר/י לאישור ושיוך):"
                                        else "מספר ילדים התנדבו למשימה זו — בחר/י מי יקבל אותה:"
                                    } else {
                                        "Select volunteer to assign this task:"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.primaryAccent
                                    )
                                )
                                openVolunteers.forEach { proposal ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "🙋 ${proposal.requesterChildName}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = glass.textPrimary
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                        FamilyPrimaryButton(
                                            text = if (isHebrew) {
                                                "בחר ב-${proposal.requesterChildName} ✓"
                                            } else {
                                                "Assign to ${proposal.requesterChildName} ✓"
                                            },
                                            onClick = { onParentSelectVolunteer(proposal.id) },
                                            containerColor = GoogleGreen,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .height(34.dp)
                                                .testTag("select_open_volunteer_${proposal.id}")
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
}

@Composable
private fun TaskCollaborationProposalsCard(
    proposals: List<TaskCollaborationProposal>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onChildRespond: (proposalId: String, approve: Boolean) -> Unit,
    onParentDecide: (proposalId: String, approve: Boolean) -> Unit,
    onCancelProposal: (proposalId: String) -> Unit = {}
) {
    val glass = LocalSoftGlassColors.current
    FamilyGlassCard(
        cornerRadius = 22.dp,
        isTintedBlue = true,
        elevation = 4.dp,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task_proposals_banner")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isHebrew) "🤝 הצעות שיתוף והחלפת משימות" else "🤝 Task Volunteering & Swap Requests",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glass.textPrimary
                    )
                )
                FamilyStatusChip(
                    label = "${proposals.count { it.status.isPending }} ${if (isHebrew) "פעילות" else "active"}",
                    containerColor = glass.primaryAccentSoftBg,
                    contentColor = glass.primaryAccent
                )
            }

            proposals.forEach { proposal ->
                val isAwaitingMyChildResponse = !currentMember.isParent &&
                    !proposal.isOpenTaskVolunteer &&
                    proposal.status == TaskProposalStatus.PENDING_CHILD_APPROVAL &&
                    proposal.targetChildId == currentMember.id
                val isAwaitingParentDecision = currentMember.isParent &&
                    proposal.status == TaskProposalStatus.PENDING_PARENT_APPROVAL
                val canRequesterCancel = !currentMember.isParent &&
                    proposal.requesterChildId == currentMember.id &&
                    proposal.status.isPending

                val statusColor = when (proposal.status) {
                    TaskProposalStatus.APPROVED_BY_PARENT -> GoogleGreen
                    TaskProposalStatus.CLOSED_NOT_SELECTED -> glass.textSecondary
                    TaskProposalStatus.REJECTED_BY_CHILD, TaskProposalStatus.REJECTED_BY_PARENT, TaskProposalStatus.CANCELLED -> GoogleRed
                    else -> Color(0xFFE8710A)
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.cardBackground,
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proposal_card_${proposal.id}")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = when {
                                    proposal.isOpenTaskVolunteer -> {
                                        if (isHebrew) "📢 התנדבות למשימה פתוחה" else "📢 Open Task Volunteer"
                                    }
                                    proposal.type == TaskProposalType.SWAP_TASKS -> {
                                        if (isHebrew) "🔁 הצעת החלפת משימות" else "🔁 Task Swap Proposal"
                                    }
                                    else -> {
                                        if (isHebrew) "🙋 הצעה לביצוע משימה" else "🙋 Volunteer Proposal"
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = glass.primaryAccent
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = statusColor.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = proposal.status.getDisplayName(isHebrew),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        val desc = when {
                            proposal.isOpenTaskVolunteer -> {
                                if (isHebrew) {
                                    "${proposal.requesterChildName} מתנדב/ת לבצע את המשימה הפתוחה \"${proposal.primaryTaskTitle}\" (+${proposal.primaryTaskPoints} נק׳)"
                                } else {
                                    "${proposal.requesterChildName} volunteers for open task \"${proposal.primaryTaskTitle}\" (+${proposal.primaryTaskPoints} pts)"
                                }
                            }
                            proposal.type == TaskProposalType.SWAP_TASKS -> {
                                if (isHebrew) {
                                    "${proposal.requesterChildName} מציע/ה להחליף את \"${proposal.offeredSwapTaskTitle.orEmpty()}\" (${proposal.offeredSwapTaskPoints ?: 0} נק׳) תמורת \"${proposal.primaryTaskTitle}\" (${proposal.primaryTaskPoints} נק׳) של ${proposal.targetChildName}"
                                } else {
                                    "${proposal.requesterChildName} proposes swapping \"${proposal.offeredSwapTaskTitle.orEmpty()}\" for \"${proposal.primaryTaskTitle}\" (${proposal.targetChildName})"
                                }
                            }
                            else -> {
                                if (isHebrew) {
                                    "${proposal.requesterChildName} מבקש/ת לבצע את \"${proposal.primaryTaskTitle}\" (+${proposal.primaryTaskPoints} נק׳) במקום ${proposal.targetChildName}"
                                } else {
                                    "${proposal.requesterChildName} wants to perform \"${proposal.primaryTaskTitle}\" instead of ${proposal.targetChildName}"
                                }
                            }
                        }

                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = glass.textPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )

                        if (isAwaitingMyChildResponse) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FamilyPrimaryButton(
                                    text = if (isHebrew) "אשר העברה להורה ✓" else "Approve ✓",
                                    onClick = { onChildRespond(proposal.id, true) },
                                    containerColor = GoogleGreen,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .testTag("child_approve_proposal_${proposal.id}")
                                )
                                OutlinedButton(
                                    onClick = { onChildRespond(proposal.id, false) },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, GoogleRed.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .height(36.dp)
                                        .testTag("child_reject_proposal_${proposal.id}")
                                ) {
                                    Text(
                                        text = if (isHebrew) "דחה" else "Reject",
                                        color = GoogleRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        if (canRequesterCancel) {
                            OutlinedButton(
                                onClick = { onCancelProposal(proposal.id) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, glass.dangerText.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("child_cancel_proposal_${proposal.id}")
                            ) {
                                Text(
                                    text = if (isHebrew) "בטל בקשה" else "Cancel Request",
                                    color = glass.dangerText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (isAwaitingParentDecision) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FamilyPrimaryButton(
                                    text = if (proposal.isOpenTaskVolunteer) {
                                        if (isHebrew) "בחר ב-${proposal.requesterChildName} למשימה ✓" else "Select ${proposal.requesterChildName} ✓"
                                    } else {
                                        if (isHebrew) "אשר שינוי שיוך (הורה) ✓" else "Parent Approve ✓"
                                    },
                                    onClick = { onParentDecide(proposal.id, true) },
                                    containerColor = GoogleGreen,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .testTag("parent_approve_proposal_${proposal.id}")
                                )
                                OutlinedButton(
                                    onClick = { onParentDecide(proposal.id, false) },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, GoogleRed.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .height(36.dp)
                                        .testTag("parent_reject_proposal_${proposal.id}")
                                ) {
                                    Text(
                                        text = if (isHebrew) "דחה" else "Reject",
                                        color = GoogleRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
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

@Composable
private fun ProposeTaskSwapDialog(
    targetTask: FamilyTask,
    targetOwnerName: String,
    myEligibleTasks: List<FamilyTask>,
    isHebrew: Boolean,
    onConfirmSwap: (offeredTaskId: String, note: String) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var selectedOfferedTaskId by remember(myEligibleTasks) {
        mutableStateOf(myEligibleTasks.firstOrNull()?.id.orEmpty())
    }
    var noteText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("propose_task_swap_dialog"),
        title = {
            Text(
                text = if (isHebrew) "🔁 הצעת החלפת משימות" else "🔁 Propose Task Swap",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isHebrew) {
                        "המשימה המבוקשת של $targetOwnerName: \"${targetTask.title}\" (+${targetTask.points} נק׳)"
                    } else {
                        "Requested task from $targetOwnerName: \"${targetTask.title}\" (+${targetTask.points} pts)"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.primaryAccent
                    )
                )

                if (myEligibleTasks.isEmpty()) {
                    Text(
                        text = if (isHebrew) {
                            "אין לך כרגע משימות פעילות פנויות להציע להחלפה. עדיין באפשרותך להציע לבצע את המשימה באמצעות הכפתור \"🙋 אני רוצה לבצע את המשימה\"."
                        } else {
                            "You have no active tasks available to swap right now."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = glass.dangerText)
                    )
                } else {
                    Text(
                        text = if (isHebrew) "בחר/י משימה שלך להחלפה:" else "Choose one of your tasks to offer:",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                    )
                    myEligibleTasks.forEach { myTask ->
                        val isSelected = selectedOfferedTaskId == myTask.id
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) glass.primaryAccentSoftBg else glass.screenBackground,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) glass.primaryAccent else glass.cardBorder
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedOfferedTaskId = myTask.id }
                                .testTag("swap_offer_option_${myTask.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${myTask.category.defaultEmoji} ${myTask.title}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = glass.textPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "+${myTask.points} ⭐",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = glass.primaryAccent
                                    )
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text(if (isHebrew) "הערה קצרה (אופציונלי)" else "Short note (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (myEligibleTasks.isNotEmpty()) {
                FamilyPrimaryButton(
                    text = if (isHebrew) "שלח הצעת החלפה 🔁" else "Send Swap Proposal 🔁",
                    enabled = selectedOfferedTaskId.isNotBlank(),
                    onClick = { onConfirmSwap(selectedOfferedTaskId, noteText) },
                    modifier = Modifier.testTag("confirm_propose_swap_btn")
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (isHebrew) "ביטול" else "Cancel",
                    color = glass.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun ParentTaskPointsSplitDialog(
    task: FamilyTask,
    performerName: String,
    originalOwnerName: String,
    isHebrew: Boolean,
    onConfirmSplit: (performerPoints: Int, originalOwnerPoints: Int, bonusPoints: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var splitMode by remember { mutableIntStateOf(0) } // 0 = 100% performer, 1 = 100% original owner, 2 = custom split
    var customPerformerText by remember(task.points) {
        mutableStateOf(((task.points + 1) / 2).toString())
    }
    var customOrigText by remember(task.points) {
        mutableStateOf((task.points / 2).toString())
    }
    var bonusPointsText by remember { mutableStateOf("0") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val performerPoints = when (splitMode) {
        0 -> task.points
        1 -> 0
        else -> customPerformerText.trim().toIntOrNull() ?: 0
    }
    val originalOwnerPoints = when (splitMode) {
        0 -> 0
        1 -> task.points
        else -> customOrigText.trim().toIntOrNull() ?: 0
    }
    val bonusPoints = bonusPointsText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("parent_points_split_dialog"),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (isHebrew) "⚖️ אישור וחלוקת נקודות למשימה" else "⚖️ Approve & Split Task Points",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glass.textPrimary
                    )
                )
                Text(
                    text = "${task.title} • ${task.points} ${if (isHebrew) "נקודות במשימה" else "task points"}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = glass.primaryAccent,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = splitMode == 0,
                    onClick = {
                        splitMode = 0
                        errorMsg = null
                    },
                    label = {
                        Text(
                            text = if (isHebrew) "100% למבצע/ת ($performerName: +${task.points} נק׳)" else "100% to Performer ($performerName)",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("split_mode_performer_100")
                )

                if (originalOwnerName.isNotBlank()) {
                    FilterChip(
                        selected = splitMode == 1,
                        onClick = {
                            splitMode = 1
                            errorMsg = null
                        },
                        label = {
                            Text(
                                text = if (isHebrew) "100% לבעלים המקורי ($originalOwnerName: +${task.points} נק׳)" else "100% to Original ($originalOwnerName)",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("split_mode_original_100")
                    )

                    FilterChip(
                        selected = splitMode == 2,
                        onClick = {
                            splitMode = 2
                            errorMsg = null
                        },
                        label = {
                            Text(
                                text = if (isHebrew) "חלוקה מותאמת אישית בין $performerName ו-$originalOwnerName" else "Custom Split",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("split_mode_custom")
                    )
                }

                if (splitMode == 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customPerformerText,
                            onValueChange = {
                                customPerformerText = it.filter { c -> c.isDigit() }
                                errorMsg = null
                            },
                            label = { Text(performerName) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("split_performer_points_input")
                        )
                        OutlinedTextField(
                            value = customOrigText,
                            onValueChange = {
                                customOrigText = it.filter { c -> c.isDigit() }
                                errorMsg = null
                            },
                            label = { Text(originalOwnerName) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("split_original_points_input")
                        )
                    }
                }

                OutlinedTextField(
                    value = bonusPointsText,
                    onValueChange = {
                        bonusPointsText = it.filter { c -> c.isDigit() }
                        errorMsg = null
                    },
                    label = {
                        Text(
                            if (isHebrew) "בונוס מיוחד מהורה ל-$performerName (ללא הפחתה)"
                            else "Extra Parent Helping Bonus for $performerName"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("split_bonus_points_input")
                )

                errorMsg?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GoogleRed,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        },
        confirmButton = {
            FamilyPrimaryButton(
                text = if (isHebrew) "אשר משימה וחלק נקודות ✓" else "Approve & Split ✓",
                onClick = {
                    if (performerPoints < 0 || originalOwnerPoints < 0) {
                        errorMsg = if (isHebrew) "נקודות חייבות להיות חיוביות" else "Points must be non-negative"
                    } else if (performerPoints + originalOwnerPoints > task.points) {
                        errorMsg = if (isHebrew) {
                            "סך החלוקה (${performerPoints + originalOwnerPoints}) עולה על נקודות המשימה (${task.points})"
                        } else {
                            "Split total exceeds task points (${task.points})"
                        }
                    } else {
                        onConfirmSplit(performerPoints, originalOwnerPoints, bonusPoints)
                    }
                },
                modifier = Modifier.testTag("confirm_split_points_btn")
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (isHebrew) "ביטול" else "Cancel",
                    color = glass.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun RowScope.FilterPillChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val pillShape = RoundedCornerShape(14.dp)
    val pillBrush = Brush.horizontalGradient(
        colors = if (isSelected) {
            if (glass.isDark) {
                listOf(Color(0xFF6366F1), Color(0xFF3B82F6), Color(0xFF7C3AED))
            } else {
                listOf(Color(0xFF4F46E5), Color(0xFF2563EB), Color(0xFF6D28D9))
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
            0.95.dp,
            if (isSelected) Color.White.copy(alpha = 0.48f) else glass.cardBorder
        ),
        modifier = Modifier
            .weight(1f)
            .shadow(
                elevation = if (isSelected) 6.dp else 2.dp,
                shape = pillShape,
                ambientColor = if (isSelected) Color(0xFF4F46E5).copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.12f),
                spotColor = if (isSelected) Color(0xFF6D28D9).copy(alpha = 0.48f) else Color.Black.copy(alpha = 0.18f)
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


