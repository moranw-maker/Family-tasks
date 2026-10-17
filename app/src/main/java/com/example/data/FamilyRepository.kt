package com.example.data

import com.example.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

class FamilyRepository {

    private val todayEpoch = LocalDate.now().toEpochDay()

    // 1. Initial Family Members (Up to 10 capacity)
    private val initialMembers = listOf(
        FamilyMember(
            id = "user_moran",
            name = "מורן (אמא)",
            email = "Moranw@gmail.com",
            role = UserRole.PRIMARY_ADMIN,
            avatarEmoji = "👩‍💻",
            colorHex = 0xFF1A73E8,
            earnedPoints = 180,
            completedTasksCount = 24,
            isCurrentUser = true
        ),
        FamilyMember(
            id = "user_david",
            name = "דוד (אבא)",
            email = "david.fam@gmail.com",
            role = UserRole.SECONDARY_ADMIN,
            avatarEmoji = "👨‍🔧",
            colorHex = 0xFF34A853,
            earnedPoints = 140,
            completedTasksCount = 19
        ),
        FamilyMember(
            id = "user_maya",
            name = "מאיה",
            email = "maya.fam@gmail.com",
            role = UserRole.MEMBER_KID,
            avatarEmoji = "👧",
            colorHex = 0xFF9334E8,
            earnedPoints = 230,
            completedTasksCount = 31,
            schoolGrade = "כיתה ה׳"
        ),
        FamilyMember(
            id = "user_noam",
            name = "נועם",
            email = "noam.fam@gmail.com",
            role = UserRole.MEMBER_KID,
            avatarEmoji = "👦",
            colorHex = 0xFFFBBC04,
            earnedPoints = 195,
            completedTasksCount = 27,
            schoolGrade = "כיתה ג׳"
        ),
        FamilyMember(
            id = "user_daniel",
            name = "דניאל",
            email = "daniel.fam@gmail.com",
            role = UserRole.MEMBER_KID,
            avatarEmoji = "🧒",
            colorHex = 0xFFEA4335,
            earnedPoints = 90,
            completedTasksCount = 12,
            schoolGrade = "גן חובה"
        )
    )

    // 2. Initial Tasks
    private val initialTasks = listOf(
        FamilyTask(
            id = "task_1",
            title = "לסדר את הילקוט ומערכת שעות למחר",
            description = "לבדוק שיש קלמר, מחברות וספרים לפי המערכת",
            category = TaskCategory.HOMEWORK,
            assignedToMemberId = "user_maya",
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "19:00",
            priority = TaskPriority.HIGH,
            points = 15,
            recurrence = TaskRecurrence.SCHOOL_DAYS,
            isCompleted = false
        ),
        FamilyTask(
            id = "task_2",
            title = "להוריד את הכלב טיול ערב",
            description = "טיול של 20 דקות בפארק השכונתי",
            category = TaskCategory.PETS,
            assignedToMemberId = "user_noam",
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "18:30",
            priority = TaskPriority.HIGH,
            points = 20,
            recurrence = TaskRecurrence.DAILY,
            isCompleted = false
        ),
        FamilyTask(
            id = "task_3",
            title = "שטיפת כלים וסידור השיש",
            description = "להפעיל מדיח כלים כשהוא מתמלא",
            category = TaskCategory.DISHES,
            assignedToMemberId = "user_david",
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "20:30",
            priority = TaskPriority.MEDIUM,
            points = 10,
            recurrence = TaskRecurrence.DAILY,
            isCompleted = true,
            completedAtMillis = System.currentTimeMillis() - 3600000,
            completedByMemberId = "user_david"
        ),
        FamilyTask(
            id = "task_4",
            title = "איסוף משחקים בחדר",
            description = "להחזיר לגו וקופסאות למגירות",
            category = TaskCategory.BEDROOM,
            assignedToMemberId = "user_daniel",
            dateEpochDay = todayEpoch,
            dueTimeFormatted = "17:45",
            priority = TaskPriority.LOW,
            points = 10,
            recurrence = TaskRecurrence.DAILY,
            isCompleted = false
        ),
        FamilyTask(
            id = "task_5",
            title = "שיעורי בית בחשבון - עמודים 34-36",
            description = "תרגול שברים ולוח הכפל",
            category = TaskCategory.HOMEWORK,
            assignedToMemberId = "user_maya",
            dateEpochDay = todayEpoch + 1,
            dueTimeFormatted = "16:00",
            priority = TaskPriority.HIGH,
            points = 25,
            recurrence = TaskRecurrence.ONCE,
            isCompleted = false
        ),
        FamilyTask(
            id = "task_6",
            title = "קניות לשבת בסופר",
            description = "חלב, ביצים, חלות, פירות וירקות",
            category = TaskCategory.GROCERIES,
            assignedToMemberId = "user_moran",
            dateEpochDay = todayEpoch + 2,
            dueTimeFormatted = "11:00",
            priority = TaskPriority.MEDIUM,
            points = 15,
            recurrence = TaskRecurrence.WEEKLY,
            isCompleted = false
        )
    )

