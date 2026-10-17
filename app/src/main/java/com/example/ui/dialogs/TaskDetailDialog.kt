package com.example.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.*
import com.example.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TaskDetailDialog(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSendNudge: () -> Unit
) {
    val dateText = try {
        val ld = LocalDate.ofEpochDay(task.dateEpochDay)
        val today = LocalDate.now()
        when (ld) {
            today -> if (isHebrew) "היום, ${ld.format(DateTimeFormatter.ofPattern("d בMMMM yyyy"))}" else "Today, ${ld}"
            today.plusDays(1) -> if (isHebrew) "מחר, ${ld.format(DateTimeFormatter.ofPattern("d בMMMM yyyy"))}" else "Tomorrow, ${ld}"
            today.minusDays(1) -> if (isHebrew) "אתמול, ${ld.format(DateTimeFormatter.ofPattern("d בMMMM yyyy"))}" else "Yesterday, ${ld}"
            else -> ld.format(DateTimeFormatter.ofPattern("d/M/yyyy"))
        }
    } catch (_: Exception) {
        if (isHebrew) "היום" else "Today"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("task_detail_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_task_detail_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }

                    Text(
                        text = if (isHebrew) "פרטי משימה" else "Task Details",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    // Spacer to center title
                    Spacer(modifier = Modifier.size(40.dp))
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Hero Category Card (matches reference screen 4)
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = when (task.category) {
                        TaskCategory.BEDROOM -> PastelBlueBackground
                        TaskCategory.DISHES -> PastelGreenBackground
                        TaskCategory.HOMEWORK -> PastelPurpleBackground
                        TaskCategory.CLEANING -> Color(0xFFE0F2FE)
                        TaskCategory.GROCERIES -> Color(0xFFFCE7F3)
                        TaskCategory.PETS -> PastelYellowBackground
                        else -> Color(0xFFF1F5F9)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = task.category.defaultEmoji,
                            fontSize = 54.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title and Status Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (task.isCompleted) PastelGreenBackground else PastelYellowBackground,
                        border = BorderStroke(
                            1.dp,
                            if (task.isCompleted) PastelGreenBorder else PastelYellowBorder
                        )
                    ) {
                        Text(
                            text = if (task.isCompleted) {
                                if (isHebrew) "הושלם ✓" else "Completed ✓"
                            } else {
                                if (isHebrew) "ממתין לביצוע" else "Pending"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (task.isCompleted) PastelGreenDark else PastelYellowDark,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                if (task.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (isHebrew) "תיאור:" else "Description:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = task.description,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Metadata Rows
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Assignee
                    DetailInfoRow(
                        icon = Icons.Default.Person,
                        label = if (isHebrew) "אחראי" else "Assignee",
                        value = "${assignedMember?.avatarEmoji ?: "👤"} ${assignedMember?.name ?: (if (isHebrew) "לא הוגדר" else "Unassigned")}"
                    )

                    // Due Date
                    DetailInfoRow(
                        icon = Icons.Default.CalendarToday,
                        label = if (isHebrew) "תאריך יעד" else "Due Date",
                        value = "$dateText • ${task.dueTimeFormatted}"
                    )

                    // Points
                    DetailInfoRow(
                        icon = Icons.Default.Star,
                        label = if (isHebrew) "נקודות" else "Points",
                        value = "${task.points} ${if (isHebrew) "נקודות" else "pts"}",
                        valueColor = PastelYellowDark
                    )

                    // Recurrence
                    DetailInfoRow(
                        icon = Icons.Default.Repeat,
                        label = if (isHebrew) "חזרתיות" else "Recurrence",
                        value = task.recurrence.getDisplayName(isHebrew)
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Action Buttons
                Button(
                    onClick = {
                        onToggle()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (task.isCompleted) GoogleGreen else VibrantActionBlue
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("detail_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (task.isCompleted) Icons.Default.Refresh else Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (task.isCompleted) {
                            if (isHebrew) "סמן כממתין שוב" else "Mark as Pending"
                        } else {
                            if (isHebrew) "סמן כבוצע ✓" else "Mark as Completed ✓"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onEdit()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("detail_edit_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isHebrew) "ערוך" else "Edit",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    if (!task.isCompleted && currentMember.isAdmin) {
                        OutlinedButton(
                            onClick = {
                                onSendNudge()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("detail_nudge_btn")
                        ) {
                            Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp), tint = GoogleYellowDark)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isHebrew) "תזכורת" else "Nudge",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = GoogleYellowDark
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("detail_delete_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = GoogleRed, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = VibrantActionBlue,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        )
    }
}
