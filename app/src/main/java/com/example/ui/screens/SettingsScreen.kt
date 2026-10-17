package com.example.ui.screens

import android.app.Activity
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.example.BuildConfig
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.*
import com.example.ui.theme.*
import com.example.util.BiometricAuthManager
import com.example.util.BiometricStatus
import com.example.util.SoundPlayer

@Composable
fun SettingsScreen(
    settings: AppSettings,
    isHebrew: Boolean,
    onUpdateSettings: (AppSettings) -> Unit,
    onSyncNow: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showCustomSoundDialog by remember { mutableStateOf(false) }
    var customSoundInput by remember { mutableStateOf(settings.customSoundName ?: "") }
    var customSoundUriInput by remember { mutableStateOf(settings.customSoundUri) }
    var showReleaseWorkflowDialog by remember { mutableStateOf(false) }
    val bioStatus = remember { BiometricAuthManager.checkBiometricStatus(context) }

    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            customSoundUriInput = uri.toString()
            var resolvedName = "audio_file.mp3"
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameCol != -1) {
                            resolvedName = cursor.getString(nameCol) ?: resolvedName
                        }
                    }
                }
            } catch (_: Exception) {}
            customSoundInput = resolvedName
            onShowToast(if (isHebrew) "קובץ שמע נבחר: $resolvedName 🎵" else "Audio selected: $resolvedName 🎵")
            SoundPlayer.playSound(context, settings.alertSound, uri.toString(), settings.soundVolume)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        androidx.compose.ui.graphics.Color(0xFFEBF3FE),
                        androidx.compose.ui.graphics.Color(0xFFF7F9FD),
                        androidx.compose.ui.graphics.Color.White
                    )
                )
            )
    ) {
        // Quick Screen Help Guide
        item {
            com.example.ui.components.ScreenHelpBanner(
                title = if (isHebrew) "מרכז הגדרות והתאמה אישית" else "Settings & Customization",
                tips = if (isHebrew) listOf(
                    "שם המשפחה: שינוי שם המשפחה הראשי המוצג בכל המסכים ובקישורי ההזמנה.",
                    "גרסת התוכנה: גרסה v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) הנטענת ישירות מ-BuildConfig עם תהליך קידום שחרורים.",
                    "ערכת צבעים ועיצוב: בחירה בין כחול Google, ירוק, סגול, כתום או מנטה.",
                    "התחברות Google: ניהול חשבון Google מסונכרן ואיפוס להקמת משפחה נקייה."
                ) else listOf(
                    "Family Name: Updates the primary family title shown on top and in invites.",
                    "App Version: Version v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) loaded from BuildConfig with release increment steps.",
                    "Theme Palette: Customize your app colors with M3 Material themes.",
                    "Google Account: Manage Drive sync or reset to start a clean household."
                ),
                isHebrew = isHebrew
            )
        }

        // Top Profile Card (matches Image 2 Screen 8)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
                tonalElevation = 2.dp,
                border = BorderStroke(1.dp, OutlineLight),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_profile_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(PastelBlueBackground)
                            .border(2.dp, VibrantActionBlue, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "👩‍💻", fontSize = 28.sp)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "משפחת ${settings.familyName}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = GoogleGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = settings.googleAccountEmail,
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }
            }
        }

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
                title = if (isHebrew) "אבטחה וכניסה בטביעת אצבע" else "Security & Fingerprint Unlock",
                icon = Icons.Default.Fingerprint,
                iconColor = GoogleGreen
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status Badge
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (bioStatus == BiometricStatus.AVAILABLE) GoogleGreenLight else GoogleYellowLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (bioStatus == BiometricStatus.AVAILABLE) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (bioStatus == BiometricStatus.AVAILABLE) GoogleGreenDark else GoogleYellowDark,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isHebrew) {
                                    if (bioStatus == BiometricStatus.AVAILABLE) "חיישן ביומטרי זוהה ומוכן לשימוש ✓" else "חיישן ביומטרי נתמך (דורש הגדרה בהגדרות המכשיר)"
                                } else {
                                    if (bioStatus == BiometricStatus.AVAILABLE) "Biometric sensor ready & enrolled ✓" else "Biometric sensor supported"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Toggle 1: Enable Biometrics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isHebrew) "הפעל אימות בטביעת אצבע" else "Enable Fingerprint Authentication",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (isHebrew)
                                    "מאפשר כניסה מהירה ומאובטחת לחשבון ולמשימות"
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

                    // Toggle 2: Require Biometric on App Launch
                    if (settings.biometricEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isHebrew) "דרוש טביעת אצבע בפתיחת האפליקציה" else "Require Fingerprint on App Launch",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = if (isHebrew)
                                        "נועל את האפליקציה אוטומטית בכל פתיחה להגנה מקסימלית"
                                    else
                                        "Locks app on launch until authenticated",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }

                            Switch(
                                checked = settings.requireBiometricOnStart,
                                onCheckedChange = { isReq ->
                                    onUpdateSettings(settings.copy(requireBiometricOnStart = isReq))
                                    onShowToast(
                                        if (isReq) {
                                            if (isHebrew) "נעילת פתיחה הופעלה 🔒" else "Launch lock enabled 🔒"
                                        } else {
                                            if (isHebrew) "נעילת פתיחה כובתה" else "Launch lock disabled"
                                        }
                                    )
                                },
                                modifier = Modifier.testTag("require_biometric_switch")
                            )
                        }

                        // Backup PIN Code Configuration
                        var editingPin by remember(settings.fallbackPin) { mutableStateOf(settings.fallbackPin) }
                        var showEditPin by remember { mutableStateOf(false) }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Pin,
                                            contentDescription = null,
                                            tint = GoogleBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = if (isHebrew) "קוד גישה לגיבוי: ${settings.fallbackPin}" else "Backup PIN Code: ${settings.fallbackPin}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                    TextButton(onClick = { showEditPin = !showEditPin }) {
                                        Text(
                                            text = if (showEditPin) (if (isHebrew) "סגור" else "Close") else (if (isHebrew) "שנה קוד" else "Change PIN"),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                if (showEditPin) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = editingPin,
                                            onValueChange = { if (it.length <= 8) editingPin = it },
                                            label = { Text(if (isHebrew) "קוד PIN חדש" else "New PIN") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                if (editingPin.isNotBlank()) {
                                                    onUpdateSettings(settings.copy(fallbackPin = editingPin.trim()))
                                                    showEditPin = false
                                                    onShowToast(if (isHebrew) "קוד PIN עודכן ל-${editingPin.trim()}! 🔐" else "Backup PIN updated!")
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue)
                                        ) {
                                            Text(if (isHebrew) "שמור" else "Save")
                                        }
                                    }
                                }
                            }
                        }

                        // Test Fingerprint Button
                        OutlinedButton(
                            onClick = {
                                if (activity != null) {
                                    BiometricAuthManager.authenticate(
                                        activity = activity,
                                        title = if (isHebrew) "אימות טביעת אצבע" else "Fingerprint Verification",
                                        subtitle = if (isHebrew) "בדיקת חיישן ביומטרי לאפליקציה" else "Biometric sensor test",
                                        onSuccess = {
                                            onShowToast(if (isHebrew) "אימות טביעת אצבע עבר בהצלחה! 🔓✓" else "Fingerprint verified successfully! 🔓✓")
                                        },
                                        onError = { err ->
                                            onShowToast(if (isHebrew) "אימות: $err" else "Biometrics: $err")
                                        }
                                    )
                                } else {
                                    onShowToast(if (isHebrew) "חיישן ביומטרי מוכן לפעולה ✓" else "Biometric ready ✓")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("test_biometric_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, tint = GoogleGreenDark, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isHebrew) "בדוק אימות טביעת אצבע כעת" else "Test Fingerprint Verification Now",
                                fontWeight = FontWeight.Bold,
                                color = GoogleGreenDark
                            )
                        }
                    }
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
                        onValueChangeFinished = {
                            SoundPlayer.playSound(context, settings.alertSound, settings.customSoundUri, settings.soundVolume)
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

                        // Button to add custom sound from device files
                        TextButton(
                            onClick = { showCustomSoundDialog = true },
                            modifier = Modifier.testTag("add_custom_sound_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = GoogleBlue)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (isHebrew) "+ צליל אישי מהמכשיר" else "+ Custom Device Sound",
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
                                    onShowToast(if (isHebrew) "צליל נבחר: ${sound.soundTitle} 🔊" else "Sound selected: ${sound.soundTitle} 🔊")
                                    SoundPlayer.playSound(context, sound, null, settings.soundVolume)
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            onUpdateSettings(settings.copy(alertSound = sound, customSoundName = null))
                                            onShowToast(if (isHebrew) "צליל נבחר: ${sound.soundTitle} 🔊" else "Sound selected: ${sound.soundTitle} 🔊")
                                            SoundPlayer.playSound(context, sound, null, settings.soundVolume)
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
                                        SoundPlayer.playSound(context, sound, null, settings.soundVolume)
                                    },
                                    modifier = Modifier.size(36.dp)
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
                        val isCustomSelected = true
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = GoogleGreenLight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onShowToast(if (isHebrew) "🔊 משמיע צליל אישי: ${settings.customSoundName}" else "🔊 Playing custom: ${settings.customSoundName}")
                                    SoundPlayer.playSound(context, settings.alertSound, settings.customSoundUri, settings.soundVolume)
                                }
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
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    RadioButton(
                                        selected = isCustomSelected,
                                        onClick = {
                                            SoundPlayer.playSound(context, settings.alertSound, settings.customSoundUri, settings.soundVolume)
                                        }
                                    )
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(text = "🎵", fontSize = 14.sp)
                                            Text(
                                                text = settings.customSoundName ?: "",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = GoogleGreenDark)
                                            )
                                        }
                                        Text(
                                            text = if (!settings.customSoundUri.isNullOrBlank()) {
                                                if (isHebrew) "קובץ שמע מקומי מקבצי המכשיר ✓" else "Custom audio file from device storage ✓"
                                            } else {
                                                if (isHebrew) "צליל מותאם אישית פעיל" else "Custom alert active"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, fontSize = 9.sp)
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = {
                                            onShowToast(if (isHebrew) "🔊 משמיע צליל אישי: ${settings.customSoundName}" else "🔊 Playing custom: ${settings.customSoundName}")
                                            SoundPlayer.playSound(context, settings.alertSound, settings.customSoundUri, settings.soundVolume)
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play custom sound",
                                            tint = GoogleGreenDark
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            SoundPlayer.stop()
                                            onUpdateSettings(settings.copy(customSoundName = null, customSoundUri = null))
                                            onShowToast(if (isHebrew) "צליל אישי הוסר" else "Custom sound removed")
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Remove custom sound",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
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

        // Section: Software Version & Upgrades (Fetching from BuildConfig)
        item {
            SettingsCard(
                title = if (isHebrew) "גרסת האפליקציה וניהול שחרורים (BuildConfig)" else "App Version & Release Management",
                icon = Icons.Default.Info,
                iconColor = GoogleBlue
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (isHebrew) "גרסה נוכחית מתוך BuildConfig:" else "Current Version from BuildConfig:",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = GoogleBlueLight
                                ) {
                                    Text(
                                        text = "v${BuildConfig.VERSION_NAME}",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleBlue
                                        ),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = "Build #${BuildConfig.VERSION_CODE}",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                showReleaseWorkflowDialog = true
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("check_update_version_btn")
                        ) {
                            Icon(imageVector = Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isHebrew) "שלבי שחרור עתידי" else "Release Steps",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Step-by-step Release Increment Guide Card
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Upgrade,
                                    contentDescription = null,
                                    tint = GoogleBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (isHebrew) "שלבים מסודרים לקידום גרסה לשחרור הבא:" else "Logical Steps to Increment Version for Future Releases:",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isHebrew)
                                    "1. בקובץ app/build.gradle.kts מקדמים את currentVersionCode ב-+1 (מ-${BuildConfig.VERSION_CODE} ל-${BuildConfig.NEXT_RELEASE_VERSION_CODE}).\n" +
                                    "2. מקדמים את versionName (מ-${BuildConfig.VERSION_NAME} ל-${BuildConfig.NEXT_RELEASE_VERSION_NAME}) בהתאם לגרסה סמנטית (Major.Minor.Patch).\n" +
                                    "3. בעת ביצוע Build, ה-Android Gradle Plugin מחולל מחדש את BuildConfig ומנגיש את הערך המעודכן לכל חלקי האפליקציה."
                                else
                                    "1. In app/build.gradle.kts, increment currentVersionCode by +1 (from ${BuildConfig.VERSION_CODE} to ${BuildConfig.NEXT_RELEASE_VERSION_CODE}).\n" +
                                    "2. Bump versionName (from ${BuildConfig.VERSION_NAME} to ${BuildConfig.NEXT_RELEASE_VERSION_NAME}) following semantic versioning rules.\n" +
                                    "3. On build, the Android Gradle Plugin automatically regenerates BuildConfig with the updated values across the app.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isHebrew) "יעד שחרור הבא: v${BuildConfig.NEXT_RELEASE_VERSION_NAME} (קוד ${BuildConfig.NEXT_RELEASE_VERSION_CODE})" else "Target Next Release: v${BuildConfig.NEXT_RELEASE_VERSION_NAME} (code ${BuildConfig.NEXT_RELEASE_VERSION_CODE})",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = GoogleBlue,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                TextButton(
                                    onClick = {
                                        onShowToast(
                                            if (isHebrew)
                                                "גרסת BuildConfig נוכחית: v${BuildConfig.VERSION_NAME} | יעד הבא: v${BuildConfig.NEXT_RELEASE_VERSION_NAME} 🚀"
                                            else
                                                "Current BuildConfig: v${BuildConfig.VERSION_NAME} | Target: v${BuildConfig.NEXT_RELEASE_VERSION_NAME} 🚀"
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "אמת גרסה" else "Verify",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Google Sign In Reset / Re-login
        item {
            SettingsCard(
                title = if (isHebrew) "חשבון Google והקמת משפחה" else "Google Account & Setup",
                icon = Icons.Default.AccountCircle,
                iconColor = GoogleGreen
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (isHebrew)
                            "מחובר כעת עם: ${settings.googleAccountEmail}\nניתן לצאת ולהיכנס מחדש להקמת משפחה נקייה או החלפת משתמש:"
                        else
                            "Connected as: ${settings.googleAccountEmail}\nSwitch account or re-enter clean family setup:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            onUpdateSettings(settings.copy(hasCompletedGoogleSignIn = false))
                            onShowToast(if (isHebrew) "מעבר למסך התחברות Google..." else "Opening Google Sign-in...")
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reset_google_login_btn")
                    ) {
                        Text(
                            text = if (isHebrew) "התנתק והיכנס עם חשבון Google מחדש 🔄" else "Sign Out & Reconnect Google 🔄",
                            fontWeight = FontWeight.Bold,
                            color = GoogleBlue
                        )
                    }
                }
            }
        }
    }

    // Custom Sound Dialog (with direct access to device files via SAF)
    if (showCustomSoundDialog) {
        Dialog(onDismissRequest = {
            SoundPlayer.stop()
            showCustomSoundDialog = false
        }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = GoogleBlueLight,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = GoogleBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isHebrew) "צליל מקבצי המכשיר" else "Custom Device Sound",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            )
                        }
                        IconButton(onClick = {
                            SoundPlayer.stop()
                            showCustomSoundDialog = false
                        }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isHebrew)
                            "בחרו קובץ שמע (MP3, WAV, M4A, OGG) מזיכרון המכשיר או הגדירו צליל מותאם אישית:"
                        else
                            "Select an audio file (MP3, WAV, M4A, OGG) from device storage for family task alerts:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Button to open Device Files Picker (SAF)
                    Button(
                        onClick = { audioFilePicker.launch("audio/*") },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("pick_device_audio_file_btn")
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isHebrew) "📁 בחר קובץ שמע מהמכשיר" else "📁 Browse Device Audio Files",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    // Display selected file info & preview
                    if (!customSoundUriInput.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = GoogleGreenLight,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = GoogleGreenDark, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = customSoundInput.ifBlank { "audio_file.mp3" },
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = GoogleGreenDark)
                                    )
                                }
                                Text(
                                    text = if (isHebrew) "קובץ שמע מקומי נטען בהצלחה מקבצי המכשיר ✓" else "Local audio file loaded from device ✓",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary, fontSize = 10.sp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            SoundPlayer.playSound(context, settings.alertSound, customSoundUriInput, settings.soundVolume)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = GoogleGreenDark, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isHebrew) "השמע בדיקה ▶" else "Play Preview", color = GoogleGreenDark, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { SoundPlayer.stop() },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isHebrew) "עצור" else "Stop", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = customSoundInput,
                        onValueChange = { customSoundInput = it },
                        label = { Text(if (isHebrew) "שם הצליל המוצג באפליקציה" else "Displayed Sound Name") },
                        placeholder = { Text(if (isHebrew) "הקלטת עידוד, שיר סיום משימה..." else "Chore cheer, task song...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick suggestion chips
                    Text(
                        text = if (isHebrew) "שמות מומלצים לבחירה מהירה:" else "Quick suggestions:",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            if (isHebrew) "הקלטת עידוד 🌟" else "Family Cheer 🌟",
                            if (isHebrew) "פעמון חלל 🔔" else "Space Chime 🔔",
                            if (isHebrew) "שריקת משימה 🚀" else "Task Whistle 🚀"
                        ).forEach { suggestion ->
                            SuggestionChip(
                                onClick = { customSoundInput = suggestion },
                                label = { Text(suggestion, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            val finalName = customSoundInput.trim().ifBlank {
                                if (isHebrew) "צליל אישי 🎵" else "Custom Sound 🎵"
                            }
                            onUpdateSettings(
                                settings.copy(
                                    customSoundName = finalName,
                                    customSoundUri = customSoundUriInput
                                )
                            )
                            SoundPlayer.playSound(context, settings.alertSound, customSoundUriInput, settings.soundVolume)
                            showCustomSoundDialog = false
                            onShowToast(if (isHebrew) "צליל אישי \"$finalName\" נשמר בהצלחה! 🎵✓" else "Custom sound \"$finalName\" saved! 🎵✓")
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_custom_sound_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = if (isHebrew) "שמור צליל אישי באפליקציה" else "Save Custom Sound", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showReleaseWorkflowDialog) {
        AlertDialog(
            onDismissRequest = { showReleaseWorkflowDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.RocketLaunch,
                    contentDescription = null,
                    tint = GoogleBlue,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = if (isHebrew) "מדריך קידום גרסאות ושחרורים" else "Release Increment Guide",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isHebrew)
                            "האפליקציה טוענת את גרסתה ישירות מהקונפיגורציה של Android Gradle Plugin דרך BuildConfig:"
                        else
                            "The application loads its version information directly from the Android Gradle Plugin configuration via BuildConfig:",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = GoogleBlueLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "BuildConfig.VERSION_NAME = \"${BuildConfig.VERSION_NAME}\"",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlue
                                )
                            )
                            Text(
                                text = "BuildConfig.VERSION_CODE = ${BuildConfig.VERSION_CODE}",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlue
                                )
                            )
                            Text(
                                text = "BuildConfig.BUILD_TYPE = \"${BuildConfig.BUILD_TYPE}\"",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    Text(
                        text = if (isHebrew) "צעדים לקידום גרסה עבור שחרורים עתידיים:" else "Steps to increment for future releases:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    Text(
                        text = if (isHebrew)
                            "• שלב 1: פתח את app/build.gradle.kts\n" +
                            "• שלב 2: העלה את currentVersionCode ב-1 (קוד ייחודי עולה עבור Google Play)\n" +
                            "• שלב 3: עדכן את versionMinor ל-${BuildConfig.NEXT_RELEASE_VERSION_NAME} או versionPatch לתיקוני באגים\n" +
                            "• שלב 4: הרץ קומפילציה (compile_applet) — ה-Settings UI יציג מיידית את הגרסה החדשה!"
                        else
                            "• Step 1: Open app/build.gradle.kts\n" +
                            "• Step 2: Bump currentVersionCode by +1 (required monotonic increase for Google Play)\n" +
                            "• Step 3: Increment versionMinor to ${BuildConfig.NEXT_RELEASE_VERSION_NAME} or versionPatch for hotfixes\n" +
                            "• Step 4: Recompile (compile_applet) — Settings UI immediately displays the new version!",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showReleaseWorkflowDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue)
                ) {
                    Text(if (isHebrew) "הבנתי, תודה!" else "Got it!")
                }
            }
        )
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