    // 3. Initial Timetable periods for kids
    private val initialPeriods = listOf(
        SchedulePeriod("p1", "user_maya", SchoolDay.SUNDAY, 1, "08:00", "08:45", "מתמטיקה", "חדר 204", "המורה דנה", 0xFF1A73E8),
        SchedulePeriod("p2", "user_maya", SchoolDay.SUNDAY, 2, "08:50", "09:35", "אנגלית", "חדר 204", "המורה שרה", 0xFF9334E8),
        SchedulePeriod("p3", "user_maya", SchoolDay.SUNDAY, 3, "09:55", "10:40", "מדעים", "מעבדה 1", "המורה יואב", 0xFF34A853),
        SchedulePeriod("p4", "user_maya", SchoolDay.SUNDAY, 4, "10:45", "11:30", "ספרות", "חדר 204", "המורה רחל", 0xFFFBBC04),
        SchedulePeriod("p5", "user_maya", SchoolDay.SUNDAY, 5, "11:50", "12:35", "חינוך גופני", "אולם ספורט", "המורה אסף", 0xFFEA4335),

        SchedulePeriod("p6", "user_maya", SchoolDay.MONDAY, 1, "08:00", "08:45", "תנ\"ך", "חדר 204", "המורה שרה", 0xFFFBBC04),
        SchedulePeriod("p7", "user_maya", SchoolDay.MONDAY, 2, "08:50", "09:35", "מתמטיקה", "חדר 204", "המורה דנה", 0xFF1A73E8),
        SchedulePeriod("p8", "user_maya", SchoolDay.MONDAY, 3, "09:55", "10:40", "היסטוריה", "חדר 204", "המורה ירון", 0xFF9334E8),
        SchedulePeriod("p9", "user_maya", SchoolDay.MONDAY, 4, "10:45", "11:30", "אמנות", "סדנת אמנות", "המורה מיכל", 0xFF00ACC1),

        SchedulePeriod("p10", "user_noam", SchoolDay.SUNDAY, 1, "08:00", "08:45", "שפה ועברית", "כיתה ג׳2", "המורה אורית", 0xFF1A73E8),
        SchedulePeriod("p11", "user_noam", SchoolDay.SUNDAY, 2, "08:50", "09:35", "חשבון", "כיתה ג׳2", "המורה אורית", 0xFF34A853),
        SchedulePeriod("p12", "user_noam", SchoolDay.SUNDAY, 3, "09:55", "10:40", "טבע וסביבה", "כיתה ג׳2", "המורה גלית", 0xFFFBBC04),
        SchedulePeriod("p13", "user_noam", SchoolDay.SUNDAY, 4, "10:45", "11:30", "מוזיקה", "חדר מוזיקה", "המורה רוני", 0xFF9334E8)
    )

    // 4. Initial Document Folders & Files
    private val initialFolders = listOf(
        DocumentFolder("f_school", "טפסים לבית ספר", "School Forms", 0xFF1A73E8, "🏫"),
        DocumentFolder("f_medical", "אישורים רפואיים", "Medical & Vaccines", 0xFF34A853, "🩺"),
        DocumentFolder("f_tests", "לוח מבחנים וחוגים", "Tests & Activities", 0xFFFBBC04, "📅"),
        DocumentFolder("f_bills", "ביטוחים וחשבונות", "Insurance & Bills", 0xFFEA4335, "📄")
    )

