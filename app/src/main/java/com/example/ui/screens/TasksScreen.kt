package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.components.TimeFormatUtils
import com.example.ui.dialogs.TaskDetailDialog
import com.example.ui.theme.*
import java.time.LocalDate

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
    onDismissTaskError: () -> Unit = {}
) {
    var filterTab by remember { mutableIntStateOf(0) } // 0 = All, 1 = Today, 2 = Week, 3 = Completed
    var searchQuery by remember { mutableStateOf("") }
    var selectedTaskForDetail by remember { mutableStateOf<FamilyTask?>(null) }

    val todayEpoch = remember { LocalDate.now().toEpochDay() }
    val topChampion = remember(members) {
        members.maxByOrNull { it.earnedPoints } ?: currentMember
    }
    val totalPoints = remember(members) {
        members.sumOf { it.earnedPoints }
    }
    val completedThisMonth = remember(tasks) {
        tasks.count { it.isCompleted }
    }

    // Filter tasks based on member, status, date, and search query.
    // Tasks that are SAVING, PENDING_APPROVAL (!approvedByParent), or FAILED stay visible in active tabs
    // so children see pending-approval status and parents see tasks awaiting their approval.
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
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // Role-aware interface: hide task creation FAB from CHILD users
            if (currentMember.isParent) {
                FloatingActionButton(
                    onClick = onAddNewTask,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 3.dp,
                        pressedElevation = 6.dp
                    ),
                    modifier = Modifier.testTag("add_task_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (isHebrew) "הוסף משימה" else "Add Task",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("tasks_screen")
        ) {
            // 1. Compact Header + Family Summary Card
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .then(
                            if (currentMember.isParent) {
                                Modifier.clickable { onOpenFamilyAdmin() }
                            } else {
                                Modifier
                            }
                        )
                        .testTag("our_family_banner_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            // Stacked family avatars
                            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                members.take(4).forEach { m ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Color(m.colorHex).copy(alpha = 0.18f))
                                            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = m.avatarEmoji, fontSize = 15.sp)
                                    }
                                }
                            }

                            Column {
                                Text(
                                    text = if (isHebrew) "שלום, ${currentMember.name}" else "Hello, ${currentMember.name}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Text(
                                    text = if (isHebrew)
                                        "המשפחה שלנו • ${members.size} חברים פעילים"
                                    else
                                        "Our Family • ${members.size} active members",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }

                        if (currentMember.isParent) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = GoogleBlueLight,
                                border = BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "ניהול" else "Manage",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleBlueDark
                                        )
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                        contentDescription = null,
                                        tint = GoogleBlueDark,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Compact Soft-Tactile Stats Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Total Points Card (Soft warm amber/coral)
                    Surface(
                        onClick = onOpenRewards,
                        shape = RoundedCornerShape(18.dp),
                        color = PastelYellowBackground,
                        border = BorderStroke(1.dp, PastelYellowBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(74.dp)
                            .testTag("stat_card_points")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.Center) {
                                Text(
                                    text = "$totalPoints",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = PastelYellowDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew) "נקודות לפרסים" else "Reward Points",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = PastelYellowDark.copy(alpha = 0.85f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = PastelYellowBorder.copy(alpha = 0.45f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.EmojiEvents,
                                        contentDescription = null,
                                        tint = PastelYellowDark,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Completed Chores Card (Soft sage/mint)
                    Surface(
                        onClick = { filterTab = 3 },
                        shape = RoundedCornerShape(18.dp),
                        color = PastelGreenBackground,
                        border = BorderStroke(1.dp, PastelGreenBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(74.dp)
                            .testTag("stat_card_completed")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.Center) {
                                Text(
                                    text = "$completedThisMonth",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = PastelGreenDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew) "הושלמו החודש" else "Done This Month",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = PastelGreenDark.copy(alpha = 0.85f),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = PastelGreenBorder.copy(alpha = 0.45f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.TaskAlt,
                                        contentDescription = null,
                                        tint = PastelGreenDark,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Role-Appropriate Action / Status Bar (PARENT can create tasks; CHILD views assigned tasks)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isHebrew) "משימות המשפחה" else "Family Tasks",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    if (currentMember.isParent) {
                        Button(
                            onClick = onAddNewTask,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp),
                            modifier = Modifier
                                .height(38.dp)
                                .testTag("add_task_primary_button")
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isHebrew) "+ הוסף משימה" else "+ Add Task",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }

                if (!currentMember.isParent) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = PastelGreenBackground,
                        border = BorderStroke(1.dp, PastelGreenBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("child_role_info_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = PastelGreenDark,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = if (isHebrew)
                                        "מחובר כילד/ה (${currentMember.name})"
                                    else
                                        "Signed in as Child (${currentMember.name})",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = PastelGreenDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew)
                                        "סמנו כבוצע את המשימות שמשויכות אליכם כדי לשלוח לאישור הורה ולצבור נקודות"
                                    else
                                        "Complete tasks assigned to you to submit for parent approval",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                )
                            }
                        }
                    }
                }
            }

            // 4. Compact Filter Tabs, Search Bar & Member Chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Filter Pills
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

                    // Compact Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = if (isHebrew) "חיפוש משימות..." else "Search tasks...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task_search_field")
                    )

                    // Member Avatars Filter
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            val isAllSelected = selectedMemberFilter == null
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isAllSelected) GoogleBlueLight else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onFilterChange(null) }
                                    .testTag("filter_member_all")
                            ) {
                                Text(
                                    text = if (isHebrew) "כל החברים" else "All Members",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        items(members) { m ->
                            val isSelected = selectedMemberFilter == m.id
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onFilterChange(m.id) }
                                    .testTag("filter_member_${m.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(text = m.avatarEmoji, fontSize = 13.sp)
                                    Text(
                                        text = m.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Clear Hebrew Error Banner when a task write or approval fails
            if (!taskOperationError.isNullOrBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = GoogleRedLight,
                        border = BorderStroke(1.dp, GoogleRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task_operation_error_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                    tint = GoogleRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = if (isHebrew) "שגיאה בשמירת המשימה בענן" else "Cloud Task Sync Error",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleRed
                                        )
                                    )
                                    Text(
                                        text = taskOperationError,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = TextPrimary,
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
                                    tint = GoogleRed
                                )
                            }
                        }
                    }
                }
            }

            // 6. Tasks List
            if (filteredTasks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = if (filterTab == 3) Icons.Default.EmojiEvents else Icons.Default.CheckCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (filterTab == 3) {
                                    if (isHebrew) "אין משימות שהושלמו בסינון זה" else "No completed tasks in this view"
                                } else {
                                    if (isHebrew) "אין משימות ממתינות! הכל נקי ומסודר" else "All tasks are done!"
                                },
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                        }
                    }
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

            // 7. Compact Soft-Lavender Household Champion Card
            item {
                Surface(
                    onClick = onOpenRewards,
                    shape = RoundedCornerShape(18.dp),
                    color = PastelPurpleBackground,
                    border = BorderStroke(1.dp, PastelPurpleBorder),
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("achievement_champion_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (isHebrew) "מוביל הנקודות במשפחה" else "Household Points Leader",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PastelPurpleDark
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${topChampion.avatarEmoji} ${topChampion.name} • ${topChampion.earnedPoints} ${if (isHebrew) "נקודות" else "points"}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = PastelPurpleBorder.copy(alpha = 0.5f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.WorkspacePremium,
                                    contentDescription = null,
                                    tint = PastelPurpleDark,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
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
            isSaving -> GoogleBlue.copy(alpha = 0.18f)
            isFailed -> GoogleRedLight
            isFullyApproved -> GoogleGreen
            isPendingApproval -> GoogleYellow
            else -> Color.Transparent
        },
        animationSpec = spring(),
        label = "checkBg"
    )

    val cardBorderColor = when {
        isFailed -> GoogleRed
        isSaving -> GoogleBlue
        isFullyApproved -> PastelGreenBorder
        isPendingApproval -> PastelYellowBorder
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    val normalizedTime = remember(task.dueTimeFormatted) {
        TimeFormatUtils.normalize24HourString(task.dueTimeFormatted)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, cardBorderColor),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onCardClick() }
            .testTag("task_card_${task.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Checkbox button (interactive only if user is permitted to toggle this task)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(checkBgColor)
                        .border(
                            2.dp,
                            when {
                                isFailed -> GoogleRed
                                isSaving -> GoogleBlue
                                isFullyApproved -> GoogleGreen
                                isPendingApproval -> GoogleYellow
                                canToggle -> Color(0xFF94A3B8)
                                else -> Color(0xFFE2E8F0)
                            },
                            CircleShape
                        )
                        .clickable(enabled = !isSaving && canToggle) { onToggle() }
                        .testTag("task_toggle_${task.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isSaving -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = GoogleBlue
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
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Task Title, 24h Time, Points, Sync Status & Member info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (isFullyApproved) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (isFullyApproved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

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
                        isSaving -> GoogleBlue
                        isFailed -> GoogleRed
                        isPendingApproval -> PastelYellowDark
                        isFullyApproved -> GoogleGreenDark
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${task.points} ${if (isHebrew) "נק׳" else "pts"}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isFullyApproved) GoogleGreenDark else WarmCoralDark
                            )
                        )

                        Text(text = "•", color = TextSecondary, fontSize = 11.sp)

                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = statusColor,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.testTag(statusTag)
                        )

                        if (assignedMember != null) {
                            Text(text = "•", color = TextSecondary, fontSize = 11.sp)
                            Text(
                                text = "${assignedMember.avatarEmoji} ${assignedMember.name}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Category badge + Contextual menu for PARENT secondary actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when (task.category) {
                            TaskCategory.BEDROOM -> GoogleBlueLight
                            TaskCategory.DISHES -> PastelGreenBackground
                            TaskCategory.HOMEWORK -> SoftLavenderLight
                            TaskCategory.CLEANING -> SoftTealLight
                            TaskCategory.GROCERIES -> WarmCoralLight
                            TaskCategory.PETS -> PastelYellowBackground
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = task.category.defaultEmoji, fontSize = 18.sp)
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            color = GoogleRed
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = GoogleRed
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
                    shape = RoundedCornerShape(12.dp),
                    color = PastelYellowBackground,
                    border = BorderStroke(1.dp, PastelYellowBorder),
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
                                color = PastelYellowDark,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onApprove,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleGreen),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("approve_task_${task.id}")
                        ) {
                            Text(
                                text = if (isHebrew) "אשר ביצוע ✓" else "Approve ✓",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
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
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
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
