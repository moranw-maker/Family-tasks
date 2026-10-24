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
import com.example.model.*
import com.example.ui.components.TimeFormatUtils
import com.example.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Polished Material 3 Modal Bottom Sheet displaying full task details, assigned member,
 * scheduled date & 24h time, completion/approval status, reward points, and role-permitted actions.
 * Supports dismissal by swiping down or tapping outside without navigating away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailBottomSheet(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSendNudge: () -> Unit = {},
    syncState: TaskCompletionSyncState = TaskCompletionSyncState.IDLE,
    onApprove: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier
            .testTag("task_detail_bottom_sheet")
    ) {
        TaskDetailSheetContent(
            task = task,
            assignedMember = assignedMember,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = onDismiss,
            onToggle = onToggle,
            onEdit = onEdit,
            onDelete = onDelete,
            onSendNudge = onSendNudge,
            syncState = syncState,
            onApprove = onApprove
        )
    }
}

/**
 * Compatibility wrapper so callers invoking [TaskDetailDialog] open the polished Material 3
 * [TaskDetailBottomSheet] with identical testTags and behavior.
 */
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
    onSendNudge: () -> Unit = {},
    syncState: TaskCompletionSyncState = TaskCompletionSyncState.IDLE,
    onApprove: () -> Unit = {}
) {
    TaskDetailBottomSheet(
        task = task,
        assignedMember = assignedMember,
        currentMember = currentMember,
        isHebrew = isHebrew,
        onDismiss = onDismiss,
        onToggle = onToggle,
        onEdit = onEdit,
        onDelete = onDelete,
        onSendNudge = onSendNudge,
        syncState = syncState,
        onApprove = onApprove
    )
}