    private val initialFiles = listOf(
        FamilyDocument("doc_1", "f_school", "אישור יציאה לטיול שנתי - מאיה.pdf", "PDF", "240 KB", "מורן (אמא)", "היום, 10:15", true),
        FamilyDocument("doc_2", "f_school", "לוח חופשות ופעילויות תשפ\"ז.pdf", "PDF", "1.2 MB", "מורן (אמא)", "אתמול", true),
        FamilyDocument("doc_3", "f_medical", "פנקס חיסונים מעודכן - נועם.pdf", "PDF", "520 KB", "דוד (אבא)", "12/10/2026", true),
        FamilyDocument("doc_4", "f_medical", "טופס הצהרת בריאות שנתית.pdf", "PDF", "180 KB", "מורן (אמא)", "01/09/2026", true),
        FamilyDocument("doc_5", "f_tests", "מערכת חוגי כדורסל וציור.png", "PNG", "840 KB", "מורן (אמא)", "השבוע", true),
        FamilyDocument("doc_6", "f_bills", "פוליסת ביטוח תלמידים.pdf", "PDF", "1.5 MB", "דוד (אבא)", "15/09/2026", true)
    )

    // 5. Initial Notifications
    private val initialNotifications = listOf(
        AppNotification("notif_1", "משימה חדשה שובצה", "מאיה, קיבלת משימה: לסדר את הילקוט ומערכת שעות (+15 נקודות)", System.currentTimeMillis() - 1800000, NotificationType.TASK_ASSIGNED, false, "מאיה"),
        AppNotification("notif_2", "משימה הושלמה בהצלחה!", "דוד סימן כמבוצע: שטיפת כלים וסידור השיש 👏", System.currentTimeMillis() - 3600000, NotificationType.TASK_COMPLETED, true, "דוד (אבא)")
    )

    // Flows
    private val _members = MutableStateFlow(initialMembers)
    val members: StateFlow<List<FamilyMember>> = _members.asStateFlow()

    private val _tasks = MutableStateFlow(initialTasks)
    val tasks: StateFlow<List<FamilyTask>> = _tasks.asStateFlow()

    private val _periods = MutableStateFlow(initialPeriods)
    val periods: StateFlow<List<SchedulePeriod>> = _periods.asStateFlow()

    private val _folders = MutableStateFlow(initialFolders)
    val folders: StateFlow<List<DocumentFolder>> = _folders.asStateFlow()

    private val _files = MutableStateFlow(initialFiles)
    val files: StateFlow<List<FamilyDocument>> = _files.asStateFlow()

    private val _notifications = MutableStateFlow(initialNotifications)
    val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    private val _latestAlert = MutableStateFlow<AppNotification?>(null)
    val latestAlert: StateFlow<AppNotification?> = _latestAlert.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val currentMember: FamilyMember
        get() = _members.value.find { it.isCurrentUser }
            ?: _members.value.firstOrNull()
            ?: FamilyMember(
                id = "user_default_admin",
                name = if (_settings.value.language == AppLanguage.HEBREW) "מנהל ראשי" else "Primary Admin",
                email = _settings.value.googleAccountEmail,
                role = UserRole.PRIMARY_ADMIN,
                avatarEmoji = "👩‍💻",
                colorHex = 0xFF1A73E8L,
                isCurrentUser = true
            )

    fun switchCurrentUser(memberId: String) {
        _members.value = _members.value.map {
            it.copy(isCurrentUser = it.id == memberId)
        }
    }

    // Task Actions
    fun addTask(task: FamilyTask) {
        _tasks.value = _tasks.value + task
        val assigned = _members.value.find { it.id == task.assignedToMemberId }
        val title = if (_settings.value.language == AppLanguage.HEBREW) "משימה חדשה נשלחה!" else "New task assigned!"
        val msg = if (_settings.value.language == AppLanguage.HEBREW)
            "${assigned?.name ?: "חבר בית"}: ${task.title} (+${task.points} נק׳)"
        else
            "${assigned?.name ?: "Member"}: ${task.title} (+${task.points} pts)"

        pushNotification(
            AppNotification(
                id = "notif_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.TASK_ASSIGNED,
                targetMemberName = assigned?.name
            )
        )
    }

