package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilyPrimaryButton
import com.example.ui.components.SoftGlassScreenBackground
import com.example.ui.components.velvetNeumorphicSheen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DeletedAlarmSyncNotice
import com.example.model.FamilyAlarm
import com.example.model.FamilyMember
import com.example.model.SchoolDay
import com.example.model.UserRole
import com.example.notifications.ActiveRingingAlarmState
import com.example.notifications.AlarmAudioController
import com.example.notifications.FamilyAlarmRingingService
import com.example.notifications.FamilyAlarmScheduler
import com.example.ui.components.FamilyTimeSelectionDialog
import com.example.ui.components.TimeFormatUtils
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.GoogleBlueDark
import com.example.ui.theme.GoogleBlueLight
import com.example.ui.theme.GoogleRed
import com.example.ui.theme.GoogleRedDark
import com.example.ui.theme.GoogleRedLight
import com.example.ui.theme.LocalSoftGlassColors
import com.example.ui.theme.OutlineLight
import com.example.ui.theme.PastelGreenBackground
import com.example.ui.theme.PastelGreenDark
import com.example.ui.theme.PastelYellowBackground
import com.example.ui.theme.PastelYellowBorder
import com.example.ui.theme.PastelYellowDark
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Duration in milliseconds for the auto-dismissing green alarm saved banner (7 seconds).
 */
const val ALARM_SAVED_SUCCESS_BANNER_DURATION_MS = 7_000L

/**
 * Immutable state for the auto-dismissing green alarm saved confirmation banner.
 * Each save generates a unique [id] so that an older coroutine timer never dismisses a newer banner.
 */
data class AlarmSavedBannerState(
    val id: Long,
    val headlineText: String,
    val nextRingSubtitleText: String
)

/**
 * Ringtone picker categories for [FamilyAlarmRingtonePickerScreen].
 */
enum class RingtonePickerTab(val labelHe: String, val labelEn: String, val testTag: String) {
    SYSTEM_ALARMS(
        labelHe = "צלילי מערכת",
        labelEn = "System Sounds",
        testTag = "ringtone_tab_system_alarms"
    ),
    ADDITIONAL_SOUNDS(
        labelHe = "צלילים נוספים",
        labelEn = "More Sounds",
        testTag = "ringtone_tab_additional_sounds"
    ),
    CUSTOM_SOUND(
        labelHe = "צליל מותאם אישית",
        labelEn = "Custom Sound",
        testTag = "ringtone_tab_custom_sound"
    )
}

/**
 * Standalone Family Alarm Clock screen ("שעונים מעוררים") — Clean Soft Glass Redesign.
 *
 * Features:
 * - Clean Soft Glass header with "שעונים מעוררים" title and compact blue (+) button.
 * - Non-blocking green confirmation banner ("השעון המעורר נשמר בהצלחה") that auto-dismisses after 7 seconds.
 * - Role-aware ownership & privacy:
 *   - Parent personal alarms are strictly private and hidden from children.
 *   - Parent-created child alarms sync to the child's device and are protected from child edits.
 * - Full-screen Soft Glass editor (`FamilyAlarmEditorScreen`) and dedicated Ringtone Picker (`FamilyAlarmRingtonePickerScreen`).
 */
