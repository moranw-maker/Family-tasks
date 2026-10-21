package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.BuildConfig
import com.example.model.*
import com.example.notifications.FamilyAlarmReceiver
import com.example.notifications.FamilyAlarmScheduler
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
    onShowToast: (String) -> Unit = {}
) {
    val isHebrew = appLanguage == AppLanguage.HEBREW
    val context = LocalContext.current
    val diagnosticState by FamilyAlarmScheduler.diagnosticState.collectAsState()
    var showAlarmDiagnosticsPanel by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                    "ענן משפחתי משותף: נהלו את חיבור Google, קוד ההזמנה של המשפחה וסנכרון ליומן ו-Drive.",
                    "הרשאות הורה מול ילד/ה: ניהול המשפחה ושינוי שם המשפחה פתוחים להורים (PARENT) בלבד.",
                    "שפה ולוח שנה: החליפו בין עברית לאנגלית ובחרו את היום הראשון בשבוע.",
                    "נעילה ביומטרית: הפעילו הגנת טביעת אצבע או קוד סודי לפתיחת האפליקציה."
                ) else listOf(
                    "Shared Family Cloud: Manage Google account sync, family invite code, Calendar & Drive.",
                    "Parent vs Child Roles: Family management and renaming are restricted to PARENT members.",
                    "Language & Calendar: Switch between Hebrew RTL and English LTR.",
                    "Biometric Lock: Enable fingerprint or PIN protection."
                ),
                isHebrew = isHebrew
            )
        }

        // 1. Family Identity & Admin Management Card
        item {
            SettingsCardContainer(
                title = if (isHebrew) "משפחה והרשאות ניהול" else "Family & Admin Management",
                icon = Icons.Default.FamilyRestroom,
                iconTint = GoogleBlue
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = familyName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                        )
                        val identityLabel = currentMember.familyIdentity.getDisplayName(isHebrew)
                        val roleLabel = currentMember.role.getDisplayName(isHebrew)
                        Text(
                            text = if (isHebrew)
                                "פרופיל פעיל: ${currentMember.name} • זהות: $identityLabel ($roleLabel)"
                            else
                                "Active: ${currentMember.nameEn} • Identity: $identityLabel ($roleLabel)",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.testTag("settings_member_identity_badge")
                        )
                    }

                    if (currentMember.isParent) {
                        OutlinedButton(
                            onClick = onEditFamilyName,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.testTag("settings_edit_family_name_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isHebrew) "שנה שם" else "Rename", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (currentMember.isParent) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isHebrew) "זהות הורה בפרופיל (נשמר בענן):" else "Parent Identity in Profile:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextSecondary,
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

                    Button(
                        onClick = onOpenAdminDashboard,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_open_admin_btn")
                    ) {
                        Icon(imageVector = Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isHebrew) "פתח לוח ניהול משפחה והזמנות" else "Open Family & Invitations Dashboard",
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = PastelGreenBackground,
                        border = BorderStroke(1.dp, PastelGreenBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("child_role_info_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = PastelGreenDark,
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
                                        color = PastelGreenDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew)
                                        "משויך למשפחת $familyName • סמנו משימות כבוצע לשליחה לאישור הורה וצבירת נקודות"
                                    else
                                        "Connected to $familyName • Complete tasks to submit for parent approval",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Google Workspace & Cloud Family Group Synchronization Card
        item {
            SettingsCardContainer(
                title = if (isHebrew) "סנכרון חשבון Google וענן משפחתי" else "Google Account & Cloud Family Sync",
                icon = Icons.Default.CloudDone,
                iconTint = GoogleGreen
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = GoogleBlueLight,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = googleEmail,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlueDark
                                )
                            )
                            Text(
                                text = if (isHebrew)
                                    "חברים מסונכרנים: ${cloudFamilyGroup.syncedMembersCount} • תפקיד: ${currentMember.role.getDisplayName(true)}"
                                else
                                    "Synced members: ${cloudFamilyGroup.syncedMembersCount} • Role: ${currentMember.role.getDisplayName(false)}",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                        }

                        Button(
                            onClick = onOpenGoogleSignIn,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("settings_google_connect_btn")
                        ) {
                            Text(
                                text = if (isHebrew) "החלף / חבר" else "Connect",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                SettingsSwitchRow(
                    title = if (isHebrew) "גיבוי מסמכים אוטומטי ל-Google Drive" else "Auto-Backup Files to Google Drive",
                    subtitle = if (isHebrew) "שמירת קבצי לימודים, רפואה וציורים בענן" else "Sync family documents to Google Drive",
                    checked = autoSyncDrive,
                    onCheckedChange = onAutoSyncDriveChange,
                    testTag = "switch_auto_sync_drive"
                )
            }
        }

        // 3. Language & RTL Direction
        item {
            SettingsCardContainer(
                title = if (isHebrew) "שפה וכיוון תצוגה (RTL / LTR)" else "Language & Layout Direction",
                icon = Icons.Default.Language,
                iconTint = Color(0xFF9334E6)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppLanguage.values().forEach { lang ->
                        val isSelected = appLanguage == lang
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
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
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. First Day of Week Selection (Sunday vs Monday)
        item {
            SettingsCardContainer(
                title = if (isHebrew) "היום הראשון בשבוע בלוח השנה" else "Calendar First Day of Week",
                icon = Icons.Default.DateRange,
                iconTint = GoogleYellowDark
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FirstDayOfWeek.values().forEach { dayOption ->
                        val isSelected = firstDayOfWeek == dayOption
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
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
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Theme Mode Selection (System, Light, Dark)
        item {
            SettingsCardContainer(
                title = if (isHebrew) "ערכת נושא ותצוגה" else "Theme Appearance",
                icon = Icons.Default.Palette,
                iconTint = Color(0xFF00ACC1)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeMode.values().forEach { mode ->
                        val isSelected = themeMode == mode
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onThemeModeChange(mode) }
                                .testTag("theme_mode_${mode.name}")
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = mode.getDisplayName(isHebrew),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 6. Security (Biometric Lock) & Smart School Alerts
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

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchRow(
                    title = if (isHebrew) "נעילה ביומטרית / קוד גישה 🔒" else "Biometric / PIN App Lock 🔒",
                    subtitle = if (isHebrew) "הגנה על פרטיות המסמכים והמשימות המשפחתיות" else "Protect family files and settings with fingerprint/PIN",
                    checked = biometricLockEnabled,
                    onCheckedChange = onBiometricLockChange,
                    testTag = "switch_biometric_lock"
                )

                if (biometricLockEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onLockNow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lock_app_now_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHebrew) "נעל את האפליקציה עכשיו" else "Lock App Now",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 6b. Alarm Clock Advanced Settings & Diagnostics ("שעונים מעוררים והשכמה")
        item(key = "settings_alarm_diagnostics_card") {
            val hasExactPermission = remember(diagnosticState, showAlarmDiagnosticsPanel) {
                FamilyAlarmScheduler.canScheduleExactAlarms(context)
            }
            val canUseFullScreen = remember(diagnosticState, showAlarmDiagnosticsPanel) {
                FamilyAlarmScheduler.canUseFullScreenIntent(context)
            }
            val hasNotifications = remember(diagnosticState, showAlarmDiagnosticsPanel) {
                FamilyAlarmScheduler.hasNotificationPermission(context)
            }
            val isBatteryUnrestricted = remember(showAlarmDiagnosticsPanel) {
                FamilyAlarmScheduler.isIgnoringBatteryOptimizations(context)
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, Color(0xFFDCE8F8)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_alarm_diagnostics_section")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEAF2FE)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Alarm,
                                    contentDescription = null,
                                    tint = Color(0xFF15366B),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isHebrew) "שעונים מעוררים והשכמה" else "Alarms & Wake-Up",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.testTag("settings_alarm_diagnostics_title")
                                )
                                Text(
                                    text = if (isHebrew) "הגדרות מתקדמות וכלי אבחון" else "Advanced settings & diagnostic tools",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                            HorizontalDivider(color = Color(0xFFE5EEF9))

                            // Permission Readiness Checks
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFF5F8FD),
                                border = BorderStroke(1.dp, Color(0xFFDCE8F8)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "סטטוס הרשאות מערכת לשעון מעורר" else "System Alarm Permissions Status",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleBlueDark
                                        )
                                    )

                                    AlarmPermissionDiagnosticRow(
                                        label = if (isHebrew) "הרשאת התראה מדויקת (Exact Alarm)" else "Exact Alarm Permission",
                                        isGranted = hasExactPermission,
                                        actionLabel = if (isHebrew) "אשר הרשאה" else "Grant",
                                        testTag = "grant_exact_alarm_permission_button",
                                        onAction = { FamilyAlarmScheduler.openExactAlarmPermissionSettings(context) }
                                    )

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
        }

        // 7. App Version Info Footer
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "FamilyTasks Workspace ${com.example.util.AppVersionManager.displayVersionLabel} (Build ${com.example.util.AppVersionManager.currentVersionCode}) • Google Calendar & Drive Edition",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.testTag("settings_app_version_footer")
                )
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
                color = if (isGranted) PastelGreenDark else PastelYellowDark,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        if (isGranted) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = PastelGreenBackground
            ) {
                Text(
                    text = "תקין",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = PastelGreenDark,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
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
                    color = GoogleBlue
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

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF5F8FD),
        border = BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("developer_alarm_diagnostics_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isHebrew) "כלי בדיקה ואבחון צלצול" else "Alarm Diagnostic Tools",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = GoogleBlueDark
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
                Button(
                    onClick = onSchedule15sTest,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoogleBlue,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("diagnostic_alarm_15s_button")
                ) {
                    Text(
                        text = if (isHebrew) "בדיקת צלצול בעוד 15 שניות" else "Test Alarm in 15 Seconds",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onStopRinging,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 44.dp)
                        .testTag("stop_active_alarm_ringing_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AlarmOff,
                        contentDescription = if (isHebrew) "השתק" else "Silence",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isHebrew) "השתק" else "Silence",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                val permStatusText = if (diagnosticState.exactAlarmPermissionGranted) {
                    "✓ הרשאת התראה מדויקת: מאושרת (${diagnosticState.schedulingMethod.ifBlank { "EXACT" }})"
                } else {
                    "⚠️ הרשאת התראה מדויקת: לא זמינה (שימוש ב-AlarmClock / Window)"
                }
                Text(
                    text = permStatusText,
                    style = MaterialTheme.typography.labelSmall,
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
                        color = if (diagnosticState.isScheduled) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = if (diagnosticState.receiverTriggered) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = if (diagnosticState.ringingServiceStarted) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = if (diagnosticState.audioPlaybackStarted) PastelGreenDark else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("diag_status_audio")
                )

                if (diagnosticState.errorMessage.isNotBlank()) {
                    Text(
                        text = "❌ שגיאה: ${diagnosticState.errorMessage}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoogleRedDark
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
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            content()
        }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
