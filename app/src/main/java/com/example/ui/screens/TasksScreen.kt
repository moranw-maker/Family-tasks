package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
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
    onOpenRewards: () -> Unit = {}
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

    // Filter tasks based on member, status, date, and search query
    val filteredTasks = tasks.filter { task ->
        val matchesMember = selectedMemberFilter == null || task.assignedToMemberId == selectedMemberFilter
        val matchesSearch = searchQuery.isBlank() || task.title.contains(searchQuery, ignoreCase = true) || task.description.contains(searchQuery, ignoreCase = true)
        val matchesTab = when (filterTab) {
            0 -> !task.isCompleted
            1 -> !task.isCompleted && task.dateEpochDay == todayEpoch
            2 -> !task.isCompleted && task.dateEpochDay <= todayEpoch + 7
            3 -> task.isCompleted
            else -> true
        }
        matchesMember && matchesSearch && matchesTab
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddNewTask,
                shape = CircleShape,
                containerColor = VibrantActionBlue,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(6.dp),
                modifier = Modifier.testTag("add_task_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Task", modifier = Modifier.size(24.dp))
            }
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 10.dp,
                bottom = innerPadding.calculateBottomPadding() + 90.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF3F7FE),
                            Color(0xFFF8FAFD),
                            Color(0xFFFFFFFF)
                        )
                    )
                )
        ) {
            // 1. Welcome Greeting Banner (matches Image 2 Screen 2)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = if (isHebrew) "שלום, ${currentMember.name}! 👋" else "Hello, ${currentMember.name}! 👋",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = if (isHebrew) "המשפחה שלך מחכה לך" else "Your family is waiting for you",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }

                    // Sunny weather / cheerful icon
                    Text(text = "☀️", fontSize = 26.sp)
                }
            }

            // 2. Top "Our Family" Card (matches Image 1 Right Phone)
            item {
                Surface(
                    onClick = onOpenFamilyAdmin,
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, OutlineLight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("our_family_banner_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Stacked family avatars
                            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                members.take(4).forEach { m ->
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(Color(m.colorHex).copy(alpha = 0.2f))
                                            .border(1.5.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = m.avatarEmoji, fontSize = 17.sp)
                                    }
                                }
                            }

                            Column {
                                Text(
                                    text = if (isHebrew) "המשפחה שלנו" else "Our Family",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                                Text(
                                    text = if (isHebrew) "${members.size} חברים בבית" else "${members.size} members",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = VibrantActionBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 3. Stats Row (matches Image 1 Right Phone & Image 2 Screen 2)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Total Points Card (Yellow pastel)
                    Surface(
                        onClick = onOpenRewards,
                        shape = RoundedCornerShape(22.dp),
                        color = PastelYellowBackground,
                        border = BorderStroke(1.dp, PastelYellowBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(88.dp)
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
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = PastelYellowDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew) "נקודות כוללות" else "Total Points",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = PastelYellowDark.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Text(text = "⭐", fontSize = 28.sp)
                        }
                    }

                    // Completed Chores Card (Green pastel)
                    Surface(
                        onClick = { filterTab = 3 },
                        shape = RoundedCornerShape(22.dp),
                        color = PastelGreenBackground,
                        border = BorderStroke(1.dp, PastelGreenBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(88.dp)
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
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = PastelGreenDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew) "משימות החודש" else "Monthly Tasks",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = PastelGreenDark.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Text(text = "✅", fontSize = 28.sp)
                        }
                    }
                }
            }

            // 4. Section Title & Prominent Primary "+ הוסף משימה" Button
            item {
                Column {
                    Text(
                        text = if (isHebrew) "המשימות שלי" else "My Tasks",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        ),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    Button(
                        onClick = onAddNewTask,
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("add_task_primary_button")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isHebrew) "+ הוסף משימה" else "+ Add Task",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    }
                }
            }

            // 5. Filter Tabs & Search Bar (matches Image 2 Screen 3)
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

                    // Search text field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(if (isHebrew) "חיפוש משימות..." else "Search chores...") },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = VibrantActionBlue,
                            unfocusedBorderColor = OutlineLight
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("task_search_field")
                    )

                    // Member Avatars Filter
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selectedMemberFilter == null) GoogleBlueLight else Color.White,
                                border = BorderStroke(1.dp, if (selectedMemberFilter == null) VibrantActionBlue else OutlineLight),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onFilterChange(null) }
                            ) {
                                Text(
                                    text = if (isHebrew) "כל החברים" else "All Members",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (selectedMemberFilter == null) VibrantActionBlue else TextSecondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }

                        items(members) { m ->
                            val isSelected = selectedMemberFilter == m.id
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) GoogleBlueLight else Color.White,
                                border = BorderStroke(1.dp, if (isSelected) VibrantActionBlue else OutlineLight),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onFilterChange(m.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(text = m.avatarEmoji, fontSize = 14.sp)
                                    Text(
                                        text = m.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) VibrantActionBlue else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. Tasks List
            if (filteredTasks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, OutlineLight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Text(text = if (filterTab == 3) "🏆" else "✨", fontSize = 42.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (filterTab == 3) {
                                    if (isHebrew) "אין משימות שהושלמו בסינון זה" else "No completed tasks in this view"
                                } else {
                                    if (isHebrew) "אין משימות ממתינות! הכל נקי ומסודר 👏" else "All chores are done! Awesome"
                                },
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            } else {
                items(filteredTasks, key = { it.id }) { task ->
                    val assignedMember = members.find { it.id == task.assignedToMemberId }
                    TaskItemCardModern(
                        task = task,
                        assignedMember = assignedMember,
                        isHebrew = isHebrew,
                        onToggle = { onToggleTask(task.id) },
                        onCardClick = { selectedTaskForDetail = task }
                    )
                }
            }

            // 7. "🏆 ההישגים שלנו" (Our Achievements - matches Image 1 Right Phone)
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isHebrew) "ההישגים שלנו 🏆" else "Our Achievements 🏆",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    onClick = onOpenRewards,
                    shape = RoundedCornerShape(22.dp),
                    color = PastelPurpleBackground,
                    border = BorderStroke(1.dp, PastelPurpleBorder),
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("achievement_champion_card")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFFEDE4FF),
                                        Color(0xFFF7F3FF),
                                        Color(0xFFE9DCFC)
                                    )
                                )
                            )
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = if (isHebrew) "!אלוף המשפחה" else "Household Champion!",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = PastelPurpleDark
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${topChampion.name} • ${topChampion.earnedPoints} ${if (isHebrew) "נקודות" else "points"}",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = TextSecondary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }

                            Text(text = "👑", fontSize = 36.sp)
                        }
                    }
                }
            }
        }
    }

    // Modal Task Details Dialog (matches Image 2 Screen 4)
    selectedTaskForDetail?.let { activeTask ->
        val activeAssignee = members.find { it.id == activeTask.assignedToMemberId }
        TaskDetailDialog(
            task = activeTask,
            assignedMember = activeAssignee,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = { selectedTaskForDetail = null },
            onToggle = { onToggleTask(activeTask.id) },
            onEdit = { onEditTask(activeTask) },
            onDelete = { onDeleteTask(activeTask.id) },
            onSendNudge = { onSendNudge(activeTask) }
        )
    }
}

