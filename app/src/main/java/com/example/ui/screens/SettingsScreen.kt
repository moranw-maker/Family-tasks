package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.notifications.FamilySoundManager
import com.example.notifications.NotificationSoundSource
import com.example.notifications.FamilyAlarmReceiver
import com.example.notifications.FamilyAlarmScheduler
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilyMemberAvatar
import com.example.ui.components.FamilyPrimaryButton
import com.example.ui.components.FamilySecondaryButton
import com.example.ui.components.FamilySectionHeader
import com.example.ui.components.FamilySettingsRow
import com.example.ui.components.FamilyStatusChip
import com.example.ui.components.SoftGlassScreenBackground
import com.example.ui.theme.*

@Composable
fun SettingsScreen(
    familyName: String,
    currentMember: FamilyMember,
    isGoogleSignedIn: Boolean,
    googleEmail: String,
    cloudFamilyGroup: CloudFamilyGroup,
    autoSyncCalendar: Boolean,
    autoSyncDrive: Boolean,
    appLanguage: AppLanguage,
    themeMode: AppThemeMode,
    firstDayOfWeek: FirstDayOfWeek,
    biometricLockEnabled: Boolean,
    tenMinuteClassAlertsEnabled: Boolean,
    onEditFamilyName: () -> Unit,
    onOpenAdminDashboard: () -> Unit,
    onOpenGoogleSignIn: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onAutoSyncCalendarChange: (Boolean) -> Unit,
    onAutoSyncDriveChange: (Boolean) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onFirstDayOfWeekChange: (FirstDayOfWeek) -> Unit,
    onBiometricLockChange: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    onTenMinuteAlertsChange: (Boolean) -> Unit,
    onUpdateFamilyIdentity: (FamilyIdentity) -> Unit = {},
    onShowToast: (String) -> Unit = {},
    appTextScale: AppTextScale = AppTextScale.NORMAL,
    onTextScaleChange: (AppTextScale) -> Unit = {},
    onOpenPointsManagement: () -> Unit = {},
    onUpdateMemberAvatar: (targetMemberId: String, avatarId: String) -> Unit = { _, _ -> }
) {
    val isHebrew = appLanguage == AppLanguage.HEBREW
    val glass = LocalSoftGlassColors.current
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val diagnosticState by FamilyAlarmScheduler.diagnosticState.collectAsState()
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    var soundConfigRefreshTick by remember { mutableIntStateOf(0) }
    var showAvatarPickerForMember by remember { mutableStateOf<FamilyMember?>(null) }
    var showAlarmDiagnosticsPanel by remember {
        mutableStateOf(
            !FamilyAlarmScheduler.canScheduleExactAlarms(context) ||
                !FamilyAlarmScheduler.canUseFullScreenIntent(context)
        )
    }

    LaunchedEffect(currentMember.id) {
        if (currentMember.id.isNotBlank()) {
            FamilySoundManager.setActiveNotificationMemberId(context, currentMember.id)
            soundConfigRefreshTick++
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionRefreshTick++
                soundConfigRefreshTick++
                if (FamilyAlarmScheduler.canScheduleExactAlarms(context)) {
                    FamilyAlarmScheduler.restoreAlarmsOnBoot(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            FamilySoundManager.stopNotificationPreview(context)
            com.example.notifications.AlarmAudioController.stopPreview(context)
        }
    }

    showAvatarPickerForMember?.let { memberForAvatar ->
        com.example.ui.components.AvatarPickerDialog(
            targetMember = memberForAvatar,
            isHebrew = isHebrew,
            onDismiss = { showAvatarPickerForMember = null },
            onSaveAvatar = { chosenAvatarId ->
                onUpdateMemberAvatar(memberForAvatar.id, chosenAvatarId)
                showAvatarPickerForMember = null
            }
        )
    }

    SoftGlassScreenBackground(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings_screen"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Quick Screen Help Guide
            item {
                com.example.ui.components.ScreenHelpBanner(
                    title = if (isHebrew)
                        "הגדרות, ענן ואבטחה (${com.example.util.AppVersionManager.displayVersionLabel})"
                    else
                        "Settings, Cloud & Security (${com.example.util.AppVersionManager.displayVersionLabel})",
                    tips = if (isHebrew) listOf(
                        "מראה ונגישות: בחרו ערכת נושא (בהיר, כהה או לפי הגדרות המכשיר) והתאימו את גודל הטקסט באפליקציה.",
                        "ענן משפחתי משותף: נהלו את חיבור Google, קוד ההזמנה של המשפחה וסנכרון ליומן ו-Drive.",
                        "הרשאות הורה מול ילד/ה: ניהול המשפחה ושינוי שם המשפחה פתוחים להורים (PARENT) בלבד.",
                        "התראות ושעונים: כלי אבחון ובדיקת צלצול לשעונים מעוררים והתראות שיעור."
                    ) else listOf(
                        "Appearance & Accessibility: Choose Light/Dark/System theme and adjust global text size.",
                        "Shared Family Cloud: Manage Google account sync, family invite code, Calendar & Drive.",
                        "Parent vs Child Roles: Family management and renaming are restricted to PARENT members.",
                        "Notifications & Alarms: Diagnostic tools and 15-second alarm test."
                    ),
                    isHebrew = isHebrew
                )
            }

            // ============================================================
            // SECTION 1: מראה ונגישות (Appearance & Accessibility)
            // ============================================================
            item {
                FamilySectionHeader(
                    title = if (isHebrew) "מראה ונגישות" else "Appearance & Accessibility",
                    subtitle = if (isHebrew) "ערכת נושא, גודל טקסט, שפה וכיוון תצוגה" else "Theme mode, text size, language & layout"
                )
            }

            // 1A. Theme Mode Selection ("ערכת נושא • Velvet Edition": "בהיר" (Velvet Light), "כהה" (Velvet Dark), "לפי הגדרות המכשיר")
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "ערכת נושא • Velvet Edition" else "Theme Mode • Velvet Edition",
                    icon = Icons.Default.Palette,
                    iconTint = glass.primaryAccent
                ) {
                    val orderedModes = listOf(AppThemeMode.LIGHT, AppThemeMode.DARK, AppThemeMode.SYSTEM)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        orderedModes.forEach { mode ->
                            val isSelected = themeMode == mode
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) glass.primaryAccent else glass.screenBackground,
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) glass.primaryAccent else glass.cardBorder
                                ),
                                shadowElevation = if (isSelected) 3.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onThemeModeChange(mode) }
                                    .testTag("theme_mode_${mode.name}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = mode.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) {
                                                if (glass.isDark) Color(0xFF0B1120) else Color.White
                                            } else {
                                                glass.textPrimary
                                            }
                                        ),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = mode.getEditionSubtitle(isHebrew),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) {
                                                if (glass.isDark) Color(0xFF1E293B) else Color.White.copy(alpha = 0.88f)
                                            } else {
                                                glass.textMuted
                                            }
                                        ),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1B. Global Text Size Adjustment ("גודל טקסט" + Live Preview "כך ייראה הטקסט באפליקציה")
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "גודל טקסט" else "Text Size",
                    icon = Icons.Default.FormatSize,
                    iconTint = SoftTeal
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppTextScale.values().forEach { scaleOption ->
                            val isSelected = appTextScale == scaleOption
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) glass.primaryAccent else glass.screenBackground,
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) glass.primaryAccent else glass.cardBorder
                                ),
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onTextScaleChange(scaleOption) }
                                    .testTag("text_scale_${scaleOption.name}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = scaleOption.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) {
                                                if (glass.isDark) Color(0xFF0B1120) else Color.White
                                            } else {
                                                glass.textPrimary
                                            }
                                        ),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Live Preview inside Settings using FamilyGlassCard
                    FamilyGlassCard(
                        cornerRadius = 16.dp,
                        isTintedBlue = true,
                        elevation = 1.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_text_scale_preview")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isHebrew) "כך ייראה הטקסט באפליקציה" else "This is how text will look in the app",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = glass.textPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            FamilyStatusChip(
                                label = "${(appTextScale.scaleFactor * 100).toInt()}%"
                            )
                        }
                    }
                }
            }

            // 1C. Language & RTL Direction
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "שפה וכיוון תצוגה (RTL / LTR)" else "Language & Layout Direction",
                    icon = Icons.Default.Language,
                    iconTint = SoftLavender
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppLanguage.values().forEach { lang ->
                            val isSelected = appLanguage == lang
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) glass.primaryAccent else glass.screenBackground,
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) glass.primaryAccent else glass.cardBorder
                                ),
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onLanguageChange(lang) }
                                    .testTag("lang_option_${lang.code}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lang.displayName,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) {
                                                if (glass.isDark) Color(0xFF0B1120) else Color.White
                                            } else {
                                                glass.textPrimary
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1D. First Day of Week Selection (Sunday vs Monday)
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "היום הראשון בשבוע בלוח השנה" else "Calendar First Day of Week",
                    icon = Icons.Default.DateRange,
                    iconTint = GoogleYellow
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FirstDayOfWeek.values().forEach { dayOption ->
                            val isSelected = firstDayOfWeek == dayOption
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) glass.primaryAccent else glass.screenBackground,
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) glass.primaryAccent else glass.cardBorder
                                ),
                                shadowElevation = if (isSelected) 2.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onFirstDayOfWeekChange(dayOption) }
                                    .testTag("first_day_${dayOption.name}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayOption.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) {
                                                if (glass.isDark) Color(0xFF0B1120) else Color.White
                                            } else {
                                                glass.textPrimary
                                            }
                                        ),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ============================================================
            // SECTION 2: התראות ושעונים (Notifications & Alarms)
            // ============================================================
            item {
                FamilySectionHeader(
                    title = if (isHebrew) "התראות ושעונים" else "Notifications & Alarms",
                    subtitle = if (isHebrew) "התראות שיעור, נעילה ביומטרית ואבחון שעון מעורר" else "Class alerts, biometric lock & alarm diagnostics"
                )
            }

            // 2A. Security (Biometric Lock) & Smart School Alerts
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "אבטחה והתראות חכמות" else "Security & Smart Alerts",
                    icon = Icons.Default.Security,
                    iconTint = GoogleRed
                ) {
                    SettingsSwitchRow(
                        title = if (isHebrew) "התראת שיעור 10 דקות לפני תחילתו 🔔" else "10-Minute Pre-Class Alert 🔔",
                        subtitle = if (isHebrew) "שליחת התראה אוטומטית לילדים 10 דקות לפני כל שיעור במערכת" else "Alert children 10 minutes before each school period",
                        checked = tenMinuteClassAlertsEnabled,
                        onCheckedChange = onTenMinuteAlertsChange,
                        testTag = "switch_ten_min_alerts"
                    )

                    HorizontalDivider(color = glass.divider, modifier = Modifier.padding(vertical = 8.dp))

                    SettingsSwitchRow(
                        title = if (isHebrew) "נעילה ביומטרית / קוד גישה 🔒" else "Biometric / PIN App Lock 🔒",
                        subtitle = if (isHebrew) "הגנה על פרטיות המסמכים והמשימות המשפחתיות" else "Protect family files and settings with fingerprint/PIN",
                        checked = biometricLockEnabled,
                        onCheckedChange = onBiometricLockChange,
                        testTag = "switch_biometric_lock"
                    )

                    if (biometricLockEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FamilySecondaryButton(
                            text = if (isHebrew) "נעל את האפליקציה עכשיו" else "Lock App Now",
                            leadingIcon = Icons.Default.Lock,
                            onClick = onLockNow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("lock_app_now_btn")
                        )
                    }
                }
            }

            // 2A-2. Simplified Notification Sound Settings ("צליל התראות משימות • Velvet Edition")
            item(key = "settings_velvet_sound_studio_card") {
                NotificationSoundSettingsSection(
                    memberId = currentMember.id,
                    isHebrew = isHebrew,
                    refreshTick = soundConfigRefreshTick,
                    onSelectionChanged = { updatedSelection ->
                        soundConfigRefreshTick++
                        val label = updatedSelection.displayTitle(isHebrew)
                        onShowToast(
                            if (isHebrew) "צליל ההתראות נשמר: $label 🔔"
                            else "Notification sound saved: $label 🔔"
                        )
                    }
                )
            }

            // 2B. Alarm Clock Advanced Settings & Diagnostics ("שעונים מעוררים והשכמה")
            item(key = "settings_alarm_diagnostics_card") {
                val hasExactPermission = remember(diagnosticState, showAlarmDiagnosticsPanel, permissionRefreshTick) {
                    FamilyAlarmScheduler.canScheduleExactAlarms(context)
                }
                val canUseFullScreen = remember(diagnosticState, showAlarmDiagnosticsPanel, permissionRefreshTick) {
                    FamilyAlarmScheduler.canUseFullScreenIntent(context)
                }
                val hasNotifications = remember(diagnosticState, showAlarmDiagnosticsPanel, permissionRefreshTick) {
                    FamilyAlarmScheduler.hasNotificationPermission(context)
                }
                val isBatteryUnrestricted = remember(showAlarmDiagnosticsPanel, permissionRefreshTick) {
                    FamilyAlarmScheduler.isIgnoringBatteryOptimizations(context)
                }

                FamilyGlassCard(
                    cornerRadius = 24.dp,
                    elevation = 3.dp,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_alarm_diagnostics_section")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAlarmDiagnosticsPanel = !showAlarmDiagnosticsPanel }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("settings_alarm_diagnostics_entry"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = glass.primaryAccentSoftBg,
                                border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Alarm,
                                        contentDescription = null,
                                        tint = glass.primaryAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isHebrew) "שעונים מעוררים והשכמה" else "Alarms & Wake-Up",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.textPrimary
                                    ),
                                    modifier = Modifier.testTag("settings_alarm_diagnostics_title")
                                )
                                val allCriticalPermissionsReady = hasExactPermission && canUseFullScreen
                                Text(
                                    text = if (isHebrew) {
                                        when {
                                            !hasExactPermission -> "⚠️ נדרש אישור הרשאת שעונים מעוררים ותזכורות לצלצול כשהאפליקציה סגורה"
                                            !canUseFullScreen -> "⚠️ נדרש אישור \"התראות במסך מלא\" לפתיחה אוטומטית של מסך הזריחה"
                                            else -> "הרשאות שעון מדויק ומסך מלא פעילות ✓ • הגדרות וכלי אבחון"
                                        }
                                    } else {
                                        when {
                                            !hasExactPermission -> "⚠️ Exact Alarms & Reminders permission required when app is closed"
                                            !canUseFullScreen -> "⚠️ Full-screen notification permission required for automatic sunrise screen"
                                            else -> "Exact alarm & full-screen permissions active ✓ • Settings & diagnostics"
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (allCriticalPermissionsReady) glass.textSecondary else glass.warningText,
                                        fontWeight = if (allCriticalPermissionsReady) FontWeight.Normal else FontWeight.SemiBold
                                    ),
                                    modifier = Modifier.testTag("settings_alarm_diagnostics_subtitle")
                                )
                            }
                        }

                        Icon(
                            imageVector = if (showAlarmDiagnosticsPanel) {
                                Icons.Default.ExpandLess
                            } else if (isHebrew) {
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = if (isHebrew) "פתח כלי אבחון" else "Open diagnostics",
                            tint = glass.textSecondary
                        )
                    }

                    AnimatedVisibility(visible = showAlarmDiagnosticsPanel) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                .testTag("settings_alarm_diagnostics_panel"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HorizontalDivider(color = glass.divider)

                            // Permission Readiness Checks
                            FamilyGlassCard(
                                cornerRadius = 16.dp,
                                isTintedBlue = true,
                                elevation = 1.dp,
                                contentPadding = PaddingValues(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = if (isHebrew) "סטטוס הרשאות מערכת לשעון מעורר" else "System Alarm Permissions Status",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glass.primaryAccent
                                        )
                                    )

                                    AlarmPermissionDiagnosticRow(
                                        label = if (isHebrew) "הרשאת התראה מדויקת (Alarms & Reminders)" else "Exact Alarm Permission (Alarms & Reminders)",
                                        isGranted = hasExactPermission,
                                        actionLabel = if (isHebrew) "אשר הרשאה" else "Grant",
                                        testTag = "grant_exact_alarm_permission_button",
                                        onAction = { FamilyAlarmScheduler.openExactAlarmPermissionSettings(context) }
                                    )

                                    if (!hasExactPermission) {
                                        FamilyPrimaryButton(
                                            text = if (isHebrew) {
                                                "פתח הרשאת \"שעונים מעוררים ותזכורות\" באנדרואיד"
                                            } else {
                                                "Open Android Alarms & Reminders Permission"
                                            },
                                            leadingIcon = Icons.Default.Alarm,
                                            onClick = { FamilyAlarmScheduler.openExactAlarmPermissionSettings(context) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("open_exact_alarm_settings_primary_button")
                                        )
                                    }

                                    AlarmPermissionDiagnosticRow(
                                        label = if (isHebrew) "הצגה במסך מלא מעל נעילה (Full-Screen)" else "Full-Screen Lock Intent",
                                        isGranted = canUseFullScreen,
                                        actionLabel = if (isHebrew) "אפשר מסך מלא" else "Allow",
                                        testTag = "grant_fullscreen_alarm_permission_button",
                                        onAction = { FamilyAlarmScheduler.openFullScreenIntentSettings(context) }
                                    )

                                    if (!canUseFullScreen) {
                                        Text(
                                            text = if (isHebrew) {
                                                "נדרשת הרשאת התראה במסך מלא כדי שהשעון המעורר יוכל להופיע מעל מסך הנעילה."
                                            } else {
                                                "Full-screen notification permission is required so the alarm can appear over the lock screen."
                                            },
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = glass.warningText,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            modifier = Modifier.testTag("settings_fullscreen_alarm_warning_text")
                                        )
                                        FamilyPrimaryButton(
                                            text = if (isHebrew) {
                                                "אפשר מסך מלא"
                                            } else {
                                                "Allow Full-Screen"
                                            },
                                            leadingIcon = Icons.Default.Fullscreen,
                                            onClick = { FamilyAlarmScheduler.openFullScreenIntentSettings(context) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("open_fullscreen_alarm_settings_primary_button")
                                        )
                                    }

                                    AlarmPermissionDiagnosticRow(
                                        label = if (isHebrew) "התראות מערכת (Notifications)" else "System Notifications",
                                        isGranted = hasNotifications,
                                        actionLabel = if (isHebrew) "הפעל התראות" else "Enable",
                                        testTag = "grant_alarm_notification_permission_button",
                                        onAction = { FamilyAlarmScheduler.openNotificationSettings(context) }
                                    )

                                    AlarmPermissionDiagnosticRow(
                                        label = if (isHebrew) "החרגה מחיסכון בסוללה (Unrestricted Battery)" else "Unrestricted Battery",
                                        isGranted = isBatteryUnrestricted,
                                        actionLabel = if (isHebrew) "הגדרות סוללה" else "Battery",
                                        testTag = "open_battery_optimization_button",
                                        onAction = { FamilyAlarmScheduler.openBatteryOptimizationSettings(context) }
                                    )
                                }
                            }

                            // 15-Second Real Diagnostic Alarm & Pipeline Status
                            SettingsDeveloperAlarmDiagnosticsContent(
                                diagnosticState = diagnosticState,
                                isHebrew = isHebrew,
                                onSchedule15sTest = {
                                    val res = FamilyAlarmScheduler.scheduleDiagnostic15SecondAlarm(
                                        context = context,
                                        currentMemberName = currentMember.name
                                    )
                                    if (res.isScheduled) {
                                        onShowToast(
                                            if (isHebrew) "בדיקת צלצול אמיתית נקבעה ב-AlarmManager לעוד 15 שניות ⏱️"
                                            else "Real 15-second AlarmManager test scheduled ⏱️"
                                        )
                                    } else {
                                        onShowToast(
                                            if (isHebrew) "שגיאה בתזמון בדיקת 15 שניות: ${res.errorMessage}"
                                            else "Failed to schedule 15s test: ${res.errorMessage}"
                                        )
                                    }
                                },
                                onStopRinging = {
                                    FamilyAlarmReceiver.stopActiveRinging(context)
                                    onShowToast(
                                        if (isHebrew) "כל צלצול פעיל הופסק" else "Stopped active alarm ringing"
                                    )
                                },
                                onReset = {
                                    FamilyAlarmScheduler.resetDiagnosticState(context)
                                }
                            )
                        }
                    }
                }
            }

            // ============================================================
            // SECTION 3: משפחה וחשבון (Family & Account)
            // ============================================================
            item {
                FamilySectionHeader(
                    title = if (isHebrew) "משפחה וחשבון" else "Family & Account",
                    subtitle = if (isHebrew) "פרופיל משפחתי, הרשאות הורה/ילד וסנכרון Google" else "Family profile, roles & Google cloud sync"
                )
            }

            // 3A. Family Identity & Admin Management Card
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "משפחה והרשאות ניהול" else "Family & Admin Management",
                    icon = Icons.Default.FamilyRestroom,
                    iconTint = glass.primaryAccent
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            FamilyMemberAvatar(member = currentMember, size = 42.dp, isHighlighted = true)
                            Column {
                                Text(
                                    text = familyName,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.textPrimary
                                    )
                                )
                                val identityLabel = currentMember.familyIdentity.getDisplayName(isHebrew)
                                val roleLabel = currentMember.role.getDisplayName(isHebrew)
                                Text(
                                    text = if (isHebrew)
                                        "פרופיל פעיל: ${currentMember.name} • זהות: $identityLabel ($roleLabel)"
                                    else
                                        "Active: ${currentMember.nameEn} • Identity: $identityLabel ($roleLabel)",
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary),
                                    modifier = Modifier.testTag("settings_member_identity_badge")
                                )
                            }
                        }

                        if (currentMember.isParent) {
                            FamilySecondaryButton(
                                text = if (isHebrew) "שנה שם" else "Rename",
                                leadingIcon = Icons.Default.Edit,
                                onClick = onEditFamilyName,
                                modifier = Modifier.testTag("settings_edit_family_name_btn")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    FamilySecondaryButton(
                        text = if (isHebrew) "🎨 בחר אווטאר" else "🎨 Choose Avatar",
                        onClick = { showAvatarPickerForMember = currentMember },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_avatar_picker_btn")
                    )

                    if (currentMember.isParent) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isHebrew) "זהות הורה בפרופיל (נשמר בענן):" else "Parent Identity in Profile:",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = glass.textSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isFather = currentMember.familyIdentity == FamilyIdentity.FATHER
                            val isMother = currentMember.familyIdentity == FamilyIdentity.MOTHER
                            FilterChip(
                                selected = isFather,
                                onClick = { onUpdateFamilyIdentity(FamilyIdentity.FATHER) },
                                label = {
                                    Text(
                                        text = if (isHebrew) "👨 אבא (Father)" else "👨 Father",
                                        fontWeight = if (isFather) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_identity_father_chip")
                            )
                            FilterChip(
                                selected = isMother,
                                onClick = { onUpdateFamilyIdentity(FamilyIdentity.MOTHER) },
                                label = {
                                    Text(
                                        text = if (isHebrew) "👩 אמא (Mother)" else "👩 Mother",
                                        fontWeight = if (isMother) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_identity_mother_chip")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FamilyPrimaryButton(
                            text = if (isHebrew) "פתח לוח ניהול משפחה והזמנות" else "Open Family & Invitations Dashboard",
                            leadingIcon = Icons.Default.AdminPanelSettings,
                            onClick = onOpenAdminDashboard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_admin_btn")
                        )
                    } else {
                        Spacer(modifier = Modifier.height(10.dp))
                        FamilyGlassCard(
                            cornerRadius = 16.dp,
                            accentColor = GoogleGreen,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("child_role_info_banner")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = glass.successText,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = if (isHebrew)
                                            "מחובר כילד/ה (${currentMember.name})"
                                        else
                                            "Signed in as Child (${currentMember.name})",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = glass.successText
                                        )
                                    )
                                    Text(
                                        text = if (isHebrew)
                                            "משויך למשפחת $familyName • סמנו משימות כבוצע לשליחה לאישור הורה וצבירת נקודות"
                                        else
                                            "Connected to $familyName • Complete tasks to submit for parent approval",
                                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3A-2. Parent-Only Family Points Management Section ("ניהול נקודות משפחתי ⭐")
            if (currentMember.isParent) {
                item(key = "settings_family_points_management_item") {
                    SettingsCardContainer(
                        title = if (isHebrew) "ניהול נקודות משפחתי ⭐" else "Family Points Management ⭐",
                        icon = Icons.Default.Stars,
                        iconTint = Color(0xFFF59E0B)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_points_management_card"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (isHebrew) {
                                    "נהלו את חשבונות הנקודות של בני המשפחה בזמן אמת: הוספת נקודות בונוס, הפחתה מבוקרת, איפוס יתרה וצפייה בהיסטוריית השינויים המלאה."
                                } else {
                                    "Manage family member points in real time: add bonus points, deduct points, reset balance, and view the full change history."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = glass.textSecondary
                                )
                            )

                            FamilyPrimaryButton(
                                text = if (isHebrew) "ניהול נקודות משפחתי ⭐" else "Family Points Management ⭐",
                                leadingIcon = Icons.Default.Stars,
                                onClick = onOpenPointsManagement,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_open_points_management_btn")
                            )
                        }
                    }
                }
            }

            // 3B. Google Workspace & Cloud Family Group Synchronization Card
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "סנכרון חשבון Google וענן משפחתי" else "Google Account & Cloud Family Sync",
                    icon = Icons.Default.CloudDone,
                    iconTint = GoogleGreen
                ) {
                    FamilyGlassCard(
                        cornerRadius = 16.dp,
                        isTintedBlue = true,
                        elevation = 1.dp,
                        contentPadding = PaddingValues(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = googleEmail,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.primaryAccent
                                    )
                                )
                                Text(
                                    text = if (isHebrew)
                                        "חברים מסונכרנים: ${cloudFamilyGroup.syncedMembersCount} • תפקיד: ${currentMember.role.getDisplayName(true)}"
                                    else
                                        "Synced members: ${cloudFamilyGroup.syncedMembersCount} • Role: ${currentMember.role.getDisplayName(false)}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)
                                )
                            }

                            FamilyPrimaryButton(
                                text = if (isHebrew) "החלף / חבר" else "Connect",
                                onClick = onOpenGoogleSignIn,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("settings_google_connect_btn")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    SettingsSwitchRow(
                        title = if (isHebrew) "סנכרון אוטומטי ל-Google Calendar" else "Auto-Sync to Google Calendar",
                        subtitle = if (isHebrew) "כל משימה חדשה מתווספת ליומן המשותף" else "Sync chores to shared Google Calendar",
                        checked = autoSyncCalendar,
                        onCheckedChange = onAutoSyncCalendarChange,
                        testTag = "switch_auto_sync_calendar"
                    )

                    HorizontalDivider(color = glass.divider, modifier = Modifier.padding(vertical = 6.dp))

                    SettingsSwitchRow(
                        title = if (isHebrew) "גיבוי מסמכים אוטומטי ל-Google Drive" else "Auto-Backup Files to Google Drive",
                        subtitle = if (isHebrew) "שמירת קבצי לימודים, רפואה וציורים בענן" else "Sync family documents to Google Drive",
                        checked = autoSyncDrive,
                        onCheckedChange = onAutoSyncDriveChange,
                        testTag = "switch_auto_sync_drive"
                    )
                }
            }

            // ============================================================
            // SECTION 4: מידע (Information & Version Footer)
            // ============================================================
            item {
                FamilySectionHeader(
                    title = if (isHebrew) "מידע" else "Information"
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "FamilyTasks Workspace ${com.example.util.AppVersionManager.displayVersionLabel} (Build ${com.example.util.AppVersionManager.currentVersionCode}) • Google Calendar & Drive Edition",
                        style = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("settings_app_version_footer")
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmPermissionDiagnosticRow(
    label: String,
    isGranted: Boolean,
    actionLabel: String,
    testTag: String,
    onAction: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (isGranted) "✓" else "⚠️",
                fontSize = 13.sp,
                color = if (isGranted) glass.successText else glass.warningText,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = glass.textPrimary,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        if (isGranted) {
            FamilyStatusChip(
                label = "תקין",
                containerColor = glass.successBg,
                contentColor = glass.successText
            )
        } else {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(30.dp)
                    .testTag(testTag)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = glass.primaryAccent
                )
            }
        }
    }
}

