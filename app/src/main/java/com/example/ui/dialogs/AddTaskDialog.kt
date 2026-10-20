package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.*
import com.example.ui.components.TimeFormatUtils
import com.example.ui.components.TimePickerField
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
    var title by remember { mutableStateOf(taskToEdit?.title ?: "") }
    var description by remember { mutableStateOf(taskToEdit?.description ?: "") }
    var selectedMemberId by remember { mutableStateOf(taskToEdit?.assignedToMemberId ?: (members.firstOrNull()?.id ?: "")) }
    var selectedCategory by remember { mutableStateOf(taskToEdit?.category ?: TaskCategory.CLEANING) }
    var selectedPriority by remember { mutableStateOf(taskToEdit?.priority ?: TaskPriority.MEDIUM) }
    var selectedRecurrence by remember { mutableStateOf(taskToEdit?.recurrence ?: TaskRecurrence.DAILY) }
    var dueTime by remember { mutableStateOf(TimeFormatUtils.normalize24HourString(taskToEdit?.dueTimeFormatted, "18:00")) }
    var points by remember { mutableIntStateOf(taskToEdit?.points ?: 15) }
    var selectedTaskDate by remember {
        mutableStateOf(
            taskToEdit?.let { LocalDate.ofEpochDay(it.dateEpochDay) } ?: initialDate
        )
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

                // Gemini Smart Suggestions
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
                                    description = if (isHebrew) "משימה מומלצת מ-Gemini לארגון הבית" else "Suggested task by Gemini"
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

                // Assign to Member (Select who does this task)
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
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, GoogleBlue) else null,
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

                // Date Quick Selection (preserves selectedTaskDate when changing time)
                val today = remember { LocalDate.now() }
                val dateOptions = remember(initialDate, today, isHebrew) {
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

                Spacer(modifier = Modifier.height(10.dp))

                // Time & Points Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimePickerField(
                        label = if (isHebrew) "שעת יעד" else "Due Time",
                        time = dueTime,
                        onTimeSelected = { dueTime = it },
                        isHebrew = isHebrew,
                        modifier = Modifier.weight(1f)
                    )

                    // Points Chips
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHebrew) "נקודות פרס:" else "Reward Points:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 15, 20).forEach { pt ->
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
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Submit Button
                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            val normalized24h = TimeFormatUtils.normalize24HourString(dueTime, "18:00")
                            val (preservedEpochDay, _) = TimeFormatUtils.combineDateAnd24HourTimeToEpochMillis(
                                dateEpochDay = selectedTaskDate.toEpochDay(),
                                time24h = normalized24h
                            )
                            val taskResult = if (taskToEdit != null) {
                                taskToEdit.copy(
                                    title = title.trim(),
                                    description = description.trim(),
                                    category = selectedCategory,
                                    assignedToMemberId = selectedMemberId,
                                    dateEpochDay = preservedEpochDay,
                                    dueTimeFormatted = normalized24h,
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
                                    priority = selectedPriority,
                                    points = points,
                                    recurrence = selectedRecurrence,
                                    isCompleted = false
                                )
                            }
                            onSaveTask(taskResult)
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("save_task_btn")
                ) {
                    Text(
                        text = if (taskToEdit != null) {
                            if (isHebrew) "שמור שינויים במשימה" else "Save Task Changes"
                        } else {
                            if (isHebrew) "שמור ושבץ משימה" else "Save & Assign Chore"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
