package com.example.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.window.DialogProperties
import com.example.model.SchedulePeriod
import com.example.model.SchoolDay
import com.example.ui.components.TimeFormatUtils
import com.example.ui.components.TimePickerField
import com.example.ui.theme.LocalSoftGlassColors

@Composable
fun AddPeriodDialog(
    childId: String,
    childName: String,
    isHebrew: Boolean,
    periodToEdit: SchedulePeriod? = null,
    onDismiss: () -> Unit,
    onSavePeriod: (SchedulePeriod) -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var subject by remember { mutableStateOf(periodToEdit?.subjectName ?: "") }
    var room by remember { mutableStateOf(periodToEdit?.roomNumber ?: "") }
    var teacher by remember { mutableStateOf(periodToEdit?.teacherName ?: "") }
    var selectedDay by remember { mutableStateOf(periodToEdit?.day ?: SchoolDay.SUNDAY) }
    var periodIndex by remember { mutableIntStateOf(periodToEdit?.periodIndex ?: 1) }
    var startTime by remember { mutableStateOf(periodToEdit?.startTime ?: "08:00") }
    var endTime by remember { mutableStateOf(periodToEdit?.endTime ?: "08:45") }

    val scrollState = rememberScrollState()
    val safeDismiss = { onDismiss() }

    Dialog(
        onDismissRequest = safeDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass.cardBackground,
            border = BorderStroke(1.dp, glass.cardBorder),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .imePadding()
                .systemBarsPadding()
                .padding(vertical = 12.dp)
                .heightIn(max = 640.dp)
                .testTag("add_period_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Fixed Compact Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (periodToEdit != null) {
                            if (isHebrew) "עריכת שיעור במערכת: $childName" else "Edit Period for $childName"
                        } else {
                            if (isHebrew) "הוספת שיעור למערכת: $childName" else "Add Period for $childName"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = glass.primaryAccent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = safeDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = glass.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = glass.divider)
                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Form Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                ) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text(if (isHebrew) "מקצוע (לדוגמה: מתמטיקה, אנגלית)" else "Subject") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = glass.primaryAccent,
                            unfocusedBorderColor = glass.cardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (isHebrew) "יום לימודים (א׳-ו׳):" else "School Day (Sun-Fri):",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SchoolDay.schoolDays.forEach { day ->
                            val isSelected = day == selectedDay
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) glass.primaryAccent else glass.screenBackground,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) glass.primaryAccent else glass.cardBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedDay = day }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isHebrew) day.shortHe else day.getDisplayName(false).take(3),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) {
                                                if (glass.isDark) Color(0xFF0B1120) else Color.White
                                            } else {
                                                glass.textSecondary
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (isHebrew) "שעה במערכת (1-7):" else "Period Number (1-7):",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = glass.textPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (1..7).forEach { idx ->
                            val isSelected = idx == periodIndex
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) glass.primaryAccent else glass.screenBackground,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) glass.primaryAccent else glass.cardBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        periodIndex = idx
                                        val defaultStartMin = 8 * 60 + (idx - 1) * 50
                                        val defaultEndMin = defaultStartMin + 45
                                        startTime = "%02d:%02d".format(defaultStartMin / 60, defaultStartMin % 60)
                                        endTime = "%02d:%02d".format(defaultEndMin / 60, defaultEndMin % 60)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$idx",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) {
                                                if (glass.isDark) Color(0xFF0B1120) else Color.White
                                            } else {
                                                glass.textSecondary
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimePickerField(
                            label = if (isHebrew) "שעת התחלה" else "Start Time",
                            time = startTime,
                            onTimeSelected = { startTime = it },
                            isHebrew = isHebrew,
                            modifier = Modifier.weight(1f)
                        )

                        TimePickerField(
                            label = if (isHebrew) "שעת סיום" else "End Time",
                            time = endTime,
                            onTimeSelected = { endTime = it },
                            isHebrew = isHebrew,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = room,
                            onValueChange = { room = it },
                            label = { Text(if (isHebrew) "כיתה / חדר" else "Room") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = glass.primaryAccent,
                                unfocusedBorderColor = glass.cardBorder
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = teacher,
                            onValueChange = { teacher = it },
                            label = { Text(if (isHebrew) "שם מורה" else "Teacher") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = glass.primaryAccent,
                                unfocusedBorderColor = glass.cardBorder
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = glass.divider)
                Spacer(modifier = Modifier.height(12.dp))

                // Permanently Visible Bottom Action Bar ("ביטול" + "שמור שיעור")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = safeDismiss,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, glass.cardBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("cancel_period_button")
                    ) {
                        Text(
                            text = if (isHebrew) "ביטול" else "Cancel",
                            fontWeight = FontWeight.SemiBold,
                            color = glass.textSecondary
                        )
                    }

                    Button(
                        onClick = {
                            if (subject.isNotBlank()) {
                                val period = SchedulePeriod(
                                    id = periodToEdit?.id ?: "p_${System.currentTimeMillis()}",
                                    childId = childId,
                                    day = selectedDay,
                                    periodIndex = periodIndex,
                                    startTime = TimeFormatUtils.normalize24HourString(startTime.ifBlank { "08:00" }),
                                    endTime = TimeFormatUtils.normalize24HourString(endTime.ifBlank { "08:45" }),
                                    subjectName = subject.trim(),
                                    roomNumber = room.trim(),
                                    teacherName = teacher.trim(),
                                    isAlertEnabled = true
                                )
                                onSavePeriod(period)
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glass.primaryAccent,
                            contentColor = if (glass.isDark) Color(0xFF0B1120) else Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                        modifier = Modifier
                            .weight(1.4f)
                            .height(48.dp)
                            .testTag("save_period_button")
                    ) {
                        Text(
                            text = if (periodToEdit != null) {
                                if (isHebrew) "שמור שינויים" else "Save Changes"
                            } else {
                                if (isHebrew) "שמור שיעור" else "Save Period"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddFolderDialog(
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSaveFolder: (nameHebrew: String, nameEnglish: String, colorHex: Long, iconEmoji: String) -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var folderName by remember { mutableStateOf("") }
    var selectedEmoji by remember { mutableStateOf("📁") }

    val emojis = listOf("📁", "📂", "📄", "📷", "🏥", "🎓", "🏠", "⭐")
    val safeDismiss = { onDismiss() }

    Dialog(
        onDismissRequest = safeDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = glass.cardBackground,
            border = BorderStroke(1.dp, glass.cardBorder),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp)
                .testTag("add_folder_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isHebrew) "תיקייה חדשה" else "New Folder",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = glass.primaryAccent
                        )
                    )
                    IconButton(onClick = safeDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = glass.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(if (isHebrew) "שם התיקייה" else "Folder Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = glass.primaryAccent,
                        unfocusedBorderColor = glass.cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_folder_name_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    emojis.forEach { emoji ->
                        val isSelected = selectedEmoji == emoji
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) glass.primaryAccent else glass.screenBackground,
                            border = BorderStroke(1.dp, if (isSelected) glass.primaryAccent else glass.cardBorder),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedEmoji = emoji }
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val trimmed = folderName.trim()
                        if (trimmed.isNotBlank()) {
                            onSaveFolder(
                                trimmed,
                                trimmed,
                                0xFF1A73E8,
                                selectedEmoji
                            )
                        }
                    },
                    enabled = folderName.trim().isNotBlank(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = glass.primaryAccent,
                        contentColor = if (glass.isDark) Color(0xFF0B1120) else Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_add_folder_button")
                ) {
                    Text(
                        text = if (isHebrew) "צור תיקייה" else "Create Folder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
