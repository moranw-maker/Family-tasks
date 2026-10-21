package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.*
import com.example.ui.components.*
import com.example.ui.dialogs.*
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.viewmodel.CalendarViewMode
import com.example.viewmodel.FamilyViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FamilyTasksApp()
        }
    }
}

@Composable
fun FamilyTasksApp(
    viewModel: FamilyViewModel = viewModel()
) {
    val context = LocalContext.current
    remember(context) {
        viewModel.attachApplicationContext(context)
        true
    }

    // Collect States
    val familyName by viewModel.familyName.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val pendingInvitations by viewModel.pendingInvitations.collectAsStateWithLifecycle()
    val currentMember by viewModel.currentMember.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val schedulePeriods by viewModel.schedulePeriods.collectAsStateWithLifecycle()
    val familyAlarms by viewModel.familyAlarms.collectAsStateWithLifecycle()
    val deletedAlarmNotices by viewModel.deletedAlarmNotices.collectAsStateWithLifecycle()
    val activeRingingAlarm by com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()

    // V1.6 Startup State Machine, Membership & Invitation Validation States
    val startupState by viewModel.startupState.collectAsStateWithLifecycle()
    val membershipState by viewModel.membershipState.collectAsStateWithLifecycle()
    val validatedInvitationPreview by viewModel.validatedInvitationPreview.collectAsStateWithLifecycle()
    val invitationErrorMessage by viewModel.invitationErrorMessage.collectAsStateWithLifecycle()
    val isProcessingFamilyAction by viewModel.isProcessingFamilyAction.collectAsStateWithLifecycle()
    val isCreatingInvitation by viewModel.isCreatingInvitation.collectAsStateWithLifecycle()
    val invitationCreationError by viewModel.invitationCreationError.collectAsStateWithLifecycle()
    val taskSyncStates by viewModel.taskSyncStates.collectAsStateWithLifecycle()
    val taskOperationError by viewModel.taskOperationError.collectAsStateWithLifecycle()

    // Google Auth & Cloud Family Group states
    val isGoogleSignedIn by viewModel.isGoogleSignedIn.collectAsStateWithLifecycle()
    val googleEmail by viewModel.googleUserEmail.collectAsStateWithLifecycle()
    val googleDisplayName by viewModel.googleUserDisplayName.collectAsStateWithLifecycle()
    val cloudFamilyGroup by viewModel.cloudFamilyGroup.collectAsStateWithLifecycle()
    val autoSyncCalendar by viewModel.autoSyncCalendar.collectAsStateWithLifecycle()
    val autoSyncDrive by viewModel.autoSyncDrive.collectAsStateWithLifecycle()

    // Real Firebase Auth states
    val firebaseCurrentUser by viewModel.firebaseCurrentUser.collectAsStateWithLifecycle()
    val isAuthenticating by viewModel.isAuthenticating.collectAsStateWithLifecycle()
    val authErrorMessage by viewModel.authErrorMessage.collectAsStateWithLifecycle()

    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val firstDayOfWeek by viewModel.firstDayOfWeek.collectAsStateWithLifecycle()
    val biometricLockEnabled by viewModel.biometricLockEnabled.collectAsStateWithLifecycle()
    val isAppUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
    val tenMinuteClassAlertsEnabled by viewModel.tenMinuteClassAlertsEnabled.collectAsStateWithLifecycle()
    val timetableViewMode by viewModel.timetableViewMode.collectAsStateWithLifecycle()
    val filesViewMode by viewModel.filesViewMode.collectAsStateWithLifecycle()

    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val calendarMode by viewModel.calendarMode.collectAsStateWithLifecycle()
    val selectedMemberFilter by viewModel.selectedMemberFilter.collectAsStateWithLifecycle()
    val selectedChildForTimetable by viewModel.selectedChildForTimetable.collectAsStateWithLifecycle()
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val activeUploads by viewModel.activeUploads.collectAsStateWithLifecycle()
    val fileSortOption by viewModel.fileSortOption.collectAsStateWithLifecycle()
    val fileSortAscending by viewModel.fileSortAscending.collectAsStateWithLifecycle()
    val fileSearchQuery by viewModel.fileSearchQuery.collectAsStateWithLifecycle()
    val familyDriveConfig by viewModel.familyDriveConfig.collectAsStateWithLifecycle()
    val driveConsentPendingIntent by viewModel.driveConsentPendingIntent.collectAsStateWithLifecycle()
    val driveConsentIntent by viewModel.driveConsentIntent.collectAsStateWithLifecycle()
    val pendingDrivePickerRequest by viewModel.pendingDrivePickerRequest.collectAsStateWithLifecycle()
    val drivePickerVerificationError by viewModel.drivePickerVerificationError.collectAsStateWithLifecycle()

    // Dialog / BottomSheet States
    val showAddTaskDialog by viewModel.showAddTaskDialog.collectAsStateWithLifecycle()
    val taskToEdit by viewModel.taskToEdit.collectAsStateWithLifecycle()
    val showInviteMemberDialog by viewModel.showInviteMemberDialog.collectAsStateWithLifecycle()
    val showEditFamilyNameDialog by viewModel.showEditFamilyNameDialog.collectAsStateWithLifecycle()
    val showAddPeriodDialog by viewModel.showAddPeriodDialog.collectAsStateWithLifecycle()
    val periodToEdit by viewModel.periodToEdit.collectAsStateWithLifecycle()
    val showAddFolderDialog by viewModel.showAddFolderDialog.collectAsStateWithLifecycle()
    val showGoogleProfileSheet by viewModel.showGoogleProfileSheet.collectAsStateWithLifecycle()
    val showNotificationHistorySheet by viewModel.showNotificationHistorySheet.collectAsStateWithLifecycle()
    val showGoogleSignInDialog by viewModel.showGoogleSignInDialog.collectAsStateWithLifecycle()

    var showRewardsSheet by remember { mutableStateOf(false) }
    var showCameraScannerDialog by remember { mutableStateOf(false) }
    var fileToPreview by remember { mutableStateOf<FamilyDocument?>(null) }
    var fileToRename by remember { mutableStateOf<FamilyDocument?>(null) }

    val aiMessages by viewModel.aiMessages.collectAsStateWithLifecycle()
    val isAiGenerating by viewModel.isAiGenerating.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val activeBannerAlert by viewModel.activeBannerAlert.collectAsStateWithLifecycle()

    // Explicit demo mode toggle (only activated if user explicitly clicks "Continue with Sample Demo Family")
    var isExplicitDemoMode by remember { mutableStateOf(false) }

    // Restore existing Firebase Auth session and Firestore user/family profile on launch
    LaunchedEffect(Unit) {
        viewModel.restoreExistingGoogleSession()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    val isHebrew = appLanguage == AppLanguage.HEBREW
    val layoutDirection = if (appLanguage.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val unreadCount = remember(notifications) { notifications.count { !it.isRead } }

    MyApplicationTheme {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            if (biometricLockEnabled && !isAppUnlocked) {
                BiometricLockScreen(
                    settings = AppSettings(
                        familyTitleHe = familyName,
                        familyTitleEn = familyName,
                        biometricEnabled = biometricLockEnabled
                    ),
                    isHebrew = isHebrew,
                    onUnlock = { viewModel.unlockApp() },
                    onEmergencyBypass = { viewModel.unlockApp() }
                )
            } else {
                // V1.6 Safe Startup State Machine:
                // Only enter the dashboard when StartupState is ReadyToEnterDashboard (and Membership is ActiveMember),
                // or when the user explicitly clicked offline demo preview mode.
                // Never navigate to onboarding before Firestore profile/membership loading finishes!
                val isReadyForDashboard = isExplicitDemoMode || (
                    startupState is StartupState.ReadyToEnterDashboard &&
                        membershipState is FamilyMembershipState.ActiveMember
                )
                val shouldShowOnboarding = !isReadyForDashboard

                if (shouldShowOnboarding) {
                    GoogleOnboardingScreen(
                        isHebrew = isHebrew,
                        isAuthenticating = isAuthenticating,
                        authErrorMessage = authErrorMessage,
                        isGoogleSignedIn = isGoogleSignedIn || firebaseCurrentUser != null,
                        authenticatedEmail = firebaseCurrentUser?.email ?: googleEmail,
                        authenticatedDisplayName = firebaseCurrentUser?.displayName ?: googleDisplayName,
                        startupState = startupState,
                        membershipState = membershipState,
                        validatedInvitation = validatedInvitationPreview,
                        invitationErrorMessage = invitationErrorMessage,
                        isProcessingFamilyAction = isProcessingFamilyAction,
                        onLaunchRealGoogleSignIn = { ctx, famName, onAuthSuccess ->
                            viewModel.launchRealGoogleSignIn(ctx, famName) { email, displayName, photoUrl ->
                                onAuthSuccess(email, displayName, photoUrl)
                            }
                        },
                        onRetryStartupLoading = {
                            viewModel.retryStartupLoading()
                        },
                        onValidateInviteCode = { code ->
                            viewModel.validateInviteCodeForJoining(code)
                        },
                        onClearValidatedInvite = {
                            viewModel.clearValidatedInvitationPreview()
                        },
                        onConfirmJoinWithValidatedInvite = { code, memberName, familyIdentity ->
                            viewModel.confirmJoinFamilyWithValidatedInvite(
                                rawCode = code,
                                memberDisplayName = memberName,
                                familyIdentity = familyIdentity
                            )
                        },
                        onCreateFamilyAfterLogin = { newFamilyName, parentDisplayName, familyIdentity ->
                            viewModel.createNewFamilyOnFirstLogin(
                                familyNameInput = newFamilyName,
                                parentDisplayName = parentDisplayName,
                                familyIdentity = familyIdentity
                            )
                        },
                        onSignOutGoogle = {
                            isExplicitDemoMode = false
                            viewModel.signOutGoogleAccount()
                        },
                        onStartCleanFamily = { famName, adminName, email, familyIdentity ->
                            viewModel.startNewCleanFamily(famName, adminName, email, familyIdentity)
                        },
                        onJoinExistingFamily = { code, memberName, email ->
                            viewModel.joinCloudFamilyByInviteCode(code, memberName, email)
                        },
                        onContinueDemoMode = {
                            isExplicitDemoMode = true
                        }
                    )
                } else {
                    // BackHandler for secondary screens
                    if (currentScreen != AppScreen.HOME) {
                        BackHandler {
                            viewModel.navigateTo(AppScreen.HOME)
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            contentWindowInsets = WindowInsets.safeDrawing,
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            topBar = {
                                TopFamilyBar(
                                    currentMember = currentMember,
                                    unreadNotificationsCount = unreadCount,
                                    isDriveSyncActive = autoSyncDrive,
                                    currentLanguage = appLanguage,
                                    familyTitle = familyName,
                                    onLanguageToggle = {
                                        val nextLang = if (appLanguage == AppLanguage.HEBREW) AppLanguage.ENGLISH else AppLanguage.HEBREW
                                        viewModel.setLanguage(nextLang)
                                    },
                                    onOpenGemini = { viewModel.navigateTo(AppScreen.AI_ASSISTANT) },
                                    onOpenNotifications = { viewModel.openNotificationHistorySheet() },
                                    onOpenProfile = { viewModel.openGoogleProfileSheet() },
                                    onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) }
                                )
                            },
                            bottomBar = {
                                FamilyBottomNavBar(
                                    currentScreen = currentScreen,
                                    isParent = currentMember.isParent,
                                    isHebrew = isHebrew,
                                    onNavigate = { viewModel.navigateTo(it) }
                                )
                            }
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                when (currentScreen) {
                                    AppScreen.HOME -> {
                                        TasksScreen(
                                            tasks = tasks,
                                            members = members,
                                            currentMember = currentMember,
                                            selectedMemberFilter = selectedMemberFilter,
                                            isHebrew = isHebrew,
                                            onFilterChange = { viewModel.setMemberFilter(it) },
                                            onToggleTask = { viewModel.toggleTask(it) },
                                            onEditTask = { viewModel.openAddTaskDialog(it) },
                                            onDeleteTask = { viewModel.deleteTask(it) },
                                            onSendNudge = { viewModel.sendParentNudge(it) },
                                            onAddNewTask = { viewModel.openAddTaskDialog(null) },
                                            onOpenFamilyAdmin = { viewModel.navigateTo(AppScreen.ADMIN_DASHBOARD) },
                                            onOpenRewards = { showRewardsSheet = true },
                                            taskSyncStates = taskSyncStates,
                                            taskOperationError = taskOperationError,
                                            onApproveTask = { viewModel.approveTaskCompletion(it) },
                                            onDismissTaskError = { viewModel.clearTaskOperationError() }
                                        )
                                    }

                                    AppScreen.CALENDAR -> {
                                        CalendarScreen(
                                            tasks = tasks,
                                            periods = schedulePeriods,
                                            members = members,
                                            currentMember = currentMember,
                                            selectedDate = selectedDate,
                                            calendarMode = calendarMode,
                                            firstDayOfWeek = firstDayOfWeek,
                                            isHebrew = isHebrew,
                                            onModeChange = { viewModel.setCalendarViewMode(it) },
                                            onDateSelect = { viewModel.selectDate(it) },
                                            onToggleTask = { viewModel.toggleTask(it) },
                                            onEditTask = { viewModel.openAddTaskDialog(it) },
                                            onDeleteTask = { viewModel.deleteTask(it) },
                                            onSendNudge = { viewModel.sendParentNudge(it) },
                                            onAddNewTask = { viewModel.openAddTaskDialog(null) },
                                            taskSyncStates = taskSyncStates,
                                            onApproveTask = { viewModel.approveTaskCompletion(it) }
                                        )
                                    }

                                    AppScreen.TIMETABLE -> {
                                        TimetableScreen(
                                            members = members,
                                            selectedChildId = selectedChildForTimetable,
                                            periods = schedulePeriods,
                                            currentMember = currentMember,
                                            timetableViewMode = timetableViewMode,
                                            isHebrew = isHebrew,
                                            onSelectChild = { viewModel.selectChildForTimetable(it) },
                                            onViewModeChange = { viewModel.setTimetableViewMode(it) },
                                            onAddPeriod = { viewModel.openAddPeriodDialog(null) },
                                            onEditPeriod = { viewModel.openAddPeriodDialog(it) },
                                            onDeletePeriod = { viewModel.deleteSchedulePeriod(it) },
                                            onTestAlert = { period, childName ->
                                                viewModel.testTenMinuteAlert(period, childName)
                                            }
                                        )
                                    }

                                    AppScreen.ALARMS -> {
                                        FamilyAlarmScreen(
                                            alarms = familyAlarms,
                                            members = members,
                                            currentMember = currentMember,
                                            isHebrew = isHebrew,
                                            deletedAlarmNotices = deletedAlarmNotices,
                                            onDismissDeletedNotice = { viewModel.dismissDeletedAlarmNotice(it) },
                                            onSaveAlarm = { viewModel.saveFamilyAlarm(it) },
                                            onToggleAlarm = { viewModel.toggleFamilyAlarm(it) },
                                            onDeleteAlarm = { viewModel.deleteFamilyAlarm(it) },
                                            onShowToast = { viewModel.showToastMessage(it) },
                                            onNavigateBack = { viewModel.navigateTo(AppScreen.HOME) }
                                        )
                                    }

                                    AppScreen.FILES -> {
                                        FilesScreen(
                                            folders = folders,
                                            selectedFolderId = selectedFolderId,
                                            files = documents,
                                            currentMember = currentMember,
                                            members = members,
                                            viewMode = filesViewMode,
                                            isHebrew = isHebrew,
                                            activeUploads = activeUploads,
                                            sortOption = fileSortOption,
                                            sortAscending = fileSortAscending,
                                            searchQuery = fileSearchQuery,
                                            onSelectFolder = { viewModel.selectFolder(it) },
                                            onNavigateUpFolder = { viewModel.navigateUpFolder() },
                                            onViewModeChange = { viewModel.setFilesViewMode(it) },
                                            onSortOptionChange = { viewModel.setFileSortOption(it) },
                                            onSearchQueryChange = { viewModel.setFileSearchQuery(it) },
                                            onAddFolder = { viewModel.openAddFolderDialog() },
                                            onDeleteFolder = { viewModel.deleteSharedFolder(it) },
                                            onUploadFile = { folderId, name, ext, size ->
                                                viewModel.simulateUploadFileToDrive(folderId, name, ext, size)
                                            },
                                            onUploadBinaryFile = { folderId, name, ext, bytes ->
                                                viewModel.uploadBinaryFileToFamily(folderId, name, ext, bytes)
                                            },
                                            onCancelUpload = { viewModel.cancelFileUpload(it) },
                                            onRetryUpload = { viewModel.retryFileUpload(it) },
                                            onDismissUpload = { viewModel.dismissUploadTaskState(it) },
                                            onRenameFile = { fileId, newName ->
                                                viewModel.renameDocument(fileId, newName)
                                            },
                                            onMoveFile = { fileId, targetFolderId ->
                                                viewModel.moveDocumentToFolder(fileId, targetFolderId)
                                            },
                                            onRenameFolder = { folderId, newNameHe ->
                                                viewModel.renameSharedFolder(folderId, newNameHe)
                                            },
                                            onDeleteFile = { viewModel.deleteDocument(it) },
                                            familyDriveConfig = familyDriveConfig,
                                            driveConsentPendingIntent = driveConsentPendingIntent,
                                            driveConsentIntent = driveConsentIntent,
                                            onConnectFamilyDrive = { viewModel.setupOrReconnectFamilyGoogleDrive() },
                                            onClearDriveConsentPrompts = { viewModel.clearDriveConsentPrompts() },
                                            onShareDriveWithMember = { memberId, email ->
                                                viewModel.shareFamilyDriveWithMember(memberId, email)
                                            },
                                            onShareDriveWithAllMembers = {
                                                viewModel.shareFamilyDriveWithAllMembers()
                                            },
                                            onUpdateMemberGoogleEmail = { memberId, email ->
                                                viewModel.updateMemberGoogleEmail(memberId, email)
                                            },
                                            pendingDrivePickerRequest = pendingDrivePickerRequest,
                                            drivePickerVerificationError = drivePickerVerificationError,
                                            onRequestSharedFolderPicker = {
                                                viewModel.requestSharedFolderPickerAuthorization()
                                            },
                                            onVerifyPickedSharedFolder = { pickedDriveId, pickedName ->
                                                viewModel.verifyAndConnectPickedSharedFolder(pickedDriveId, pickedName)
                                            },
                                            onDismissDrivePicker = {
                                                viewModel.dismissDrivePickerAuthorizationDialog()
                                            },
                                            fileBytesProvider = { doc -> viewModel.fetchDocumentBytes(doc) },
                                            onShowToast = { viewModel.showToastMessage(it) }
                                        )
                                    }

                                    AppScreen.AI_ASSISTANT -> {
                                        val latestAiResponse = remember(aiMessages) {
                                            aiMessages.lastOrNull { !it.first }?.second
                                        }
                                        GeminiScreen(
                                            currentMember = currentMember,
                                            members = members,
                                            isHebrew = isHebrew,
                                            onAskGemini = { viewModel.askGeminiAssistant(it) },
                                            onAddSuggestedTasks = { viewModel.generateSmartTasksForKids() },
                                            isLoading = isAiGenerating,
                                            responseContent = latestAiResponse,
                                            onShowToast = { viewModel.showToastMessage(it) }
                                        )
                                    }

                                    AppScreen.ADMIN_DASHBOARD -> {
                                        AdminDashboardScreen(
                                            familyName = familyName,
                                            members = members,
                                            currentMember = currentMember,
                                            tasks = tasks,
                                            documents = documents,
                                            pendingInvitations = pendingInvitations,
                                            isHebrew = isHebrew,
                                            onEditFamilyName = { viewModel.openEditFamilyNameDialog() },
                                            onInviteMember = { viewModel.openInviteMemberDialog() },
                                            onSwitchMember = { viewModel.switchMember(it) },
                                            onChangeRole = { memberId, newRole ->
                                                viewModel.changeMemberRole(memberId, newRole)
                                            },
                                            onAcceptInvite = { viewModel.acceptInvitation(it) },
                                            onRemoveMember = { viewModel.removeMember(it) },
                                            onRevokeInvitation = { viewModel.revokePendingInvitation(it) },
                                            onGenerateShareText = { name, role ->
                                                viewModel.getDynamicInviteShareText(name, role, isHebrew)
                                            },
                                            onFormatInvitationShareText = { invite ->
                                                viewModel.getWhatsAppInviteShareText(invite, isHebrew)
                                            }
                                        )
                                    }

                                    AppScreen.SETTINGS -> {
                                        SettingsScreen(
                                            familyName = familyName,
                                            currentMember = currentMember,
                                            isGoogleSignedIn = isGoogleSignedIn,
                                            googleEmail = googleEmail,
                                            cloudFamilyGroup = cloudFamilyGroup,
                                            autoSyncCalendar = autoSyncCalendar,
                                            autoSyncDrive = autoSyncDrive,
                                            appLanguage = appLanguage,
                                            themeMode = themeMode,
                                            firstDayOfWeek = firstDayOfWeek,
                                            biometricLockEnabled = biometricLockEnabled,
                                            tenMinuteClassAlertsEnabled = tenMinuteClassAlertsEnabled,
                                            onEditFamilyName = { viewModel.openEditFamilyNameDialog() },
                                            onOpenAdminDashboard = { viewModel.navigateTo(AppScreen.ADMIN_DASHBOARD) },
                                            onOpenGoogleSignIn = { viewModel.openGoogleSignInDialog() },
                                            onSignOutGoogle = {
                                                isExplicitDemoMode = false
                                                viewModel.signOutGoogleAccount()
                                            },
                                            onAutoSyncCalendarChange = { viewModel.setAutoSyncCalendar(it) },
                                            onAutoSyncDriveChange = { viewModel.setAutoSyncDrive(it) },
                                            onLanguageChange = { viewModel.setLanguage(it) },
                                            onThemeModeChange = { viewModel.setThemeMode(it) },
                                            onFirstDayOfWeekChange = { viewModel.setFirstDayOfWeek(it) },
                                            onBiometricLockChange = { viewModel.setBiometricLock(it) },
                                            onLockNow = { viewModel.lockApp() },
                                            onTenMinuteAlertsChange = { viewModel.setTenMinuteClassAlerts(it) },
                                            onUpdateFamilyIdentity = { newIdentity ->
                                                viewModel.updateCurrentUserProfile(newIdentity = newIdentity)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Floating In-App Push Notification Heads-Up Banner
                        activeBannerAlert?.let { notif ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                            ) {
                                InAppNotificationBanner(
                                    alert = notif,
                                    onDismiss = { viewModel.dismissBannerAlert() }
                                )
                            }
                        }
                    }

                    // --- Modals, Sheets & Dialogs ---

                    if (showAddTaskDialog && currentMember.isParent) {
                        AddTaskDialog(
                            members = members,
                            isHebrew = isHebrew,
                            taskToEdit = taskToEdit,
                            onDismiss = { viewModel.closeAddTaskDialog() },
                            onSaveTask = { taskResult ->
                                viewModel.saveTask(
                                    id = if (taskToEdit != null) taskResult.id else null,
                                    title = taskResult.title,
                                    description = taskResult.description,
                                    category = taskResult.category,
                                    assignedToMemberId = taskResult.assignedToMemberId,
                                    dateEpochDay = taskResult.dateEpochDay,
                                    dueTimeFormatted = taskResult.dueTimeFormatted,
                                    priority = taskResult.priority,
                                    recurrence = taskResult.recurrence,
                                    points = taskResult.points
                                )
                            }
                        )
                    }

                    if (showRewardsSheet) {
                        RewardsSheet(
                            currentMember = currentMember,
                            isHebrew = isHebrew,
                            onClaimReward = { reward ->
                                viewModel.redeemReward(reward)
                            },
                            onDismiss = { showRewardsSheet = false }
                        )
                    }

                    if (showInviteMemberDialog && currentMember.isParent) {
                        InviteMemberDialog(
                            familyName = familyName,
                            isPrimaryAdmin = currentMember.isParent,
                            isHebrew = isHebrew,
                            onDismiss = { viewModel.closeInviteMemberDialog() },
                            onGenerateShareText = { name, role ->
                                viewModel.getDynamicInviteShareText(name, role, isHebrew)
                            },
                            onFormatInvitationShareText = { invite ->
                                viewModel.getWhatsAppInviteShareText(invite, isHebrew)
                            },
                            isCreatingInvitation = isCreatingInvitation,
                            invitationErrorMessage = invitationCreationError,
                            onCreateInvitationAsync = { name, _, role, email, grade, onConfirmed ->
                                viewModel.createRoleSpecificInvitation(
                                    invitedRole = role,
                                    invitedNickname = name,
                                    intendedEmail = email.orEmpty(),
                                    schoolGrade = grade,
                                    onCreated = onConfirmed
                                )
                            },
                            onInvite = { name, emoji, role, email, grade ->
                                viewModel.inviteMember(name, emoji, role, email, grade)
                            }
                        )
                    }

                    if (showEditFamilyNameDialog && currentMember.isParent) {
                        EditFamilyNameDialog(
                            currentFamilyName = familyName,
                            isHebrew = isHebrew,
                            onDismiss = { viewModel.closeEditFamilyNameDialog() },
                            onSaveFamilyName = { viewModel.updateFamilyName(it) }
                        )
                    }

                    if (showAddPeriodDialog && currentMember.isParent) {
                        val selectedKid = members.find { it.id == selectedChildForTimetable } ?: members.firstOrNull()
                        AddPeriodDialog(
                            childId = selectedChildForTimetable,
                            childName = selectedKid?.name ?: "",
                            isHebrew = isHebrew,
                            periodToEdit = periodToEdit,
                            onDismiss = { viewModel.closeAddPeriodDialog() },
                            onSavePeriod = { period ->
                                viewModel.saveSchedulePeriod(
                                    id = if (periodToEdit != null) period.id else null,
                                    childId = period.childId,
                                    day = period.day,
                                    periodIndex = period.periodIndex,
                                    subjectName = period.subjectName,
                                    roomNumber = period.roomNumber,
                                    teacherName = period.teacherName,
                                    startTime = period.startTime,
                                    endTime = period.endTime,
                                    colorHex = period.colorHex,
                                    tenMinuteAlertEnabled = period.tenMinuteAlertEnabled
                                )
                            }
                        )
                    }

                    if (showAddFolderDialog) {
                        AddFolderDialog(
                            isHebrew = isHebrew,
                            onDismiss = { viewModel.closeAddFolderDialog() },
                            onSaveFolder = { nameHe, nameEn, colorHex, emoji ->
                                viewModel.createNewFolder(nameHe, nameEn, emoji, colorHex)
                            }
                        )
                    }

                    if (showGoogleProfileSheet) {
                        GoogleProfileSheet(
                            familyName = familyName,
                            currentMember = currentMember,
                            allMembers = members,
                            isGoogleSignedIn = isGoogleSignedIn,
                            googleEmail = googleEmail,
                            cloudFamilyGroup = cloudFamilyGroup,
                            isHebrew = isHebrew,
                            onDismiss = { viewModel.closeGoogleProfileSheet() },
                            onSwitchMember = { viewModel.switchMember(it) },
                            onOpenInviteDialog = {
                                viewModel.closeGoogleProfileSheet()
                                viewModel.openInviteMemberDialog()
                            },
                            onOpenEditFamilyName = {
                                viewModel.closeGoogleProfileSheet()
                                viewModel.openEditFamilyNameDialog()
                            },
                            onOpenGoogleSignIn = {
                                viewModel.closeGoogleProfileSheet()
                                viewModel.openGoogleSignInDialog()
                            },
                            onSignOutGoogle = {
                                isExplicitDemoMode = false
                                viewModel.signOutGoogleAccount()
                            }
                        )
                    }

                    if (showGoogleSignInDialog) {
                        GoogleSignInDialog(
                            currentEmail = googleEmail,
                            members = members,
                            isHebrew = isHebrew,
                            onDismiss = { viewModel.closeGoogleSignInDialog() },
                            onSignInSuccess = { email ->
                                viewModel.signInWithGoogleAccount(email, email.substringBefore("@"), null)
                            },
                            onSelectMember = { memberId ->
                                members.find { it.id == memberId }?.let { viewModel.switchMember(it) }
                            }
                        )
                    }

                    if (showNotificationHistorySheet) {
                        NotificationHistorySheet(
                            notifications = notifications,
                            isHebrew = isHebrew,
                            onClearAll = { viewModel.closeNotificationHistorySheet() },
                            onDismiss = { viewModel.closeNotificationHistorySheet() }
                        )
                    }

                    if (showCameraScannerDialog) {
                        CameraScannerDialog(
                            folders = folders,
                            initialFolderId = selectedFolderId,
                            isHebrew = isHebrew,
                            onDismiss = { showCameraScannerDialog = false },
                            onUploadBinaryFile = { folderId, name, ext, bytes ->
                                viewModel.uploadBinaryFileToFamily(folderId, name, ext, bytes)
                            },
                            onConfirmScannedDocument = { folderId, name, _ ->
                                viewModel.simulateUploadFileToDrive(folderId, name, "PDF", "1.4 MB")
                            }
                        )
                    }

                    fileToPreview?.let { doc ->
                        FilePreviewDialog(
                            file = doc,
                            isHebrew = isHebrew,
                            fileBytesProvider = { docToFetch ->
                                viewModel.fetchDocumentBytes(docToFetch)
                            },
                            onDismiss = { fileToPreview = null },
                            onRename = {
                                fileToPreview = null
                                fileToRename = doc
                            },
                            onDelete = {
                                viewModel.deleteDocument(doc.id)
                                fileToPreview = null
                            },
                            onShowToast = { viewModel.showToastMessage(it) }
                        )
                    }

                    fileToRename?.let { doc ->
                        RenameFileDialog(
                            file = doc,
                            isHebrew = isHebrew,
                            onDismiss = { fileToRename = null },
                            onConfirmRename = { fileId, newName ->
                                viewModel.renameDocument(fileId, newName)
                                fileToRename = null
                            }
                        )
                    }
                    // Global Full-Screen Alarm Ringing Overlay (appears immediately over any screen if an alarm is ringing)
                    activeRingingAlarm?.let { ringing ->
                        val appCtx = androidx.compose.ui.platform.LocalContext.current
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("global_active_alarm_ringing_overlay")
                        ) {
                            com.example.notifications.FullScreenAlarmRingingContent(
                                timeFormatted = ringing.timeFormatted,
                                alarmLabel = ringing.title,
                                snoozeMinutes = ringing.snoozeMinutes,
                                vibrateEnabled = ringing.vibrateEnabled,
                                onSnooze = {
                                    com.example.notifications.FamilyAlarmRingingService.snoozeActiveAlarm(appCtx)
                                    viewModel.showToastMessage(
                                        if (isHebrew) "נודניק הופעל ל-${ringing.snoozeMinutes} דקות 💤"
                                        else "Snoozed for ${ringing.snoozeMinutes} minutes 💤"
                                    )
                                },
                                onDismiss = {
                                    com.example.notifications.FamilyAlarmRingingService.dismissActiveAlarm(appCtx, ringing.alarmId)
                                    viewModel.showToastMessage(
                                        if (isHebrew) "השעון המעורר כובה ✓"
                                        else "Alarm dismissed ✓"
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyBottomNavBar(
    currentScreen: AppScreen,
    isParent: Boolean,
    isHebrew: Boolean,
    onNavigate: (AppScreen) -> Unit
) {
    // V1.6 Requirement 1:
    // PARENT sees the Family Management tab; CHILD sees their permitted tasks, calendar, timetable, alarms, and files.
    val items = buildList {
        add(NavBarItem(AppScreen.HOME, if (isHebrew) "משימות" else "Tasks", Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle, "nav_home"))
        add(NavBarItem(AppScreen.CALENDAR, if (isHebrew) "לוח שנה" else "Calendar", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth, "nav_calendar"))
        add(NavBarItem(AppScreen.TIMETABLE, if (isHebrew) "מערכת" else "Schedule", Icons.Filled.Schedule, Icons.Outlined.Schedule, "nav_timetable"))
        add(NavBarItem(AppScreen.ALARMS, if (isHebrew) "שעון מעורר" else "Alarms", Icons.Filled.Alarm, Icons.Outlined.Alarm, "nav_alarms"))
        add(NavBarItem(AppScreen.FILES, if (isHebrew) "מסמכים" else "Files", Icons.Filled.Folder, Icons.Outlined.Folder, "nav_files"))
        if (isParent) {
            add(NavBarItem(AppScreen.ADMIN_DASHBOARD, if (isHebrew) "משפחה" else "Family", Icons.Filled.AdminPanelSettings, Icons.Outlined.AdminPanelSettings, "nav_admin"))
        }
    }

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bottom_navigation_bar")
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            val isCompactWidth = maxWidth < 380.dp || items.size >= 6
            val iconSize = if (isCompactWidth) 19.dp else 21.dp
            val labelFontSize = if (isCompactWidth) 9.5.sp else 10.5.sp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isCompactWidth) 2.dp else 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentScreen == item.screen
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onNavigate(item.screen) }
                            .background(
                                if (isSelected) GoogleBlue.copy(alpha = 0.12f) else Color.Transparent
                            )
                            .padding(horizontal = 2.dp, vertical = 5.dp)
                            .defaultMinSize(minWidth = 44.dp, minHeight = 48.dp)
                            .testTag(item.testTag)
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            tint = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(iconSize)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            fontSize = labelFontSize,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class NavBarItem(
    val screen: AppScreen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)