@Composable
fun FamilyAlarmScreen(
    alarms: List<FamilyAlarm>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    deletedAlarmNotices: List<DeletedAlarmSyncNotice> = emptyList(),
    onDismissDeletedNotice: (String) -> Unit = {},
    onSaveAlarm: (FamilyAlarm) -> Unit,
    onToggleAlarm: (String) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onShowToast: (String) -> Unit,
    onNavigateBack: (() -> Unit)? = null
) {
    if (onNavigateBack != null) {
        BackHandler { onNavigateBack() }
    }

    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val activeRingingAlarm by FamilyAlarmRingingService.activeRingingAlarm.collectAsState()
    val previewState by AlarmAudioController.previewState.collectAsState()

    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    val hasExactAlarmPermission = remember(permissionRefreshTick, alarms) {
        FamilyAlarmScheduler.canScheduleExactAlarms(context)
    }
    val hasFullScreenPermission = remember(permissionRefreshTick, alarms) {
        FamilyAlarmScheduler.canUseFullScreenIntent(context)
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

    var alarmToEdit by remember { mutableStateOf<FamilyAlarm?>(null) }
    var showAddOrEditDialog by remember { mutableStateOf(false) }
    var alarmPendingDelete by remember { mutableStateOf<FamilyAlarm?>(null) }
    var activeSuccessBanner by remember { mutableStateOf<AlarmSavedBannerState?>(null) }

    // Lifecycle-aware 7-second auto-dismiss timer keyed by activeSuccessBanner.id
    // If a new alarm is saved before 7 seconds elapse, the previous LaunchedEffect is cancelled
    // and restarted for the new banner ID, so an older timer never hides a newer message.
    activeSuccessBanner?.let { banner ->
        LaunchedEffect(banner.id) {
            delay(ALARM_SAVED_SUCCESS_BANNER_DURATION_MS)
            if (activeSuccessBanner?.id == banner.id) {
                activeSuccessBanner = null
            }
        }
    }

    // Filter alarms so parent personal alarms are strictly private and never visible to children
    val visibleAlarms = remember(alarms, currentMember, members) {
        alarms.filter { alarm ->
            alarm.isVisibleToMember(
                currentMember = currentMember,
                members = members
            )
        }
    }

    // Ensure any sound preview stops when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            AlarmAudioController.stopPreview(context)
        }
    }

    if (showAddOrEditDialog) {
        FamilyAlarmEditorScreen(
            alarmToEdit = alarmToEdit,
            members = members,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = {
                AlarmAudioController.stopPreview(context)
                showAddOrEditDialog = false
                alarmToEdit = null
            },
            onSave = { savedAlarm, alsoSetSystemClock ->
                AlarmAudioController.stopPreview(context)
                onSaveAlarm(savedAlarm)
                val targetId = savedAlarm.effectiveAssignedMemberId
                val isRemoteChildTarget = targetId.isNotBlank() && targetId != currentMember.id

                val nextSummary = formatNextTriggerRelativeSummary(savedAlarm, isHebrew)
                activeSuccessBanner = AlarmSavedBannerState(
                    id = System.nanoTime(),
                    headlineText = if (isHebrew) "השעון המעורר נשמר בהצלחה" else "Alarm saved successfully",
                    nextRingSubtitleText = if (isHebrew) {
                        if (isRemoteChildTarget) {
                            "הצלצול הבא במכשיר של ${savedAlarm.assignedMemberName}: $nextSummary"
                        } else {
                            "הצלצול הבא: $nextSummary"
                        }
                    } else {
                        "Next ring: $nextSummary"
                    }
                )

                if (alsoSetSystemClock && !isRemoteChildTarget) {
                    openAndroidSystemClock(context, savedAlarm, isHebrew, onShowToast)
                }

                showAddOrEditDialog = false
                alarmToEdit = null
            }
        )
        return
    }

    val glassColors = LocalSoftGlassColors.current

    SoftGlassScreenBackground(
        modifier = Modifier
            .fillMaxSize()
            .testTag("family_alarm_screen")
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val isCompactScreen = maxWidth < 360.dp
            val horizontalPad = if (isCompactScreen) 12.dp else 16.dp

            LazyColumn(
                contentPadding = PaddingValues(
                    start = horizontalPad,
                    end = horizontalPad,
                    top = 12.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .testTag("family_alarms_list")
            ) {
                // 1. Luxury Soft Neumorphism Header ("שעונים מעוררים" + Prominent Raised 3D Add Button)
                item(key = "main_alarm_header") {
                    FamilyGlassCard(
                        cornerRadius = 24.dp,
                        isTintedBlue = true,
                        elevation = 7.dp,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, glassColors.primaryAccent.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .size(42.dp)
                                        .shadow(
                                            elevation = 4.dp,
                                            shape = RoundedCornerShape(14.dp),
                                            spotColor = glassColors.primaryAccent.copy(alpha = 0.30f)
                                        )
                                        .background(
                                            Brush.verticalGradient(
                                                colors = if (glassColors.isDark) {
                                                    listOf(Color(0xFF25375D), glassColors.primaryAccentSoft)
                                                } else {
                                                    listOf(Color.White, Color(0xFFDCEBFE))
                                                }
                                            ),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .velvetNeumorphicSheen(isDark = glassColors.isDark, cornerRadius = 14.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Alarm,
                                            contentDescription = null,
                                            tint = glassColors.primaryAccent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (isHebrew) "שעונים מעוררים" else "Alarms",
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glassColors.textPrimary,
                                        fontSize = 22.sp
                                    ),
                                    modifier = Modifier.testTag("main_alarm_screen_title")
                                )
                            }

                            // Prominent Raised 3D Velvet Add (+) button
                            FamilyPrimaryButton(
                                text = if (isHebrew) "חדש" else "New",
                                leadingIcon = Icons.Default.Add,
                                onClick = {
                                    alarmToEdit = null
                                    showAddOrEditDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
                                    .testTag("add_alarm_fab")
                            )
                        }
                    }
                }

            // 2. Auto-Dismissing Green Success Message (7 Seconds)
            item(key = "alarm_saved_success_banner_slot") {
                AnimatedVisibility(
                    visible = activeSuccessBanner != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    activeSuccessBanner?.let { banner ->
                        AlarmSavedSuccessBanner(
                            banner = banner,
                            isHebrew = isHebrew,
                            onDismissEarly = { activeSuccessBanner = null }
                        )
                    }
                }
            }

            // 2B. Exact Alarm Permission Prompt Banner (shown only if SCHEDULE_EXACT_ALARM is not granted)
            if (!hasExactAlarmPermission) {
                item(key = "exact_alarm_permission_banner") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (glassColors.isDark) glassColors.warningBg else PastelYellowBackground,
                        border = BorderStroke(1.dp, if (glassColors.isDark) glassColors.warningText.copy(alpha = 0.45f) else PastelYellowBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("alarm_screen_exact_permission_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (isHebrew) "נדרשת הרשאת \"שעונים מעוררים ותזכורות\"" else "Alarms & Reminders Permission Required",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (glassColors.isDark) glassColors.warningText else PastelYellowDark
                                    )
                                )
                                Text(
                                    text = if (isHebrew) {
                                        "כדי שהשעון המעורר יצלצל במדויק גם כשהאפליקציה סגורה או המסך נעול, אשרו את ההרשאה בהגדרות אנדרואיד."
                                    } else {
                                        "Allow Alarms & Reminders in Android Settings so alarms ring reliably when the app is closed."
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = glassColors.textSecondary
                                    )
                                )
                            }

                            Button(
                                onClick = { FamilyAlarmScheduler.openExactAlarmPermissionSettings(context) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GoogleBlue,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("alarm_screen_grant_exact_permission_btn")
                            ) {
                                Text(
                                    text = if (isHebrew) "אשר הרשאה" else "Allow",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 2C. Full-Screen Alarm Presentation Permission Banner (shown if NotificationManager.canUseFullScreenIntent() is false)
            if (!hasFullScreenPermission) {
                item(key = "fullscreen_alarm_permission_banner") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (glassColors.isDark) glassColors.warningBg else PastelYellowBackground,
                        border = BorderStroke(1.dp, if (glassColors.isDark) glassColors.warningText.copy(alpha = 0.45f) else PastelYellowBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("alarm_screen_fullscreen_permission_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (isHebrew) {
                                        "נדרשת הרשאת התראה במסך מלא כדי שהשעון המעורר יוכל להופיע מעל מסך הנעילה."
                                    } else {
                                        "Full-screen notification permission is required so the alarm can appear over the lock screen."
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (glassColors.isDark) glassColors.warningText else PastelYellowDark
                                    ),
                                    modifier = Modifier.testTag("alarm_screen_fullscreen_permission_text")
                                )
                            }

                            Button(
                                onClick = { FamilyAlarmScheduler.openFullScreenIntentSettings(context) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GoogleBlue,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("alarm_screen_grant_fullscreen_permission_btn")
                            ) {
                                Text(
                                    text = if (isHebrew) "אפשר מסך מלא" else "Allow Full-Screen",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 3. Live Ringing Alarm Card (when an alarm is actively ringing on this device)
            activeRingingAlarm?.let { ringing ->
                item(key = "active_ringing_banner") {
                    ActiveRingingAlarmCard(
                        ringing = ringing,
                        isHebrew = isHebrew,
                        onSnooze = {
                            FamilyAlarmRingingService.snoozeActiveAlarm(context)
                            onShowToast(
                                if (isHebrew) "נודניק הופעל ל-${ringing.snoozeMinutes} דקות 💤"
                                else "Snoozed for ${ringing.snoozeMinutes} minutes 💤"
                            )
                        },
                        onDismiss = {
                            FamilyAlarmRingingService.dismissActiveAlarm(context, ringing.alarmId)
                            onShowToast(
                                if (isHebrew) "השעון המעורר כובה ✓"
                                else "Alarm dismissed ✓"
                            )
                        }
                    )
                }
            }

            // 4. Recently Deleted Alarms Cloud vs Target Device Sync Notices
            items(
                items = deletedAlarmNotices,
                key = { "deleted_notice_${it.alarmId}" }
            ) { notice ->
                DeletedAlarmSyncNoticeCard(
                    notice = notice,
                    isHebrew = isHebrew,
                    onDismiss = { onDismissDeletedNotice(notice.alarmId) }
                )
            }

            // 5. Alarms List or Empty State
            if (visibleAlarms.isEmpty()) {
                item(key = "empty_alarms_state") {
                    FamilyGlassCard(
                        cornerRadius = 24.dp,
                        isTintedBlue = true,
                        elevation = 7.dp,
                        contentPadding = PaddingValues(vertical = 36.dp, horizontal = 20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = glassColors.primaryAccentSoft,
                                border = BorderStroke(1.dp, glassColors.primaryAccent.copy(alpha = 0.30f)),
                                modifier = Modifier
                                    .size(68.dp)
                                    .shadow(4.dp, CircleShape, spotColor = glassColors.primaryAccent.copy(alpha = 0.25f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Alarm,
                                        contentDescription = null,
                                        tint = glassColors.primaryAccent,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (isHebrew) "עדיין אין שעונים מעוררים מוגדרים" else "No alarms configured yet",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = glassColors.textPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isHebrew)
                                    "לחצו על כפתור ה־(+) למעלה כדי להוסיף שעון מעורר חדש"
                                else
                                    "Tap the (+) button above to create a new alarm",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = glassColors.textSecondary
                                )
                            )
                        }
                    }
                }
            } else {
                items(visibleAlarms, key = { it.id }) { alarm ->
                    val targetId = alarm.effectiveAssignedMemberId
                    val assignedMember = members.find { it.id == targetId }
                    val creatorMember = members.find { it.id == alarm.createdByMemberId }
                    val canModify = alarm.canMemberModify(currentMember)
                    val canDelete = alarm.canMemberDelete(currentMember)
                    val isThisAlarmPreviewing = previewState.isPlaying &&
                        previewState.activeSoundType == alarm.soundType &&
                        (alarm.soundType != AlarmAudioController.SOUND_CUSTOM_URI ||
                            previewState.activeCustomUri == alarm.customSoundUri)

                    FamilyAlarmCard(
                        alarm = alarm,
                        assignedMember = assignedMember,
                        creatorMember = creatorMember,
                        currentMember = currentMember,
                        canModify = canModify,
                        canDelete = canDelete,
                        isPreviewing = isThisAlarmPreviewing,
                        isHebrew = isHebrew,
                        onPreviewToggle = {
                            if (isThisAlarmPreviewing) {
                                AlarmAudioController.stopPreview(context)
                            } else {
                                AlarmAudioController.startPreview(
                                    context = context,
                                    soundType = alarm.soundType,
                                    customSoundUri = alarm.customSoundUri,
                                    volumePercent = alarm.volumePercent
                                )
                            }
                        },
                        onToggle = {
                            if (canModify) {
                                onToggleAlarm(alarm.id)
                            } else {
                                onShowToast(
                                    if (isHebrew) "שעון זה הוגדר ע״י הורה ואינו ניתן לכיבוי או שינוי מחשבון ילד 🔒"
                                    else "This alarm is managed by a parent and cannot be modified 🔒"
                                )
                            }
                        },
                        onEdit = {
                            if (canModify) {
                                alarmToEdit = alarm
                                showAddOrEditDialog = true
                            } else {
                                onShowToast(
                                    if (isHebrew) "שעון זה מנוהל ע״י הורה (צפייה בלבד) 🔒"
                                    else "This alarm is managed by a parent (view only) 🔒"
                                )
                            }
                        },
                        onRequestDelete = {
                            if (canDelete) {
                                alarmPendingDelete = alarm
                            } else {
                                onShowToast(
                                    if (isHebrew) "אין הרשאה למחוק שעון מעורר שהוגדר ע״י הורה 🔒"
                                    else "Only a parent can delete this alarm 🔒"
                                )
                            }
                        }
                    )
                }
            }
        }
        }
    }

    // Hebrew Delete Confirmation Dialog
    alarmPendingDelete?.let { alarmToDelete ->
        AlertDialog(
            onDismissRequest = { alarmPendingDelete = null },
            shape = RoundedCornerShape(22.dp),
            title = {
                Text(
                    text = "למחוק את השעון המעורר?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("delete_alarm_dialog_title")
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "האם אתה בטוח שברצונך למחוק את השעון המעורר?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${alarmToDelete.formattedTime24h} • ${alarmToDelete.title}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoogleBlueDark
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = alarmToDelete.id
                        alarmPendingDelete = null
                        onDeleteAlarm(id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD93025),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("delete_alarm_confirm_button")
                ) {
                    Text("מחיקה", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { alarmPendingDelete = null },
                    modifier = Modifier.testTag("delete_alarm_cancel_button")
                ) {
                    Text("ביטול", fontWeight = FontWeight.SemiBold)
                }
            },
            modifier = Modifier.testTag("delete_alarm_confirm_dialog")
        )
    }
}

/**
 * Non-blocking green confirmation banner that appears when an alarm is created or updated
 * and automatically disappears after 7 seconds.
 */
@Composable
fun AlarmSavedSuccessBanner(
    banner: AlarmSavedBannerState,
    isHebrew: Boolean,
    onDismissEarly: () -> Unit
) {
    Surface(
        onClick = onDismissEarly,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFE6F6EC),
        border = BorderStroke(1.2.dp, Color(0xFF34A853).copy(alpha = 0.45f)),
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("alarm_saved_success_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = if (isHebrew) "נשמר בהצלחה" else "Saved",
                tint = Color(0xFF1E7E34),
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = banner.headlineText,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF145A26)
                    ),
                    modifier = Modifier.testTag("alarm_saved_success_headline")
                )
                Text(
                    text = banner.nextRingSubtitleText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E7E34)
                    ),
                    modifier = Modifier.testTag("alarm_saved_success_subtitle")
                )
            }
        }
    }
}