@Composable
private fun SettingsDeveloperAlarmDiagnosticsContent(
    diagnosticState: com.example.notifications.AlarmDiagnosticState,
    isHebrew: Boolean,
    onSchedule15sTest: () -> Unit,
    onStopRinging: () -> Unit,
    onReset: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(diagnosticState.isScheduled, diagnosticState.triggerAtMillis) {
        while (diagnosticState.isScheduled && !diagnosticState.receiverTriggered) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(500L)
        }
        nowMillis = System.currentTimeMillis()
    }

    val remainingSeconds = if (diagnosticState.isScheduled && diagnosticState.triggerAtMillis > nowMillis) {
        ((diagnosticState.triggerAtMillis - nowMillis + 999L) / 1000L).coerceAtLeast(0L)
    } else {
        0L
    }

    FamilyGlassCard(
        cornerRadius = 16.dp,
        isTintedBlue = true,
        elevation = 1.dp,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("developer_alarm_diagnostics_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isHebrew) "כלי בדיקה ואבחון צלצול" else "Alarm Diagnostic Tools",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.primaryAccent
                    )
                )
                if (diagnosticState.isScheduled || diagnosticState.errorMessage.isNotBlank()) {
                    TextButton(
                        onClick = onReset,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = if (isHebrew) "איפוס" else "Reset",
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FamilyPrimaryButton(
                    text = if (isHebrew) "בדיקת צלצול בעוד 15 שניות" else "Test Alarm in 15 Seconds",
                    onClick = onSchedule15sTest,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("diagnostic_alarm_15s_button")
                )

                FamilySecondaryButton(
                    text = if (isHebrew) "השתק" else "Silence",
                    leadingIcon = Icons.Default.AlarmOff,
                    onClick = onStopRinging,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("stop_active_alarm_ringing_button")
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.cardBackground, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                val permStatusText = if (diagnosticState.exactAlarmPermissionGranted) {
                    "✓ הרשאת התראה מדויקת: מאושרת (${diagnosticState.schedulingMethod.ifBlank { "EXACT" }})"
                } else {
                    "⚠️ הרשאת התראה מדויקת: לא זמינה (שימוש ב-AlarmClock / Window)"
                }
                Text(
                    text = permStatusText,
                    style = MaterialTheme.typography.labelSmall.copy(color = glass.textPrimary),
                    modifier = Modifier.testTag("diag_status_permission")
                )

                val scheduledText = if (diagnosticState.isScheduled) {
                    val countNote = if (!diagnosticState.receiverTriggered && remainingSeconds > 0L) {
                        " (בעוד ${remainingSeconds} שניות)"
                    } else ""
                    "✓ 1. נקבע ב-AlarmManager: כן$countNote"
                } else {
                    "○ 1. נקבע ב-AlarmManager: טרם הופעל"
                }
                Text(
                    text = scheduledText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.isScheduled) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.isScheduled) glass.successText else glass.textSecondary
                    ),
                    modifier = Modifier.testTag("diag_status_scheduled")
                )

                val receiverText = if (diagnosticState.receiverTriggered) {
                    "✓ 2. BroadcastReceiver הופעל (FamilyAlarmReceiver)"
                } else {
                    "○ 2. BroadcastReceiver הופעל: ממתין..."
                }
                Text(
                    text = receiverText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.receiverTriggered) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.receiverTriggered) glass.successText else glass.textSecondary
                    ),
                    modifier = Modifier.testTag("diag_status_receiver")
                )

                val serviceText = if (diagnosticState.ringingServiceStarted) {
                    "✓ 3. שירות צלצול הופעל (FamilyAlarmRingingService Foreground)"
                } else {
                    "○ 3. שירות צלצול הופעל: ממתין..."
                }
                Text(
                    text = serviceText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.ringingServiceStarted) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.ringingServiceStarted) glass.successText else glass.textSecondary
                    ),
                    modifier = Modifier.testTag("diag_status_service")
                )

                val audioText = if (diagnosticState.audioPlaybackStarted) {
                    val engine = diagnosticState.audioEngineDetails.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
                    "✓ 4. ניגון שמע הופעל (USAGE_ALARM)$engine"
                } else {
                    "○ 4. ניגון שמע הופעל: ממתין..."
                }
                Text(
                    text = audioText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (diagnosticState.audioPlaybackStarted) FontWeight.Bold else FontWeight.Normal,
                        color = if (diagnosticState.audioPlaybackStarted) glass.successText else glass.textSecondary
                    ),
                    modifier = Modifier.testTag("diag_status_audio")
                )

                if (diagnosticState.errorMessage.isNotBlank()) {
                    Text(
                        text = "❌ שגיאה: ${diagnosticState.errorMessage}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = glass.dangerText
                        ),
                        modifier = Modifier.testTag("diag_status_error")
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCardContainer(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalSoftGlassColors.current
    FamilyGlassCard(
        cornerRadius = 24.dp,
        elevation = 3.dp,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = iconTint.copy(alpha = if (glass.isDark) 0.24f else 0.14f),
                border = BorderStroke(1.dp, iconTint.copy(alpha = 0.35f)),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        content()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    FamilySettingsRow(
        title = title,
        subtitle = subtitle,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag)
            )
        }
    )
}