    fun toggleTask(taskId: String) {
        var completedTaskTitle = ""
        var assignedMemberId = ""
        var pointsGained = 0
        var isNowCompleted = false

        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                isNowCompleted = !task.isCompleted
                completedTaskTitle = task.title
                assignedMemberId = task.assignedToMemberId
                pointsGained = task.points
                task.copy(
                    isCompleted = isNowCompleted,
                    completedAtMillis = if (isNowCompleted) System.currentTimeMillis() else null,
                    completedByMemberId = if (isNowCompleted) currentMember.id else null
                )
            } else task
        }

        // Update member points & completed stats
        if (isNowCompleted && assignedMemberId.isNotEmpty()) {
            _members.value = _members.value.map { member ->
                if (member.id == assignedMemberId) {
                    member.copy(
                        earnedPoints = member.earnedPoints + pointsGained,
                        completedTasksCount = member.completedTasksCount + 1
                    )
                } else member
            }

            val assigned = _members.value.find { it.id == assignedMemberId }
            val completer = currentMember
            val title = if (_settings.value.language == AppLanguage.HEBREW) "משימה בוצעה בהצלחה! 🎉" else "Task Completed! 🎉"
            val msg = if (_settings.value.language == AppLanguage.HEBREW)
                "${completer.name} ביצע/ה: $completedTaskTitle (+${pointsGained} נק׳ עבור ${assigned?.name ?: "המשתמש"})"
            else
                "${completer.name} completed: $completedTaskTitle (+${pointsGained} pts for ${assigned?.name ?: "member"})"

            pushNotification(
                AppNotification(
                    id = "notif_${System.currentTimeMillis()}",
                    title = title,
                    message = msg,
                    timestampMillis = System.currentTimeMillis(),
                    type = NotificationType.TASK_COMPLETED,
                    targetMemberName = assigned?.name,
                    completedByMemberName = completer.name,
                    completedByAvatar = completer.avatarEmoji,
                    taskTitle = completedTaskTitle,
                    pointsGained = pointsGained
                )
            )
        }
    }

    fun updateTask(updatedTask: FamilyTask) {
        _tasks.value = _tasks.value.map { if (it.id == updatedTask.id) updatedTask else it }
    }

    fun deleteTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
    }

    // Member Management (up to 10 members)
    fun addMember(name: String, email: String, role: UserRole, avatarEmoji: String, schoolGrade: String? = null): Boolean {
        if (_members.value.size >= 10) return false
        val newId = "user_${System.currentTimeMillis()}"
        val colors = listOf(0xFF1A73E8, 0xFF34A853, 0xFFFBBC04, 0xFFEA4335, 0xFF9334E8, 0xFF00ACC1)
        val color = colors[_members.value.size % colors.size]

        val newMember = FamilyMember(
            id = newId,
            name = name,
            email = email,
            role = role,
            avatarEmoji = avatarEmoji,
            colorHex = color,
            earnedPoints = 0,
            completedTasksCount = 0,
            schoolGrade = schoolGrade,
            isCurrentUser = false
        )
        _members.value = _members.value + newMember

        val title = if (_settings.value.language == AppLanguage.HEBREW) "חבר משפחה חדש הצטרף!" else "New Family Member Joined!"
        val msg = if (_settings.value.language == AppLanguage.HEBREW)
            "$name ($email) צורף בהצלחה למשפחה"
        else
            "$name ($email) joined the family successfully"

        pushNotification(
            AppNotification(
                id = "notif_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.MEMBER_INVITED,
                targetMemberName = name
            )
        )
        return true
    }

    fun updateMemberRole(memberId: String, newRole: UserRole) {
        // Only allow if not demoting the last primary admin
        val isTargetPrimary = _members.value.find { it.id == memberId }?.role == UserRole.PRIMARY_ADMIN
        val primaryCount = _members.value.count { it.role == UserRole.PRIMARY_ADMIN }
        if (isTargetPrimary && newRole != UserRole.PRIMARY_ADMIN && primaryCount <= 1) {
            return // prevent leaving household without primary admin
        }
        _members.value = _members.value.map {
            if (it.id == memberId) it.copy(role = newRole) else it
        }
    }

    fun updateMemberProfile(memberId: String, name: String, email: String, role: UserRole, avatarEmoji: String, schoolGrade: String?) {
        _members.value = _members.value.map { member ->
            if (member.id == memberId) {
                // If member was primary admin and tried to change to kid/secondary, ensure at least one primary admin remains
                val finalRole = if (member.isPrimaryAdmin && role != UserRole.PRIMARY_ADMIN) {
                    val otherPrimaries = _members.value.count { it.id != memberId && it.role == UserRole.PRIMARY_ADMIN }
                    if (otherPrimaries == 0) UserRole.PRIMARY_ADMIN else role
                } else {
                    role
                }
                member.copy(
                    name = name,
                    email = email,
                    role = finalRole,
                    avatarEmoji = avatarEmoji,
                    schoolGrade = schoolGrade
                )
            } else {
                member
            }
        }
    }

    fun removeMember(memberId: String) {
        if (_members.value.size <= 1) return
        _members.value = _members.value.filter { it.id != memberId }
    }

    // Timetable Actions
    fun addPeriod(period: SchedulePeriod) {
        _periods.value = _periods.value + period
    }

    fun updatePeriod(period: SchedulePeriod) {
        _periods.value = _periods.value.map { if (it.id == period.id) period else it }
    }

    fun deletePeriod(periodId: String) {
        _periods.value = _periods.value.filter { it.id != periodId }
    }

    // Document & Folder Actions
    fun addFolder(nameHebrew: String, nameEnglish: String, colorHex: Long, iconEmoji: String) {
        val folder = DocumentFolder(
            id = "f_${System.currentTimeMillis()}",
            nameHebrew = nameHebrew,
            nameEnglish = nameEnglish,
            colorHex = colorHex,
            iconEmoji = iconEmoji
        )
        _folders.value = _folders.value + folder
    }

    fun uploadDocument(folderId: String, name: String, extension: String, sizeFormatted: String) {
        val newDoc = FamilyDocument(
            id = "doc_${System.currentTimeMillis()}",
            folderId = folderId,
            name = name,
            extension = extension.uppercase(),
            sizeFormatted = sizeFormatted,
            uploadedByName = currentMember.name,
            uploadDateFormatted = if (_settings.value.language == AppLanguage.HEBREW) "עכשיו" else "Just now",
            isDriveSynced = true
        )
        _files.value = listOf(newDoc) + _files.value

        val title = if (_settings.value.language == AppLanguage.HEBREW) "קובץ חדש הועלה 📁" else "New file uploaded 📁"
        val msg = if (_settings.value.language == AppLanguage.HEBREW)
            "${currentMember.name} העלה: $name (סונכרן ל-Google Drive)"
        else
            "${currentMember.name} uploaded: $name (Synced to Google Drive)"

        pushNotification(
            AppNotification(
                id = "notif_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.FILE_SHARED
            )
        )
    }

    fun renameDocument(fileId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            _files.value = _files.value.map { file ->
                if (file.id == fileId) {
                    file.copy(name = trimmed)
                } else file
            }
        }
    }

    fun deleteDocument(fileId: String) {
        _files.value = _files.value.filter { it.id != fileId }
    }

    // Nudge Reminder (Admin reminds a kid)
    fun sendNudge(task: FamilyTask) {
        val member = _members.value.find { it.id == task.assignedToMemberId }
        val title = if (_settings.value.language == AppLanguage.HEBREW) "תזכורת מההורים! ⏰" else "Nudge from parents! ⏰"
        val msg = if (_settings.value.language == AppLanguage.HEBREW)
            "${member?.name ?: "היי"}, אל תשכח/י לבצע: ${task.title}"
        else
            "${member?.name ?: "Hey"}, please remember to do: ${task.title}"

        pushNotification(
            AppNotification(
                id = "notif_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.NUDGE_REMINDER,
                targetMemberName = member?.name
            )
        )
    }

    fun testSchoolAlert(period: SchedulePeriod, childName: String) {
        val title = if (_settings.value.language == AppLanguage.HEBREW) "התראת שיעור מתחיל! 🔔" else "Class starting soon! 🔔"
        val msg = if (_settings.value.language == AppLanguage.HEBREW)
            "$childName: שיעור ${period.subjectName} מתחיל ב-${period.startTime} (${period.roomNumber})"
        else
            "$childName: ${period.subjectName} starts at ${period.startTime} (${period.roomNumber})"

        pushNotification(
            AppNotification(
                id = "notif_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.SCHOOL_START,
                targetMemberName = childName
            )
        )
    }

    private fun pushNotification(notification: AppNotification) {
        _notifications.value = listOf(notification) + _notifications.value
        _latestAlert.value = notification
    }

    fun dismissLatestAlert() {
        _latestAlert.value = null
    }

    fun markNotificationsRead() {
        _notifications.value = _notifications.value.map { it.copy(isRead = true) }
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
    }

    fun upgradeAppVersion() {
        val nextVersion = (_settings.value.appVersion + 0.1).let {
            Math.round(it * 10.0) / 10.0
        }
        _settings.value = _settings.value.copy(appVersion = nextVersion)
        val formatted = String.format(java.util.Locale.US, "%.1f", nextVersion)
        val title = if (_settings.value.language == AppLanguage.HEBREW) "שדרוג גרסה הושלם! 🚀" else "Update Installed! 🚀"
        val msg = if (_settings.value.language == AppLanguage.HEBREW)
            "האפליקציה שודרגה בהצלחה לגרסה $formatted"
        else
            "App successfully updated to version $formatted"
        pushNotification(
            AppNotification(
                id = "notif_version_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.TASK_ASSIGNED
            )
        )
    }

    fun updateFamilyName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            _settings.value = _settings.value.copy(familyName = trimmed)
            val title = if (_settings.value.language == AppLanguage.HEBREW) "שם המשפחה עודכן!" else "Family Name Updated!"
            val msg = if (_settings.value.language == AppLanguage.HEBREW)
                "שם המשפחה עודכן בהצלחה ל-\"$trimmed\""
            else
                "Family name updated to \"$trimmed\""
            pushNotification(
                AppNotification(
                    id = "notif_${System.currentTimeMillis()}",
                    title = title,
                    message = msg,
                    timestampMillis = System.currentTimeMillis(),
                    type = NotificationType.MEMBER_INVITED
                )
            )
        }
    }

    fun setGoogleSignInCompleted(completed: Boolean, email: String = "Moranw@gmail.com") {
        _settings.value = _settings.value.copy(
            hasCompletedGoogleSignIn = completed,
            googleAccountEmail = email
        )
    }

    fun initializeCleanFamily(
        adminName: String,
        adminEmail: String,
        familyName: String,
        adminAvatar: String = "👩‍💻"
    ) {
        val primaryAdmin = FamilyMember(
            id = "user_admin_${System.currentTimeMillis() % 10000}",
            name = adminName.trim().ifBlank { "מורן" },
            email = adminEmail.trim().ifBlank { "Moranw@gmail.com" },
            role = UserRole.PRIMARY_ADMIN,
            avatarEmoji = adminAvatar,
            colorHex = 0xFF1A73E8,
            earnedPoints = 100,
            completedTasksCount = 0,
            isCurrentUser = true
        )
        _members.value = listOf(primaryAdmin)
        _tasks.value = emptyList()
        _files.value = emptyList()
        _periods.value = emptyList()
        _settings.value = _settings.value.copy(
            familyName = familyName.trim().ifBlank { "וקסלר" },
            hasCompletedGoogleSignIn = true,
            googleAccountEmail = adminEmail.trim().ifBlank { "Moranw@gmail.com" }
        )
        pushNotification(
            AppNotification(
                id = "notif_welcome_${System.currentTimeMillis()}",
                title = if (_settings.value.language == AppLanguage.HEBREW) "ברוכים הבאים ל-Family Hub! 🌟" else "Welcome to Family Hub! 🌟",
                message = if (_settings.value.language == AppLanguage.HEBREW)
                    "המשפחה הוקמה בהצלחה נקייה מנתונים. שתפו את קוד ההזמנה עם שאר בני הבית"
                else
                    "Household initialized clean. Share your invite link with family members",
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.MEMBER_INVITED
            )
        )
    }

    fun loadSampleData() {
        _members.value = initialMembers
        _tasks.value = initialTasks
        _files.value = initialFiles
        _periods.value = initialPeriods
    }

    fun syncCloudNow() {
        _settings.value = _settings.value.copy(
            isDriveSyncActive = true,
            lastSyncFormatted = if (_settings.value.language == AppLanguage.HEBREW) "עודכן עכשיו" else "Just now"
        )
        val title = if (_settings.value.language == AppLanguage.HEBREW) "סנכרון ענן הושלם" else "Cloud sync complete"
        val msg = if (_settings.value.language == AppLanguage.HEBREW)
            "כל המשימות, מערכות השעות והמסמכים מעודכנים בזמן אמת"
        else
            "All tasks, timetables, and documents are synced in real time"

        pushNotification(
            AppNotification(
                id = "notif_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.TASK_ASSIGNED
            )
        )
    }
}
