package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FamilyRepository
import com.example.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class AppScreen {
    TASKS,
    CALENDAR,
    TIMETABLE,
    FILES,
    ADMIN_DASHBOARD,
    SETTINGS,
    GEMINI
}

enum class CalendarViewMode {
    MONTH, WEEK, DAY
}

class FamilyViewModel(
    private val repository: FamilyRepository = FamilyRepository(),
    private val geminiService: com.example.data.GeminiService = com.example.data.GeminiService()
) : ViewModel() {

    // Main Flows
    val members: StateFlow<List<FamilyMember>> = repository.members
    val tasks: StateFlow<List<FamilyTask>> = repository.tasks
    val periods: StateFlow<List<SchedulePeriod>> = repository.periods
    val folders: StateFlow<List<DocumentFolder>> = repository.folders
    val files: StateFlow<List<FamilyDocument>> = repository.files
    val notifications: StateFlow<List<AppNotification>> = repository.notifications
    val latestAlert: StateFlow<AppNotification?> = repository.latestAlert
    val settings: StateFlow<AppSettings> = repository.settings

    // Navigation and View States
    private val _currentScreen = MutableStateFlow(AppScreen.TASKS)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _calendarMode = MutableStateFlow(CalendarViewMode.MONTH)
    val calendarMode: StateFlow<CalendarViewMode> = _calendarMode.asStateFlow()

    private val _selectedCalendarDate = MutableStateFlow(LocalDate.now())
    val selectedCalendarDate: StateFlow<LocalDate> = _selectedCalendarDate.asStateFlow()

    private val _selectedMemberFilter = MutableStateFlow<String?>(null) // null = all members
    val selectedMemberFilter: StateFlow<String?> = _selectedMemberFilter.asStateFlow()

    private val _selectedChildTimetableId = MutableStateFlow("user_maya")
    val selectedChildTimetableId: StateFlow<String> = _selectedChildTimetableId.asStateFlow()

    private val _selectedFolderId = MutableStateFlow<String?>("f_school")
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    // Dialog & Sheet States
    private val _showAddTaskDialog = MutableStateFlow(false)
    val showAddTaskDialog: StateFlow<Boolean> = _showAddTaskDialog.asStateFlow()

    private val _taskToEdit = MutableStateFlow<FamilyTask?>(null)
    val taskToEdit: StateFlow<FamilyTask?> = _taskToEdit.asStateFlow()

    private val _showInviteMemberDialog = MutableStateFlow(false)
    val showInviteMemberDialog: StateFlow<Boolean> = _showInviteMemberDialog.asStateFlow()

    private val _memberToEdit = MutableStateFlow<FamilyMember?>(null)
    val memberToEdit: StateFlow<FamilyMember?> = _memberToEdit.asStateFlow()

    private val _showAddPeriodDialog = MutableStateFlow(false)
    val showAddPeriodDialog: StateFlow<Boolean> = _showAddPeriodDialog.asStateFlow()

    private val _periodToEdit = MutableStateFlow<SchedulePeriod?>(null)
    val periodToEdit: StateFlow<SchedulePeriod?> = _periodToEdit.asStateFlow()

    private val _showAddFolderDialog = MutableStateFlow(false)
    val showAddFolderDialog: StateFlow<Boolean> = _showAddFolderDialog.asStateFlow()

    private val _showNotificationsSheet = MutableStateFlow(false)
    val showNotificationsSheet: StateFlow<Boolean> = _showNotificationsSheet.asStateFlow()

    private val _showGoogleProfileSheet = MutableStateFlow(false)
    val showGoogleProfileSheet: StateFlow<Boolean> = _showGoogleProfileSheet.asStateFlow()

    private val _showEditFamilyNameDialog = MutableStateFlow(false)
    val showEditFamilyNameDialog: StateFlow<Boolean> = _showEditFamilyNameDialog.asStateFlow()

    private val _showGoogleSignInDialog = MutableStateFlow(false)
    val showGoogleSignInDialog: StateFlow<Boolean> = _showGoogleSignInDialog.asStateFlow()

    // Gemini AI states
    private val _geminiLoading = MutableStateFlow(false)
    val geminiLoading: StateFlow<Boolean> = _geminiLoading.asStateFlow()

    private val _geminiResponse = MutableStateFlow<String?>(null)
    val geminiResponse: StateFlow<String?> = _geminiResponse.asStateFlow()

    // Status message / toast
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        // Auto dismiss latest push banner after 4.5 seconds
        viewModelScope.launch {
            latestAlert.collectLatest { alert ->
                if (alert != null) {
                    delay(4500)
                    repository.dismissLatestAlert()
                }
            }
        }
    }

    val currentMember: FamilyMember
        get() = repository.currentMember

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun setCalendarMode(mode: CalendarViewMode) {
        _calendarMode.value = mode
    }

    fun setSelectedCalendarDate(date: LocalDate) {
        _selectedCalendarDate.value = date
    }

    fun setMemberFilter(memberId: String?) {
        _selectedMemberFilter.value = memberId
    }

    fun setSelectedChildTimetable(childId: String) {
        _selectedChildTimetableId.value = childId
    }

    fun setSelectedFolder(folderId: String?) {
        _selectedFolderId.value = folderId
    }

    fun switchUser(memberId: String) {
        repository.switchCurrentUser(memberId)
        val member = members.value.find { it.id == memberId }
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        val msg = if (isHebrew) "הוחלף משתמש ל: ${member?.name}" else "Switched user to: ${member?.name}"
        showToast(msg)
    }

    // Dialog controls
    fun openAddTaskDialog() {
        _taskToEdit.value = null
        _showAddTaskDialog.value = true
    }

    fun openEditTaskDialog(task: FamilyTask) {
        _taskToEdit.value = task
        _showAddTaskDialog.value = true
    }

    fun closeAddTaskDialog() {
        _taskToEdit.value = null
        _showAddTaskDialog.value = false
    }

    fun openInviteMemberDialog() {
        _memberToEdit.value = null
        _showInviteMemberDialog.value = true
    }

    fun openEditMemberDialog(member: FamilyMember) {
        _memberToEdit.value = member
        _showInviteMemberDialog.value = true
    }

    fun closeInviteMemberDialog() {
        _memberToEdit.value = null
        _showInviteMemberDialog.value = false
    }

    fun openAddPeriodDialog() {
        _periodToEdit.value = null
        _showAddPeriodDialog.value = true
    }

    fun openEditPeriodDialog(period: SchedulePeriod) {
        _periodToEdit.value = period
        _showAddPeriodDialog.value = true
    }

    fun closeAddPeriodDialog() {
        _periodToEdit.value = null
        _showAddPeriodDialog.value = false
    }

    fun openAddFolderDialog() { _showAddFolderDialog.value = true }
    fun closeAddFolderDialog() { _showAddFolderDialog.value = false }

    fun openNotificationsSheet() {
        _showNotificationsSheet.value = true
        repository.markNotificationsRead()
    }
    fun closeNotificationsSheet() { _showNotificationsSheet.value = false }

    fun openGoogleProfileSheet() { _showGoogleProfileSheet.value = true }
    fun closeGoogleProfileSheet() { _showGoogleProfileSheet.value = false }

    fun openEditFamilyNameDialog() { _showEditFamilyNameDialog.value = true }
    fun closeEditFamilyNameDialog() { _showEditFamilyNameDialog.value = false }

    fun updateFamilyName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotBlank()) {
            repository.updateFamilyName(trimmed)
            closeEditFamilyNameDialog()
            val isHeb = settings.value.language == AppLanguage.HEBREW
            showToast(if (isHeb) "שם המשפחה עודכן ל-\"$trimmed\" בהצלחה! ✨" else "Family name updated to \"$trimmed\"! ✨")
        }
    }

    fun openGoogleSignInDialog() { _showGoogleSignInDialog.value = true }
    fun closeGoogleSignInDialog() { _showGoogleSignInDialog.value = false }

    fun completeGoogleSignIn(accountEmail: String) {
        repository.setGoogleSignInCompleted(true, accountEmail)
        closeGoogleSignInDialog()
        val isHeb = settings.value.language == AppLanguage.HEBREW
        showToast(if (isHeb) "התחברת בהצלחה עם $accountEmail! 🌟" else "Connected with $accountEmail! 🌟")
    }

    fun setFilesViewMode(mode: FilesViewMode) {
        repository.updateSettings(settings.value.copy(filesViewMode = mode))
    }

    fun setTimetableViewMode(mode: TimetableViewMode) {
        repository.updateSettings(settings.value.copy(timetableViewMode = mode))
    }

    // Actions
    fun saveTask(task: FamilyTask) {
        val exists = tasks.value.any { it.id == task.id }
        if (exists) {
            repository.updateTask(task)
            val isHebrew = settings.value.language == AppLanguage.HEBREW
            showToast(if (isHebrew) "המשימה עודכנה בהצלחה! ✓" else "Task updated successfully! ✓")
        } else {
            repository.addTask(task)
        }
        closeAddTaskDialog()
    }

    fun addTask(task: FamilyTask) {
        saveTask(task)
    }

    fun savePeriod(period: SchedulePeriod) {
        val exists = periods.value.any { it.id == period.id }
        if (exists) {
            repository.updatePeriod(period)
            val isHebrew = settings.value.language == AppLanguage.HEBREW
            showToast(if (isHebrew) "השיעור במערכת עודכן בהצלחה! ✓" else "Period updated successfully! ✓")
        } else {
            repository.addPeriod(period)
        }
        closeAddPeriodDialog()
    }

    fun toggleTask(taskId: String) {
        repository.toggleTask(taskId)
    }

    fun deleteTask(taskId: String) {
        repository.deleteTask(taskId)
    }

    fun sendNudge(task: FamilyTask) {
        repository.sendNudge(task)
    }

    fun saveMember(memberId: String?, name: String, email: String, role: UserRole, avatarEmoji: String, schoolGrade: String? = null): Boolean {
        return if (memberId != null) {
            repository.updateMemberProfile(memberId, name, email, role, avatarEmoji, schoolGrade)
            val isHebrew = settings.value.language == AppLanguage.HEBREW
            showToast(if (isHebrew) "פרטי המשתמש עודכנו בהצלחה! ✓" else "User profile updated successfully! ✓")
            closeInviteMemberDialog()
            true
        } else {
            addMember(name, email, role, avatarEmoji, schoolGrade)
        }
    }

    fun addMember(name: String, email: String, role: UserRole, avatarEmoji: String, schoolGrade: String? = null): Boolean {
        val success = repository.addMember(name, email, role, avatarEmoji, schoolGrade)
        if (success) {
            closeInviteMemberDialog()
        } else {
            val isHebrew = settings.value.language == AppLanguage.HEBREW
            showToast(if (isHebrew) "הגעת למגבלת 10 משתמשים במשפחה" else "Maximum 10 family members reached")
        }
        return success
    }

    fun updateMemberRole(memberId: String, newRole: UserRole) {
        repository.updateMemberRole(memberId, newRole)
    }

    fun removeMember(memberId: String) {
        repository.removeMember(memberId)
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        showToast(if (isHebrew) "המשתמש הוסר מהמשפחה" else "Member removed from household")
    }


    fun addPeriod(period: SchedulePeriod) {
        repository.addPeriod(period)
        closeAddPeriodDialog()
    }

    fun deletePeriod(periodId: String) {
        repository.deletePeriod(periodId)
    }

    fun testSchoolAlert(period: SchedulePeriod, childName: String) {
        repository.testSchoolAlert(period, childName)
    }

    fun addFolder(nameHebrew: String, nameEnglish: String, colorHex: Long, iconEmoji: String) {
        repository.addFolder(nameHebrew, nameEnglish, colorHex, iconEmoji)
        closeAddFolderDialog()
    }

    fun uploadDocument(folderId: String, name: String, extension: String, sizeFormatted: String) {
        repository.uploadDocument(folderId, name, extension, sizeFormatted)
    }

    fun renameFile(fileId: String, newName: String) {
        repository.renameDocument(fileId, newName)
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        showToast(if (isHebrew) "שם הקובץ עודכן ל-\"$newName\" ✓" else "File renamed to \"$newName\" ✓")
    }

    fun deleteFile(fileId: String) {
        repository.deleteDocument(fileId)
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        showToast(if (isHebrew) "הקובץ נמחק מ-Google Drive" else "File deleted from Google Drive")
    }

    fun initializeCleanFamily(name: String, email: String, familyName: String, avatar: String) {
        repository.initializeCleanFamily(name, email, familyName, avatar)
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        showToast(if (isHebrew) "ברוכים הבאים $name! המשפחה הוקמה בהצלחה 🌟" else "Welcome $name! Household setup complete 🌟")
    }

    fun loadSampleData() {
        repository.loadSampleData()
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        showToast(if (isHebrew) "נתוני דוגמה נטענו בהצלחה" else "Sample data loaded")
    }

    fun dismissAlert() {
        repository.dismissLatestAlert()
    }

    fun clearAllNotifications() {
        repository.clearNotifications()
    }

    fun updateSettings(newSettings: AppSettings) {
        repository.updateSettings(newSettings)
    }

    fun upgradeAppVersion() {
        repository.upgradeAppVersion()
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        val versionStr = settings.value.formattedVersion
        showToast(if (isHebrew) "התוכנה שודרגה לגרסה חדשה בהצלחה! 🚀" else "App successfully updated! 🚀")
    }

    fun syncCloudNow() {
        repository.syncCloudNow()
        val isHebrew = settings.value.language == AppLanguage.HEBREW
        showToast(if (isHebrew) "סנכרון ענן התבצע בהצלחה!" else "Cloud sync completed successfully!")
    }

    fun showToast(msg: String) {
        _snackbarMessage.value = msg
        viewModelScope.launch {
            delay(3000)
            if (_snackbarMessage.value == msg) {
                _snackbarMessage.value = null
            }
        }
    }

    fun askGemini(prompt: String) {
        viewModelScope.launch {
            _geminiLoading.value = true
            val familyContext = "Family members: " + members.value.joinToString { "${it.name} (${it.role})" }
            val result = geminiService.generateFamilyAdvice(prompt, familyContext)
            result.onSuccess { text ->
                _geminiResponse.value = text
            }.onFailure { err ->
                _geminiResponse.value = "Error: ${err.message}"
            }
            _geminiLoading.value = false
        }
    }

    fun addGeminiSuggestedTasks() {
        val todayEpoch = java.time.LocalDate.now().toEpochDay()
        val maya = members.value.find { it.id == "user_maya" }
        val noam = members.value.find { it.id == "user_noam" }
        val daniel = members.value.find { it.id == "user_daniel" }

        val task1 = FamilyTask(
            id = "gemini_task_${System.currentTimeMillis()}_1",
            title = "החלפת מצעים וארגון מדפי החדר (הצעת Gemini)",
            description = "לסדר ספרים, צעצועים ומצעים נקיים",
            category = TaskCategory.BEDROOM,
            assignedToMemberId = maya?.id ?: currentMember.id,
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
            assignedToMemberId = noam?.id ?: currentMember.id,
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
            assignedToMemberId = daniel?.id ?: currentMember.id,
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "17:30",
            priority = TaskPriority.LOW,
            points = 10
        )
        repository.addTask(task1)
        repository.addTask(task2)
        repository.addTask(task3)
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }
}
