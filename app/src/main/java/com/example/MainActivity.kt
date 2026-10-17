package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppLanguage
import com.example.ui.components.*
import com.example.ui.dialogs.*
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.util.NotificationHelper
import com.example.viewmodel.AppScreen
import com.example.viewmodel.FamilyViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createNotificationChannels(this)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            val viewModel: FamilyViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val isHebrew = settings.language == AppLanguage.HEBREW

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isHebrew) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MyApplicationTheme(themeColor = settings.themeColor) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        FamilyTasksApp(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun FamilyTasksApp(viewModel: FamilyViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val periods by viewModel.periods.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val latestAlert by viewModel.latestAlert.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val calendarMode by viewModel.calendarMode.collectAsStateWithLifecycle()
    val selectedCalendarDate by viewModel.selectedCalendarDate.collectAsStateWithLifecycle()
    val selectedMemberFilter by viewModel.selectedMemberFilter.collectAsStateWithLifecycle()
    val selectedChildTimetableId by viewModel.selectedChildTimetableId.collectAsStateWithLifecycle()
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()

    val showAddTaskDialog by viewModel.showAddTaskDialog.collectAsStateWithLifecycle()
    val taskToEdit by viewModel.taskToEdit.collectAsStateWithLifecycle()
    val showInviteMemberDialog by viewModel.showInviteMemberDialog.collectAsStateWithLifecycle()
    val memberToEdit by viewModel.memberToEdit.collectAsStateWithLifecycle()
    val showAddPeriodDialog by viewModel.showAddPeriodDialog.collectAsStateWithLifecycle()
    val periodToEdit by viewModel.periodToEdit.collectAsStateWithLifecycle()
    val showAddFolderDialog by viewModel.showAddFolderDialog.collectAsStateWithLifecycle()
    val showNotificationsSheet by viewModel.showNotificationsSheet.collectAsStateWithLifecycle()
    val showGoogleProfileSheet by viewModel.showGoogleProfileSheet.collectAsStateWithLifecycle()
    val showEditFamilyNameDialog by viewModel.showEditFamilyNameDialog.collectAsStateWithLifecycle()
    val showGoogleSignInDialog by viewModel.showGoogleSignInDialog.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val geminiLoading by viewModel.geminiLoading.collectAsStateWithLifecycle()
    val geminiResponse by viewModel.geminiResponse.collectAsStateWithLifecycle()

    var showRewardsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val isHebrew = settings.language == AppLanguage.HEBREW
    val currentMember = viewModel.currentMember
    val unreadCount = notifications.count { !it.isRead }
    val familyTitle = settings.getFamilyTitle(isHebrew)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        viewModel.initContext(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                // Allow the initial activity window and Compose tree to finish rendering before presenting system dialog
                kotlinx.coroutines.delay(500)
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // 1. Initial Google Login & Clean Household Setup Gate
    val activeFirebaseUser by com.example.auth.GoogleAuthHelper.authManager.currentUser.collectAsStateWithLifecycle()
    if (!settings.hasCompletedGoogleSignIn || activeFirebaseUser == null) {
        GoogleOnboardingScreen(
            initialEmail = settings.googleAccountEmail,
            isHebrew = isHebrew,
            onStartCleanFamily = { name, email, famName, avatar ->
                viewModel.initializeCleanFamily(name, email, famName, avatar)
            },
            onJoinWithCode = { code ->
                viewModel.joinFamilyWithCode(code)
            }
        )
        return
    }

    // 2. Biometric Fingerprint Lock Gate
    var isAppUnlocked by remember {
        mutableStateOf(!settings.biometricEnabled || !settings.requireBiometricOnStart)
    }

    LaunchedEffect(settings.requireBiometricOnStart, settings.biometricEnabled) {
        if (!settings.biometricEnabled || !settings.requireBiometricOnStart) {
            isAppUnlocked = true
        }
    }

    if (!isAppUnlocked) {
        BiometricLockScreen(
            settings = settings,
            isHebrew = isHebrew,
            onUnlock = { isAppUnlocked = true },
            onEmergencyBypass = { isAppUnlocked = true }
        )
        return
    }

    // Handle back button on sub-screens
    BackHandler(enabled = currentScreen != AppScreen.TASKS) {
        viewModel.navigateTo(AppScreen.TASKS)
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopFamilyBar(
                    currentMember = currentMember,
                    unreadNotificationsCount = unreadCount,
                    isDriveSyncActive = settings.isDriveSyncActive,
                    currentLanguage = settings.language,
                    familyTitle = familyTitle,
                    onLanguageToggle = {
                        val nextLang = if (isHebrew) AppLanguage.ENGLISH else AppLanguage.HEBREW
                        viewModel.updateSettings(settings.copy(language = nextLang))
                    },
                    onOpenGemini = { viewModel.navigateTo(AppScreen.GEMINI) },
                    onOpenNotifications = { viewModel.openNotificationsSheet() },
                    onOpenProfile = { viewModel.openGoogleProfileSheet() },
                    onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) }
                )

                // In-App Push Notification banner (floats directly below top bar)
                InAppNotificationBanner(
                    alert = latestAlert,
                    onDismiss = { viewModel.dismissAlert() }
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier.testTag("bottom_nav_bar")
            ) {
                // 1. Tasks / Home
                NavigationBarItem(
                    selected = currentScreen == AppScreen.TASKS,
                    onClick = { viewModel.navigateTo(AppScreen.TASKS) },
                    icon = {
                        Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
                    },
                    label = {
                        Text(
                            text = if (isHebrew) "בית" else "Home",
                            fontWeight = if (currentScreen == AppScreen.TASKS) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VibrantActionBlue,
                        indicatorColor = GoogleBlueLight
                    ),
                    modifier = Modifier.testTag("nav_tasks")
                )

                // 2. Calendar
                NavigationBarItem(
                    selected = currentScreen == AppScreen.CALENDAR,
                    onClick = { viewModel.navigateTo(AppScreen.CALENDAR) },
                    icon = {
                        Icon(imageVector = Icons.Default.CalendarMonth, contentDescription = "Calendar")
                    },
                    label = {
                        Text(
                            text = if (isHebrew) "לוח שנה" else "Calendar",
                            fontWeight = if (currentScreen == AppScreen.CALENDAR) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VibrantActionBlue,
                        indicatorColor = GoogleBlueLight
                    ),
                    modifier = Modifier.testTag("nav_calendar")
                )

                // 3. Timetable
                NavigationBarItem(
                    selected = currentScreen == AppScreen.TIMETABLE,
                    onClick = { viewModel.navigateTo(AppScreen.TIMETABLE) },
                    icon = {
                        Icon(imageVector = Icons.Default.School, contentDescription = "Timetable")
                    },
                    label = {
                        Text(
                            text = if (isHebrew) "מערכת" else "Schedule",
                            fontWeight = if (currentScreen == AppScreen.TIMETABLE) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VibrantActionBlue,
                        indicatorColor = GoogleBlueLight
                    ),
                    modifier = Modifier.testTag("nav_timetable")
                )

                // 4. Files & Drive
                NavigationBarItem(
                    selected = currentScreen == AppScreen.FILES,
                    onClick = { viewModel.navigateTo(AppScreen.FILES) },
                    icon = {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = "Files")
                    },
                    label = {
                        Text(
                            text = if (isHebrew) "קבצים" else "Files",
                            fontWeight = if (currentScreen == AppScreen.FILES) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VibrantActionBlue,
                        indicatorColor = GoogleBlueLight
                    ),
                    modifier = Modifier.testTag("nav_files")
                )

                // 5. Family & Admin Dashboard
                NavigationBarItem(
                    selected = currentScreen == AppScreen.ADMIN_DASHBOARD,
                    onClick = { viewModel.navigateTo(AppScreen.ADMIN_DASHBOARD) },
                    icon = {
                        Icon(imageVector = Icons.Default.Group, contentDescription = "Family")
                    },
                    label = {
                        Text(
                            text = if (isHebrew) "משפחה" else "Family",
                            fontWeight = if (currentScreen == AppScreen.ADMIN_DASHBOARD) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VibrantActionBlue,
                        indicatorColor = GoogleBlueLight
                    ),
                    modifier = Modifier.testTag("nav_admin")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            when (currentScreen) {
                AppScreen.TASKS -> {
                    TasksScreen(
                        tasks = tasks,
                        members = members,
                        currentMember = currentMember,
                        selectedMemberFilter = selectedMemberFilter,
                        isHebrew = isHebrew,
                        onFilterChange = { viewModel.setMemberFilter(it) },
                        onToggleTask = { viewModel.toggleTask(it) },
                        onEditTask = { viewModel.openEditTaskDialog(it) },
                        onDeleteTask = { viewModel.deleteTask(it) },
                        onSendNudge = { viewModel.sendNudge(it) },
                        onAddNewTask = { viewModel.openAddTaskDialog() },
                        onOpenFamilyAdmin = { viewModel.navigateTo(AppScreen.ADMIN_DASHBOARD) },
                        onOpenRewards = { showRewardsSheet = true }
                    )
                }
                AppScreen.CALENDAR -> {
                    CalendarScreen(
                        tasks = tasks,
                        periods = periods,
                        members = members,
                        currentMember = currentMember,
                        calendarMode = calendarMode,
                        selectedDate = selectedCalendarDate,
                        firstDayOfWeek = settings.firstDayOfWeek,
                        isHebrew = isHebrew,
                        onModeChange = { viewModel.setCalendarMode(it) },
                        onDateSelect = { viewModel.setSelectedCalendarDate(it) },
                        onToggleTask = { viewModel.toggleTask(it) },
                        onEditTask = { viewModel.openEditTaskDialog(it) },
                        onDeleteTask = { viewModel.deleteTask(it) },
                        onSendNudge = { viewModel.sendNudge(it) },
                        onAddNewTask = { viewModel.openAddTaskDialog() }
                    )
                }
                AppScreen.TIMETABLE -> {
                    TimetableScreen(
                        periods = periods,
                        members = members,
                        currentMember = currentMember,
                        selectedChildId = selectedChildTimetableId,
                        timetableViewMode = settings.timetableViewMode,
                        isHebrew = isHebrew,
                        onSelectChild = { viewModel.setSelectedChildTimetable(it) },
                        onViewModeChange = { viewModel.setTimetableViewMode(it) },
                        onAddPeriod = { viewModel.openAddPeriodDialog() },
                        onEditPeriod = { viewModel.openEditPeriodDialog(it) },
                        onDeletePeriod = { viewModel.deletePeriod(it) },
                        onTestAlert = { period, name -> viewModel.testSchoolAlert(period, name) }
                    )
                }
                AppScreen.FILES -> {
                    FilesScreen(
                        folders = folders,
                        files = files,
                        currentMember = currentMember,
                        selectedFolderId = selectedFolderId,
                        viewMode = settings.filesViewMode,
                        isHebrew = isHebrew,
                        onSelectFolder = { viewModel.setSelectedFolder(it) },
                        onViewModeChange = { viewModel.setFilesViewMode(it) },
                        onAddFolder = { viewModel.openAddFolderDialog() },
                        onUploadFile = { folderId, name, ext, size ->
                            viewModel.uploadDocument(folderId, name, ext, size)
                        },
                        onRenameFile = { fileId, newName -> viewModel.renameFile(fileId, newName) },
                        onDeleteFile = { fileId -> viewModel.deleteFile(fileId) },
                        onShowToast = { viewModel.showToast(it) }
                    )
                }
                AppScreen.ADMIN_DASHBOARD -> {
                    AdminDashboardScreen(
                        members = members,
                        tasks = tasks,
                        currentMember = currentMember,
                        isHebrew = isHebrew,
                        familyTitle = familyTitle,
                        onInviteMember = { viewModel.openInviteMemberDialog() },
                        onEditMember = { viewModel.openEditMemberDialog(it) },
                        onEditFamilyName = { viewModel.openEditFamilyNameDialog() },
                        onUpdateRole = { memberId, role -> viewModel.updateMemberRole(memberId, role) },
                        onRemoveMember = { viewModel.removeMember(it) },
                        onSendNudge = { viewModel.sendNudge(it) },
                        onSendPushInvite = { viewModel.sendInvitationNotification(it) },
                        onShowToast = { viewModel.showToast(it) }
                    )
                }
                AppScreen.SETTINGS -> {
                    SettingsScreen(
                        settings = settings,
                        isHebrew = isHebrew,
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onSyncNow = { viewModel.syncCloudNow() },
                        onShowToast = { viewModel.showToast(it) }
                    )
                }
                AppScreen.GEMINI -> {
                    GeminiScreen(
                        currentMember = currentMember,
                        members = members,
                        isHebrew = isHebrew,
                        onAskGemini = { viewModel.askGemini(it) },
                        onAddSuggestedTasks = { viewModel.addGeminiSuggestedTasks() },
                        isLoading = geminiLoading,
                        responseContent = geminiResponse,
                        onShowToast = { viewModel.showToast(it) }
                    )
                }
            }
        }
    }

    // Modal Dialogs & Sheets
    if (showAddTaskDialog) {
        AddTaskDialog(
            members = members,
            isHebrew = isHebrew,
            taskToEdit = taskToEdit,
            onDismiss = { viewModel.closeAddTaskDialog() },
            onSaveTask = { viewModel.saveTask(it) }
        )
    }

    if (showInviteMemberDialog) {
        InviteMemberDialog(
            currentMemberCount = members.size,
            isHebrew = isHebrew,
            memberToEdit = memberToEdit,
            familyTitle = familyTitle,
            inviteCode = settings.getDynamicInviteCode(),
            inviteLink = settings.getDynamicInviteLink(),
            onDismiss = { viewModel.closeInviteMemberDialog() },
            onConfirm = { name, email, role, emoji, grade ->
                viewModel.saveMember(memberToEdit?.id, name, email, role, emoji, grade)
            },
            onDeleteMember = { viewModel.removeMember(it) },
            onSendPushInvite = { viewModel.sendInvitationNotification(it) }
        )
    }

    if (showAddPeriodDialog) {
        val activeKid = members.find { it.id == selectedChildTimetableId } ?: members.firstOrNull() ?: currentMember
        AddPeriodDialog(
            childId = activeKid.id,
            childName = activeKid.name,
            isHebrew = isHebrew,
            periodToEdit = periodToEdit,
            onDismiss = { viewModel.closeAddPeriodDialog() },
            onSavePeriod = { viewModel.savePeriod(it) }
        )
    }

    if (showAddFolderDialog) {
        AddFolderDialog(
            isHebrew = isHebrew,
            onDismiss = { viewModel.closeAddFolderDialog() },
            onSaveFolder = { heb, eng, color, emoji ->
                viewModel.addFolder(heb, eng, color, emoji)
            }
        )
    }

    if (showGoogleProfileSheet) {
        GoogleProfileSheet(
            currentMember = currentMember,
            members = members,
            isHebrew = isHebrew,
            familyTitle = familyTitle,
            onSwitchUser = { viewModel.switchUser(it) },
            onInviteMember = { viewModel.openInviteMemberDialog() },
            onEditMember = { viewModel.openEditMemberDialog(it) },
            onEditFamilyName = { viewModel.openEditFamilyNameDialog() },
            onOpenGoogleSignIn = { viewModel.openGoogleSignInDialog() },
            onDismiss = { viewModel.closeGoogleProfileSheet() }
        )
    }

    if (showEditFamilyNameDialog) {
        EditFamilyNameDialog(
            currentFamilyName = settings.familyName,
            isHebrew = isHebrew,
            onDismiss = { viewModel.closeEditFamilyNameDialog() },
            onSaveFamilyName = { viewModel.updateFamilyName(it) }
        )
    }

    if (showGoogleSignInDialog) {
        GoogleSignInDialog(
            currentEmail = settings.googleAccountEmail,
            members = members,
            isHebrew = isHebrew,
            onDismiss = { viewModel.closeGoogleSignInDialog() },
            onSignInSuccess = { viewModel.completeGoogleSignIn(it) },
            onSelectMember = { viewModel.switchUser(it) }
        )
    }

    if (showNotificationsSheet) {
        NotificationHistorySheet(
            notifications = notifications,
            isHebrew = isHebrew,
            onClearAll = { viewModel.clearAllNotifications() },
            onDismiss = { viewModel.closeNotificationsSheet() }
        )
    }

    if (showRewardsSheet) {
        RewardsSheet(
            currentMember = currentMember,
            isHebrew = isHebrew,
            onClaimReward = { reward ->
                viewModel.showToast(if (isHebrew) "מזל טוב! פרס '${reward.title}' מומש בהצלחה 🎁" else "Reward '${reward.title}' claimed! 🎁")
            },
            onDismiss = { showRewardsSheet = false }
        )
    }
}
