package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.auth.FirebaseAuthManager
import com.example.data.FamilyRepository
import com.example.model.*
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class CalendarViewMode {
    MONTH,
    WEEK,
    DAY
}

class FamilyViewModel(
    val repository: FamilyRepository = FamilyRepository(),
    val authManager: FirebaseAuthManager = FirebaseAuthManager()
) : ViewModel() {

    fun attachApplicationContext(context: Context) {
        repository.attachApplicationContext(context)
    }

    // Core data streams from Repository
    val familyName: StateFlow<String> = repository.familyName
    val members: StateFlow<List<FamilyMember>> = repository.members
    val currentMember: StateFlow<FamilyMember> = repository.currentMember
    val tasks: StateFlow<List<FamilyTask>> = repository.tasks
    val rewards: StateFlow<List<RewardItem>> = repository.rewards
    val localRoomTasksFlow: Flow<List<Task>> = repository.localRoomTasksFlow
    val schedulePeriods: StateFlow<List<SchedulePeriod>> = repository.schedulePeriods
    val familyAlarms: StateFlow<List<FamilyAlarm>> = repository.familyAlarms
    val deletedAlarmNotices: StateFlow<List<DeletedAlarmSyncNotice>> = repository.deletedAlarmNotices
    val folders: StateFlow<List<DocumentFolder>> = repository.folders
    val documents: StateFlow<List<FamilyDocument>> = repository.documents
    val notifications: StateFlow<List<FamilyNotificationAlert>> = repository.notifications
    val activeBannerAlert: StateFlow<FamilyNotificationAlert?> = repository.activeBannerAlert

    // V1.6 Membership, Startup State Machine & Role-Specific Invitation streams
    val startupState: StateFlow<StartupState> = repository.startupState
    val membershipState: StateFlow<FamilyMembershipState> = repository.membershipState
    val pendingInvitations: StateFlow<List<FamilyInvitation>> = repository.pendingInvitations
    val taskSyncStates: StateFlow<Map<String, TaskCompletionSyncState>> = repository.taskSyncStates
    val taskOperationError: StateFlow<String?> = repository.taskOperationError
    val pointsHistory: StateFlow<List<PointsHistoryEntry>> = repository.pointsHistory
    val isProcessingPointsOperation: StateFlow<Boolean> = repository.isProcessingPointsOperation
    val pointsOperationError: StateFlow<String?> = repository.pointsOperationError
    val taskProposals: StateFlow<List<TaskCollaborationProposal>> = repository.taskProposals
    val rewardRedemptions: StateFlow<List<RewardRedemptionRequest>> = repository.rewardRedemptions
    val collaborationError: StateFlow<String?> = repository.collaborationError

    private val _validatedInvitationPreview = MutableStateFlow<FamilyInvitation?>(null)
    val validatedInvitationPreview: StateFlow<FamilyInvitation?> = _validatedInvitationPreview.asStateFlow()

    private val _invitationErrorMessage = MutableStateFlow<String?>(null)
    val invitationErrorMessage: StateFlow<String?> = _invitationErrorMessage.asStateFlow()

    private val _isProcessingFamilyAction = MutableStateFlow(false)
    val isProcessingFamilyAction: StateFlow<Boolean> = _isProcessingFamilyAction.asStateFlow()

    private val _isCreatingInvitation = MutableStateFlow(false)
    val isCreatingInvitation: StateFlow<Boolean> = _isCreatingInvitation.asStateFlow()

    private val _invitationCreationError = MutableStateFlow<String?>(null)
    val invitationCreationError: StateFlow<String?> = _invitationCreationError.asStateFlow()

    private val _lastCreatedRoleFilter = MutableStateFlow(UserRole.CHILD)
    val lastCreatedRoleFilter: StateFlow<UserRole> = _lastCreatedRoleFilter.asStateFlow()

    // Google Auth & Cloud Family Group streams
    val isGoogleSignedIn: StateFlow<Boolean> = repository.isGoogleSignedIn
    val googleUserEmail: StateFlow<String> = repository.googleUserEmail
    val googleUserDisplayName: StateFlow<String> = repository.googleUserDisplayName
    val googleUserPhotoUrl: StateFlow<String?> = repository.googleUserPhotoUrl
    val cloudFamilyGroup: StateFlow<CloudFamilyGroup> = repository.cloudFamilyGroup
    val autoSyncCalendar: StateFlow<Boolean> = repository.autoSyncCalendar
    val autoSyncDrive: StateFlow<Boolean> = repository.autoSyncDrive

    // Real Firebase Auth state streams
    val firebaseCurrentUser: StateFlow<FirebaseUser?> = authManager.currentUser
    private val _isAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating.asStateFlow()

    private val _authErrorMessage = MutableStateFlow<String?>(null)
    val authErrorMessage: StateFlow<String?> = _authErrorMessage.asStateFlow()

    // Preferences
    val appLanguage: StateFlow<AppLanguage> = repository.appLanguage
    val themeMode: StateFlow<AppThemeMode> = repository.themeMode
    val appTextScale: StateFlow<AppTextScale> = repository.appTextScale
    val firstDayOfWeek: StateFlow<FirstDayOfWeek> = repository.firstDayOfWeek
    val biometricLockEnabled: StateFlow<Boolean> = repository.biometricLockEnabled
    val isAppUnlocked: StateFlow<Boolean> = repository.isAppUnlocked
    val tenMinuteClassAlertsEnabled: StateFlow<Boolean> = repository.tenMinuteClassAlertsEnabled
    val timetableViewMode: StateFlow<TimetableViewMode> = repository.timetableViewMode
    val filesViewMode: StateFlow<FilesViewMode> = repository.filesViewMode
    val tasksViewMode: StateFlow<TasksViewMode> = repository.tasksViewMode

    // Navigation & UI States
    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _calendarMode = MutableStateFlow(CalendarViewMode.MONTH)
    val calendarMode: StateFlow<CalendarViewMode> = _calendarMode.asStateFlow()

    private val _selectedMemberFilter = MutableStateFlow<String?>(null)
    val selectedMemberFilter: StateFlow<String?> = _selectedMemberFilter.asStateFlow()

    private val _selectedChildForTimetable = MutableStateFlow("m_kid1")
    val selectedChildForTimetable: StateFlow<String> = _selectedChildForTimetable.asStateFlow()

    val selectedFolderId: StateFlow<String?> = repository.selectedFolderId
    val activeUploads: StateFlow<List<FileUploadTaskState>> = repository.activeUploads
    val fileSortOption: StateFlow<FileSortOption> = repository.fileSortOption
    val fileSortAscending: StateFlow<Boolean> = repository.fileSortAscending
    val fileSearchQuery: StateFlow<String> = repository.fileSearchQuery
    val familyDriveConfig: StateFlow<FamilyDriveConfig> = repository.familyDriveConfig
    val driveConsentPendingIntent: StateFlow<android.app.PendingIntent?> = repository.driveConsentPendingIntent
    val driveConsentIntent: StateFlow<android.content.Intent?> = repository.driveConsentIntent
    val pendingDrivePickerRequest: StateFlow<DrivePickerAuthorizationRequest?> = repository.pendingDrivePickerRequest
    val drivePickerVerificationError: StateFlow<String?> = repository.drivePickerVerificationError

    // Dialog / BottomSheet Visibility States
    private val _showAddTaskDialog = MutableStateFlow(false)
    val showAddTaskDialog: StateFlow<Boolean> = _showAddTaskDialog.asStateFlow()

    private val _taskToEdit = MutableStateFlow<FamilyTask?>(null)
    val taskToEdit: StateFlow<FamilyTask?> = _taskToEdit.asStateFlow()

    private val _showInviteMemberDialog = MutableStateFlow(false)
    val showInviteMemberDialog: StateFlow<Boolean> = _showInviteMemberDialog.asStateFlow()

    private val _showEditFamilyNameDialog = MutableStateFlow(false)
    val showEditFamilyNameDialog: StateFlow<Boolean> = _showEditFamilyNameDialog.asStateFlow()

    private val _showAddPeriodDialog = MutableStateFlow(false)
    val showAddPeriodDialog: StateFlow<Boolean> = _showAddPeriodDialog.asStateFlow()

    private val _periodToEdit = MutableStateFlow<SchedulePeriod?>(null)
    val periodToEdit: StateFlow<SchedulePeriod?> = _periodToEdit.asStateFlow()

    private val _showAddFolderDialog = MutableStateFlow(false)
    val showAddFolderDialog: StateFlow<Boolean> = _showAddFolderDialog.asStateFlow()

    private val _showGoogleProfileSheet = MutableStateFlow(false)
    val showGoogleProfileSheet: StateFlow<Boolean> = _showGoogleProfileSheet.asStateFlow()

    private val _showNotificationHistorySheet = MutableStateFlow(false)
    val showNotificationHistorySheet: StateFlow<Boolean> = _showNotificationHistorySheet.asStateFlow()

    private val _showGoogleSignInDialog = MutableStateFlow(false)
    val showGoogleSignInDialog: StateFlow<Boolean> = _showGoogleSignInDialog.asStateFlow()

    // AI Assistant States
    private val _aiMessages = MutableStateFlow<List<Pair<Boolean, String>>>(
        listOf(
            false to "שלום! אני העוזר המשפחתי החכם (מופעל ב-Gemini AI) ✨\n" +
                "אני מחובר ליומן המשפחתי, למערכת השעות ולתיקיית ה-Google Drive שלכם. איך אפשר לעזור היום?"
        )
    )
    val aiMessages: StateFlow<List<Pair<Boolean, String>>> = _aiMessages.asStateFlow()

    private val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.taskOperationError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _snackbarMessage.value = err
                }
            }
        }
        viewModelScope.launch {
            repository.driveOperationMessage.collect { msg ->
                if (!msg.isNullOrBlank()) {
                    _snackbarMessage.value = msg
                    repository.clearDriveOperationMessage()
                }
            }
        }
        viewModelScope.launch {
            repository.pointsOperationError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _snackbarMessage.value = err
                }
            }
        }
        viewModelScope.launch {
            repository.collaborationError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _snackbarMessage.value = err
                }
            }
        }
    }

    /**
     * Restores an existing FirebaseAuth session on application launch without restarting
     * interactive Google Sign-In or overwriting existing Firestore profile/family fields.
     */
    fun restoreExistingGoogleSession() {
        val user = firebaseCurrentUser.value
        com.example.util.FirestoreErrorHandler.logStartupMilestone(
            stage = com.example.util.FirestoreErrorHandler.StartupStage.RESTORE_AUTH_SESSION,
            collectionPath = "auth",
            databaseId = repository.configuredDatabaseId,
            note = if (user != null) "Restoring existing FirebaseAuth session" else "No existing FirebaseAuth session found"
        )
        if (user != null) {
            repository.onGoogleSignInSuccess(
                email = user.email.orEmpty(),
                displayName = user.displayName.orEmpty(),
                photoUrl = user.photoUrl?.toString()
            )
        }
    }

    /**
     * Retries loading the authenticated user's profile and family membership from Firestore
     * after a transient network error without resetting any profile or family data.
     */
    fun retryStartupLoading() {
        viewModelScope.launch {
            repository.retryStartupLoading()
        }
    }

    // --- Navigation Actions ---

    fun navigateTo(screen: AppScreen) {
        if ((screen == AppScreen.ADMIN_DASHBOARD || screen == AppScreen.POINTS_MANAGEMENT) && !currentMember.value.isParent) {
            _snackbarMessage.value = if (screen == AppScreen.POINTS_MANAGEMENT) {
                "גישה לניהול נקודות משפחתי מותרת להורים בלבד 🔒"
            } else {
                "גישה לניהול המשפחה מותרת להורים בלבד 🔒"
            }
            return
        }
        _currentScreen.value = screen
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun setCalendarViewMode(mode: CalendarViewMode) {
        _calendarMode.value = mode
    }

    fun setMemberFilter(memberId: String?) {
        _selectedMemberFilter.value = memberId
    }

    fun selectChildForTimetable(childId: String) {
        _selectedChildForTimetable.value = childId
    }

    fun selectFolder(folderId: String?) {
        repository.selectFolder(folderId)
    }

    fun navigateUpFolder(): Boolean {
        return repository.navigateUpFolder()
    }

    fun setFileSortOption(option: FileSortOption) {
        repository.setFileSortOption(option)
    }

    fun setFileSearchQuery(query: String) {
        repository.setFileSearchQuery(query)
    }

    // --- Dialog & Sheet Controls ---

    fun openAddTaskDialog(task: FamilyTask? = null) {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים ליצור או לערוך משימות משפחתיות 🔒"
            return
        }
        val authoritativeTask = task?.let { requested ->
            tasks.value.find { it.id == requested.id } ?: requested
        }
        _taskToEdit.value = authoritativeTask
        _showAddTaskDialog.value = true
    }

    fun closeAddTaskDialog() {
        _showAddTaskDialog.value = false
        _taskToEdit.value = null
    }

    fun openInviteMemberDialog() {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים להזמין חברים למשפחה 🔒"
            return
        }
        _invitationCreationError.value = null
        _showInviteMemberDialog.value = true
    }

    fun closeInviteMemberDialog() {
        _invitationCreationError.value = null
        _showInviteMemberDialog.value = false
    }

    fun clearInvitationCreationError() {
        _invitationCreationError.value = null
    }

    fun openEditFamilyNameDialog() {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים לשנות את שם המשפחה 🔒"
            return
        }
        _showEditFamilyNameDialog.value = true
    }

    fun closeEditFamilyNameDialog() {
        _showEditFamilyNameDialog.value = false
    }

    fun openAddPeriodDialog(period: SchedulePeriod? = null) {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים לערוך את מערכת השעות 🔒"
            return
        }
        _periodToEdit.value = period
        _showAddPeriodDialog.value = true
    }

    fun closeAddPeriodDialog() {
        _showAddPeriodDialog.value = false
        _periodToEdit.value = null
    }

    fun openAddFolderDialog() {
        _showAddFolderDialog.value = true
    }

    fun closeAddFolderDialog() {
        _showAddFolderDialog.value = false
    }

    fun openGoogleProfileSheet() {
        _showGoogleProfileSheet.value = true
    }

    fun closeGoogleProfileSheet() {
        _showGoogleProfileSheet.value = false
    }

    fun openNotificationHistorySheet() {
        _showNotificationHistorySheet.value = true
        repository.markAllNotificationsRead()
    }

    fun closeNotificationHistorySheet() {
        _showNotificationHistorySheet.value = false
    }

    fun openGoogleSignInDialog() {
        _showGoogleSignInDialog.value = true
    }

    fun closeGoogleSignInDialog() {
        _showGoogleSignInDialog.value = false
    }

    // --- Family & Google Account Operations (V1.6) ---

    fun updateFamilyName(newName: String) {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים רשאים לעדכן את שם המשפחה 🔒"
            return
        }
        repository.updateFamilyName(newName)
        _showEditFamilyNameDialog.value = false
        _snackbarMessage.value = "שם המשפחה עודכן ל-\"$newName\" וסונכרן לענן ✓"
    }

    /**
     * Updates the current user's personal profile (`name`, `familyIdentity` such as FATHER or MOTHER,
     * and `avatarEmoji`) and persists it to Firestore without changing their authorization role.
     */
    fun updateCurrentUserProfile(
        newName: String? = null,
        newIdentity: FamilyIdentity? = null,
        newAvatarEmoji: String? = null
    ) {
        val ok = repository.updateCurrentUserProfile(
            newName = newName ?: currentMember.value.name,
            newIdentity = newIdentity,
            newAvatarEmoji = newAvatarEmoji
        )
        if (ok) {
            val member = currentMember.value
            val isHe = appLanguage.value == AppLanguage.HEBREW
            val idLabel = member.familyIdentity.getDisplayName(isHe)
            _snackbarMessage.value = if (isHe) {
                "הפרופיל האישי עודכן (${member.name} • $idLabel) ונשמר בענן ✓"
            } else {
                "Profile updated (${member.name} • $idLabel) and saved to cloud ✓"
            }
        }
    }

    /**
     * Launches Google Sign-In via Jetpack Credential Manager + Firebase Auth.
     * V1.6: Does NOT automatically create a family or assign PARENT.
     * Instead, `repository.onGoogleSignInSuccess` checks Firestore membership and either
     * restores the user's validated family/role or transitions to `FamilyMembershipState.NoMembership`.
     */
    fun launchRealGoogleSignIn(
        context: Context,
        familyGroupName: String? = null,
        onAuthenticated: ((email: String, displayName: String, photoUrl: String?) -> Unit)? = null
    ) {
        _isAuthenticating.value = true
        _authErrorMessage.value = null
        viewModelScope.launch {
            val result = authManager.signInWithGoogle(context)
            _isAuthenticating.value = false
            result.onSuccess { user ->
                val email = user.email ?: "user@gmail.com"
                val name = user.displayName ?: email.substringBefore("@")
                val photoUrl = user.photoUrl?.toString()
                repository.onGoogleSignInSuccess(email, name, familyGroupName, photoUrl)
                _showGoogleSignInDialog.value = false
                onAuthenticated?.invoke(email, name, photoUrl)
            }.onFailure { error ->
                val msg = error.localizedMessage ?: "ההתחברות לחשבון Google נכשלה."
                _authErrorMessage.value = msg
                _snackbarMessage.value = msg
            }
        }
    }

    fun clearAuthError() {
        _authErrorMessage.value = null
    }

    fun signInWithGoogleAccount(email: String, displayName: String, familyGroupName: String? = null) {
        repository.onGoogleSignInSuccess(email, displayName, familyGroupName)
        _showGoogleSignInDialog.value = false
    }

    /**
     * V1.6 Requirement 3:
     * Called when an authenticated user with no family explicitly chooses "Create a family".
     * Creates the family in Firestore, assigns the creator the PARENT authorization role,
     * and persists their selected [FamilyIdentity] (`FATHER` or `MOTHER`).
     */
    fun createNewFamilyOnFirstLogin(
        familyNameInput: String,
        parentDisplayName: String,
        familyIdentity: FamilyIdentity = FamilyIdentity.FATHER,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        _isProcessingFamilyAction.value = true
        _invitationErrorMessage.value = null
        viewModelScope.launch {
            val result = repository.createFamilyAsParent(
                familyNameInput = familyNameInput,
                parentDisplayName = parentDisplayName,
                familyIdentity = familyIdentity
            )
            _isProcessingFamilyAction.value = false
            result.onSuccess { active ->
                _currentScreen.value = AppScreen.HOME
                val identityLabel = active.familyIdentity.labelHe
                _snackbarMessage.value = "ברוכים הבאים! משפחת \"${active.familyName}\" נוצרה בהצלחה ($identityLabel • הורה) 🎉"
                onComplete?.invoke(true)
            }.onFailure { err ->
                _invitationErrorMessage.value = err.localizedMessage ?: "יצירת המשפחה נכשלה. נסו שוב."
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * V1.6 Requirement 5 (Step 1 of Joining Flow):
     * Validates an invitation code entered by the user and exposes `validatedInvitationPreview`
     * showing the family name and the immutable invited role (`PARENT` or `CHILD`).
     */
    fun validateInviteCodeForJoining(rawCode: String) {
        _invitationErrorMessage.value = null
        _validatedInvitationPreview.value = null
        val clean = rawCode.trim().uppercase()
        if (clean.isBlank()) {
            _invitationErrorMessage.value = "נא להזין קוד הזמנה."
            return
        }
        _isProcessingFamilyAction.value = true
        viewModelScope.launch {
            when (val res = repository.validateInvitationCode(clean)) {
                is InvitationValidationResult.Valid -> {
                    _isProcessingFamilyAction.value = false
                    _validatedInvitationPreview.value = res.invitation
                }
                is InvitationValidationResult.Invalid -> {
                    _isProcessingFamilyAction.value = false
                    _invitationErrorMessage.value = res.messageHe
                }
            }
        }
    }

    fun clearValidatedInvitationPreview() {
        _validatedInvitationPreview.value = null
        _invitationErrorMessage.value = null
    }

    /**
     * V1.6 Requirement 5 (Step 2 of Joining Flow):
     * Confirms joining the family using the validated invitation code.
     * Creates membership with the exact role specified by the invitation (`CHILD` or `PARENT`),
     * preserves the user's [FamilyIdentity] (`FATHER`/`MOTHER` for parents),
     * and marks the invitation as redeemed.
     */
    fun confirmJoinFamilyWithValidatedInvite(
        rawCode: String,
        memberDisplayName: String,
        familyIdentity: FamilyIdentity? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        _isProcessingFamilyAction.value = true
        _invitationErrorMessage.value = null
        viewModelScope.launch {
            val result = repository.redeemInvitationAndJoinFamily(
                rawCode = rawCode,
                memberDisplayName = memberDisplayName,
                familyIdentity = familyIdentity
            )
            _isProcessingFamilyAction.value = false
            result.onSuccess { active ->
                _validatedInvitationPreview.value = null
                _currentScreen.value = AppScreen.HOME
                val roleLabel = if (active.role == UserRole.PARENT) {
                    "${active.familyIdentity.labelHe} (הורה)"
                } else {
                    "ילד/ה (חבר משפחה)"
                }
                _snackbarMessage.value = "הצטרפת בהצלחה למשפחת \"${active.familyName}\" בתפקיד $roleLabel! 🎉"
                onComplete?.invoke(true)
            }.onFailure { err ->
                _invitationErrorMessage.value = err.localizedMessage ?: "ההצטרפות למשפחה נכשלה. בדקו את הקוד ונסו שוב."
                onComplete?.invoke(false)
            }
        }
    }

    fun startNewCleanFamily(
        familyNameInput: String,
        adminNameInput: String,
        emailInput: String,
        familyIdentity: FamilyIdentity = FamilyIdentity.FATHER
    ) {
        repository.initializeCleanFamily(familyNameInput, adminNameInput, emailInput, familyIdentity)
        _currentScreen.value = AppScreen.HOME
        _snackbarMessage.value = "ברוכים הבאים! משפחת \"$familyNameInput\" נוצרה בהצלחה 🎉"
    }

    fun joinCloudFamilyByInviteCode(
        inviteCode: String,
        memberName: String,
        memberEmail: String,
        familyIdentity: FamilyIdentity? = null
    ): Boolean {
        val ok = repository.joinExistingCloudFamilyByCode(inviteCode, memberName, memberEmail, familyIdentity)
        if (ok) {
            _showGoogleSignInDialog.value = false
            _currentScreen.value = AppScreen.HOME
            _snackbarMessage.value = "הצטרפת למשפחה בענן בהצלחה! (קוד: ${inviteCode.uppercase()}) 🎉"
        } else {
            _snackbarMessage.value = "קוד ההזמנה אינו תקף או שפג תוקפו"
        }
        return ok
    }

    fun signOutGoogleAccount() {
        authManager.signOut()
        repository.signOutGoogle()
        _validatedInvitationPreview.value = null
        _invitationErrorMessage.value = null
        _showGoogleProfileSheet.value = false
        _snackbarMessage.value = "התנתקת מחשבון Google"
    }

    /**
     * Switches task display filter / view mode for [member] without stripping the current user's
     * role or permissions (`currentMember.role` / `currentMember.isParent`) and without hiding
     * the Family Management ("ניהול המשפחה") section.
     */
    fun switchMember(member: FamilyMember) {
        val nextFilter = if (_selectedMemberFilter.value == member.id) null else member.id
        _selectedMemberFilter.value = nextFilter
        repository.toggleTasksViewMode()
        val isHe = appLanguage.value == AppLanguage.HEBREW
        _snackbarMessage.value = if (nextFilter != null) {
            if (isHe) "סינון תצוגת משימות: ${member.name} (הרשאות הורה נשמרות)"
            else "Tasks filtered for: ${member.name} (parent permissions preserved)"
        } else {
            if (isHe) "תצוגת כל משימות המשפחה"
            else "Showing all family tasks"
        }
    }

    fun setTasksViewMode(mode: TasksViewMode) {
        repository.setTasksViewMode(mode)
    }

    fun toggleTasksViewMode(): TasksViewMode {
        val next = repository.toggleTasksViewMode()
        val isHe = appLanguage.value == AppLanguage.HEBREW
        _snackbarMessage.value = if (isHe) {
            if (next == TasksViewMode.COMPACT) "תצוגת משימות קומפקטית הופעלה ✨"
            else "תצוגת משימות רגילה הופעלה ✨"
        } else {
            if (next == TasksViewMode.COMPACT) "Compact tasks view enabled ✨"
            else "Standard tasks view enabled ✨"
        }
        return next
    }

    /**
     * V1.6 Requirement 4:
     * Generates a role-specific invitation (`PARENT` or `CHILD`) and persists it in Firestore.
     * Never exposes or shares a code until Firestore confirms that `/family_invites/{code}` was committed.
     */
    fun createRoleSpecificInvitation(
        invitedRole: UserRole,
        invitedNickname: String = "",
        intendedEmail: String = "",
        schoolGrade: String? = null,
        onCreated: ((FamilyInvitation) -> Unit)? = null
    ) {
        if (!currentMember.value.isParent) {
            val err = "רק הורה רשאי ליצור הזמנות למשפחה 🔒"
            _invitationCreationError.value = err
            _snackbarMessage.value = err
            return
        }
        _lastCreatedRoleFilter.value = invitedRole
        _isCreatingInvitation.value = true
        _invitationCreationError.value = null
        viewModelScope.launch {
            val result = repository.createRoleSpecificInvitation(
                invitedRole = invitedRole,
                invitedNickname = invitedNickname,
                intendedEmail = intendedEmail,
                schoolGrade = schoolGrade
            )
            _isCreatingInvitation.value = false
            result.onSuccess { invitation ->
                _invitationCreationError.value = null
                val roleHe = if (invitation.invitedRole == UserRole.PARENT) "הורה" else "ילד/ה"
                _snackbarMessage.value = "נוצר ונשמר בענן קוד הזמנה (${invitation.code}) לתפקיד $roleHe 🔑"
                onCreated?.invoke(invitation)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "שגיאה בשמירת קוד ההזמנה בענן. נסו שוב."
                _invitationCreationError.value = msg
                _snackbarMessage.value = msg
            }
        }
    }

    fun retryLastRoleInvitation(onCreated: ((FamilyInvitation) -> Unit)? = null) {
        createRoleSpecificInvitation(
            invitedRole = _lastCreatedRoleFilter.value,
            onCreated = onCreated
        )
    }

    fun inviteMember(
        name: String,
        emoji: String,
        role: UserRole,
        googleEmail: String?,
        schoolGrade: String?
    ): FamilyInvitation? {
        val created = repository.inviteNewMember(name, emoji, role, googleEmail, schoolGrade)
        if (created == null) {
            val errMsg = repository.lastClassifiedInvitationFailure?.messageHe
                ?: if (!currentMember.value.isParent) {
                    "רק הורה רשאי להזמין חברים למשפחה 🔒"
                } else {
                    "שגיאה בשמירת קוד ההזמנה בענן. נסו שוב."
                }
            _invitationCreationError.value = errMsg
            _snackbarMessage.value = errMsg
        } else {
            _invitationCreationError.value = null
            val roleHe = if (created.invitedRole == UserRole.PARENT) "הורה" else "ילד/ה"
            _snackbarMessage.value = "נוצרה הזמנה (${created.code}) עבור ${name.ifBlank { roleHe }} בתפקיד $roleHe ✉️"
        }
        return created
    }

    fun revokePendingInvitation(code: String) {
        if (repository.revokeInvitation(code)) {
            _snackbarMessage.value = "קוד ההזמנה $code בוטל ✓"
        } else {
            _snackbarMessage.value = "רק הורה רשאי לבטל הזמנות 🔒"
        }
    }

    fun acceptInvitation(memberIdOrCode: String) {
        repository.acceptMemberInvitation(memberIdOrCode)
        _snackbarMessage.value = "ההזמנה אושרה! החבר צורף למשפחה 🎉"
    }

    fun getWhatsAppInviteShareText(invitation: FamilyInvitation, isHebrew: Boolean): String {
        return repository.getWhatsAppInviteShareText(invitation, isHebrew)
    }

    fun getDynamicInviteShareText(memberName: String, role: UserRole, isHebrew: Boolean): String {
        return repository.getDynamicInviteShareText(memberName, role, isHebrew)
    }

    fun changeMemberRole(memberId: String, newRole: UserRole) {
        val ok = repository.changeMemberRole(memberId, newRole)
        if (ok) {
            val roleHe = if (newRole.isParentRole) "הורה (מנהל משפחה)" else "ילד/ה (חבר משפחה)"
            _snackbarMessage.value = "התפקיד עודכן ל-$roleHe ✓"
        } else {
            _snackbarMessage.value = "לא ניתן לשנות תפקיד להורה האחרון במשפחה (חייב להישאר לפחות הורה אחד) ⚠️"
        }
    }

    fun removeMember(memberId: String) {
        val ok = repository.removeFamilyMember(memberId)
        if (ok) {
            _snackbarMessage.value = "חבר המשפחה הוסר מהקבוצה"
        } else {
            _snackbarMessage.value = "לא ניתן להסיר את ההורה האחרון במשפחה ⚠️"
        }
    }

    // --- Task Operations ---

    fun toggleTask(taskId: String) {
        val ok = repository.toggleTaskCompletion(taskId)
        if (!ok) {
            _snackbarMessage.value = repository.taskOperationError.value
                ?: "ילדים יכולים לסמן כבוצע רק משימות שמשויכות אליהם 🔒"
        }
    }

    fun approveTaskCompletion(taskId: String) {
        if (!currentMember.value.isParent) {
            val err = "רק הורה רשאי לאשר השלמת משימות 🔒"
            _snackbarMessage.value = err
            return
        }
        val target = tasks.value.find { it.id == taskId }
        val ok = repository.approveTaskCompletion(taskId)
        if (ok) {
            val pts = target?.points ?: 0
            _snackbarMessage.value = "השלמת המשימה אושרה! +$pts נקודות הוענקו ✓"
        } else {
            _snackbarMessage.value = repository.taskOperationError.value
                ?: "שגיאה באישור המשימה מול הענן. נסו שוב."
        }
    }

    fun clearTaskOperationError() {
        repository.clearTaskOperationError()
    }

    fun resyncTaskRemindersForActiveMember() {
        repository.resyncTaskRemindersForActiveMember()
    }

    fun saveTask(
        id: String?,
        title: String,
        description: String,
        category: TaskCategory,
        assignedToMemberId: String,
        dateEpochDay: Long,
        dueTimeFormatted: String,
        priority: TaskPriority,
        recurrence: TaskRecurrence,
        points: Int,
        hasDueTime: Boolean = false,
        reminderOffset: TaskReminderOffset = TaskReminderOffset.NONE,
        isOpenForVolunteering: Boolean = false
    ) {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים ליצור או לערוך משימות 🔒"
            return
        }
        val effectiveHasDueTime = hasDueTime || reminderOffset != TaskReminderOffset.NONE
        val effectiveReminderOffset = if (effectiveHasDueTime) reminderOffset else TaskReminderOffset.NONE
        val effectiveReminderEnabled = effectiveHasDueTime && effectiveReminderOffset != TaskReminderOffset.NONE
        val effectiveAssignee = if (isOpenForVolunteering) "" else assignedToMemberId
        val nowMillis = System.currentTimeMillis()
        if (id == null) {
            val newTask = FamilyTask(
                title = title,
                description = description,
                category = category,
                assignedToMemberId = effectiveAssignee,
                isOpenForVolunteering = isOpenForVolunteering,
                dateEpochDay = dateEpochDay,
                dueTimeFormatted = dueTimeFormatted,
                hasDueTime = effectiveHasDueTime,
                reminderOffset = effectiveReminderOffset,
                reminderEnabled = effectiveReminderEnabled,
                priority = priority,
                recurrence = recurrence,
                points = points,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis
            )
            repository.addTask(newTask)
            _snackbarMessage.value = if (isOpenForVolunteering) {
                "משימה פתוחה להתנדבות פורסמה לכל הילדים 📢"
            } else {
                "משימה חדשה נוספה וסונכרנה ליומן Google ✓"
            }
        } else {
            val existing = tasks.value.find { it.id == id }
            if (existing != null) {
                repository.updateTask(
                    existing.copy(
                        title = title,
                        description = description,
                        category = category,
                        assignedToMemberId = effectiveAssignee,
                        isOpenForVolunteering = isOpenForVolunteering,
                        dateEpochDay = dateEpochDay,
                        dueTimeFormatted = dueTimeFormatted,
                        hasDueTime = effectiveHasDueTime,
                        reminderOffset = effectiveReminderOffset,
                        reminderEnabled = effectiveReminderEnabled,
                        priority = priority,
                        recurrence = recurrence,
                        points = points,
                        updatedAtMillis = nowMillis
                    )
                )
                _snackbarMessage.value = "המשימה עודכנה בהצלחה ✓"
            }
        }
        closeAddTaskDialog()
    }

    fun deleteTask(taskId: String) {
        if (!repository.deleteTask(taskId)) {
            _snackbarMessage.value = "רק הורים יכולים למחוק משימות 🔒"
            return
        }
        _snackbarMessage.value = "✅ המשימה בוטלה"
    }

    fun redeemReward(reward: RewardItem) {
        val ok = repository.requestRewardRedemption(reward.id)
        if (ok) {
            _snackbarMessage.value = "בקשה למימוש הפרס \"${reward.title}\" נשלחה לאישור הורה! 🎁"
        } else {
            _snackbarMessage.value = "אין מספיק נקודות למימוש הפרס עדיין 💪"
        }
    }

    fun sendParentNudge(task: FamilyTask) {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים לשלוח תזכורת לביצוע משימה 🔒"
            return
        }
        repository.sendParentNudgeForTask(task)
        _snackbarMessage.value = "נשלחה תזכורת להתראה במכשיר! 👋"
    }

    // --- School Timetable Operations ---

    fun saveSchedulePeriod(
        id: String?,
        childId: String,
        day: SchoolDay,
        periodIndex: Int,
        subjectName: String,
        roomNumber: String,
        teacherName: String,
        startTime: String,
        endTime: String,
        colorHex: Long,
        tenMinuteAlertEnabled: Boolean
    ) {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים לערוך את מערכת השעות 🔒"
            return
        }
        val period = SchedulePeriod(
            id = id ?: "p_${System.currentTimeMillis()}",
            childId = childId,
            day = day,
            periodIndex = periodIndex,
            subjectName = subjectName,
            roomNumber = roomNumber,
            teacherName = teacherName,
            startTime = startTime,
            endTime = endTime,
            colorHex = colorHex,
            tenMinuteAlertEnabled = tenMinuteAlertEnabled
        )
        if (id == null) {
            repository.addSchedulePeriod(period)
            _snackbarMessage.value = "שיעור חדש נוסף למערכת השעות (כולל התראת 10 דק') 🔔"
        } else {
            repository.updateSchedulePeriod(period)
            _snackbarMessage.value = "השיעור עודכן במערכת השעות ✓"
        }
        closeAddPeriodDialog()
    }

    fun deleteSchedulePeriod(periodId: String) {
        if (repository.deleteSchedulePeriod(periodId)) {
            _snackbarMessage.value = "השיעור הוסר ממערכת השעות"
        }
    }

    fun testTenMinuteAlert(period: SchedulePeriod, childName: String) {
        repository.triggerTenMinuteClassAlertPreview(period, childName)
    }

    // --- Family Alarm Clock Operations ---

    fun saveFamilyAlarm(alarm: FamilyAlarm) {
        if (repository.addOrUpdateFamilyAlarm(alarm)) {
            _snackbarMessage.value = "שעון מעורר משפחתי נשמר (${alarm.formattedTime24h}) ⏰"
        }
    }

    fun toggleFamilyAlarm(alarmId: String) {
        repository.toggleFamilyAlarm(alarmId)
    }

    fun deleteFamilyAlarm(alarmId: String) {
        val target = familyAlarms.value.find { it.id == alarmId }
        val isRemoteChildTarget = target != null &&
            target.isTargetedToAnotherMember() &&
            !target.isTargetedAtDeviceMember(currentMember.value.id)
        if (repository.deleteFamilyAlarm(alarmId)) {
            _snackbarMessage.value = if (isRemoteChildTarget) {
                "השעון המעורר נמחק מהענן — ממתין לאישור ביטול ממכשיר היעד ☁️"
            } else {
                "השעון המעורר נמחק ובוטל ממכשיר זה ✓"
            }
        }
    }

    fun dismissDeletedAlarmNotice(alarmId: String) {
        repository.dismissDeletedAlarmNotice(alarmId)
    }

    fun resyncAlarmsForActiveMember() {
        repository.resyncAlarmsForActiveMember()
    }

    // --- Shared Family Folders & Files Operations (Google Drive FamilyTasks) ---

    fun setupOrReconnectFamilyGoogleDrive() {
        repository.setupOrReconnectFamilyGoogleDrive { res ->
            res.onSuccess { cfg ->
                if (cfg.isConfigured) {
                    _snackbarMessage.value = "תיקיית Google Drive המשפחתית (${cfg.rootFolderName}) מחוברת ומסונכרנת ✓"
                } else if (cfg.connectionStatus == DriveConnectionStatus.WAITING_FOR_PARENT_SETUP) {
                    _snackbarMessage.value = cfg.errorMessageHe ?: "ממתין שהורה יגדיר את תיקיית FamilyTasks ב-Google Drive."
                }
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "חיבור ל-Google Drive נכשל."
            }
        }
    }

    fun clearDriveConsentPrompts() {
        repository.clearDriveConsentPrompts()
    }

    fun requestSharedFolderPickerAuthorization() {
        repository.requestSharedFolderPickerAuthorization()
    }

    fun dismissDrivePickerAuthorizationDialog() {
        repository.dismissDrivePickerAuthorizationDialog()
    }

    fun verifyAndConnectPickedSharedFolder(pickedDriveId: String, pickedName: String) {
        repository.verifyAndConnectPickedSharedFolder(pickedDriveId, pickedName) { res ->
            res.onSuccess { cfg ->
                _snackbarMessage.value = "תיקיית המשפחה המשותפת (${cfg.rootFolderName}) אומתה וחוברה בהצלחה ✓"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "התיקייה שנבחרה אינה תואמת לתיקיית המשפחה המשותפת."
            }
        }
    }

    fun shareFamilyDriveWithMember(memberId: String, email: String) {
        repository.shareFamilyDriveWithMember(memberId, email) { res ->
            res.onSuccess {
                _snackbarMessage.value = "תיקיית FamilyTasks שותפה בהצלחה עם $email ✓"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "שיתוף התיקייה נכשל."
            }
        }
    }

    fun shareFamilyDriveWithAllMembers() {
        repository.shareFamilyDriveWithAllMembers { res ->
            res.onSuccess {
                _snackbarMessage.value = "תיקיית FamilyTasks שותפה עם כל בני המשפחה ב-Google Drive ✓"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "שיתוף התיקייה נכשל."
            }
        }
    }

    fun updateMemberGoogleEmail(memberId: String, email: String) {
        if (repository.updateMemberGoogleEmail(memberId, email)) {
            _snackbarMessage.value = "כתובת ה-Google עודכנה ל-$email ✓"
        }
    }

    fun createNewFolder(
        nameHe: String,
        nameEn: String,
        emoji: String,
        colorHex: Long,
        parentFolderId: String? = selectedFolderId.value
    ) {
        val folder = DocumentFolder(
            nameHe = nameHe,
            nameEn = nameEn.ifBlank { nameHe },
            iconEmoji = emoji,
            colorHex = colorHex,
            familyId = repository.activeFamilyId.value,
            parentFolderId = parentFolderId,
            createdByUid = currentMember.value.id,
            createdByName = currentMember.value.name
        )
        repository.addFolder(folder)
        closeAddFolderDialog()
        _snackbarMessage.value = "תיקייה חדשה נוצרה בתיקיית FamilyTasks המשותפת 📁"
    }

    fun renameSharedFolder(folderId: String, newNameHe: String) {
        if (repository.renameFolder(folderId, newNameHe, newNameHe)) {
            _snackbarMessage.value = "שם התיקייה עודכן ל-\"$newNameHe\" ב-Google Drive ✓"
        } else {
            _snackbarMessage.value = "נא להזין שם תיקייה תקין"
        }
    }

    fun moveSharedFolder(folderId: String, newParentFolderId: String?) {
        if (repository.moveFolderToParent(folderId, newParentFolderId)) {
            _snackbarMessage.value = "התיקייה הועברה בהצלחה ב-Google Drive ✓"
        }
    }

    fun deleteSharedFolder(folderId: String) {
        if (repository.deleteFolder(folderId)) {
            _snackbarMessage.value = "התיקייה המשותפת הוסרה מ-Google Drive 🗑️"
        }
    }

    fun uploadBinaryFileToFamily(
        folderId: String?,
        fileName: String,
        extension: String,
        bytes: ByteArray,
        thumbnailBase64: String? = null
    ): String {
        val targetFolder = folderId?.takeIf { it.isNotBlank() }
            ?: selectedFolderId.value.orEmpty()
        return repository.startFileUpload(
            folderId = targetFolder,
            rawFileName = fileName,
            rawExtension = extension,
            rawBytes = bytes,
            thumbnailBase64 = thumbnailBase64
        ) { res ->
            res.onSuccess { doc ->
                _snackbarMessage.value = "הקובץ \"${doc.name}\" הועלה לתיקיית FamilyTasks ב-Google Drive ✓"
            }.onFailure { err ->
                if (err !is kotlinx.coroutines.CancellationException) {
                    _snackbarMessage.value = err.localizedMessage ?: "העלאת הקובץ ל-Google Drive נכשלה. ניתן לנסות שוב."
                }
            }
        }
    }

    fun simulateUploadFileToDrive(folderId: String, fileName: String, extension: String, sizeFormatted: String) {
        val targetFolder = folderId.ifBlank { selectedFolderId.value.orEmpty() }
        val ext = com.example.files.FamilyFileManagerHelper.extractExtension(fileName, extension)
        val payloadBytes = when {
            ext == "PDF" -> com.example.files.FamilyFileManagerHelper.generateDocumentPdfBytes(
                title = fileName,
                uploaderName = currentMember.value.name,
                dateFormatted = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                bodyLines = listOf("קובץ משפחתי משותף: $fileName", "גודל: $sizeFormatted")
            )
            ext in setOf("JPG", "JPEG", "PNG", "WEBP") ->
                com.example.files.FamilyFileManagerHelper.generateSampleImageBytes(fileName, currentMember.value.name)
            else -> "קובץ משפחתי משותף: $fileName ($sizeFormatted)".toByteArray(Charsets.UTF_8)
        }
        uploadBinaryFileToFamily(
            folderId = targetFolder,
            fileName = fileName,
            extension = ext,
            bytes = payloadBytes
        )
    }

    fun cancelFileUpload(uploadId: String) {
        repository.cancelFileUpload(uploadId)
        _snackbarMessage.value = "העלאת הקובץ בוטלה"
    }

    fun retryFileUpload(uploadId: String) {
        repository.retryFileUpload(uploadId) { res ->
            res.onSuccess { doc ->
                _snackbarMessage.value = "הקובץ \"${doc.name}\" הועלה ל-Google Drive בהצלחה ✓"
            }.onFailure { err ->
                _snackbarMessage.value = err.localizedMessage ?: "העלאת הקובץ נכשלה שוב"
            }
        }
    }

    fun dismissUploadTaskState(uploadId: String) {
        repository.dismissUploadTaskState(uploadId)
    }

    fun renameDocument(documentId: String, newName: String) {
        if (repository.renameDocument(documentId, newName)) {
            _snackbarMessage.value = "שם הקובץ עודכן ל-\"$newName\" ב-Google Drive ✓"
        }
    }

    fun moveDocumentToFolder(documentId: String, targetFolderId: String) {
        if (repository.moveDocumentToFolder(documentId, targetFolderId)) {
            val folderName = if (targetFolderId.isBlank()) {
                "תיקיית FamilyTasks הראשית"
            } else {
                folders.value.find { it.id == targetFolderId }?.nameHe ?: "תיקייה"
            }
            _snackbarMessage.value = "הקובץ הועבר אל \"$folderName\" ב-Google Drive ✓"
        }
    }

    fun deleteDocument(documentId: String) {
        if (repository.deleteDocument(documentId)) {
            _snackbarMessage.value = "הקובץ הוסר מתיקיית FamilyTasks ב-Google Drive 🗑️"
        }
    }

    suspend fun fetchDocumentBytes(doc: FamilyDocument): ByteArray? {
        return repository.fetchDocumentBytes(doc)
    }

    fun showCustomSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    // --- Notification Banner Controls ---

    fun dismissBannerAlert() {
        repository.dismissActiveBanner()
    }

    // --- Settings & Preferences ---

    fun setLanguage(lang: AppLanguage) {
        repository.setLanguage(lang)
    }

    fun setThemeMode(mode: AppThemeMode) {
        repository.setThemeMode(mode)
    }

    fun setAppTextScale(scale: AppTextScale) {
        repository.setAppTextScale(scale)
    }

    fun setFirstDayOfWeek(day: FirstDayOfWeek) {
        repository.setFirstDayOfWeek(day)
    }

    fun setTimetableViewMode(mode: TimetableViewMode) {
        repository.setTimetableViewMode(mode)
    }

    fun setFilesViewMode(mode: FilesViewMode) {
        repository.setFilesViewMode(mode)
    }

    fun setBiometricLock(enabled: Boolean) {
        repository.setBiometricLock(enabled)
    }

    fun unlockApp() {
        repository.unlockAppSession()
    }

    fun lockApp() {
        repository.lockAppSession()
    }

    fun setTenMinuteClassAlerts(enabled: Boolean) {
        repository.setTenMinuteClassAlerts(enabled)
    }

    fun setAutoSyncCalendar(enabled: Boolean) {
        repository.setAutoSyncCalendar(enabled)
    }

    fun setAutoSyncDrive(enabled: Boolean) {
        repository.setAutoSyncDrive(enabled)
    }

    // --- Gemini AI Smart Assistant ---

    fun askGeminiAssistant(prompt: String) {
        if (prompt.isBlank()) return
        _aiMessages.value = _aiMessages.value + (true to prompt)
        _isAiGenerating.value = true

        val lower = prompt.lowercase()
        val pendingCount = tasks.value.count { !it.isCompleted }
        val kidsNames = members.value.filter { it.role == UserRole.CHILD }.joinToString(", ") { it.name }

        val response = when {
            lower.contains("משימות") || lower.contains("חלוקה") || lower.contains("תורנות") -> {
                val canGenerate = currentMember.value.isParent
                if (canGenerate) {
                    generateSmartTasksForKids()
                }
                "ניתחתי את לוח המשימות של **${familyName.value}** ($pendingCount משימות פתוחות כרגע).\n\n" +
                    (if (canGenerate) "✨ הוספתי אוטומטית 3 משימות מאוזנות להיום עבור הילדים ($kidsNames):\n" else "✨ הצעה לחלוקת משימות מאוזנת עבור הילדים ($kidsNames):\n") +
                    "1. **סידור שולחן לימודים ומחברות** (15 נק')\n" +
                    "2. **פינוי כלים מהכיור למדיח** (15 נק')\n" +
                    "3. **הוצאת אשפה וטיול ערב עם הכלב** (20 נק')"
            }
            lower.contains("מערכת") || lower.contains("שיעור") || lower.contains("בית ספר") -> {
                "בדקתי את מערכת השעות המשפחתית 🏫:\n" +
                    "• **נועם (כיתה ז'):** מחר מתחיל ב-08:00 במתמטיקה מוגבר (חדר 204).\n" +
                    "• **מאיה (כיתה ד'):** שפה וקריאה ב-08:00 ואמנות ב-09:50.\n" +
                    "• **דניאל (כיתה ב'):** קריאה וכתיבה ב-08:00.\n" +
                    "🔔 התראות של 10 דקות לפני כל שיעור מופעלות עבור כל הילדים."
            }
            else -> {
                "בשמחה! משפחת **${familyName.value}** מסונכרנת בזמן אמת ☁️.\n" +
                    "• חברים פעילים: ${members.value.size} (${members.value.count { it.isParent }} הורים, ${members.value.count { it.isChild }} ילדים)\n" +
                    "• משימות להיום: $pendingCount ממתינות לביצוע\n" +
                    "אפשר לבקש ממני לחלק משימות שבועיות או לבדוק את מערכת השעות!"
            }
        }

        _aiMessages.value = _aiMessages.value + (false to response)
        _isAiGenerating.value = false
    }

    fun generateSmartTasksForKids() {
        if (!currentMember.value.isParent) {
            _snackbarMessage.value = "רק הורים יכולים להוסיף משימות למשפחה 🔒"
            return
        }
        val todayEpoch = LocalDate.now().toEpochDay()
        val kids = members.value.filter { it.role == UserRole.CHILD }
        val kid1 = kids.getOrNull(0)
        val kid2 = kids.getOrNull(1)
        val kid3 = kids.getOrNull(2)

        val task1 = FamilyTask(
            id = "gemini_task_${System.currentTimeMillis()}_1",
            title = "החלפת מצעים וארגון מדפי החדר (הצעת Gemini)",
            description = "לסדר ספרים, צעצועים ומצעים נקיים",
            category = TaskCategory.BEDROOM,
            assignedToMemberId = kid2?.id ?: currentMember.value.id,
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "17:00",
            priority = TaskPriority.MEDIUM,
            points = 15
        )
        val task2 = FamilyTask(
            id = "gemini_task_${System.currentTimeMillis()}_2",
            title = "טיול ארוך עם הכלב ומחזור בקבוקים (הצעת Gemini)",
            description = "טיול וסיבוב בפארק",
            category = TaskCategory.PETS,
            assignedToMemberId = kid1?.id ?: currentMember.value.id,
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "18:00",
            priority = TaskPriority.HIGH,
            points = 20
        )
        val task3 = FamilyTask(
            id = "gemini_task_${System.currentTimeMillis()}_3",
            title = "איסוף משחקים וצעצועים לקופסאות (הצעת Gemini)",
            description = "לסדר לגו ומשחקי קופסה",
            category = TaskCategory.BEDROOM,
            assignedToMemberId = kid3?.id ?: currentMember.value.id,
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "17:30",
            priority = TaskPriority.LOW,
            points = 10
        )
        repository.addTask(task1)
        repository.addTask(task2)
        repository.addTask(task3)
        _snackbarMessage.value = "3 משימות חדשות נוספו מלוח ההמלצות של Gemini ✓"
    }

    // --- Family Points Management (Parent Only) ---

    fun addMemberPoints(
        memberId: String,
        amount: Int,
        reason: String,
        operationId: String = "pts_add_${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Boolean {
        val targetName = members.value.find { it.id == memberId }?.name.orEmpty()
        val ok = repository.addMemberPoints(
            memberId = memberId,
            amount = amount,
            reason = reason,
            operationId = operationId
        )
        if (ok) {
            val isHe = appLanguage.value == AppLanguage.HEBREW
            _snackbarMessage.value = if (isHe) {
                "נוספו +$amount נקודות ל-$targetName ⭐"
            } else {
                "Added +$amount points to $targetName ⭐"
            }
        }
        return ok
    }

    fun deductMemberPoints(
        memberId: String,
        amount: Int,
        reason: String,
        allowNegativeBalance: Boolean = false,
        operationId: String = "pts_ded_${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Boolean {
        val targetName = members.value.find { it.id == memberId }?.name.orEmpty()
        val ok = repository.deductMemberPoints(
            memberId = memberId,
            amount = amount,
            reason = reason,
            allowNegativeBalance = allowNegativeBalance,
            operationId = operationId
        )
        if (ok) {
            val isHe = appLanguage.value == AppLanguage.HEBREW
            _snackbarMessage.value = if (isHe) {
                "הופחתו -$amount נקודות מ-$targetName"
            } else {
                "Deducted -$amount points from $targetName"
            }
        }
        return ok
    }

    fun resetMemberPoints(
        memberId: String,
        reason: String = "איפוס יתרת נקודות ע\"י הורה",
        operationId: String = "pts_rst_${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Boolean {
        val targetName = members.value.find { it.id == memberId }?.name.orEmpty()
        val ok = repository.resetMemberPoints(
            memberId = memberId,
            reason = reason,
            operationId = operationId
        )
        if (ok) {
            val isHe = appLanguage.value == AppLanguage.HEBREW
            _snackbarMessage.value = if (isHe) {
                "יתרת הנקודות של $targetName אופסה ל-0 ✓"
            } else {
                "Reset $targetName's points balance to 0 ✓"
            }
        }
        return ok
    }

    fun clearPointsOperationError() {
        repository.clearPointsOperationError()
    }

    // --- Task Collaboration (Volunteer & Swap) ---

    fun proposeVolunteerForTask(taskId: String, note: String = ""): Boolean {
        val ok = repository.proposeVolunteerForTask(taskId, note)
        if (ok) {
            _snackbarMessage.value = "ההצעה לביצוע המשימה נשלחה לאישור! 🙋"
        }
        return ok
    }

    fun proposeTaskSwap(offeredTaskId: String, targetTaskId: String, note: String = ""): Boolean {
        val ok = repository.proposeTaskSwap(offeredTaskId, targetTaskId, note)
        if (ok) {
            _snackbarMessage.value = "הצעת החלפת המשימות נשלחה לאישור! 🔁"
        }
        return ok
    }

    fun respondToTaskProposalAsChild(proposalId: String, approve: Boolean): Boolean {
        val ok = repository.respondToTaskProposalAsChild(proposalId, approve)
        if (ok) {
            _snackbarMessage.value = if (approve) {
                "אישרת את הבקשה! כעת ממתין לאישור הורה 🛡️"
            } else {
                "דחית את הבקשה"
            }
        }
        return ok
    }

    fun decideTaskProposalAsParent(proposalId: String, approve: Boolean): Boolean {
        val ok = repository.decideTaskProposalAsParent(proposalId, approve)
        if (ok) {
            _snackbarMessage.value = if (approve) {
                "שינוי שיוך המשימה אושר ועודכן! ✅"
            } else {
                "הבקשה נדחתה ע״י הורה"
            }
        }
        return ok
    }

    fun cancelTaskProposal(proposalId: String): Boolean {
        val ok = repository.cancelTaskProposal(proposalId)
        if (ok) {
            _snackbarMessage.value = "✅ ההתנדבות בוטלה"
        }
        return ok
    }

    fun volunteerForOpenTask(taskId: String, note: String = ""): Boolean {
        val ok = repository.volunteerForOpenTask(taskId, note)
        if (ok) {
            _snackbarMessage.value = "✅ ההתנדבות נשלחה לאישור ההורה"
        }
        return ok
    }

    fun selectVolunteerForOpenTask(proposalId: String): Boolean {
        val ok = repository.selectVolunteerForOpenTask(proposalId)
        if (ok) {
            _snackbarMessage.value = "המתנדב/ת נבחר/ה והמשימה שויכה בהצלחה! ✅"
        }
        return ok
    }

    fun approveTaskCompletionWithSplit(
        taskId: String,
        performerPoints: Int,
        originalOwnerPoints: Int = 0,
        bonusPointsForPerformer: Int = 0,
        parentNote: String = ""
    ): Boolean {
        val ok = repository.approveTaskCompletionWithSplit(
            taskId = taskId,
            performerPoints = performerPoints,
            originalOwnerPoints = originalOwnerPoints,
            bonusPointsForPerformer = bonusPointsForPerformer
        )
        if (ok) {
            _snackbarMessage.value = "המשימה אושרה והנקודות חולקו בהצלחה! ⭐"
        }
        return ok
    }

    // --- Parent Points Transfer & Helping Bonus ---

    fun transferPointsBetweenMembers(
        fromMemberId: String,
        toMemberId: String,
        amount: Int,
        reason: String
    ): Boolean {
        val ok = repository.transferPointsBetweenMembers(fromMemberId, toMemberId, amount, reason)
        if (ok) {
            val fromName = members.value.find { it.id == fromMemberId }?.name.orEmpty()
            val toName = members.value.find { it.id == toMemberId }?.name.orEmpty()
            _snackbarMessage.value = "הועברו $amount נקודות מ-$fromName אל $toName 🔄"
        }
        return ok
    }

    fun grantHelpingBonusPoints(
        memberId: String,
        amount: Int,
        reason: String,
        relatedMemberId: String? = null,
        taskId: String? = null
    ): Boolean {
        val ok = repository.grantHelpingBonusPoints(memberId, amount, reason, relatedMemberId, taskId)
        if (ok) {
            val targetName = members.value.find { it.id == memberId }?.name.orEmpty()
            _snackbarMessage.value = "הוענק בונוס עזרה (+$amount נק׳) ל-$targetName 🌟"
        }
        return ok
    }

    // --- Reward Catalog Management (Parent Only) & Redemptions ---

    fun createReward(
        title: String,
        pointsCost: Int,
        iconEmoji: String = "🎁",
        description: String = "",
        isActive: Boolean = true
    ): Boolean {
        val ok = repository.createReward(title, pointsCost, iconEmoji, description, isActive)
        if (ok) {
            _snackbarMessage.value = "פרס חדש נוסף לקטלוג: $title 🎁"
        }
        return ok
    }

    fun createRewardInCatalog(
        title: String,
        pointsCost: Int,
        iconEmoji: String = "🎁",
        description: String = "",
        isActive: Boolean = true
    ): Boolean = createReward(title, pointsCost, iconEmoji, description, isActive)

    fun updateReward(
        rewardId: String,
        title: String,
        pointsCost: Int,
        iconEmoji: String,
        description: String,
        isActive: Boolean = true
    ): Boolean {
        val ok = repository.updateReward(rewardId, title, pointsCost, iconEmoji, description, isActive)
        if (ok) {
            _snackbarMessage.value = "הפרס עודכן בקטלוג ✓"
        }
        return ok
    }

    fun updateRewardInCatalog(
        rewardId: String,
        title: String,
        pointsCost: Int,
        iconEmoji: String,
        description: String,
        isActive: Boolean = true
    ): Boolean = updateReward(rewardId, title, pointsCost, iconEmoji, description, isActive)

    fun setRewardActiveState(rewardId: String, isActive: Boolean): Boolean {
        val ok = repository.setRewardActiveState(rewardId, isActive)
        if (ok) {
            _snackbarMessage.value = if (isActive) "הפרס הופעל בקטלוג ✓" else "הפרס הושבת זמנית בקטלוג"
        }
        return ok
    }

    fun deleteRewardFromCatalog(rewardId: String): Boolean {
        val ok = repository.deleteRewardFromCatalog(rewardId)
        if (ok) {
            _snackbarMessage.value = "הפרס הוסר מהקטלוג"
        }
        return ok
    }

    fun requestRewardRedemption(reward: RewardItem): Boolean {
        val ok = repository.redeemReward(reward)
        if (ok) {
            _snackbarMessage.value = "בקשה למימוש \"${reward.title}\" נשלחה לאישור הורה! 🎁"
        }
        return ok
    }

    fun requestRewardRedemption(rewardId: String): Boolean {
        val reward = rewards.value.find { it.id == rewardId }
        val ok = repository.requestRewardRedemption(rewardId)
        if (ok) {
            _snackbarMessage.value = "בקשה למימוש \"${reward?.title.orEmpty()}\" נשלחה לאישור הורה! 🎁"
        }
        return ok
    }

    fun decideRewardRedemptionAsParent(
        redemptionId: String,
        approve: Boolean,
        rejectionReason: String = ""
    ): Boolean {
        val ok = repository.decideRewardRedemptionAsParent(redemptionId, approve, rejectionReason)
        if (ok) {
            _snackbarMessage.value = if (approve) {
                "מימוש הפרס אושר והנקודות הופחתו! 🎉"
            } else {
                "בקשת מימוש הפרס נדחתה (הנקודות נשמרו)"
            }
        }
        return ok
    }

    fun setPersonalRewardGoal(
        rewardId: String?,
        targetChildId: String = currentMember.value.id
    ): Boolean {
        val ok = repository.setPersonalRewardGoal(rewardId, targetChildId)
        if (ok) {
            val rewardTitle = rewardId?.let { id -> rewards.value.find { it.id == id }?.title }
            _snackbarMessage.value = if (!rewardTitle.isNullOrBlank()) {
                "נקבע יעד חיסכון אישי: \"$rewardTitle\" 🎯"
            } else {
                "יעד הפרס האישי נוקה"
            }
        }
        return ok
    }

    fun getRewardGoalProgress(memberId: String = currentMember.value.id): RewardGoalProgressUiModel? {
        return repository.getRewardGoalProgress(memberId)
    }

    fun updateMemberAvatar(
        targetMemberId: String = currentMember.value.id,
        avatarId: String
    ): Boolean {
        val ok = repository.updateMemberAvatar(targetMemberId, avatarId)
        if (ok) {
            _snackbarMessage.value = "האווטאר עודכן בהצלחה! 🎨"
        } else {
            _snackbarMessage.value = repository.taskOperationError.value
                ?: "ילד יכול לשנות רק את האווטאר של עצמו 🔒"
        }
        return ok
    }

    fun showToastMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }
}
