package com.example.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.SchedulePeriod
import com.example.model.SchoolDay
import com.example.ui.components.TimePickerField
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.VibrantActionBlue

@Composable
fun AddPeriodDialog(
    childId: String,
    childName: String,
    isHebrew: Boolean,
    periodToEdit: SchedulePeriod? = null,
    onDismiss: () -> Unit,
    onSavePeriod: (SchedulePeriod) -> Unit
) {
    var subject by remember { mutableStateOf(periodToEdit?.subjectName ?: "") }
    var room by remember { mutableStateOf(periodToEdit?.roomNumber ?: "") }
    var teacher by remember { mutableStateOf(periodToEdit?.teacherName ?: "") }
    var selectedDay by remember { mutableStateOf(periodToEdit?.day ?: SchoolDay.SUNDAY) }
    var periodIndex by remember { mutableIntStateOf(periodToEdit?.periodIndex ?: 1) }
    var startTime by remember { mutableStateOf(periodToEdit?.startTime ?: "08:00") }
    var endTime by remember { mutableStateOf(periodToEdit?.endTime ?: "08:45") }

    val scrollState = androidx.compose.foundation.rememberScrollState()
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
                .testTag("add_period_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(scrollState)
            ) {
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
                            color = GoogleBlue
                        )
                    )
                    IconButton(onClick = safeDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text(if (isHebrew) "מקצוע (לדוגמה: מתמטיקה, אנגלית)" else "Subject") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

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

                Spacer(modifier = Modifier.height(10.dp))

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
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = teacher,
                        onValueChange = { teacher = it },
                        label = { Text(if (isHebrew) "שם מורה" else "Teacher") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (subject.isNotBlank()) {
                            val period = SchedulePeriod(
                                id = periodToEdit?.id ?: "p_${System.currentTimeMillis()}",
                                childId = childId,
                                day = selectedDay,
                                periodIndex = periodIndex,
                                startTime = startTime.ifBlank { "08:00" },
                                endTime = endTime.ifBlank { "08:45" },
                                subjectName = subject.trim(),
                                roomNumber = room.trim(),
                                teacherName = teacher.trim(),
                                isAlertEnabled = true
                            )
                            onSavePeriod(period)
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
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

@Composable
fun AddFolderDialog(
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSaveFolder: (nameHebrew: String, nameEnglish: String, colorHex: Long, iconEmoji: String) -> Unit
) {
    var nameHebrew by remember { mutableStateOf("") }
    var nameEnglish by remember { mutableStateOf("") }
    var selectedEmoji by remember { mutableStateOf("📁") }

    val emojis = listOf("📁", "📝", "🏥", "🎨", "⚽", "🎓", "📑", "💳")
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
                        text = if (isHebrew) "פתיחת תיקייה משותפת חדשה" else "New Shared Folder",
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

                OutlinedTextField(
                    value = nameHebrew,
                    onValueChange = { nameHebrew = it },
                    label = { Text(if (isHebrew) "שם התיקייה בעברית" else "Folder Name (Hebrew)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = nameEnglish,
                    onValueChange = { nameEnglish = it },
                    label = { Text(if (isHebrew) "שם התיקייה באנגלית" else "Folder Name (English)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    emojis.forEach { emoji ->
                        val isSelected = selectedEmoji == emoji
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
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
                        if (nameHebrew.isNotBlank() || nameEnglish.isNotBlank()) {
                            onSaveFolder(
                                nameHebrew.ifBlank { nameEnglish },
                                nameEnglish.ifBlank { nameHebrew },
                                0xFF1A73E8,
                                selectedEmoji
                            )
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = if (isHebrew) "צור תיקייה משותפת" else "Create Shared Folder",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