@Composable
private fun TaskItemCardModern(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    isHebrew: Boolean,
    onToggle: () -> Unit,
    onCardClick: () -> Unit
) {
    val checkBgColor by animateColorAsState(
        targetValue = if (task.isCompleted) Color(0xFF10B981) else Color.Transparent,
        animationSpec = spring(),
        label = "checkBg"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        border = BorderStroke(1.dp, if (task.isCompleted) PastelGreenBorder else OutlineLight),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onCardClick() }
            .testTag("task_card_${task.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Checkbox button on the left (RTL aligned)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(checkBgColor)
                    .border(
                        2.dp,
                        if (task.isCompleted) Color(0xFF10B981) else Color(0xFFCBD5E1),
                        CircleShape
                    )
                    .clickable { onToggle() }
                    .testTag("task_toggle_${task.id}"),
                contentAlignment = Alignment.Center
            ) {
                if (task.isCompleted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Task Title, Points & Member info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (task.isCompleted) Color(0xFF94A3B8) else TextPrimary
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${task.points} ${if (isHebrew) "נקודות" else "pts"}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (task.isCompleted) Color(0xFF10B981) else Color(0xFFE11D48)
                        )
                    )

                    Text(text = "•", color = TextSecondary, fontSize = 11.sp)

                    Text(
                        text = if (task.isCompleted) {
                            if (isHebrew) "הושלם" else "Done"
                        } else {
                            if (isHebrew) "היום" else "Today"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    if (assignedMember != null) {
                        Text(text = "•", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            text = "${assignedMember.avatarEmoji} ${assignedMember.name}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Right Category Icon badge (matches Image 1 Right Phone)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = when (task.category) {
                    TaskCategory.BEDROOM -> Color(0xFFEFF6FF)
                    TaskCategory.DISHES -> Color(0xFFECFDF5)
                    TaskCategory.HOMEWORK -> Color(0xFFFAF5FF)
                    TaskCategory.CLEANING -> Color(0xFFF0FDF4)
                    TaskCategory.GROCERIES -> Color(0xFFFDF2F8)
                    TaskCategory.PETS -> Color(0xFFFFFBEB)
                    else -> Color(0xFFF8FAFC)
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = task.category.defaultEmoji, fontSize = 22.sp)
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
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) VibrantActionBlue else Color.White,
        border = BorderStroke(1.dp, if (isSelected) VibrantActionBlue else OutlineLight),
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) Color.White else TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
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
        isHebrew = isHebrew,
        onToggle = onToggle,
        onCardClick = onEdit
    )
}
