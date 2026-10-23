package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
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
    onTextScaleChange: (AppTextScale) -> Unit = {}
) {
    val isHebrew = appLanguage == AppLanguage.HEBREW
    val glass = LocalSoftGlassColors.current
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val diagnosticState by FamilyAlarmScheduler.diagnosticState.collectAsState()
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    var showAlarmDiagnosticsPanel by remember {
        mutableStateOf(!FamilyAlarmScheduler.canScheduleExactAlarms(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionRefreshTick++
                if (FamilyAlarmScheduler.canScheduleExactAlarms(context)) {
                    FamilyAlarmScheduler.restoreAlarmsOnBoot(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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

            // 1A. Theme Mode Selection ("ערכת נושא": "בהיר", "כהה", "לפי הגדרות המכשיר")
            item {
                SettingsCardContainer(
                    title = if (isHebrew) "ערכת נושא" else "Theme Mode",
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
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
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
                                        textAlign = TextAlign.Center
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
                                Text(
                                    text = if (isHebrew) {
                                        if (hasExactPermission) "הרשאת שעון מעורר מדויק פעילה ✓ • הגדרות וכלי אבחון"
                                        else "⚠️ נדרש אישור הרשאת שעונים מעוררים ותזכורות לצלצול כשהאפליקציה סגורה"
                                    } else {
                                        if (hasExactPermission) "Exact alarm permission active ✓ • Settings & diagnostics"
                                        else "⚠️ Exact Alarms & Reminders permission required when app is closed"
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (hasExactPermission) glass.textSecondary else glass.warningText,
                                        fontWeight = if (hasExactPermission) FontWeight.Normal else FontWeight.SemiBold
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
                                        actionLabel = if (isHebrew) "אשר מסך מלא" else "Allow",
                                        testTag = "grant_fullscreen_alarm_permission_button",
                                        onAction = { FamilyAlarmScheduler.openFullScreenIntentSettings(context) }
                                    )

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
