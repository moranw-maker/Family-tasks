package com.example.ui.components

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.*

/**
 * Convenient, intuitive time selection field with click-to-open dialog
 * and quick-select presets for children and parents.
 */
@Composable
fun TimePickerField(
    label: String,
    time: String,
    onTimeSelected: (String) -> Unit,
    isHebrew: Boolean,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.5.dp, Color(0xFFE2E8F0)),
        shadowElevation = 1.dp,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { showDialog = true }
            .testTag("time_picker_field_$label")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = time.ifBlank { "18:00" },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B),
                        fontSize = 17.sp
                    )
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = GoogleBlueLight,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = "Select Time",
                        tint = VibrantActionBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showDialog) {
        FamilyTimeSelectionDialog(
            initialTime = time.ifBlank { "18:00" },
            title = label,
            isHebrew = isHebrew,
            onDismiss = { showDialog = false },
            onConfirm = { chosenTime ->
                onTimeSelected(chosenTime)
                showDialog = false
            }
        )
    }
}

/**
 * Beautiful, convenient dialog for picking time with:
 * 1. Quick presets (Morning, Noon, Afternoon, Evening, Night)
 * 2. Visual +/- hour and minute step controls
 * 3. One-click native Android clock picker launcher
 */
@Composable
fun FamilyTimeSelectionDialog(
    initialTime: String,
    title: String,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val context = LocalContext.current
    val parsedParts = remember(initialTime) {
        val parts = initialTime.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 18
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        Pair(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }

    var selectedHour by remember { mutableIntStateOf(parsedParts.first) }
    var selectedMinute by remember { mutableIntStateOf(parsedParts.second) }

    val formattedTime = remember(selectedHour, selectedMinute) {
        String.format("%02d:%02d", selectedHour, selectedMinute)
    }

    val quickPresets = remember(isHebrew) {
        if (isHebrew) {
            listOf(
                "08:00" to "בוקר ☀️",
                "12:00" to "צהריים 🥪",
                "16:00" to "אחה״צ 🎨",
                "18:00" to "ערב 🏠",
                "20:00" to "לילה 🌙"
            )
        } else {
            listOf(
                "08:00" to "Morning ☀️",
                "12:00" to "Noon 🥪",
                "16:00" to "Afternoon 🎨",
                "18:00" to "Evening 🏠",
                "20:00" to "Night 🌙"
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with clock icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = GoogleBlueLight,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = VibrantActionBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = if (isHebrew) "בחירת שעה" else "Choose Time",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Big display of chosen time with Hour and Minute controls
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hour column
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { selectedHour = (selectedHour + 1) % 24 },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Hour +", tint = VibrantActionBlue)
                            }
                            Text(
                                text = String.format("%02d", selectedHour),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = VibrantActionBlue,
                                    fontSize = 32.sp
                                )
                            )
                            IconButton(
                                onClick = { selectedHour = if (selectedHour == 0) 23 else selectedHour - 1 },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Hour -", tint = VibrantActionBlue)
                            }
                            Text(
                                text = if (isHebrew) "שעות" else "Hours",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }

                        Text(
                            text = ":",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF94A3B8),
                                fontSize = 32.sp
                            ),
                            modifier = Modifier.padding(bottom = 20.dp)
                        )

                        // Minute column
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { selectedMinute = (selectedMinute + 5) % 60 },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Minute +", tint = VibrantActionBlue)
                            }
                            Text(
                                text = String.format("%02d", selectedMinute),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = VibrantActionBlue,
                                    fontSize = 32.sp
                                )
                            )
                            IconButton(
                                onClick = { selectedMinute = if (selectedMinute < 5) 55 else selectedMinute - 5 },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Minute -", tint = VibrantActionBlue)
                            }
                            Text(
                                text = if (isHebrew) "דקות" else "Minutes",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Native clock picker button
                OutlinedButton(
                    onClick = {
                        val tpd = TimePickerDialog(
                            context,
                            android.R.style.Theme_DeviceDefault_Light_Dialog,
                            { _, h, m ->
                                selectedHour = h
                                selectedMinute = m
                            },
                            selectedHour,
                            selectedMinute,
                            true
                        )
                        tpd.show()
                    },
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, GoogleBlueLight),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = GoogleBlueLight.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = VibrantActionBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isHebrew) "פתח שעון מחוגים מלא ⏱️" else "Open Clock Picker ⏱️",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = VibrantActionBlue
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick presets row
                Text(
                    text = if (isHebrew) "שעות נפוצות לבחירה מהירה:" else "Quick Time Presets:",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    ),
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(quickPresets) { (timeStr, label) ->
                        val isPresetActive = formattedTime == timeStr
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isPresetActive) VibrantActionBlue else Color(0xFFF1F5F9),
                            border = if (isPresetActive) null else BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val parts = timeStr.split(":")
                                    selectedHour = parts[0].toInt()
                                    selectedMinute = parts[1].toInt()
                                }
                        ) {
                            Text(
                                text = "$label $timeStr",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPresetActive) Color.White else Color(0xFF334155)
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Action Buttons (Confirm & Cancel) - Matches reference button styles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.5.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text(
                            text = if (isHebrew) "ביטול" else "Cancel",
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                    }

                    Button(
                        onClick = { onConfirm(formattedTime) },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(50.dp)
                            .testTag("confirm_time_button")
                    ) {
                        Text(
                            text = if (isHebrew) "אישור ($formattedTime)" else "Confirm ($formattedTime)",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