/**
 * Simplified, clean, unified Velvet Edition Notification Sound Settings interface.
 *
 * Provides:
 * - Currently selected notification sound header banner at the top (`notification_selected_sound_banner`).
 * - Silent notification option ("ללא שמע (שקט)") (`notification_sound_silent_option`).
 * - Exactly 3 sound sources:
 *   1. Built-in Sounds ("צלילים מובנים") — the 10 attached MP3 files with clear names,
 *      clear visual selection highlight, automatic saving on click, and Play/Stop preview button.
 *   2. Android System Sounds ("צלילי מערכת Android") — opens native Android Ringtone Picker.
 *   3. Custom Device Sound ("צליל אישי מהמכשיר") — opens Android Storage Access Framework (`OpenDocument`).
 */
@Composable
fun NotificationSoundSettingsSection(
    memberId: String,
    isHebrew: Boolean = true,
    refreshTick: Int = 0,
    onSelectionChanged: (com.example.notifications.UnifiedNotificationSoundSelection) -> Unit = {}
) {
    val context = LocalContext.current
    val glass = LocalSoftGlassColors.current
    val previewState by FamilySoundManager.notificationPreviewState.collectAsState()
    var internalTick by remember { mutableIntStateOf(0) }

    val builtInSounds = remember(context) {
        FamilySoundManager.getVerifiedBuiltInNotificationSounds(context)
    }
    val currentSelection = remember(context, memberId, refreshTick, internalTick) {
        FamilySoundManager.getUnifiedNotificationSoundSelection(context, memberId)
    }

    val systemRingtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val updated = FamilySoundManager.handleSystemNotificationRingtonePickerResult(
            context = context,
            resultCode = result.resultCode,
            data = result.data,
            memberId = memberId
        )
        if (updated != null) {
            internalTick++
            onSelectionChanged(updated)
        }
    }

    val customFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri: Uri? ->
        if (pickedUri != null) {
            FamilySoundManager.selectCustomFileNotificationSound(
                context = context,
                documentUri = pickedUri,
                memberId = memberId
            ).onSuccess { updated ->
                internalTick++
                onSelectionChanged(updated)
            }
        }
    }

    SettingsCardContainer(
        title = if (isHebrew) "צלילי התראות משימות • Velvet Edition" else "Task Notification Sounds • Velvet Edition",
        icon = Icons.Default.NotificationsActive,
        iconTint = glass.primaryAccent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("notification_sound_settings_section"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ============================================================
            // Top Header: Currently Selected Notification Sound
            // ============================================================
            FamilyGlassCard(
                cornerRadius = 16.dp,
                isTintedBlue = true,
                elevation = 1.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notification_selected_sound_banner")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHebrew) "הצליל הנבחר כרגע" else "Currently Selected Sound",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = glass.textSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentSelection.displayTitle(isHebrew),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.primaryAccent
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("notification_selected_sound_title")
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FamilyStatusChip(
                            label = currentSelection.sourceBadge(isHebrew),
                            modifier = Modifier.testTag("notification_selected_sound_source_badge")
                        )
                    }

                    if (!currentSelection.isSilent && currentSelection.soundUri.isNotBlank()) {
                        val headerKey = when (currentSelection.source) {
                            NotificationSoundSource.BUILT_IN -> currentSelection.builtInSoundId
                            NotificationSoundSource.SYSTEM -> "system_selected"
                            NotificationSoundSource.CUSTOM_FILE -> "custom_selected"
                            NotificationSoundSource.SILENT -> ""
                        }
                        val isPlayingCurrent = previewState.isPlaying && (
                            previewState.activeSoundKey == headerKey ||
                                previewState.activeUri == currentSelection.soundUri
                            )
                        IconButton(
                            onClick = {
                                FamilySoundManager.toggleNotificationPreview(
                                    context = context,
                                    soundKey = headerKey,
                                    uri = runCatching { Uri.parse(currentSelection.soundUri) }.getOrNull()
                                )
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isPlayingCurrent) GoogleRedLight else glass.primaryAccentSoftBg)
                                .testTag("notification_selected_sound_preview_btn")
                        ) {
                            Icon(
                                imageVector = if (isPlayingCurrent) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isPlayingCurrent) {
                                    if (isHebrew) "עצור השמעה" else "Stop preview"
                                } else {
                                    if (isHebrew) "השמע צליל נבחר" else "Preview selected sound"
                                },
                                tint = if (isPlayingCurrent) GoogleRedDark else glass.primaryAccent,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // ============================================================
            // Option: Silent Notification ("ללא שמע (שקט)")
            // ============================================================
            val isSilentSelected = currentSelection.source == NotificationSoundSource.SILENT
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSilentSelected) glass.primaryAccentSoftBg else glass.screenBackground,
                border = BorderStroke(
                    width = if (isSilentSelected) 1.5.dp else 1.dp,
                    color = if (isSilentSelected) glass.primaryAccent else glass.cardBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        val updated = FamilySoundManager.selectSilentNotificationSound(
                            context = context,
                            memberId = memberId
                        )
                        internalTick++
                        onSelectionChanged(updated)
                    }
                    .testTag("notification_sound_silent_option")
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = null,
                            tint = if (isSilentSelected) glass.primaryAccent else glass.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isHebrew) "ללא שמע (שקט)" else "Silent (No Sound)",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSilentSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = if (isSilentSelected) glass.primaryAccent else glass.textPrimary
                            )
                        )
                    }
                    Icon(
                        imageVector = if (isSilentSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSilentSelected) glass.primaryAccent else glass.textMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ============================================================
            // Source 2 & Source 3: צלילי מערכת Android + צליל אישי מהמכשיר
            // ============================================================
            val isSystemSelected = currentSelection.source == NotificationSoundSource.SYSTEM
            val isPlayingSystem = previewState.isPlaying && previewState.activeSoundKey == "system_selected"

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSystemSelected) glass.primaryAccentSoftBg else glass.screenBackground,
                border = BorderStroke(
                    width = if (isSystemSelected) 1.5.dp else 1.dp,
                    color = if (isSystemSelected) glass.primaryAccent else glass.cardBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        FamilySoundManager.stopNotificationPreview(context)
                        val intent = FamilySoundManager.createSystemNotificationRingtonePickerIntent(
                            context = context,
                            memberId = memberId,
                            isHebrew = isHebrew
                        )
                        systemRingtonePickerLauncher.launch(intent)
                    }
                    .testTag("notification_system_sound_picker_btn")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = glass.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isHebrew) "צלילי מערכת Android" else "Android System Sounds",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = glass.textPrimary
                                )
                            )
                            Text(
                                text = if (isSystemSelected) {
                                    currentSelection.displayTitle(isHebrew)
                                } else {
                                    if (isHebrew) "פתיחת בורר הצלילים המקורי של Android" else "Open Android System Ringtone Picker"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSystemSelected) glass.primaryAccent else glass.textSecondary,
                                    fontWeight = if (isSystemSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isSystemSelected && currentSelection.soundUri.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    FamilySoundManager.toggleNotificationPreview(
                                        context = context,
                                        soundKey = "system_selected",
                                        uri = runCatching { Uri.parse(currentSelection.soundUri) }.getOrNull()
                                    )
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isPlayingSystem) GoogleRedLight else glass.primaryAccentSoftBg)
                                    .testTag("notification_system_sound_preview_btn")
                            ) {
                                Icon(
                                    imageVector = if (isPlayingSystem) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingSystem) {
                                        if (isHebrew) "עצור השמעה" else "Stop preview"
                                    } else {
                                        if (isHebrew) "השמע צליל מערכת" else "Preview system sound"
                                    },
                                    tint = if (isPlayingSystem) GoogleRedDark else glass.primaryAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Icon(
                            imageVector = if (isSystemSelected) {
                                Icons.Default.CheckCircle
                            } else if (isHebrew) {
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = null,
                            tint = if (isSystemSelected) glass.primaryAccent else glass.textSecondary
                        )
                    }
                }
            }

            val isCustomSelected = currentSelection.source == NotificationSoundSource.CUSTOM_FILE
            val isPlayingCustom = previewState.isPlaying && previewState.activeSoundKey == "custom_selected"

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isCustomSelected) glass.primaryAccentSoftBg else glass.screenBackground,
                border = BorderStroke(
                    width = if (isCustomSelected) 1.5.dp else 1.dp,
                    color = if (isCustomSelected) glass.primaryAccent else glass.cardBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        FamilySoundManager.stopNotificationPreview(context)
                        customFilePickerLauncher.launch(arrayOf("audio/*"))
                    }
                    .testTag("notification_custom_file_picker_btn")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = glass.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isHebrew) "צליל אישי מהמכשיר" else "Custom Device Sound",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = glass.textPrimary
                                )
                            )
                            Text(
                                text = if (isCustomSelected) {
                                    currentSelection.displayTitle(isHebrew)
                                } else {
                                    if (isHebrew) "בחירת קובץ שמע מקומי מהמכשיר" else "Select local audio file from device"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isCustomSelected) glass.primaryAccent else glass.textSecondary,
                                    fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isCustomSelected && currentSelection.soundUri.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    FamilySoundManager.toggleNotificationPreview(
                                        context = context,
                                        soundKey = "custom_selected",
                                        uri = runCatching { Uri.parse(currentSelection.soundUri) }.getOrNull()
                                    )
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isPlayingCustom) GoogleRedLight else glass.primaryAccentSoftBg)
                                    .testTag("notification_custom_file_preview_btn")
                            ) {
                                Icon(
                                    imageVector = if (isPlayingCustom) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlayingCustom) {
                                        if (isHebrew) "עצור השמעה" else "Stop preview"
                                    } else {
                                        if (isHebrew) "השמע קובץ אישי" else "Preview custom file"
                                    },
                                    tint = if (isPlayingCustom) GoogleRedDark else glass.primaryAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Icon(
                            imageVector = if (isCustomSelected) {
                                Icons.Default.CheckCircle
                            } else if (isHebrew) {
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft
                            } else {
                                Icons.AutoMirrored.Filled.KeyboardArrowRight
                            },
                            contentDescription = null,
                            tint = if (isCustomSelected) glass.primaryAccent else glass.textSecondary
                        )
                    }
                }
            }

            // ============================================================
            // Source 1: צלילים מובנים (10 Built-in MP3 Sounds)
            // ============================================================
            Text(
                text = if (isHebrew) "צלילים מובנים (10 צלילים)" else "Built-in Sounds (10 sounds)",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                ),
                modifier = Modifier.padding(top = 2.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notification_builtin_sounds_list"),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                builtInSounds.forEach { sound ->
                    val isSelected = currentSelection.source == NotificationSoundSource.BUILT_IN &&
                        currentSelection.builtInSoundId == sound.id
                    val isPlayingThis = previewState.isPlaying && previewState.activeSoundKey == sound.id

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) glass.primaryAccentSoftBg else glass.screenBackground,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) glass.primaryAccent else glass.cardBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val updated = FamilySoundManager.selectBuiltInNotificationSound(
                                    context = context,
                                    soundId = sound.id,
                                    memberId = memberId
                                )
                                internalTick++
                                onSelectionChanged(updated)
                            }
                            .testTag("notification_builtin_sound_row_${sound.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = {
                                        FamilySoundManager.toggleNotificationPreview(
                                            context = context,
                                            soundKey = sound.id,
                                            uri = Uri.parse(sound.buildResourceUri(context))
                                        )
                                    },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(if (isPlayingThis) GoogleRedLight else glass.primaryAccentSoftBg)
                                        .testTag("notification_builtin_sound_preview_${sound.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isPlayingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlayingThis) {
                                            if (isHebrew) "עצור השמעה" else "Stop preview"
                                        } else {
                                            if (isHebrew) "השמע תצוגה מקדימה" else "Play preview"
                                        },
                                        tint = if (isPlayingThis) GoogleRedDark else glass.primaryAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Text(
                                    text = if (isHebrew) sound.titleHe else sound.titleEn,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (isSelected) glass.primaryAccent else glass.textPrimary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) glass.primaryAccent else glass.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

