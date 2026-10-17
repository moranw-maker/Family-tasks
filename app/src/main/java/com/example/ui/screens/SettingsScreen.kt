package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
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
import com.example.ui.theme.*

@Composable
fun SettingsScreen(
    settings: AppSettings,
    isHebrew: Boolean,
    onUpdateSettings: (AppSettings) -> Unit,
    onSyncNow: () -> Unit,
    onShowToast: (String) -> Unit
) {
    var showCustomSoundDialog by remember { mutableStateOf(false) }
    var customSoundInput by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Section: Main Family Name (שם המשפחה הראשי)
        item {
            var editingFamilyName by remember(settings.familyName) { mutableStateOf(settings.familyName) }
            SettingsCard(
                title = if (isHebrew) "שם המשפחה הראשי" else "Main Family Name",
                icon = Icons.Default.Home,
                iconColor = GoogleBlue
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (isHebrew) "שם המשפחה המוצג בכותרת האפליקציה, ביומן ובתיקיות ה-Drive:" else "Family name displayed in app header, schedule & Drive:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editingFamilyName,
                            onValueChange = { editingFamilyName = it },
                            label = { Text(if (isHebrew) "שם המשפחה" else "Family Name") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_family_name_input")
                        )
                        Button(
                            onClick = {
                                if (editingFamilyName.isNotBlank()) {
                                    onUpdateSettings(settings.copy(familyName = editingFamilyName.trim()))
                                    onShowToast(if (isHebrew) "שם המשפחה עודכן ל-\"${editingFamilyName.trim()}\"!" else "Family name updated!")
                                }
                            },
                            enabled = editingFamilyName.isNotBlank() && editingFamilyName.trim() != settings.familyName,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                            modifier = Modifier.testTag("save_settings_family_name_btn")
                        ) {
                            Text(text = if (isHebrew) "שמור" else "Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Section: Theme Color Selection (בחירת Theme בצבעים שונים)
        item {
            SettingsCard(
                title = if (isHebrew) "ערכת צבעים ועיצוב (Theme)" else "Theme & Color Palette",
                icon = Icons.Default.Palette,
                iconColor = Color(settings.themeColor.primaryHex)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (isHebrew) "בחרו את צבע הנושא הראשי של האפליקציה:" else "Choose the app's primary theme color:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        AppThemeColor.values().forEach { themeColorOption ->
                            val isSelected = settings.themeColor == themeColorOption
                            val optionPrimary = Color(themeColorOption.primaryHex)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onUpdateSettings(settings.copy(themeColor = themeColorOption))
                                        onShowToast(if (isHebrew) "ערכת צבעים שונתה ל: ${themeColorOption.getName(isHebrew)}" else "Theme set to ${themeColorOption.getName(isHebrew)}")
                                    }
                                    .padding(4.dp)
                                    .testTag("theme_color_${themeColorOption.name}")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(optionPrimary)
                                        .border(
                                            if (isSelected) 3.dp else 0.dp,
                                            if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = themeColorOption.getName(isHebrew).split(" ").first(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) optionPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Language Selection (בחירת שפה)
        item {
            SettingsCard(
                title = if (isHebrew) "שפת הממשק" else "Language",
                icon = Icons.Default.Language,
                iconColor = GoogleBlue
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LanguageOptionPill(
                        title = "🇮🇱 עברית",
                        isSelected = settings.language == AppLanguage.HEBREW,
                        onClick = {
                            onUpdateSettings(settings.copy(language = AppLanguage.HEBREW))
                            onShowToast("שפת הממשק שונתה לעברית")
                        },
                        modifier = Modifier.weight(1f),
                        testTag = "lang_hebrew"
                    )

                    LanguageOptionPill(
                        title = "🇺🇸 English",
                        isSelected = settings.language == AppLanguage.ENGLISH,
                        onClick = {
                            onUpdateSettings(settings.copy(language = AppLanguage.ENGLISH))
                            onShowToast("Language set to English")
                        },
                        modifier = Modifier.weight(1f),
                        testTag = "lang_english"
                    )
                }
            }
        }

        // Section: Biometric / Fingerprint (כניסה עם טביעת אצבע)
        item {
            SettingsCard(
                title = if (isHebrew) "אבטחה וכניסה ביומטרית" else "Security & Biometrics",
                icon = Icons.Default.Fingerprint,
                iconColor = GoogleGreen
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHebrew) "כניסה עם טביעת אצבע / זיהוי פנים" else "Fingerprint / Face Unlock",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isHebrew)
                                "מאפשר כניסה מהירה ומאובטחת לניהול המשימות"
                            else
                                "Fast and secure biometric sign-in",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }

                    Switch(
                        checked = settings.biometricEnabled,
                        onCheckedChange = { isEnabled ->
                            onUpdateSettings(settings.copy(biometricEnabled = isEnabled))
                            onShowToast(
                                if (isEnabled) {
                                    if (isHebrew) "כניסה בטביעת אצבע הופעלה ✓" else "Fingerprint unlock enabled ✓"
                                } else {
                                    if (isHebrew) "כניסה בטביעת אצבע כובתה" else "Fingerprint unlock disabled"
                                }
                            )
                        },
                        modifier = Modifier.testTag("biometric_switch")
                    )
                }
            }
        }

        // Section: Cloud Backup & Sync (גיבוי וסנכרון ענן)
        item {
            SettingsCard(
                title = if (isHebrew) "גיבוי וסנכרון בענן" else "Cloud Backup & Sync",
                icon = Icons.Default.CloudSync,
                iconColor = GoogleBlue
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHebrew) "סנכרון בזמן אמת פעיל" else "Real-time sync active",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isHebrew)
                                "סנכרון אחרון: ${settings.lastSyncFormatted}"
                            else
                                "Last synced: ${settings.lastSyncFormatted}",
                            style = MaterialTheme.typography.bodySmall.copy(color = GoogleGreenDark, fontWeight = FontWeight.SemiBold)
                        )
                    }

                    Button(
                        onClick = onSyncNow,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        modifier = Modifier.testTag("sync_now_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = if (isHebrew) "סנכרן כעת" else "Sync Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section: Notification Sounds & Volume + Custom Sound (צלילים, עוצמה וצליל מותאם אישית)
        item {
            SettingsCard(
                title = if (isHebrew) "צלילי התראות ועוצמת שמע" else "Notification Sounds & Volume",
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                iconColor = GoogleYellowDark
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Sound Volume Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isHebrew) "עוצמת צליל התראה" else "Alert Sound Volume",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "${(settings.soundVolume * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = GoogleBlue)
                        )
                    }

                    Slider(
                        value = settings.soundVolume,
                        onValueChange = { newVol ->
                            onUpdateSettings(settings.copy(soundVolume = newVol))
                        },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = GoogleBlue,
                            activeTrackColor = GoogleBlue
                        ),
                        modifier = Modifier.testTag("sound_volume_slider")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isHebrew) "בחר צליל עבור משימות ומערכת שעות:" else "Choose sound for chores & timetable:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        // Button to add custom sound
                        TextButton(
                            onClick = { showCustomSoundDialog = true },
                            modifier = Modifier.testTag("add_custom_sound_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = GoogleBlue)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (isHebrew) "+ צליל אישי" else "+ Custom Sound",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GoogleBlue
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Preset Alert Sounds
                    AlertSound.values().forEach { sound ->
                        val isSelected = settings.alertSound == sound && settings.customSoundName == null
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onUpdateSettings(settings.copy(alertSound = sound, customSoundName = null))
                                    onShowToast(if (isHebrew) "צליל נבחר: ${sound.soundTitle}" else "Sound selected: ${sound.soundTitle}")
                                }
                                .testTag("sound_option_${sound.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            onUpdateSettings(settings.copy(alertSound = sound, customSoundName = null))
                                        }
                                    )
                                    Text(
                                        text = sound.soundTitle,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        onShowToast(if (isHebrew) "🔊 משמיע צליל: ${sound.soundTitle}" else "🔊 Playing sound: ${sound.soundTitle}")
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play sound",
                                        tint = GoogleBlue
                                    )
                                }
                            }
                        }
                    }

                    // Custom Sound Item (if added)
                    if (settings.customSoundName != null) {
                        val isCustomSelected = settings.customSoundName != null
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = GoogleGreenLight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(14.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(selected = isCustomSelected, onClick = {})
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(text = "🎵", fontSize = 14.sp)
                                            Text(
                                                text = settings.customSoundName ?: "",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = GoogleGreenDark)
                                            )
                                        }
                                        Text(
                                            text = if (isHebrew) "צליל מותאם אישית פעיל" else "Custom alert active",
                                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, fontSize = 9.sp)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        onShowToast(if (isHebrew) "🔊 משמיע צליל אישי: ${settings.customSoundName}" else "🔊 Playing custom: ${settings.customSoundName}")
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play custom sound",
                                        tint = GoogleGreenDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: First Day of the Week (היום הראשון של השבוע)
        item {
            SettingsCard(
                title = if (isHebrew) "היום הראשון של השבוע" else "First Day of the Week",
                icon = Icons.Default.CalendarToday,
                iconColor = GoogleRed
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    FirstDayOfWeek.values().forEach { firstDay ->
                        val isSelected = settings.firstDayOfWeek == firstDay
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onUpdateSettings(settings.copy(firstDayOfWeek = firstDay))
                                    onShowToast(if (isHebrew) "יום ראשון בשבוע הוגדר ל: ${firstDay.getDisplayName(isHebrew)}" else "First day set")
                                }
                                .testTag("first_day_${firstDay.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onUpdateSettings(settings.copy(firstDayOfWeek = firstDay))
                                    }
                                )
                                Text(
                                    text = firstDay.getDisplayName(isHebrew),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Custom Sound Dialog
    if (showCustomSoundDialog) {
        Dialog(onDismissRequest = { showCustomSoundDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
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
                            text = if (isHebrew) "הוספת צליל מותאם אישית" else "Add Custom Sound",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = GoogleBlue)
                        )
                        IconButton(onClick = { showCustomSoundDialog = false }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isHebrew)
                            "הגדירו שם לצליל או הקלטה מותאמת אישית להתראות משימות:"
                        else
                            "Name your custom sound or recording for task alerts:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = customSoundInput,
                        onValueChange = { customSoundInput = it },
                        label = { Text(if (isHebrew) "שם הצליל (לדוגמה: הקלטת עידוד, פעמון חלל)" else "Sound Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            if (customSoundInput.isNotBlank()) {
                                onUpdateSettings(settings.copy(customSoundName = customSoundInput.trim()))
                                showCustomSoundDialog = false
                                onShowToast(if (isHebrew) "צליל אישי נוסף בהצלחה! 🎵" else "Custom sound added! 🎵")
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(text = if (isHebrew) "שמור צליל אישי" else "Save Custom Sound", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            content()
        }
    }
}

@Composable
fun LanguageOptionPill(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
