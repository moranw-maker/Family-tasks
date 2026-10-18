package com.example.ui.screens

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
import com.example.BuildConfig
import com.example.model.*
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
    onUpdateFamilyIdentity: (FamilyIdentity) -> Unit = {}
) {
    val isHebrew = appLanguage == AppLanguage.HEBREW

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFEBF3FE),
                        Color(0xFFF7F9FD),
                        Color.White
                    )
                )
            )
            .testTag("settings_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick Screen Help Guide
        item {
            com.example.ui.components.ScreenHelpBanner(
                title = if (isHebrew) "הגדרות, ענן ואבטחה (V1.6)" else "Settings, Cloud & Security (V1.6)",
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

        // 7. App Version Info Footer
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "FamilyTasks Workspace V${BuildConfig.VERSION_NAME} • Google Calendar & Drive Edition",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
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