@Composable
private fun ActiveRingingAlarmCard(
    ringing: ActiveRingingAlarmState,
    isHebrew: Boolean,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF123262),
        contentColor = Color.White,
        shadowElevation = 8.dp,
        border = BorderStroke(1.5.dp, GoogleBlueLight),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_ringing_alarm_banner")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "⏰", fontSize = 24.sp)
                    }
                    Column {
                        Text(
                            text = ringing.timeFormatted,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = ringing.title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = GoogleBlueLight
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onSnooze,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("active_alarm_snooze_button")
                ) {
                    Icon(imageVector = Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "נודניק (${ringing.snoozeMinutes} דק׳)" else "Snooze (${ringing.snoozeMinutes}m)",
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD93025),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("active_alarm_dismiss_button")
                ) {
                    Icon(imageVector = Icons.Default.AlarmOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "כיבוי" else "Dismiss",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DeletedAlarmSyncNoticeCard(
    notice: DeletedAlarmSyncNotice,
    isHebrew: Boolean,
    onDismiss: () -> Unit
) {
    val isPendingRemoteCancel = notice.isRemoteTarget && !notice.isConfirmedCancelledOnTarget
    val bgColor = if (isPendingRemoteCancel) PastelYellowBackground else PastelGreenBackground
    val fgColor = if (isPendingRemoteCancel) PastelYellowDark else PastelGreenDark
    val borderColor = if (isPendingRemoteCancel) PastelYellowBorder else PastelGreenDark.copy(alpha = 0.35f)

    val statusText = if (isPendingRemoteCancel) {
        if (isHebrew) {
            "☁️ השעון (${notice.formattedTime} • ${notice.title}) נמחק מהענן — ממתין לאישור ביטול ממכשיר של ${notice.targetMemberName}"
        } else {
            "☁️ Deleted in cloud (${notice.formattedTime} • ${notice.title}) — waiting for cancellation on ${notice.targetMemberName}'s device"
        }
    } else {
        if (isHebrew) {
            if (notice.isRemoteTarget) {
                "✓ השעון (${notice.formattedTime} • ${notice.title}) נמחק ובוטל במכשיר של ${notice.targetMemberName}"
            } else {
                "✓ השעון (${notice.formattedTime} • ${notice.title}) נמחק ובוטל ממכשיר זה"
            }
        } else {
            "✓ Alarm deleted and cancelled (${notice.formattedTime})"
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("deleted_alarm_sync_notice_${notice.alarmId}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = fgColor
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("dismiss_deleted_notice_${notice.alarmId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = if (isHebrew) "סגור הודעה" else "Dismiss notice",
                    tint = fgColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Clean Soft Glass Alarm Card matching the visual reference.
 *
 * Displays:
 * - Large alarm time (e.g. 07:00)
 * - Alarm name (e.g. "בוקר טוב רועי!")
 * - Repeat days or one-time status + assigned family member when relevant
 * - Quick sound preview button + Enable/Disable Switch
 * - Tap card to edit + subtle edit/delete icons
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FamilyAlarmCard(
    alarm: FamilyAlarm,
    assignedMember: FamilyMember?,
    creatorMember: FamilyMember?,
    currentMember: FamilyMember,
    canModify: Boolean,
    canDelete: Boolean,
    isPreviewing: Boolean,
    isHebrew: Boolean,
    onPreviewToggle: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val glassColors = LocalSoftGlassColors.current
    val targetId = alarm.effectiveAssignedMemberId
    val isTargetedAtThisDevice = alarm.isTargetedAtDeviceMember(currentMember.id)
    val isRemoteTarget = alarm.isTargetedToAnotherMember() && !isTargetedAtThisDevice
    val isTargetAcknowledged = alarm.isTargetDeviceAcknowledged()
    val isPendingTargetDevice = alarm.isEnabled && isRemoteTarget && !isTargetAcknowledged

    val targetDisplayName = assignedMember?.name
        ?: alarm.assignedMemberName.takeIf { it.isNotBlank() }
        ?: if (isHebrew) "בן משפחה" else "Family Member"

    val repeatDaysText = remember(alarm.daysOfWeek, isHebrew) {
        when {
            alarm.daysOfWeek.size >= 7 ->
                if (isHebrew) "כל יום" else "Every day"
            alarm.daysOfWeek.size == 6 && SchoolDay.SATURDAY !in alarm.daysOfWeek ->
                if (isHebrew) "א׳–ו׳ • ימי לימודים" else "Sun–Fri • School days"
            alarm.daysOfWeek.size == 1 ->
                if (isHebrew) "חד־פעמי (${alarm.daysOfWeek.first().shortHe})" else "One-time"
            else ->
                alarm.daysOfWeek.joinToString(", ") {
                    if (isHebrew) it.shortHe else it.getDisplayName(false).take(3)
                }
        }
    }

    val assignmentText = remember(alarm, currentMember.id, targetId, targetDisplayName, isHebrew) {
        when {
            targetId == currentMember.id ->
                if (isHebrew) "השעון שלי" else "My Alarm"
            targetId.isNotBlank() ->
                if (isHebrew) "עבור $targetDisplayName" else "For $targetDisplayName"
            else ->
                if (isHebrew) "שעון משפחתי" else "Family Alarm"
        }
    }

    val (statusLabel, statusBg, statusFg) = when {
        !alarm.isEnabled -> Triple(
            if (isHebrew) "כבוי" else "Off",
            if (glassColors.isDark) glassColors.secondaryContainer else Color(0xFFF0F4FA),
            if (glassColors.isDark) glassColors.textMuted else Color(0xFF6E81A0)
        )
        isPendingTargetDevice -> Triple(
            if (isHebrew) "⏳ ממתין לסנכרון במכשיר של $targetDisplayName"
            else "⏳ Syncing to $targetDisplayName",
            glassColors.warningContainer,
            glassColors.warningText
        )
        else -> Triple(
            if (isHebrew) {
                if (isTargetedAtThisDevice) "פעיל" else "מסונכרן למכשיר של $targetDisplayName"
            } else {
                if (isTargetedAtThisDevice) "Active" else "Synced to $targetDisplayName"
            },
            glassColors.successContainer,
            glassColors.successText
        )
    }

    FamilyGlassCard(
        cornerRadius = 24.dp,
        isTintedBlue = alarm.isEnabled,
        accentColor = if (alarm.isEnabled) glassColors.primaryAccent else null,
        elevation = if (alarm.isEnabled) 7.dp else 3.dp,
        onClick = if (canModify) onEdit else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("family_alarm_card_${alarm.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Time + Title + Repeat/Assignment summary
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = alarm.formattedTime24h,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 32.sp,
                                    letterSpacing = (-0.5).sp,
                                    color = if (alarm.isEnabled) glassColors.textPrimary else glassColors.textMuted
                                )
                            )
                        }

                        // Compact status pill
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusBg,
                            border = BorderStroke(0.8.dp, statusFg.copy(alpha = 0.25f)),
                            modifier = Modifier.testTag("alarm_status_badge_${alarm.id}")
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = statusFg,
                                    fontSize = 11.sp
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        if (!canModify) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = glassColors.secondaryContainer,
                                modifier = Modifier.testTag("alarm_parent_locked_badge_${alarm.id}")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = glassColors.textSecondary,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = if (isHebrew) "הורה" else "Parent",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = glassColors.textSecondary,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = alarm.title,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (alarm.isEnabled) glassColors.textPrimary else glassColors.textMuted
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = "$repeatDaysText • $assignmentText",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = glassColors.textSecondary,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Controls: 3D Raised Sound preview + Enable/Disable Switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onPreviewToggle,
                        modifier = Modifier
                            .size(40.dp)
                            .shadow(
                                elevation = 3.dp,
                                shape = CircleShape,
                                spotColor = (if (isPreviewing) glassColors.dangerText else glassColors.primaryAccent).copy(alpha = 0.25f)
                            )
                            .clip(CircleShape)
                            .background(
                                if (isPreviewing) glassColors.dangerBg else glassColors.primaryAccentSoftBg
                            )
                            .testTag("preview_alarm_card_${alarm.id}")
                    ) {
                        Icon(
                            imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isHebrew) "השמע צלצול" else "Preview sound",
                            tint = if (isPreviewing) glassColors.dangerText else glassColors.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Switch(
                        checked = alarm.isEnabled,
                        enabled = canModify,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier.testTag("toggle_alarm_${alarm.id}")
                    )
                }
            }

            if (canModify || canDelete) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = glassColors.divider, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = AlarmAudioController.cleanSoundDisplayName(
                            soundType = alarm.soundType,
                            customTitle = alarm.customSoundTitle,
                            isHebrew = isHebrew
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = glassColors.textMuted,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (canModify) {
                            IconButton(
                                onClick = onEdit,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("edit_alarm_${alarm.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = if (isHebrew) "עריכה" else "Edit",
                                    tint = glassColors.primaryAccent,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        if (canDelete) {
                            IconButton(
                                onClick = onRequestDelete,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("delete_alarm_${alarm.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = if (isHebrew) "מחיקה" else "Delete",
                                    tint = glassColors.dangerText,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Simplified Full-Screen Add/Edit Alarm Screen ("שעון מעורר חדש" / "עריכת שעון מעורר").
 *
 * Implements the clean Soft Glass structure:
 * - Fixed Top Bar with title and back arrow
 * - Scrollable Main Content:
 *   1. Prominent Alarm Time card (07:00 + "הצלצול הבא: ...")
 *   2. Repeat Days card ("חזרה על ימים") with 7 day buttons and 3 quick presets ("חד־פעמי", "ימי לימודים", "כל יום")
 *   3. Alarm Assignment ("עבור מי השעון?") dropdown + quick chips
 *   4. Optional Alarm Name ("שם השעון המעורר", placeholder "בוקר טוב רועי!")
 *   5. Compact Ringtone Row ("צלצול") with Play/Stop button + tap to open full-screen Ringtone Picker
 *   6. Compact Snooze Row ("נודניק — 5 דקות")
 *   7. Collapsible "אפשרויות נוספות" section (Vibration, Volume, Device Clock)
 * - Permanently Visible Bottom Action Bar ("ביטול" and "שמור שעון" / "שמור שינויים")
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FamilyAlarmEditorScreen(
    alarmToEdit: FamilyAlarm?,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSave: (FamilyAlarm, Boolean) -> Unit
) {
    val context = LocalContext.current
    val previewState by AlarmAudioController.previewState.collectAsState()
    val isDevice24Hour = remember(context) {
        runCatching { DateFormat.is24HourFormat(context) }.getOrDefault(true)
    }

    val childMembers = remember(members) { members.filter { it.role == UserRole.CHILD } }

    val initialTitle = remember(alarmToEdit) {
        alarmToEdit?.title?.takeUnless {
            it == "בוקר טוב!" || it == "שעון מעורר" || it == "שעון מעורר ☀️" || it == "Good Morning!"
        }.orEmpty()
    }
    val initialTimeFormatted = remember(alarmToEdit) {
        alarmToEdit?.formattedTime24h ?: "07:00"
    }
    val initialSelectedMemberId = remember(alarmToEdit, currentMember.id) {
        alarmToEdit?.effectiveAssignedMemberId?.takeIf { it.isNotBlank() }
            ?: currentMember.id
    }
    val initialSelectedDays = remember(alarmToEdit) {
        alarmToEdit?.daysOfWeek?.toSet() ?: SchoolDay.allWeekDays
            .filterNot { it == SchoolDay.SATURDAY }
            .toSet()
    }
    val initialSoundType = remember(alarmToEdit) {
        alarmToEdit?.soundType?.takeIf { it.isNotBlank() } ?: AlarmAudioController.SOUND_SYSTEM_ALARM
    }
    val initialCustomSoundUri = remember(alarmToEdit) {
        alarmToEdit?.customSoundUri.orEmpty()
    }
    val initialCustomSoundTitle = remember(alarmToEdit) {
        alarmToEdit?.customSoundTitle.orEmpty()
    }
    val initialVibrateEnabled = remember(alarmToEdit) {
        alarmToEdit?.vibrateEnabled ?: true
    }
    val initialSnoozeMinutes = remember(alarmToEdit) {
        alarmToEdit?.snoozeDurationMinutes ?: 5
    }
    val initialVolumePercent = remember(alarmToEdit) {
        (alarmToEdit?.volumePercent ?: 85).toFloat()
    }

    var title by remember(alarmToEdit) { mutableStateOf(initialTitle) }
    var timeFormatted by remember(alarmToEdit) { mutableStateOf(initialTimeFormatted) }
    var showTimePickerModal by remember { mutableStateOf(false) }

    var selectedMemberId by remember(alarmToEdit) { mutableStateOf(initialSelectedMemberId) }
    var showAssignmentDropdown by remember { mutableStateOf(false) }

    var selectedDays by remember(alarmToEdit) { mutableStateOf(initialSelectedDays) }
    var soundType by remember(alarmToEdit) { mutableStateOf(initialSoundType) }
    var customSoundUri by remember(alarmToEdit) { mutableStateOf(initialCustomSoundUri) }
    var customSoundTitle by remember(alarmToEdit) { mutableStateOf(initialCustomSoundTitle) }

    var vibrateEnabled by remember(alarmToEdit) { mutableStateOf(initialVibrateEnabled) }
    var snoozeDurationMinutes by remember(alarmToEdit) { mutableIntStateOf(initialSnoozeMinutes) }
    var showSnoozeOptions by remember { mutableStateOf(false) }
    var volumePercent by remember(alarmToEdit) { mutableFloatStateOf(initialVolumePercent) }
    var alsoSetDeviceClock by remember { mutableStateOf(false) }
    var showAdditionalOptions by remember { mutableStateOf(false) }
    var showRingtonePickerScreen by remember { mutableStateOf(false) }
    var showDiscardConfirmationDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges = remember(
        title,
        timeFormatted,
        selectedMemberId,
        selectedDays,
        soundType,
        customSoundUri,
        customSoundTitle,
        vibrateEnabled,
        snoozeDurationMinutes,
        volumePercent,
        alsoSetDeviceClock
    ) {
        title != initialTitle ||
            timeFormatted != initialTimeFormatted ||
            selectedMemberId != initialSelectedMemberId ||
            selectedDays != initialSelectedDays ||
            soundType != initialSoundType ||
            customSoundUri != initialCustomSoundUri ||
            customSoundTitle != initialCustomSoundTitle ||
            vibrateEnabled != initialVibrateEnabled ||
            snoozeDurationMinutes != initialSnoozeMinutes ||
            volumePercent.toInt() != initialVolumePercent.toInt() ||
            alsoSetDeviceClock
    }

    val handleRequestCancelOrBack = {
        AlarmAudioController.stopPreview(context)
        if (hasUnsavedChanges) {
            showDiscardConfirmationDialog = true
        } else {
            onDismiss()
        }
    }

    val isTargetRemoteChild = currentMember.isParent &&
        selectedMemberId.isNotBlank() &&
        selectedMemberId != currentMember.id

    // Dedicated Full-Screen Ringtone Picker Screen
    if (showRingtonePickerScreen) {
        FamilyAlarmRingtonePickerScreen(
            initialSoundType = soundType,
            initialCustomSoundUri = customSoundUri,
            initialCustomSoundTitle = customSoundTitle,
            initialVolumePercent = volumePercent.toInt(),
            isTargetRemoteChild = isTargetRemoteChild,
            isHebrew = isHebrew,
            onCancel = {
                AlarmAudioController.stopPreview(context)
                showRingtonePickerScreen = false
            },
            onConfirmSound = { chosenSoundType, chosenCustomUri, chosenCustomTitle, chosenVolume ->
                AlarmAudioController.stopPreview(context)
                soundType = chosenSoundType
                customSoundUri = chosenCustomUri
                customSoundTitle = chosenCustomTitle
                volumePercent = chosenVolume.toFloat()
                showRingtonePickerScreen = false
            }
        )
        return
    }

    BackHandler {
        handleRequestCancelOrBack()
    }

    val parsedTime = remember(timeFormatted) {
        val parts = timeFormatted.split(":")
        val hr = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val min = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        Pair(hr, min)
    }

    val periodBadgeText = remember(parsedTime, isDevice24Hour, isHebrew) {
        val hr = parsedTime.first
        if (!isDevice24Hour) {
            if (hr < 12) "AM" else "PM"
        } else {
            if (isHebrew) {
                when (hr) {
                    in 5..11 -> "בוקר"
                    in 12..16 -> "צהריים"
                    in 17..20 -> "ערב"
                    else -> "לילה"
                }
            } else {
                "24h"
            }
        }
    }

    val previewNextTriggerText = remember(timeFormatted, selectedDays, isHebrew, title) {
        val tempAlarm = FamilyAlarm(
            id = "preview",
            title = title.ifBlank { if (isHebrew) "בוקר טוב!" else "Good Morning!" },
            hour = parsedTime.first,
            minute = parsedTime.second,
            daysOfWeek = SchoolDay.allWeekDays.filter { it in selectedDays }.ifEmpty { SchoolDay.allWeekDays }
        )
        formatNextTriggerRelativeSummary(tempAlarm, isHebrew)
    }

    val softCardBorder = BorderStroke(1.dp, Color(0xFFDCE8F8))
    val softCardShape = RoundedCornerShape(18.dp)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("alarm_editor_screen"),
        containerColor = Color(0xFFF3F7FE),
        topBar = {
            Surface(
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("alarm_editor_header")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { handleRequestCancelOrBack() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_alarm_editor_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (isHebrew) "חזרה" else "Back",
                                tint = Color(0xFF15366B)
                            )
                        }

                        Text(
                            text = if (alarmToEdit == null) {
                                if (isHebrew) "שעון מעורר חדש" else "New Alarm"
                            } else {
                                if (isHebrew) "עריכת שעון מעורר" else "Edit Alarm"
                            },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF15366B),
                                fontSize = 20.sp
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("alarm_editor_header_title")
                        )
                    }
                    HorizontalDivider(color = Color(0xFFDCE8F8), thickness = 1.dp)
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .testTag("alarm_editor_bottom_bar")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = Color(0xFFDCE8F8), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 640.dp)
                            .align(Alignment.CenterHorizontally)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { handleRequestCancelOrBack() },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.2.dp, Color(0xFFBFD4F2)),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("cancel_alarm_editor_button")
                        ) {
                            Text(
                                text = if (isHebrew) "ביטול" else "Cancel",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF355079)
                            )
                        }

                        Button(
                            onClick = {
                                val hr = parsedTime.first
                                val min = parsedTime.second
                                val effectiveTargetId = if (!currentMember.isParent) {
                                    currentMember.id
                                } else {
                                    selectedMemberId.ifBlank { currentMember.id }
                                }
                                val assignedMember = members.find { it.id == effectiveTargetId }
                                val preservedCreatorId = alarmToEdit?.createdByMemberId?.takeIf { it.isNotBlank() } ?: currentMember.id
                                val preservedCreatorName = alarmToEdit?.createdByMemberName?.takeIf { it.isNotBlank() } ?: currentMember.name
                                val preservedCreatorRole = alarmToEdit?.createdByRole?.takeIf { it.isNotBlank() }
                                    ?: currentMember.role.toFirestoreValue()

                                val ringtoneEmoji = AlarmAudioController.builtInSoundOptions
                                    .find { it.soundType == soundType }?.emoji ?: "⏰"

                                val alarm = FamilyAlarm(
                                    id = alarmToEdit?.id ?: "alarm_${System.currentTimeMillis()}",
                                    title = title.trim().ifBlank { if (isHebrew) "בוקר טוב!" else "Good Morning!" },
                                    hour = hr,
                                    minute = min,
                                    assignedMemberId = effectiveTargetId,
                                    assignedToMemberId = effectiveTargetId,
                                    assignedMemberName = assignedMember?.name ?: currentMember.name,
                                    daysOfWeek = SchoolDay.values().filter { it in selectedDays }.ifEmpty { SchoolDay.allWeekDays },
                                    isEnabled = true,
                                    ringtoneEmoji = ringtoneEmoji,
                                    createdByMemberId = preservedCreatorId,
                                    createdByMemberName = preservedCreatorName,
                                    createdByRole = preservedCreatorRole,
                                    isPersonal = effectiveTargetId == preservedCreatorId,
                                    soundType = soundType,
                                    customSoundUri = if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) customSoundUri else "",
                                    customSoundTitle = if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) customSoundTitle else "",
                                    vibrateEnabled = vibrateEnabled,
                                    snoozeDurationMinutes = snoozeDurationMinutes,
                                    volumePercent = volumePercent.toInt().coerceIn(10, 100)
                                )
                                onSave(alarm, alsoSetDeviceClock)
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GoogleBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1.4f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("save_alarm_confirm_button")
                        ) {
                            Text(
                                text = if (alarmToEdit == null) {
                                    if (isHebrew) "שמור שעון" else "Save Alarm"
                                } else {
                                    if (isHebrew) "שמור שינויים" else "Save Changes"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("alarm_editor_dialog")
        ) {
            val isCompactWidth = maxWidth < 360.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isCompactWidth) 12.dp else 16.dp, vertical = 12.dp)
                    .testTag("alarm_editor_scroll_content"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. ALARM TIME CARD (Large time display + Next ring preview)
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color.White,
                    border = BorderStroke(1.2.dp, Color(0xFFBDD6FA)),
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { showTimePickerModal = true }
                        .testTag("alarm_editor_time_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = timeFormatted,
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF12366E),
                                        fontSize = if (isCompactWidth) 44.sp else 52.sp,
                                        letterSpacing = (-1).sp
                                    ),
                                    modifier = Modifier.testTag("alarm_editor_time_display")
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFEAF2FE)
                            ) {
                                Text(
                                    text = periodBadgeText,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleBlueDark
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEFF6FF),
                            border = BorderStroke(1.dp, Color(0xFFCCE0FA)),
                            modifier = Modifier.testTag("alarm_next_ring_preview")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = GoogleBlueDark,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = if (isHebrew) "הצלצול הבא: $previewNextTriggerText" else "Next ring: $previewNextTriggerText",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1A4B9C)
                                    )
                                )
                            }
                        }
                    }
                }

                // 2. REPEAT DAYS CARD ("חזרה על ימים")
                val singleUpcomingDay = remember(parsedTime) {
                    computeSingleUpcomingSchoolDay(parsedTime.first, parsedTime.second)
                }
                val schoolDaysSet = remember {
                    SchoolDay.allWeekDays.filterNot { it == SchoolDay.SATURDAY }.toSet()
                }
                val allDaysSet = remember { SchoolDay.allWeekDays.toSet() }

                val isOneTimeSelected = selectedDays.size == 1
                val isSchoolDaysSelected = selectedDays == schoolDaysSet
                val isAllDaysSelected = selectedDays == allDaysSet

                Surface(
                    shape = softCardShape,
                    color = Color.White,
                    border = softCardBorder,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (isHebrew) "חזרה על ימים" else "Repeat Days",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF15366B)
                            )
                        )

                        // 7 Compact Day Buttons: א׳ ב׳ ג׳ ד׳ ה׳ ו׳ ש׳
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SchoolDay.allWeekDays.forEach { day ->
                                val isSelected = day in selectedDays
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) GoogleBlue else Color(0xFFF0F5FD),
                                    border = if (isSelected) null else BorderStroke(1.dp, Color(0xFFD5E3F7)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            selectedDays = if (isSelected && selectedDays.size > 1) {
                                                selectedDays - day
                                            } else {
                                                selectedDays + day
                                            }
                                        }
                                        .testTag("alarm_day_${day.name}")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isHebrew) day.shortHe else day.getDisplayName(false).take(2),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else Color(0xFF355079)
                                            ),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        // 3 Quick Presets: "חד־פעמי", "ימי לימודים", "כל יום"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PresetPillButton(
                                label = if (isHebrew) "חד־פעמי" else "One-time",
                                isSelected = isOneTimeSelected,
                                onClick = { selectedDays = setOf(singleUpcomingDay) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("alarm_preset_one_time")
                            )
                            PresetPillButton(
                                label = if (isHebrew) "ימי לימודים" else "School days",
                                isSelected = isSchoolDaysSelected,
                                onClick = { selectedDays = schoolDaysSet },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("alarm_preset_school_days")
                            )
                            PresetPillButton(
                                label = if (isHebrew) "כל יום" else "Every day",
                                isSelected = isAllDaysSelected,
                                onClick = { selectedDays = allDaysSet },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("alarm_preset_every_day")
                            )
                        }
                    }
                }

                // 3. ALARM ASSIGNMENT ("עבור מי השעון?")
                val selectedTargetLabel = remember(selectedMemberId, currentMember, childMembers, isHebrew) {
                    if (selectedMemberId == currentMember.id || !currentMember.isParent) {
                        if (isHebrew) "השעון שלי" else "My Alarm"
                    } else {
                        val child = childMembers.find { it.id == selectedMemberId }
                        if (child != null) {
                            if (isHebrew) "ילד: ${child.name}" else "Child: ${child.name}"
                        } else {
                            if (isHebrew) "השעון שלי" else "My Alarm"
                        }
                    }
                }

                Surface(
                    shape = softCardShape,
                    color = Color.White,
                    border = softCardBorder,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = currentMember.isParent && childMembers.isNotEmpty()) {
                                        showAssignmentDropdown = true
                                    }
                                    .padding(vertical = 4.dp)
                                    .testTag("alarm_assignment_dropdown_row"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = GoogleBlueDark,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (isHebrew) "עבור מי השעון?" else "Who is this alarm for?",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF15366B)
                                        )
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFFEAF3FE),
                                    border = BorderStroke(1.dp, Color(0xFFCCE0FA))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = selectedTargetLabel,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = GoogleBlueDark
                                            )
                                        )
                                        if (currentMember.isParent && childMembers.isNotEmpty()) {
                                            Icon(
                                                imageVector = Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = GoogleBlueDark,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = showAssignmentDropdown,
                                onDismissRequest = { showAssignmentDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (isHebrew) "השעון שלי" else "My Alarm",
                                            fontWeight = if (selectedMemberId == currentMember.id) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        selectedMemberId = currentMember.id
                                        showAssignmentDropdown = false
                                    }
                                )
                                if (currentMember.isParent) {
                                    childMembers.forEach { child ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (isHebrew) "ילד: ${child.name} (${child.avatarEmoji})"
                                                    else "Child: ${child.name} (${child.avatarEmoji})",
                                                    fontWeight = if (selectedMemberId == child.id) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                selectedMemberId = child.id
                                                if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) {
                                                    soundType = AlarmAudioController.SOUND_SYSTEM_ALARM
                                                }
                                                showAssignmentDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Compact selector pills when parent has children (keeps quick 1-tap switching & testTags)
                        if (currentMember.isParent && childMembers.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val isSelf = selectedMemberId == currentMember.id
                                FilterChip(
                                    selected = isSelf,
                                    onClick = { selectedMemberId = currentMember.id },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFDCEBFE),
                                        selectedLabelColor = Color(0xFF12366E)
                                    ),
                                    label = {
                                        Text(
                                            text = if (isHebrew) "השעון שלי" else "My Alarm",
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelf) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    modifier = Modifier.testTag("alarm_assign_personal")
                                )
                                childMembers.forEach { child ->
                                    val isChildSelected = selectedMemberId == child.id
                                    FilterChip(
                                        selected = isChildSelected,
                                        onClick = {
                                            selectedMemberId = child.id
                                            if (soundType == AlarmAudioController.SOUND_CUSTOM_URI) {
                                                soundType = AlarmAudioController.SOUND_SYSTEM_ALARM
                                            }
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFDCEBFE),
                                            selectedLabelColor = Color(0xFF12366E)
                                        ),
                                        label = {
                                            Text(
                                                text = if (isHebrew) "ילד: ${child.name}" else "Child: ${child.name}",
                                                fontSize = 12.sp,
                                                fontWeight = if (isChildSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        },
                                        modifier = Modifier.testTag("alarm_assign_${child.id}")
                                    )
                                }
                            }
                        } else {
                            // Ensure testTag("alarm_assign_personal") is always present for single-user / child mode
                            Box(modifier = Modifier.testTag("alarm_assign_personal"))
                        }
                    }
                }

                // 4. ALARM NAME ("שם השעון המעורר")
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = {
                        Text(
                            text = if (isHebrew) "שם השעון המעורר" else "Alarm Name",
                            fontSize = 13.sp
                        )
                    },
                    placeholder = {
                        Text(
                            text = if (isHebrew) "בוקר טוב רועי!" else "Good Morning!",
                            fontSize = 13.sp,
                            color = Color(0xFF8A9BB5)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedBorderColor = GoogleBlue,
                        unfocusedBorderColor = Color(0xFFDCE8F8)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("alarm_title_input")
                )

                // 5. COMPACT RINGTONE ROW ("צלצול")
                Surface(
                    shape = softCardShape,
                    color = Color.White,
                    border = softCardBorder,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(softCardShape)
                        .clickable {
                            AlarmAudioController.stopPreview(context)
                            showRingtonePickerScreen = true
                        }
                        .testTag("alarm_ringtone_row")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFFEAF3FE),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = GoogleBlueDark,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isHebrew) "צלצול" else "Ringtone",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF15366B)
                                    )
                                )
                                Text(
                                    text = AlarmAudioController.cleanSoundDisplayName(
                                        soundType = soundType,
                                        customTitle = customSoundTitle,
                                        isHebrew = isHebrew
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = GoogleBlueDark,
                                        fontWeight = FontWeight.SemiBold
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
                            if (previewState.isPlaying) {
                                FilledTonalButton(
                                    onClick = { AlarmAudioController.stopPreview(context) },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = GoogleRedLight,
                                        contentColor = GoogleRedDark
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("alarm_stop_preview_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = if (isHebrew) "עצור" else "Stop",
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isHebrew) "עצור" else "Stop",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        AlarmAudioController.startPreview(
                                            context = context,
                                            soundType = soundType,
                                            customSoundUri = customSoundUri,
                                            volumePercent = volumePercent.toInt()
                                        )
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFFEAF3FE),
                                        contentColor = GoogleBlueDark
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("alarm_preview_sound_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = if (isHebrew) "השמע" else "Play",
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isHebrew) "השמע" else "Play",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = if (isHebrew) "בחר צלצול" else "Choose Ringtone",
                                tint = Color(0xFF6B80A0)
                            )
                        }
                    }
                }

                // 6. COMPACT SNOOZE ROW ("נודניק — 5 דקות")
                Surface(
                    shape = softCardShape,
                    color = Color.White,
                    border = softCardBorder,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showSnoozeOptions = !showSnoozeOptions }
                                .padding(vertical = 2.dp)
                                .testTag("alarm_snooze_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFEAF3FE),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Snooze,
                                            contentDescription = null,
                                            tint = GoogleBlueDark,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (isHebrew) "נודניק — $snoozeDurationMinutes דקות" else "Snooze — $snoozeDurationMinutes min",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF15366B)
                                    )
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(5, 10, 15).forEach { mins ->
                                    val isSelected = snoozeDurationMinutes == mins
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { snoozeDurationMinutes = mins },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFDCEBFE),
                                            selectedLabelColor = Color(0xFF12366E)
                                        ),
                                        label = {
                                            Text(
                                                text = if (isHebrew) "$mins דק׳" else "${mins}m",
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 12.sp
                                            )
                                        },
                                        modifier = Modifier.testTag("alarm_snooze_${mins}")
                                    )
                                }
                            }
                        }
                    }
                }

                // 7. COLLAPSIBLE ADDITIONAL OPTIONS ("אפשרויות נוספות" — collapsed by default)
                Surface(
                    shape = softCardShape,
                    color = Color.White,
                    border = softCardBorder,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showAdditionalOptions = !showAdditionalOptions }
                                .padding(vertical = 4.dp)
                                .testTag("alarm_additional_options_toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isHebrew) "אפשרויות נוספות" else "Additional Options",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF15366B)
                                )
                            )
                            Icon(
                                imageVector = if (showAdditionalOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isHebrew) "אפשרויות נוספות" else "Additional Options",
                                tint = Color(0xFF5A7194)
                            )
                        }

                        AnimatedVisibility(
                            visible = showAdditionalOptions,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HorizontalDivider(color = Color(0xFFEEF4FC), thickness = 1.dp)

                                // Vibration switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (isHebrew) "רטט בעת צלצול" else "Vibrate on alarm",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF1E3A66)
                                        )
                                    )
                                    Switch(
                                        checked = vibrateEnabled,
                                        onCheckedChange = { vibrateEnabled = it },
                                        modifier = Modifier.testTag("alarm_vibrate_switch")
                                    )
                                }

                                HorizontalDivider(color = Color(0xFFEEF4FC), thickness = 1.dp)

                                // Additional volume options
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isHebrew) "עוצמת הצלצול (${volumePercent.toInt()}%)" else "Alarm Volume (${volumePercent.toInt()}%)",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E3A66)
                                        )
                                    )

                                    TextButton(
                                        onClick = {
                                            if (previewState.isPlaying) {
                                                AlarmAudioController.stopPreview(context)
                                            } else {
                                                AlarmAudioController.startPreview(
                                                    context = context,
                                                    soundType = soundType,
                                                    customSoundUri = customSoundUri,
                                                    volumePercent = volumePercent.toInt(),
                                                    durationMillis = 5_000L
                                                )
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .height(28.dp)
                                            .testTag("alarm_test_volume_button")
                                    ) {
                                        Icon(
                                            imageVector = if (previewState.isPlaying) Icons.Default.Stop else Icons.Default.VolumeUp,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (previewState.isPlaying) {
                                                if (isHebrew) "עצור בדיקה" else "Stop"
                                            } else {
                                                if (isHebrew) "בדוק עוצמה" else "Test Volume"
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Slider(
                                    value = volumePercent,
                                    onValueChange = { newVol ->
                                        volumePercent = newVol
                                        AlarmAudioController.updateActivePreviewVolume(context, newVol.toInt())
                                    },
                                    valueRange = 10f..100f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp)
                                        .testTag("alarm_volume_slider")
                                )

                                // Device clock option (only when targeting this device)
                                if (!isTargetRemoteChild) {
                                    HorizontalDivider(color = Color(0xFFEEF4FC), thickness = 1.dp)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { alsoSetDeviceClock = !alsoSetDeviceClock }
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isHebrew) "הוסף גם לשעון המובנה של המכשיר" else "Also add to built-in device clock app",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF355079)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Checkbox(
                                            checked = alsoSetDeviceClock,
                                            onCheckedChange = { alsoSetDeviceClock = it },
                                            modifier = Modifier.testTag("alarm_also_set_device_clock_checkbox")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTimePickerModal) {
        FamilyTimeSelectionDialog(
            initialTime = timeFormatted,
            title = if (isHebrew) "בחירת שעת השכמה" else "Select Alarm Time",
            isHebrew = isHebrew,
            onDismiss = { showTimePickerModal = false },
            onConfirm = { chosenTime ->
                timeFormatted = TimeFormatUtils.normalize24HourString(chosenTime, timeFormatted)
                showTimePickerModal = false
            }
        )
    }

    if (showDiscardConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmationDialog = false },
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    text = if (isHebrew) "לבטל את השינויים?" else "Discard changes?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("discard_alarm_changes_title")
                )
            },
            text = {
                Text(
                    text = if (isHebrew)
                        "השינויים שביצעת לא יישמרו. האם לצאת ללא שמירה?"
                    else
                        "Your unsaved changes will be lost. Exit without saving?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirmationDialog = false
                        AlarmAudioController.stopPreview(context)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoogleRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("discard_alarm_changes_confirm_button")
                ) {
                    Text(
                        text = if (isHebrew) "צא ללא שמירה" else "Exit without saving",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDiscardConfirmationDialog = false },
                    modifier = Modifier.testTag("continue_editing_alarm_button")
                ) {
                    Text(
                        text = if (isHebrew) "המשך עריכה" else "Continue editing",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            modifier = Modifier.testTag("discard_alarm_changes_dialog")
        )
    }
}

/**
 * Dedicated Full-Screen Ringtone Picker Screen ("בחירת צליל מעורר").
 *
 * Categories:
 * 1. "צלילי מערכת" (`RingtoneManager.TYPE_ALARM`)
 * 2. "צלילים נוספים" (`RingtoneManager.TYPE_RINGTONE` and `RingtoneManager.TYPE_NOTIFICATION`)
 * 3. "צליל מותאם אישית" (Selecting a custom audio file from the phone)
 *
 * Features:
 * - Play / Stop preview button per row (only one sound plays at a time; stops on exit).
 * - Radio selection indicator + soft blue highlight card for the selected ringtone.
 * - Bottom Volume control ("עוצמת הצלצול") with slider and percentage indicator.
 * - Permanently visible bottom buttons: "ביטול" and "בחר צליל".
 */
@Composable
fun FamilyAlarmRingtonePickerScreen(
    initialSoundType: String,
    initialCustomSoundUri: String,
    initialCustomSoundTitle: String,
    initialVolumePercent: Int,
    isTargetRemoteChild: Boolean,
    isHebrew: Boolean,
    onCancel: () -> Unit,
    onConfirmSound: (soundType: String, customSoundUri: String, customSoundTitle: String, volumePercent: Int) -> Unit
) {
    val context = LocalContext.current
    val previewState by AlarmAudioController.previewState.collectAsState()

    val systemAlarmRingtones = remember(context) {
        AlarmAudioController.getSystemAlarmCategoryRingtones(context)
    }
    val additionalRingtones = remember(context) {
        AlarmAudioController.getAdditionalCategoryRingtones(context)
    }

    var selectedTab by remember {
        mutableStateOf(
            when {
                initialSoundType == AlarmAudioController.SOUND_CUSTOM_URI &&
                    initialCustomSoundUri.isNotBlank() &&
                    systemAlarmRingtones.none { it.uriString == initialCustomSoundUri } &&
                    additionalRingtones.none { it.uriString == initialCustomSoundUri } ->
                    RingtonePickerTab.CUSTOM_SOUND
                initialSoundType == AlarmAudioController.SOUND_SYSTEM_RINGTONE ||
                    initialSoundType == AlarmAudioController.SOUND_SYSTEM_NOTIFICATION ||
                    (initialSoundType == AlarmAudioController.SOUND_CUSTOM_URI &&
                        additionalRingtones.any { it.uriString == initialCustomSoundUri }) ->
                    RingtonePickerTab.ADDITIONAL_SOUNDS
                else -> RingtonePickerTab.SYSTEM_ALARMS
            }
        )
    }

    var selectedSoundType by remember { mutableStateOf(initialSoundType) }
    var selectedCustomUri by remember { mutableStateOf(initialCustomSoundUri) }
    var selectedCustomTitle by remember { mutableStateOf(initialCustomSoundTitle) }
    var volumePercent by remember { mutableFloatStateOf(initialVolumePercent.coerceIn(10, 100).toFloat()) }
    var customFileErrorHe by remember { mutableStateOf<String?>(null) }

    // Ensure any active preview stops when leaving the Ringtone Picker screen
    DisposableEffect(Unit) {
        onDispose {
            AlarmAudioController.stopPreview(context)
        }
    }

    BackHandler {
        AlarmAudioController.stopPreview(context)
        onCancel()
    }

    // Native Android System Ringtone Picker launcher
    val systemRingtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val resolved = AlarmAudioController.resolvePickedSystemRingtone(
                context = context,
                resultData = result.data,
                isHebrew = isHebrew
            )
            if (resolved != null) {
                val (pickedUriStr, toneTitle) = resolved
                customFileErrorHe = null
                if (isTargetRemoteChild) {
                    selectedSoundType = AlarmAudioController.SOUND_SYSTEM_ALARM
                    selectedCustomUri = ""
                    selectedCustomTitle = ""
                } else {
                    selectedSoundType = AlarmAudioController.SOUND_CUSTOM_URI
                    selectedCustomUri = pickedUriStr
                    selectedCustomTitle = toneTitle
                }
                AlarmAudioController.startPreview(
                    context = context,
                    soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                    customSoundUri = pickedUriStr,
                    volumePercent = volumePercent.toInt()
                )
            }
        }
    }

    // Custom audio file picker launcher ("צליל מותאם אישית")
    val customAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val importRes = AlarmAudioController.importAndPersistCustomSoundUri(context, uri)
            importRes.onSuccess { (persistedUriStr, displayName) ->
                customFileErrorHe = null
                selectedCustomUri = persistedUriStr
                selectedCustomTitle = displayName
                selectedSoundType = AlarmAudioController.SOUND_CUSTOM_URI
                AlarmAudioController.startPreview(
                    context = context,
                    soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                    customSoundUri = persistedUriStr,
                    volumePercent = volumePercent.toInt()
                )
            }.onFailure { err ->
                customFileErrorHe = err.localizedMessage ?: "לא ניתן לטעון את קובץ השמע שנבחר"
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("alarm_ringtone_picker_screen"),
        containerColor = Color(0xFFF3F7FE),
        topBar = {
            Surface(
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                AlarmAudioController.stopPreview(context)
                                onCancel()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("close_ringtone_picker_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (isHebrew) "חזרה" else "Back",
                                tint = Color(0xFF15366B)
                            )
                        }

                        Text(
                            text = if (isHebrew) "בחירת צליל מעורר" else "Select Alarm Sound",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF15366B),
                                fontSize = 20.sp
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ringtone_picker_header_title")
                        )
                    }

                    // Three Compact Category Tabs: "צלילי מערכת", "צלילים נוספים", "צליל מותאם אישית"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RingtonePickerTab.entries.forEach { tab ->
                            val isSelected = selectedTab == tab
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) GoogleBlue else Color(0xFFEEF4FC),
                                border = if (isSelected) null else BorderStroke(1.dp, Color(0xFFD5E3F7)),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { selectedTab = tab }
                                    .testTag(tab.testTag)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) tab.labelHe else tab.labelEn,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else Color(0xFF355079),
                                            fontSize = 12.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFDCE8F8), thickness = 1.dp)
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color.White,
                shadowElevation = 8.dp,
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .testTag("ringtone_picker_bottom_bar")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Volume Control ("עוצמת הצלצול" + Volume slider + Percentage indicator)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = GoogleBlueDark,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isHebrew) "עוצמת הצלצול" else "Ringtone Volume",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF15366B)
                                )
                            )
                        }

                        Text(
                            text = "${volumePercent.toInt()}%",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = GoogleBlueDark
                            ),
                            modifier = Modifier.testTag("ringtone_picker_volume_percent")
                        )
                    }

                    Slider(
                        value = volumePercent,
                        onValueChange = { newVol ->
                            volumePercent = newVol
                            AlarmAudioController.updateActivePreviewVolume(context, newVol.toInt())
                        },
                        valueRange = 10f..100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .testTag("ringtone_picker_volume_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                AlarmAudioController.stopPreview(context)
                                onCancel()
                            },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.2.dp, Color(0xFFBFD4F2)),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("cancel_ringtone_picker_button")
                        ) {
                            Text(
                                text = if (isHebrew) "ביטול" else "Cancel",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFF355079)
                            )
                        }

                        Button(
                            onClick = {
                                AlarmAudioController.stopPreview(context)
                                onConfirmSound(
                                    selectedSoundType,
                                    selectedCustomUri,
                                    selectedCustomTitle,
                                    volumePercent.toInt().coerceIn(10, 100)
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GoogleBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1.4f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("confirm_ringtone_picker_button")
                        ) {
                            Text(
                                text = if (isHebrew) "בחר צליל" else "Select Sound",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 640.dp)
                .testTag("ringtone_picker_list")
        ) {
            when (selectedTab) {
                RingtonePickerTab.SYSTEM_ALARMS -> {
                    // Default Android System Alarm Sound option
                    item(key = "default_system_alarm_option") {
                        val isSelected = selectedSoundType == AlarmAudioController.SOUND_SYSTEM_ALARM
                        val isPlayingThis = previewState.isPlaying &&
                            previewState.activeSoundType == AlarmAudioController.SOUND_SYSTEM_ALARM &&
                            previewState.activeCustomUri.isBlank()

                        RingtoneOptionRow(
                            title = if (isHebrew) "צליל שעון מעורר של המכשיר (ברירת מחדל)" else "Default Device Alarm Sound",
                            subtitle = if (isHebrew) "צליל ההתראה הראשי של מערכת האנדרואיד" else "Primary system alarm tone",
                            isSelected = isSelected,
                            isPlaying = isPlayingThis,
                            isHebrew = isHebrew,
                            onSelect = {
                                selectedSoundType = AlarmAudioController.SOUND_SYSTEM_ALARM
                                selectedCustomUri = ""
                                selectedCustomTitle = ""
                                AlarmAudioController.startPreview(
                                    context = context,
                                    soundType = AlarmAudioController.SOUND_SYSTEM_ALARM,
                                    customSoundUri = "",
                                    volumePercent = volumePercent.toInt()
                                )
                            },
                            onTogglePlay = {
                                if (isPlayingThis) {
                                    AlarmAudioController.stopPreview(context)
                                } else {
                                    selectedSoundType = AlarmAudioController.SOUND_SYSTEM_ALARM
                                    selectedCustomUri = ""
                                    selectedCustomTitle = ""
                                    AlarmAudioController.startPreview(
                                        context = context,
                                        soundType = AlarmAudioController.SOUND_SYSTEM_ALARM,
                                        customSoundUri = "",
                                        volumePercent = volumePercent.toInt()
                                    )
                                }
                            },
                            modifier = Modifier.testTag("ringtone_row_SYSTEM_ALARM")
                        )
                    }

                    // Built-in gentle/classic/energetic alarm melodies (work across all parent & child devices)
                    items(
                        items = AlarmAudioController.builtInSoundOptions.filter {
                            it.soundType != AlarmAudioController.SOUND_SYSTEM_ALARM &&
                                it.soundType != AlarmAudioController.SOUND_SYSTEM_RINGTONE &&
                                it.soundType != AlarmAudioController.SOUND_SYSTEM_NOTIFICATION
                        },
                        key = { "builtin_${it.soundType}" }
                    ) { option ->
                        val isSelected = selectedSoundType == option.soundType
                        val isPlayingThis = previewState.isPlaying &&
                            previewState.activeSoundType == option.soundType

                        RingtoneOptionRow(
                            title = if (isHebrew) option.titleHe else option.titleEn,
                            subtitle = if (isHebrew) "צליל השכמה מובנה במערכת" else "Built-in system wake-up tone",
                            isSelected = isSelected,
                            isPlaying = isPlayingThis,
                            isHebrew = isHebrew,
                            onSelect = {
                                selectedSoundType = option.soundType
                                selectedCustomUri = ""
                                selectedCustomTitle = ""
                                AlarmAudioController.startPreview(
                                    context = context,
                                    soundType = option.soundType,
                                    customSoundUri = "",
                                    volumePercent = volumePercent.toInt()
                                )
                            },
                            onTogglePlay = {
                                if (isPlayingThis) {
                                    AlarmAudioController.stopPreview(context)
                                } else {
                                    selectedSoundType = option.soundType
                                    selectedCustomUri = ""
                                    selectedCustomTitle = ""
                                    AlarmAudioController.startPreview(
                                        context = context,
                                        soundType = option.soundType,
                                        customSoundUri = "",
                                        volumePercent = volumePercent.toInt()
                                    )
                                }
                            },
                            modifier = Modifier.testTag("ringtone_row_${option.soundType}")
                        )
                    }

                    // Enumerated Native Android TYPE_ALARM ringtones on this device
                    if (!isTargetRemoteChild) {
                        items(
                            items = systemAlarmRingtones,
                            key = { "sys_alarm_${it.uriString}" }
                        ) { tone ->
                            val isSelected = selectedSoundType == AlarmAudioController.SOUND_CUSTOM_URI &&
                                selectedCustomUri == tone.uriString
                            val isPlayingThis = previewState.isPlaying &&
                                previewState.activeCustomUri == tone.uriString

                            RingtoneOptionRow(
                                title = tone.title,
                                subtitle = if (isHebrew) "צליל שעון מעורר במכשיר" else "Device Alarm Tone",
                                isSelected = isSelected,
                                isPlaying = isPlayingThis,
                                isHebrew = isHebrew,
                                onSelect = {
                                    selectedSoundType = AlarmAudioController.SOUND_CUSTOM_URI
                                    selectedCustomUri = tone.uriString
                                    selectedCustomTitle = tone.title
                                    AlarmAudioController.startPreview(
                                        context = context,
                                        soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                                        customSoundUri = tone.uriString,
                                        volumePercent = volumePercent.toInt()
                                    )
                                },
                                onTogglePlay = {
                                    if (isPlayingThis) {
                                        AlarmAudioController.stopPreview(context)
                                    } else {
                                        selectedSoundType = AlarmAudioController.SOUND_CUSTOM_URI
                                        selectedCustomUri = tone.uriString
                                        selectedCustomTitle = tone.title
                                        AlarmAudioController.startPreview(
                                            context = context,
                                            soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                                            customSoundUri = tone.uriString,
                                            volumePercent = volumePercent.toInt()
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                RingtonePickerTab.ADDITIONAL_SOUNDS -> {
                    // Default System Ringtone & Default Notification Sound
                    item(key = "default_system_ringtone_option") {
                        val isSelected = selectedSoundType == AlarmAudioController.SOUND_SYSTEM_RINGTONE
                        val isPlayingThis = previewState.isPlaying &&
                            previewState.activeSoundType == AlarmAudioController.SOUND_SYSTEM_RINGTONE &&
                            previewState.activeCustomUri.isBlank()

                        RingtoneOptionRow(
                            title = if (isHebrew) "רינגטון שיחה ראשי של המכשיר" else "Default Phone Ringtone",
                            subtitle = if (isHebrew) "צליל שיחה נכנסת של האנדרואיד" else "System incoming call ringtone",
                            isSelected = isSelected,
                            isPlaying = isPlayingThis,
                            isHebrew = isHebrew,
                            onSelect = {
                                selectedSoundType = AlarmAudioController.SOUND_SYSTEM_RINGTONE
                                selectedCustomUri = ""
                                selectedCustomTitle = ""
                                AlarmAudioController.startPreview(
                                    context = context,
                                    soundType = AlarmAudioController.SOUND_SYSTEM_RINGTONE,
                                    customSoundUri = "",
                                    volumePercent = volumePercent.toInt()
                                )
                            },
                            onTogglePlay = {
                                if (isPlayingThis) {
                                    AlarmAudioController.stopPreview(context)
                                } else {
                                    selectedSoundType = AlarmAudioController.SOUND_SYSTEM_RINGTONE
                                    selectedCustomUri = ""
                                    selectedCustomTitle = ""
                                    AlarmAudioController.startPreview(
                                        context = context,
                                        soundType = AlarmAudioController.SOUND_SYSTEM_RINGTONE,
                                        customSoundUri = "",
                                        volumePercent = volumePercent.toInt()
                                    )
                                }
                            },
                            modifier = Modifier.testTag("ringtone_row_SYSTEM_RINGTONE")
                        )
                    }

                    item(key = "default_system_notification_option") {
                        val isSelected = selectedSoundType == AlarmAudioController.SOUND_SYSTEM_NOTIFICATION
                        val isPlayingThis = previewState.isPlaying &&
                            previewState.activeSoundType == AlarmAudioController.SOUND_SYSTEM_NOTIFICATION &&
                            previewState.activeCustomUri.isBlank()

                        RingtoneOptionRow(
                            title = if (isHebrew) "צליל התראה של המכשיר" else "Default Notification Sound",
                            subtitle = if (isHebrew) "צליל הודעה קצר של האנדרואיד" else "System notification tone",
                            isSelected = isSelected,
                            isPlaying = isPlayingThis,
                            isHebrew = isHebrew,
                            onSelect = {
                                selectedSoundType = AlarmAudioController.SOUND_SYSTEM_NOTIFICATION
                                selectedCustomUri = ""
                                selectedCustomTitle = ""
                                AlarmAudioController.startPreview(
                                    context = context,
                                    soundType = AlarmAudioController.SOUND_SYSTEM_NOTIFICATION,
                                    customSoundUri = "",
                                    volumePercent = volumePercent.toInt()
                                )
                            },
                            onTogglePlay = {
                                if (isPlayingThis) {
                                    AlarmAudioController.stopPreview(context)
                                } else {
                                    selectedSoundType = AlarmAudioController.SOUND_SYSTEM_NOTIFICATION
                                    selectedCustomUri = ""
                                    selectedCustomTitle = ""
                                    AlarmAudioController.startPreview(
                                        context = context,
                                        soundType = AlarmAudioController.SOUND_SYSTEM_NOTIFICATION,
                                        customSoundUri = "",
                                        volumePercent = volumePercent.toInt()
                                    )
                                }
                            },
                            modifier = Modifier.testTag("ringtone_row_SYSTEM_NOTIFICATION")
                        )
                    }

                    if (!isTargetRemoteChild) {
                        items(
                            items = additionalRingtones,
                            key = { "add_tone_${it.uriString}" }
                        ) { tone ->
                            val isSelected = selectedSoundType == AlarmAudioController.SOUND_CUSTOM_URI &&
                                selectedCustomUri == tone.uriString
                            val isPlayingThis = previewState.isPlaying &&
                                previewState.activeCustomUri == tone.uriString

                            RingtoneOptionRow(
                                title = tone.title,
                                subtitle = if (isHebrew) "צליל נוסף מהמכשיר" else "Additional Device Sound",
                                isSelected = isSelected,
                                isPlaying = isPlayingThis,
                                isHebrew = isHebrew,
                                onSelect = {
                                    selectedSoundType = AlarmAudioController.SOUND_CUSTOM_URI
                                    selectedCustomUri = tone.uriString
                                    selectedCustomTitle = tone.title
                                    AlarmAudioController.startPreview(
                                        context = context,
                                        soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                                        customSoundUri = tone.uriString,
                                        volumePercent = volumePercent.toInt()
                                    )
                                },
                                onTogglePlay = {
                                    if (isPlayingThis) {
                                        AlarmAudioController.stopPreview(context)
                                    } else {
                                        selectedSoundType = AlarmAudioController.SOUND_CUSTOM_URI
                                        selectedCustomUri = tone.uriString
                                        selectedCustomTitle = tone.title
                                        AlarmAudioController.startPreview(
                                            context = context,
                                            soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                                            customSoundUri = tone.uriString,
                                            volumePercent = volumePercent.toInt()
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                RingtonePickerTab.CUSTOM_SOUND -> {
                    item(key = "custom_sound_picker_card") {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFDCE8F8)),
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = if (isHebrew) "צליל מותאם אישית" else "Custom Sound",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF15366B)
                                    )
                                )

                                if (isTargetRemoteChild) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFFEFF6FF),
                                        border = BorderStroke(1.dp, Color(0xFFCCE0FA)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (isHebrew)
                                                "שעון זה מיועד למכשיר של הילד/ה. כדי להבטיח שהצלצול יפעל תמיד במכשיר הילד, מומלץ לבחור מצלילי המערכת."
                                            else
                                                "This alarm rings on the child's device. System sounds are recommended so playback always works on their phone.",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = GoogleBlueDark,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = if (isHebrew)
                                            "בחרו שיר או קובץ שמע מהמכשיר (MP3, WAV, OGG, M4A) או פתחו את בורר הצלילים של אנדרואיד:"
                                        else
                                            "Choose an audio file from your phone (MP3, WAV, OGG, M4A) or open the system ringtone picker:",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFF5A7194)
                                        )
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { customAudioLauncher.launch(arrayOf("audio/*")) },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = GoogleBlue,
                                                contentColor = Color.White
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 46.dp)
                                                .testTag("alarm_custom_sound_chip")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isHebrew) "בחר קובץ מהמכשיר" else "Choose Audio File",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                runCatching {
                                                    systemRingtonePickerLauncher.launch(
                                                        AlarmAudioController.createSystemRingtonePickerIntent(
                                                            context = context,
                                                            currentSoundType = selectedSoundType,
                                                            currentCustomUri = selectedCustomUri,
                                                            isHebrew = isHebrew
                                                        )
                                                    )
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, Color(0xFFBFD4F2)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 46.dp)
                                                .testTag("alarm_system_ringtone_picker_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.NotificationsActive,
                                                contentDescription = null,
                                                tint = GoogleBlueDark,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isHebrew) "בורר צלילי מערכת" else "System Picker",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = GoogleBlueDark
                                            )
                                        }
                                    }
                                }

                                if (selectedSoundType == AlarmAudioController.SOUND_CUSTOM_URI && selectedCustomUri.isNotBlank()) {
                                    val isPlayingCustom = previewState.isPlaying &&
                                        previewState.activeCustomUri == selectedCustomUri
                                    RingtoneOptionRow(
                                        title = selectedCustomTitle.ifBlank {
                                            if (isHebrew) "צליל מותאם אישית" else "Custom Sound"
                                        },
                                        subtitle = if (isHebrew) "צליל מותאם אישית שנבחר" else "Selected custom sound",
                                        isSelected = true,
                                        isPlaying = isPlayingCustom,
                                        isHebrew = isHebrew,
                                        onSelect = {
                                            AlarmAudioController.startPreview(
                                                context = context,
                                                soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                                                customSoundUri = selectedCustomUri,
                                                volumePercent = volumePercent.toInt()
                                            )
                                        },
                                        onTogglePlay = {
                                            if (isPlayingCustom) {
                                                AlarmAudioController.stopPreview(context)
                                            } else {
                                                AlarmAudioController.startPreview(
                                                    context = context,
                                                    soundType = AlarmAudioController.SOUND_CUSTOM_URI,
                                                    customSoundUri = selectedCustomUri,
                                                    volumePercent = volumePercent.toInt()
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("selected_custom_sound_row")
                                    )
                                }

                                val errorText = customFileErrorHe ?: if (isHebrew) previewState.errorMessageHe else previewState.errorMessageEn
                                if (!errorText.isNullOrBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = GoogleRedLight,
                                        border = BorderStroke(1.dp, GoogleRed.copy(alpha = 0.4f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("alarm_audio_error_banner")
                                    ) {
                                        Text(
                                            text = "⚠️ $errorText",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = GoogleRedDark,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single ringtone row inside [FamilyAlarmRingtonePickerScreen] with:
 * - Play / Stop preview button
 * - Ringtone name & subtitle
 * - Radio selection indicator
 * - Soft blue highlight when selected
 */
@Composable
private fun RingtoneOptionRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isPlaying: Boolean,
    isHebrew: Boolean,
    onSelect: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Color(0xFFE6F0FE) else Color.White,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) GoogleBlue else Color(0xFFDCE8F8)
        ),
        shadowElevation = if (isSelected) 2.dp else 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelect() }
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
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPlaying) GoogleRedLight else Color(0xFFDCEBFE)
                        )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) {
                            if (isHebrew) "עצור" else "Stop"
                        } else {
                            if (isHebrew) "השמע" else "Play"
                        },
                        tint = if (isPlaying) GoogleRedDark else GoogleBlueDark,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = Color(0xFF15366B)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFF5A7194)
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Icon(
                imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) GoogleBlue else Color(0xFF9EB2CE),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun PresetPillButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFFDCEBFE) else Color(0xFFF3F7FE),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) GoogleBlue else Color(0xFFD5E3F7)
        ),
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color(0xFF12366E) else Color(0xFF355079),
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }
    }
}

private fun computeSingleUpcomingSchoolDay(hour: Int, minute: Int): SchoolDay {
    val now = Calendar.getInstance()
    val candidate = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (candidate.timeInMillis <= now.timeInMillis) {
        candidate.add(Calendar.DAY_OF_YEAR, 1)
    }
    return when (candidate.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> SchoolDay.SUNDAY
        Calendar.MONDAY -> SchoolDay.MONDAY
        Calendar.TUESDAY -> SchoolDay.TUESDAY
        Calendar.WEDNESDAY -> SchoolDay.WEDNESDAY
        Calendar.THURSDAY -> SchoolDay.THURSDAY
        Calendar.FRIDAY -> SchoolDay.FRIDAY
        Calendar.SATURDAY -> SchoolDay.SATURDAY
        else -> SchoolDay.SUNDAY
    }
}

@Composable
internal fun AddOrEditFamilyAlarmDialog(
    alarmToEdit: FamilyAlarm?,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSave: (FamilyAlarm, Boolean) -> Unit
) {
    FamilyAlarmEditorScreen(
        alarmToEdit = alarmToEdit,
        members = members,
        currentMember = currentMember,
        isHebrew = isHebrew,
        onDismiss = onDismiss,
        onSave = onSave
    )
}

private fun openAndroidSystemClock(
    context: Context,
    alarm: FamilyAlarm,
    isHebrew: Boolean,
    onShowToast: (String) -> Unit
) {
    try {
        val calendarDays = ArrayList<Int>()
        alarm.daysOfWeek.forEach { schoolDay ->
            when (schoolDay) {
                SchoolDay.SUNDAY -> calendarDays.add(Calendar.SUNDAY)
                SchoolDay.MONDAY -> calendarDays.add(Calendar.MONDAY)
                SchoolDay.TUESDAY -> calendarDays.add(Calendar.TUESDAY)
                SchoolDay.WEDNESDAY -> calendarDays.add(Calendar.WEDNESDAY)
                SchoolDay.THURSDAY -> calendarDays.add(Calendar.THURSDAY)
                SchoolDay.FRIDAY -> calendarDays.add(Calendar.FRIDAY)
                SchoolDay.SATURDAY -> calendarDays.add(Calendar.SATURDAY)
            }
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, alarm.title)
            putExtra(AlarmClock.EXTRA_VIBRATE, alarm.vibrateEnabled)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            if (calendarDays.isNotEmpty()) {
                putExtra(AlarmClock.EXTRA_DAYS, calendarDays)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        onShowToast(
            if (isHebrew) "השעון המעורר תוזמן ישירות במערכת FamilyTasks (${alarm.formattedTime24h}) ⏰"
            else "Alarm scheduled directly in FamilyTasks (${alarm.formattedTime24h}) ⏰"
        )
    }
}

internal fun formatNextTriggerRelativeSummary(
    alarm: FamilyAlarm,
    isHebrew: Boolean
): String {
    val nowMillis = System.currentTimeMillis()
    val nextMillis = FamilyAlarmScheduler.computeNextTriggerAtMillis(alarm, nowMillis)
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val targetCal = Calendar.getInstance().apply { timeInMillis = nextMillis }

    val diffMinutes = ((nextMillis - nowMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
    val sameDay = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)

    val tomorrowCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val isTomorrow = tomorrowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) &&
        tomorrowCal.get(Calendar.DAY_OF_YEAR) == targetCal.get(Calendar.DAY_OF_YEAR)

    val dayNameHe = when (targetCal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> "יום ראשון"
        Calendar.MONDAY -> "יום שני"
        Calendar.TUESDAY -> "יום שלישי"
        Calendar.WEDNESDAY -> "יום רביעי"
        Calendar.THURSDAY -> "יום חמישי"
        Calendar.FRIDAY -> "יום שישי"
        Calendar.SATURDAY -> "יום שבת"
        else -> ""
    }

    return if (isHebrew) {
        when {
            sameDay && diffMinutes <= 60L -> "היום בשעה ${alarm.formattedTime24h} (בעוד $diffMinutes דק׳)"
            sameDay -> "היום בשעה ${alarm.formattedTime24h}"
            isTomorrow -> "מחר בשעה ${alarm.formattedTime24h}"
            else -> "$dayNameHe בשעה ${alarm.formattedTime24h}"
        }
    } else {
        when {
            sameDay && diffMinutes <= 60L -> "Today at ${alarm.formattedTime24h} (in ${diffMinutes}m)"
            sameDay -> "Today at ${alarm.formattedTime24h}"
            isTomorrow -> "Tomorrow at ${alarm.formattedTime24h}"
            else -> "At ${alarm.formattedTime24h}"
        }
    }
}
