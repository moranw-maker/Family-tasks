package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileRenameOutline
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
import com.example.ui.theme.*

@Composable
fun EditFamilyNameDialog(
    currentFamilyName: String,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSaveFamilyName: (String) -> Unit
) {
    var familyNameInput by remember(currentFamilyName) { mutableStateOf(currentFamilyName) }

    val presets = if (isHebrew) {
        listOf("משפחת וקסלר", "משפחת כהן", "משפחת לוי", "משפחת ישראלי", "משפחת מזרחי", "משפחת גולדשטיין")
    } else {
        listOf("Wexler Family", "Cohen Family", "Levi Family", "Miller Family", "Smith Family")
    }

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
                .testTag("edit_family_name_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GoogleBlueLight,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DriveFileRenameOutline,
                                    contentDescription = null,
                                    tint = GoogleBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = if (isHebrew) "שינוי שם המשפחה הראשי" else "Edit Main Family Name",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlue
                                )
                            )
                            Text(
                                text = if (isHebrew) "מוצג בכותרת האפליקציה ובכל המודולים" else "Shown across header, tasks & Drive",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    IconButton(
                        onClick = safeDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_edit_family_name_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Name Input
                OutlinedTextField(
                    value = familyNameInput,
                    onValueChange = { familyNameInput = it },
                    label = { Text(if (isHebrew) "שם המשפחה (לדוגמה: משפחת כהן)" else "Family Name (e.g. Wexler Family)") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("family_name_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Quick presets
                Text(
                    text = if (isHebrew) "הצעות מהירות:" else "Quick Suggestions:",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presets) { preset ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (familyNameInput == preset) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (familyNameInput == preset) androidx.compose.foundation.BorderStroke(1.5.dp, GoogleBlue) else null,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { familyNameInput = preset }
                        ) {
                            Text(
                                text = preset,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (familyNameInput == preset) FontWeight.Bold else FontWeight.Normal,
                                    color = if (familyNameInput == preset) GoogleBlue else MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = safeDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isHebrew) "ביטול" else "Cancel")
                    }

                    Button(
                        onClick = {
                            if (familyNameInput.isNotBlank()) {
                                onSaveFamilyName(familyNameInput)
                            }
                        },
                        enabled = familyNameInput.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("save_family_name_btn")
                    ) {
                        Text(
                            text = if (isHebrew) "שמור שם משפחה ✓" else "Save Name ✓",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