@Composable
fun TaskDetailSheetContent(
    task: FamilyTask,
    assignedMember: FamilyMember?,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSendNudge: () -> Unit = {},
    syncState: TaskCompletionSyncState = TaskCompletionSyncState.IDLE,
    onApprove: () -> Unit = {}
) {
    val dateText = try {
        val ld = LocalDate.ofEpochDay(task.dateEpochDay)
        val today = LocalDate.now()
        when (ld) {
            today -> if (isHebrew) "היום, ${ld.format(DateTimeFormatter.ofPattern("d/M/yyyy"))}" else "Today, $ld"
            today.plusDays(1) -> if (isHebrew) "מחר, ${ld.format(DateTimeFormatter.ofPattern("d/M/yyyy"))}" else "Tomorrow, $ld"
            today.minusDays(1) -> if (isHebrew) "אתמול, ${ld.format(DateTimeFormatter.ofPattern("d/M/yyyy"))}" else "Yesterday, $ld"
            else -> ld.format(DateTimeFormatter.ofPattern("d/M/yyyy"))
        }
    } catch (_: Exception) {
        if (isHebrew) "היום" else "Today"
    }

    val normalizedTime24h = TimeFormatUtils.normalize24HourString(task.dueTimeFormatted, "16:00")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 16.dp)
            .testTag("task_detail_dialog")
    ) {
        // Header row with category badge and close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = when (task.category) {
                        TaskCategory.BEDROOM -> PastelBlueBackground
                        TaskCategory.DISHES -> PastelGreenBackground
                        TaskCategory.HOMEWORK -> PastelPurpleBackground
                        TaskCategory.CLEANING -> SoftTealLight
                        TaskCategory.GROCERIES -> WarmCoralLight
                        TaskCategory.PETS -> PastelYellowBackground
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = task.category.defaultEmoji,
                            fontSize = 22.sp
                        )
                    }
                }

                Column {
                    Text(
                        text = task.category.getDisplayName(isHebrew),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = if (isHebrew) "פרטי משימה" else "Task Details",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_task_detail_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (isHebrew) "סגור" else "Close"
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

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
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            val resolvedSyncState = when {
                syncState == TaskCompletionSyncState.SAVING -> TaskCompletionSyncState.SAVING
                syncState == TaskCompletionSyncState.FAILED -> TaskCompletionSyncState.FAILED
                task.isCompleted && task.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
                task.isCompleted && !task.approvedByParent -> TaskCompletionSyncState.PENDING_APPROVAL
                else -> TaskCompletionSyncState.IDLE
            }

            val badgeBg = when (resolvedSyncState) {
                TaskCompletionSyncState.SAVING -> PastelBlueBackground
                TaskCompletionSyncState.FAILED -> Color(0xFFFEE2E2)
                TaskCompletionSyncState.PENDING_APPROVAL -> PastelYellowBackground
                TaskCompletionSyncState.SAVED_APPROVED -> PastelGreenBackground
                TaskCompletionSyncState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
            }
            val badgeBorder = when (resolvedSyncState) {
                TaskCompletionSyncState.SAVING -> VibrantActionBlue
                TaskCompletionSyncState.FAILED -> GoogleRed
                TaskCompletionSyncState.PENDING_APPROVAL -> PastelYellowBorder
                TaskCompletionSyncState.SAVED_APPROVED -> PastelGreenBorder
                TaskCompletionSyncState.IDLE -> MaterialTheme.colorScheme.outline
            }
            val badgeTextColor = when (resolvedSyncState) {
                TaskCompletionSyncState.SAVING -> VibrantActionBlue
                TaskCompletionSyncState.FAILED -> GoogleRed
                TaskCompletionSyncState.PENDING_APPROVAL -> PastelYellowDark
                TaskCompletionSyncState.SAVED_APPROVED -> PastelGreenDark
                TaskCompletionSyncState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val badgeText = when (resolvedSyncState) {
                TaskCompletionSyncState.SAVING ->
                    if (isHebrew) "שומר בענן... ⏳" else "Saving... ⏳"
                TaskCompletionSyncState.FAILED ->
                    if (isHebrew) "שגיאה בשמירה ⚠️" else "Save Failed ⚠️"
                TaskCompletionSyncState.PENDING_APPROVAL ->
                    if (isHebrew) "ממתין לאישור הורה ⏳" else "Pending Approval ⏳"
                TaskCompletionSyncState.SAVED_APPROVED ->
                    if (isHebrew) "הושלם ואושר ✓" else "Saved & Approved ✓"
                TaskCompletionSyncState.IDLE ->
                    if (isHebrew) "ממתין לביצוע" else "Pending"
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = badgeBg,
                border = BorderStroke(1.dp, badgeBorder),
                modifier = Modifier.testTag("detail_status_badge")
            ) {
                Text(
                    text = badgeText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = badgeTextColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        if (task.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Compact Soft-Tactile Metadata Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                DetailInfoRow(
                    icon = Icons.Default.Person,
                    label = if (isHebrew) "משויך ל" else "Assigned To",
                    value = "${assignedMember?.avatarEmoji ?: "👤"} ${assignedMember?.name ?: (if (isHebrew) "חבר משפחה" else "Family Member")}"
                )

                DetailInfoRow(
                    icon = Icons.Default.Schedule,
                    label = if (isHebrew) "מועד ביצוע" else "Execution Date & Time",
                    value = if (task.hasDueTime) {
                        "$dateText • $normalizedTime24h"
                    } else {
                        "$dateText • ${if (isHebrew) "ללא שעה קבועה ($normalizedTime24h)" else "Flexible ($normalizedTime24h)"}"
                    }
                )

                if (task.hasDueTime && task.reminderOffset != TaskReminderOffset.NONE) {
                    DetailInfoRow(
                        icon = Icons.Default.NotificationsActive,
                        label = if (isHebrew) "תזכורת מתוזמנת" else "Scheduled Reminder",
                        value = task.reminderOffset.getDisplayName(isHebrew)
                    )
                }

                DetailInfoRow(
                    icon = Icons.Default.Star,
                    label = if (isHebrew) "נקודות תגמול (לאחר אישור הורה)" else "Reward Points (upon approval)",
                    value = "+${task.points}",
                    valueColor = PastelYellowDark
                )

                DetailInfoRow(
                    icon = Icons.Default.Repeat,
                    label = if (isHebrew) "תדירות" else "Recurrence",
                    value = task.recurrence.getDisplayName(isHebrew)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Role-Aware Actions:
        // - CHILD can submit completion ONLY for their own assigned tasks.
        // - PARENT can approve pending tasks, toggle completion, edit, or delete.
        val isAssignedToCurrentUser = task.assignedToMemberId == currentMember.id
        val canToggleTask = currentMember.isParent || isAssignedToCurrentUser

        if (canToggleTask) {
            Button(
                onClick = {
                    onToggle()
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (task.isCompleted) SoftTeal else MaterialTheme.colorScheme.primary
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
                    text = when {
                        syncState == TaskCompletionSyncState.SAVING ->
                            if (isHebrew) "שומר בענן... ⏳" else "Saving to Cloud... ⏳"
                        task.isCompleted ->
                            if (isHebrew) "החזר למצב ממתין לביצוע" else "Mark as Pending"
                        currentMember.isParent ->
                            if (isHebrew) "סמן כבוצע ואושר ✓" else "Mark Completed & Approved ✓"
                        else ->
                            if (isHebrew) "סמן כבוצע ושלח לאישור הורה ✓" else "Submit Completion for Approval ✓"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("detail_readonly_assignee_notice")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isHebrew)
                            "משימה זו משויכת ל-${assignedMember?.name ?: "חבר אחר"} (צפייה בלבד)"
                        else
                            "Assigned to ${assignedMember?.name ?: "another member"} (View only)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }

        // Parent Approval CTA when task is completed by child and awaiting parent approval
        if (currentMember.isParent && task.isCompleted && !task.approvedByParent) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    onApprove()
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoogleGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("detail_approve_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isHebrew)
                        "אשר ביצוע והענק +${task.points} נק' ✓"
                    else
                        "Approve & Award +${task.points} pts ✓",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
        }

        // Parent-only management controls (hidden from CHILD users)
        if (currentMember.isParent) {
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onEdit()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("detail_edit_btn")
                ) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "עריכה / שיבוץ" else "Edit / Reassign",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, GoogleRed.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleRed),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("detail_delete_btn")
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = if (isHebrew) "מחק" else "Delete", tint = GoogleRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isHebrew) "מחק" else "Delete",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = GoogleRed
                    )
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
    valueColor: Color = MaterialTheme.colorScheme.onSurface
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
