package com.example.data

import com.example.R
import com.example.model.*
import com.example.util.handleFirestoreError
import com.example.util.OperationType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.time.LocalDate

class FamilyRepository(
    private var firestore: FirebaseFirestore? = null,
    private var auth: FirebaseAuth? = null
) {
    constructor(context: android.content.Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        FirebaseAuth.getInstance()
    )

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

    private fun getSafeAuth(): FirebaseAuth? =
        auth ?: runCatching { FirebaseAuth.getInstance() }.getOrNull()

    private fun getCurrentUserId(): String? =
        getSafeAuth()?.currentUser?.uid

    fun getActiveFamilyId(): String {
        val code = _settings.value.joinedFamilyCode ?: _settings.value.getDynamicInviteCode()
        val clean = code.lowercase().replace(Regex("[^a-z0-9]"), "_").trim('_')
        return if (clean.isNotEmpty()) "fam_$clean" else "fam_default"
    }

    fun joinFamilyByCode(inviteCode: String) {
        val cleanCode = inviteCode.trim().uppercase()
        _settings.value = _settings.value.copy(joinedFamilyCode = cleanCode)
        _tasks.value = emptyList()
        _files.value = emptyList()
        _periods.value = emptyList()
        val user = getSafeAuth()?.currentUser
        if (user != null) {
            val joiningMember = FamilyMember(
                id = user.uid,
                name = user.displayName ?: if (_settings.value.language == AppLanguage.HEBREW) "חבר משפחה" else "Family Member",
                email = user.email ?: "",
                role = UserRole.MEMBER_KID,
                avatarEmoji = "🧒",
                colorHex = 0xFF1A73E8,
                earnedPoints = 0,
                completedTasksCount = 0,
                isCurrentUser = true
            )
            _members.value = listOf(joiningMember)
            attachFirestoreSync(user.uid)
        }
    }

    // Task Actions
    fun addTask(task: FamilyTask) {
        val famId = getActiveFamilyId()
        val taskWithFamily = if (task.familyId.isEmpty()) task.copy(familyId = famId) else task
        _tasks.value = _tasks.value + taskWithFamily
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

        val currentUserId = getCurrentUserId()
        if (firestore != null && currentUserId != null) {
            val payload = hashMapOf(
                "id" to taskWithFamily.id,
                "userId" to currentUserId,
                "familyId" to famId,
                "title" to taskWithFamily.title,
                "description" to taskWithFamily.description,
                "category" to taskWithFamily.category.name,
                "assignedToMemberId" to taskWithFamily.assignedToMemberId,
                "dateEpochDay" to taskWithFamily.dateEpochDay,
                "dueTimeFormatted" to taskWithFamily.dueTimeFormatted,
                "priority" to taskWithFamily.priority.name,
                "points" to taskWithFamily.points,
                "recurrence" to taskWithFamily.recurrence.name,
                "isCompleted" to taskWithFamily.isCompleted,
                "completedAtMillis" to taskWithFamily.completedAtMillis,
                "completedByMemberId" to taskWithFamily.completedByMemberId,
                "approvedByParent" to taskWithFamily.approvedByParent,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            // Canonical single source of truth: shared family tasks live in families/{famId}/tasks, personal tasks in tasks
            if (famId.isNotEmpty()) {
                firestore?.collection("families")?.document(famId)?.collection("tasks")?.document(taskWithFamily.id)?.set(payload)
                    ?.addOnFailureListener { exception ->
                        handleFirestoreError(exception, OperationType.CREATE, "families/$famId/tasks/${taskWithFamily.id}")
                    }
            } else {
                firestore?.collection("tasks")?.document(taskWithFamily.id)?.set(payload)
                    ?.addOnFailureListener { exception ->
                        handleFirestoreError(exception, OperationType.CREATE, "tasks/${taskWithFamily.id}")
                    }
            }
        }
    }

    fun toggleTask(taskId: String) {
        var completedTaskTitle = ""
        var assignedMemberId = ""
        var pointsGained = 0
        var isNowCompleted = false
        var updatedTaskSnapshot: FamilyTask? = null

        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                isNowCompleted = !task.isCompleted
                completedTaskTitle = task.title
                assignedMemberId = task.assignedToMemberId
                pointsGained = task.points
                val updated = task.copy(
                    isCompleted = isNowCompleted,
                    completedAtMillis = if (isNowCompleted) System.currentTimeMillis() else null,
                    completedByMemberId = if (isNowCompleted) currentMember.id else null
                )
                updatedTaskSnapshot = updated
                updated
            } else task
        }

        updatedTaskSnapshot?.let { updatedTask ->
            val currentUserId = getCurrentUserId()
            if (firestore != null && currentUserId != null) {
                val famId = updatedTask.familyId.ifEmpty { getActiveFamilyId() }
                val completionPayload = mapOf<String, Any?>(
                    "isCompleted" to updatedTask.isCompleted,
                    "completedAtMillis" to updatedTask.completedAtMillis,
                    "completedByMemberId" to updatedTask.completedByMemberId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (famId.isNotEmpty()) {
                    firestore?.collection("families")?.document(famId)?.collection("tasks")?.document(updatedTask.id)
                        ?.update(completionPayload)
                        ?.addOnFailureListener { exception ->
                            handleFirestoreError(exception, OperationType.UPDATE, "families/$famId/tasks/${updatedTask.id}")
                        }
                } else {
                    firestore?.collection("tasks")?.document(updatedTask.id)
                        ?.update(completionPayload)
                        ?.addOnFailureListener { exception ->
                            handleFirestoreError(exception, OperationType.UPDATE, "tasks/${updatedTask.id}")
                        }
                }
            }
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
        if (!currentMember.isAdmin) {
            // Children cannot perform full task edits; only authorized parents can edit tasks
            return
        }
        val famId = updatedTask.familyId.ifEmpty { getActiveFamilyId() }
        val taskWithFamily = updatedTask.copy(familyId = famId)
        _tasks.value = _tasks.value.map { if (it.id == taskWithFamily.id) taskWithFamily else it }
        val currentUserId = getCurrentUserId()
        if (firestore != null && currentUserId != null) {
            val payload = hashMapOf<String, Any?>(
                "id" to taskWithFamily.id,
                "familyId" to famId,
                "title" to taskWithFamily.title,
                "description" to taskWithFamily.description,
                "category" to taskWithFamily.category.name,
                "assignedToMemberId" to taskWithFamily.assignedToMemberId,
                "dateEpochDay" to taskWithFamily.dateEpochDay,
                "dueTimeFormatted" to taskWithFamily.dueTimeFormatted,
                "priority" to taskWithFamily.priority.name,
                "points" to taskWithFamily.points,
                "recurrence" to taskWithFamily.recurrence.name,
                "isCompleted" to taskWithFamily.isCompleted,
                "completedAtMillis" to taskWithFamily.completedAtMillis,
                "completedByMemberId" to taskWithFamily.completedByMemberId,
                "approvedByParent" to taskWithFamily.approvedByParent,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (famId.isNotEmpty()) {
                firestore?.collection("families")?.document(famId)?.collection("tasks")?.document(taskWithFamily.id)
                    ?.set(payload, com.google.firebase.firestore.SetOptions.merge())
                    ?.addOnFailureListener { exception ->
                        handleFirestoreError(exception, OperationType.UPDATE, "families/$famId/tasks/${taskWithFamily.id}")
                    }
            } else {
                payload["userId"] = currentUserId
                firestore?.collection("tasks")?.document(taskWithFamily.id)
                    ?.set(payload, com.google.firebase.firestore.SetOptions.merge())
                    ?.addOnFailureListener { exception ->
                        handleFirestoreError(exception, OperationType.UPDATE, "tasks/${taskWithFamily.id}")
                    }
            }
        }
    }

    fun deleteTask(taskId: String) {
        val famId = getActiveFamilyId()
        _tasks.value = _tasks.value.filter { it.id != taskId }
        val currentUserId = getCurrentUserId()
        if (firestore != null && currentUserId != null) {
            if (famId.isNotEmpty()) {
                firestore?.collection("families")?.document(famId)?.collection("tasks")?.document(taskId)?.delete()
                    ?.addOnFailureListener { exception ->
                        handleFirestoreError(exception, OperationType.DELETE, "families/$famId/tasks/$taskId")
                    }
            } else {
                firestore?.collection("tasks")?.document(taskId)?.delete()
                    ?.addOnFailureListener { exception ->
                        handleFirestoreError(exception, OperationType.DELETE, "tasks/$taskId")
                    }
            }
        }
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

        val currentUserId = getCurrentUserId()
        if (firestore != null && currentUserId != null) {
            val payload = hashMapOf(
                "id" to newDoc.id,
                "userId" to currentUserId,
                "folderId" to newDoc.folderId,
                "name" to newDoc.name,
                "extension" to newDoc.extension,
                "sizeFormatted" to newDoc.sizeFormatted,
                "uploadedByName" to newDoc.uploadedByName,
                "uploadDateFormatted" to newDoc.uploadDateFormatted,
                "isDriveSynced" to newDoc.isDriveSynced,
                "driveFileUrl" to newDoc.driveFileUrl,
                "createdAt" to FieldValue.serverTimestamp()
            )
            firestore?.collection("documents")?.document(newDoc.id)?.set(payload)
                ?.addOnFailureListener { exception ->
                    handleFirestoreError(exception, OperationType.CREATE, "documents/${newDoc.id}")
                }
        }

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

            val currentUserId = getCurrentUserId()
            if (firestore != null && currentUserId != null) {
                val file = _files.value.find { it.id == fileId }
                if (file != null) {
                    val payload = hashMapOf(
                        "id" to file.id,
                        "userId" to currentUserId,
                        "folderId" to file.folderId,
                        "name" to trimmed,
                        "extension" to file.extension,
                        "sizeFormatted" to file.sizeFormatted,
                        "uploadedByName" to file.uploadedByName,
                        "uploadDateFormatted" to file.uploadDateFormatted,
                        "isDriveSynced" to file.isDriveSynced,
                        "driveFileUrl" to file.driveFileUrl,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    firestore?.collection("documents")?.document(fileId)?.set(payload)
                        ?.addOnFailureListener { exception ->
                            handleFirestoreError(exception, OperationType.UPDATE, "documents/$fileId")
                        }
                }
            }
        }
    }

    fun deleteDocument(fileId: String) {
        _files.value = _files.value.filter { it.id != fileId }
        val currentUserId = getCurrentUserId()
        if (firestore != null && currentUserId != null) {
            firestore?.collection("documents")?.document(fileId)?.delete()
                ?.addOnFailureListener { exception ->
                    handleFirestoreError(exception, OperationType.DELETE, "documents/$fileId")
                }
        }
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

    private var appContext: android.content.Context? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var tasksListenerRegistration: ListenerRegistration? = null
    private var familyTasksListenerRegistration: ListenerRegistration? = null
    private var familyMembersListenerRegistration: ListenerRegistration? = null
    private var docsListenerRegistration: ListenerRegistration? = null
    private var activeSyncUserId: String? = null

    fun setContext(context: android.content.Context) {
        appContext = context.applicationContext
        if (firestore == null) {
            val dbId = context.applicationContext.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(dbId)
            try {
                val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder(db.firestoreSettings)
                    .setLocalCacheSettings(
                        com.google.firebase.firestore.PersistentCacheSettings.newBuilder().build()
                    )
                    .build()
                db.firestoreSettings = settings
            } catch (_: Exception) {
                // If firestore instance settings have already been locked
            }
            firestore = db
        }
        checkAndAttachAuthListener()
    }

    private val directTaskExecutor = java.util.concurrent.Executor { it.run() }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener(directTaskExecutor) { if (cont.isActive) cont.resume(it) }
            addOnFailureListener(directTaskExecutor) { if (cont.isActive) cont.resumeWithException(it) }
        }

    fun checkAndAttachAuthListener() {
        val auth = getSafeAuth() ?: return
        if (authListener == null) {
            authListener = FirebaseAuth.AuthStateListener { fbAuth ->
                val user = fbAuth.currentUser
                if (user != null) {
                    attachFirestoreSync(user.uid)
                } else {
                    detachFirestoreSync()
                }
            }
            auth.addAuthStateListener(authListener!!)
        } else {
            auth.currentUser?.let { attachFirestoreSync(it.uid) }
        }
    }

    private fun attachFirestoreSync(userId: String) {
        val db = firestore ?: return
        val famId = getActiveFamilyId()
        if (activeSyncUserId == userId && familyTasksListenerRegistration != null && familyMembersListenerRegistration != null) return
        detachFirestoreSync()
        activeSyncUserId = userId

        // Ensure hardcoded demo data is completely removed from real family accounts
        val demoMemberIds = setOf("user_moran", "user_david", "user_maya", "user_noam", "user_daniel")
        if (_members.value.any { it.id in demoMemberIds }) {
            val user = getSafeAuth()?.currentUser
            val selfMember = FamilyMember(
                id = userId,
                name = user?.displayName ?: if (_settings.value.language == AppLanguage.HEBREW) "משתמש" else "User",
                email = user?.email ?: _settings.value.googleAccountEmail,
                role = UserRole.PRIMARY_ADMIN,
                avatarEmoji = "👩‍💻",
                colorHex = 0xFF1A73E8,
                earnedPoints = 0,
                completedTasksCount = 0,
                isCurrentUser = true
            )
            _members.value = listOf(selfMember)
        }
        val demoTaskIds = setOf("task_1", "task_2", "task_3", "task_4")
        if (_tasks.value.any { it.id in demoTaskIds }) {
            _tasks.value = emptyList()
        }
        val demoDocIds = setOf("doc_1", "doc_2", "doc_3", "doc_4", "doc_5", "doc_6")
        if (_files.value.any { it.id in demoDocIds }) {
            _files.value = emptyList()
        }
        val demoPeriodIds = setOf("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9")
        if (_periods.value.any { it.id in demoPeriodIds }) {
            _periods.value = emptyList()
        }

        val famDocRef = db.collection("families").document(famId)
        val memberDocRef = famDocRef.collection("members").document(userId)
        val joinedCode = _settings.value.joinedFamilyCode

        if (!joinedCode.isNullOrBlank()) {
            // Joining flow or existing joined member reconnecting:
            // Check if the member document already exists before attempting invitation redemption
            memberDocRef.get().addOnSuccessListener { memSnapshot ->
                if (!memSnapshot.exists()) {
                    // New member joining: register through validated atomic invitation redemption FIRST
                    // before adding UID to family.memberIds
                    val current = currentMember
                    val memPayload = hashMapOf<String, Any?>(
                        "id" to userId,
                        "name" to current.name,
                        "email" to (_settings.value.googleAccountEmail.ifBlank { getSafeAuth()?.currentUser?.email ?: "" }),
                        "role" to UserRole.MEMBER_KID.name,
                        "avatarEmoji" to current.avatarEmoji,
                        "colorHex" to current.colorHex,
                        "earnedPoints" to 0,
                        "spentPoints" to 0,
                        "completedTasksCount" to 0,
                        "schoolGrade" to current.schoolGrade,
                        "inviteCode" to joinedCode
                    )
                    val inviteDocRef = db.collection("family_invites").document(joinedCode)
                    val batch = db.batch()
                    batch.set(memberDocRef, memPayload)
                    batch.update(
                        inviteDocRef,
                        mapOf(
                            "isUsed" to true,
                            "usedByUid" to userId,
                            "usedAtMillis" to System.currentTimeMillis(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    batch.commit()
                        .addOnSuccessListener {
                            // Only AFTER validated atomic invitation redemption succeeds, add UID to family.memberIds
                            famDocRef.update("memberIds", FieldValue.arrayUnion(userId))
                                .addOnFailureListener { e ->
                                    handleFirestoreError(e, OperationType.UPDATE, "families/$famId")
                                }
                            attachFamilyCollectionListeners(db, famId, userId)
                        }
                        .addOnFailureListener { e ->
                            handleFirestoreError(e, OperationType.CREATE, "families/$famId/members/$userId (atomic invite consumption)")
                        }
                } else {
                    // Legitimate existing member reconnecting
                    famDocRef.get().addOnSuccessListener { famSnapshot ->
                        if (famSnapshot.exists()) {
                            val membersList = (famSnapshot.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                            if (!membersList.contains(userId)) {
                                famDocRef.update("memberIds", FieldValue.arrayUnion(userId))
                                    .addOnFailureListener { e ->
                                        handleFirestoreError(e, OperationType.UPDATE, "families/$famId")
                                    }
                            }
                        }
                    }.addOnFailureListener { e ->
                        handleFirestoreError(e, OperationType.GET, "families/$famId")
                    }
                    attachFamilyCollectionListeners(db, famId, userId)
                }
            }.addOnFailureListener { e ->
                handleFirestoreError(e, OperationType.GET, "families/$famId/members/$userId")
            }
        } else {
            // Family creator or pre-registered existing member flow (no joinedFamilyCode)
            famDocRef.get().addOnSuccessListener { famSnapshot ->
                if (!famSnapshot.exists()) {
                    val famPayload = hashMapOf(
                        "id" to famId,
                        "familyName" to _settings.value.getFamilyTitle(false),
                        "createdById" to userId,
                        "inviteCode" to _settings.value.getDynamicInviteCode(),
                        "memberIds" to listOf(userId),
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                    famDocRef.set(famPayload)
                        .addOnSuccessListener {
                            // Top-level invite mapping created only after family exists
                            val inviteCode = _settings.value.getDynamicInviteCode()
                            db.collection("family_invites").document(inviteCode).set(
                                hashMapOf(
                                    "inviteCode" to inviteCode,
                                    "familyId" to famId,
                                    "createdById" to userId,
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            ).addOnFailureListener { e ->
                                handleFirestoreError(e, OperationType.CREATE, "family_invites/$inviteCode")
                            }
                            ensureMemberProfileForCreatorOrExistingMember(memberDocRef, famId, userId, isCreator = true)
                            attachFamilyCollectionListeners(db, famId, userId)
                        }
                        .addOnFailureListener { e ->
                            handleFirestoreError(e, OperationType.CREATE, "families/$famId")
                        }
                } else {
                    val membersList = (famSnapshot.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    val isCreator = famSnapshot.getString("createdById") == userId
                    if (isCreator && !membersList.contains(userId)) {
                        famDocRef.update("memberIds", FieldValue.arrayUnion(userId))
                            .addOnFailureListener { e ->
                                handleFirestoreError(e, OperationType.UPDATE, "families/$famId")
                            }
                    }
                    if (isCreator || membersList.contains(userId)) {
                        ensureMemberProfileForCreatorOrExistingMember(memberDocRef, famId, userId, isCreator = isCreator)
                    }
                    attachFamilyCollectionListeners(db, famId, userId)
                }
            }.addOnFailureListener { e ->
                handleFirestoreError(e, OperationType.GET, "families/$famId")
            }
        }

        // 3. Document listener
        docsListenerRegistration = db.collection("documents")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, "documents")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val remoteDocs = snapshot.documents.mapNotNull { doc ->
                        try {
                            val id = doc.getString("id") ?: doc.id
                            val folderId = doc.getString("folderId") ?: "f_school"
                            val name = doc.getString("name") ?: return@mapNotNull null
                            val extension = doc.getString("extension") ?: "PDF"
                            val size = doc.getString("sizeFormatted") ?: "1.0 MB"
                            val uploadedBy = doc.getString("uploadedByName") ?: "Member"
                            val uploadDate = doc.getString("uploadDateFormatted") ?: "Today"
                            val isDriveSynced = doc.getBoolean("isDriveSynced") ?: true
                            val driveFileUrl = doc.getString("driveFileUrl") ?: "https://drive.google.com/family-hub"
                            FamilyDocument(
                                id = id,
                                folderId = folderId,
                                name = name,
                                extension = extension,
                                sizeFormatted = size,
                                uploadedByName = uploadedBy,
                                uploadDateFormatted = uploadDate,
                                isDriveSynced = isDriveSynced,
                                driveFileUrl = driveFileUrl
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (remoteDocs.isNotEmpty()) {
                        _files.value = remoteDocs
                    }
                }
            }
    }

    private fun ensureMemberProfileForCreatorOrExistingMember(
        memberDocRef: com.google.firebase.firestore.DocumentReference,
        famId: String,
        userId: String,
        isCreator: Boolean
    ) {
        memberDocRef.get().addOnSuccessListener { memSnapshot ->
            if (!memSnapshot.exists()) {
                val current = currentMember
                val safeRole = if (isCreator) current.role.name else UserRole.MEMBER_KID.name
                val safePoints = if (isCreator) current.earnedPoints else 0
                val memPayload = hashMapOf<String, Any?>(
                    "id" to userId,
                    "name" to current.name,
                    "email" to (_settings.value.googleAccountEmail.ifBlank { getSafeAuth()?.currentUser?.email ?: "" }),
                    "role" to safeRole,
                    "avatarEmoji" to current.avatarEmoji,
                    "colorHex" to current.colorHex,
                    "earnedPoints" to safePoints,
                    "spentPoints" to 0,
                    "completedTasksCount" to if (isCreator) current.completedTasksCount else 0,
                    "schoolGrade" to current.schoolGrade
                )
                memberDocRef.set(memPayload).addOnFailureListener { e ->
                    handleFirestoreError(e, OperationType.CREATE, "families/$famId/members/$userId")
                }
            }
        }.addOnFailureListener { e ->
            handleFirestoreError(e, OperationType.GET, "families/$famId/members/$userId")
        }
    }

    private fun attachFamilyCollectionListeners(db: FirebaseFirestore, famId: String, userId: String) {
        familyTasksListenerRegistration?.remove()
        familyTasksListenerRegistration = db.collection("families").document(famId).collection("tasks")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, "families/$famId/tasks")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val remoteTasks = snapshot.documents.mapNotNull { parseTaskDoc(it, famId) }
                    _tasks.value = remoteTasks
                }
            }

        familyMembersListenerRegistration?.remove()
        familyMembersListenerRegistration = db.collection("families").document(famId).collection("members")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, "families/$famId/members")
                    return@addSnapshotListener
                }
                if (snapshot != null && !snapshot.isEmpty) {
                    val remoteMembers = snapshot.documents.mapNotNull { parseMemberDoc(it, userId) }
                    if (remoteMembers.isNotEmpty()) {
                        _members.value = remoteMembers
                    }
                }
            }
    }

    private fun mergeTasks(incoming: List<FamilyTask>) {
        val existingMap = _tasks.value.associateBy { it.id }.toMutableMap()
        for (task in incoming) {
            existingMap[task.id] = task
        }
        _tasks.value = existingMap.values.toList()
    }

    private fun parseTaskDoc(doc: com.google.firebase.firestore.DocumentSnapshot, defaultFamilyId: String): FamilyTask? {
        return try {
            val id = doc.getString("id") ?: doc.id
            val title = doc.getString("title") ?: return null
            val desc = doc.getString("description") ?: ""
            val catStr = doc.getString("category") ?: TaskCategory.OTHER.name
            val category = try { TaskCategory.valueOf(catStr) } catch (_: Exception) { TaskCategory.OTHER }
            val assigned = doc.getString("assignedToMemberId") ?: ""
            val epochDay = doc.getLong("dateEpochDay") ?: todayEpoch
            val dueTime = doc.getString("dueTimeFormatted") ?: "18:00"
            val prioStr = doc.getString("priority") ?: TaskPriority.MEDIUM.name
            val priority = try { TaskPriority.valueOf(prioStr) } catch (_: Exception) { TaskPriority.MEDIUM }
            val points = doc.getLong("points")?.toInt() ?: 10
            val recStr = doc.getString("recurrence") ?: TaskRecurrence.DAILY.name
            val recurrence = try { TaskRecurrence.valueOf(recStr) } catch (_: Exception) { TaskRecurrence.DAILY }
            val isCompleted = doc.getBoolean("isCompleted") ?: false
            val completedAt = doc.getLong("completedAtMillis")
            val completedBy = doc.getString("completedByMemberId")
            val famId = doc.getString("familyId") ?: defaultFamilyId
            val approved = doc.getBoolean("approvedByParent") ?: false
            val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time ?: System.currentTimeMillis()
            val updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.time ?: createdAt

            FamilyTask(
                id = id,
                title = title,
                description = desc,
                category = category,
                assignedToMemberId = assigned,
                dateEpochDay = epochDay,
                dueTimeFormatted = dueTime,
                priority = priority,
                points = points,
                recurrence = recurrence,
                isCompleted = isCompleted,
                completedAtMillis = completedAt,
                completedByMemberId = completedBy,
                familyId = famId,
                approvedByParent = approved,
                createdAtMillis = createdAt,
                updatedAtMillis = updatedAt
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMemberDoc(doc: com.google.firebase.firestore.DocumentSnapshot, currentUid: String): FamilyMember? {
        return try {
            val id = doc.getString("id") ?: doc.id
            val name = doc.getString("name") ?: return null
            val email = doc.getString("email") ?: ""
            val roleStr = doc.getString("role") ?: UserRole.MEMBER_KID.name
            val role = try { UserRole.valueOf(roleStr) } catch (_: Exception) { UserRole.MEMBER_KID }
            val avatar = doc.getString("avatarEmoji") ?: "👧"
            val color = doc.getLong("colorHex") ?: 0xFF1A73E8
            val points = doc.getLong("earnedPoints")?.toInt() ?: 0
            val completed = doc.getLong("completedTasksCount")?.toInt() ?: 0
            val grade = doc.getString("schoolGrade")
            FamilyMember(
                id = id,
                name = name,
                email = email,
                role = role,
                avatarEmoji = avatar,
                colorHex = color,
                earnedPoints = points,
                completedTasksCount = completed,
                schoolGrade = grade,
                isCurrentUser = (id == currentUid)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun detachFirestoreSync() {
        tasksListenerRegistration?.remove()
        tasksListenerRegistration = null
        familyTasksListenerRegistration?.remove()
        familyTasksListenerRegistration = null
        familyMembersListenerRegistration?.remove()
        familyMembersListenerRegistration = null
        docsListenerRegistration?.remove()
        docsListenerRegistration = null
        activeSyncUserId = null
    }

    fun detachAll() {
        detachFirestoreSync()
        authListener?.let { listener ->
            getSafeAuth()?.removeAuthStateListener(listener)
        }
        authListener = null
    }

    suspend fun createTaskInCloud(task: FamilyTask): Result<String> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        val famId = task.familyId
        return try {
            val payload = hashMapOf(
                "id" to task.id,
                "userId" to currentUserId,
                "familyId" to famId,
                "title" to task.title,
                "description" to task.description,
                "category" to task.category.name,
                "assignedToMemberId" to task.assignedToMemberId,
                "dateEpochDay" to task.dateEpochDay,
                "dueTimeFormatted" to task.dueTimeFormatted,
                "priority" to task.priority.name,
                "points" to task.points,
                "recurrence" to task.recurrence.name,
                "isCompleted" to task.isCompleted,
                "completedAtMillis" to task.completedAtMillis,
                "completedByMemberId" to task.completedByMemberId,
                "approvedByParent" to task.approvedByParent,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (famId.isNotEmpty()) {
                db.collection("families").document(famId).collection("tasks").document(task.id).set(payload).awaitResult()
            } else {
                db.collection("tasks").document(task.id).set(payload).awaitResult()
            }
            Result.success(task.id)
        } catch (e: Exception) {
            val path = if (famId.isNotEmpty()) "families/$famId/tasks/${task.id}" else "tasks/${task.id}"
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun completeTaskInCloud(
        taskId: String,
        familyId: String,
        isCompleted: Boolean = true,
        completedAtMillis: Long? = if (isCompleted) System.currentTimeMillis() else null,
        completedByMemberId: String? = if (isCompleted) getCurrentUserId() else null
    ): Result<Unit> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        return try {
            val completionPayload = mapOf<String, Any?>(
                "isCompleted" to isCompleted,
                "completedAtMillis" to completedAtMillis,
                "completedByMemberId" to (completedByMemberId ?: if (isCompleted) currentUserId else null),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (familyId.isNotEmpty()) {
                db.collection("families").document(familyId).collection("tasks").document(taskId)
                    .update(completionPayload).awaitResult()
            } else {
                db.collection("tasks").document(taskId)
                    .update(completionPayload).awaitResult()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val path = if (familyId.isNotEmpty()) "families/$familyId/tasks/$taskId" else "tasks/$taskId"
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    private suspend fun isUserAdminOfFamily(
        db: FirebaseFirestore,
        familyId: String,
        userId: String
    ): Boolean {
        val localMember = _members.value.find { it.id == userId }
        if (localMember != null) {
            return localMember.isAdmin
        }
        val memSnap = runCatching {
            db.collection("families").document(familyId).collection("members").document(userId).get().awaitResult()
        }.getOrNull()
        if (memSnap != null && memSnap.exists()) {
            val roleStr = memSnap.getString("role") ?: ""
            return roleStr == UserRole.PRIMARY_ADMIN.name || roleStr == UserRole.SECONDARY_ADMIN.name
        }
        val famSnap = runCatching {
            db.collection("families").document(familyId).get().awaitResult()
        }.getOrNull()
        return famSnap?.getString("createdById") == userId
    }

    suspend fun updateTaskInCloud(task: FamilyTask): Result<Unit> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        val famId = task.familyId
        if (famId.isNotEmpty() && !isUserAdminOfFamily(db, famId, currentUserId)) {
            return completeTaskInCloud(
                taskId = task.id,
                familyId = famId,
                isCompleted = task.isCompleted,
                completedAtMillis = if (task.isCompleted) (task.completedAtMillis ?: System.currentTimeMillis()) else null,
                completedByMemberId = if (task.isCompleted) (task.completedByMemberId ?: currentUserId) else null
            )
        }
        return try {
            val payload = hashMapOf<String, Any?>(
                "id" to task.id,
                "familyId" to famId,
                "title" to task.title,
                "description" to task.description,
                "category" to task.category.name,
                "assignedToMemberId" to task.assignedToMemberId,
                "dateEpochDay" to task.dateEpochDay,
                "dueTimeFormatted" to task.dueTimeFormatted,
                "priority" to task.priority.name,
                "points" to task.points,
                "recurrence" to task.recurrence.name,
                "isCompleted" to task.isCompleted,
                "completedAtMillis" to task.completedAtMillis,
                "completedByMemberId" to task.completedByMemberId,
                "approvedByParent" to task.approvedByParent,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (famId.isNotEmpty()) {
                db.collection("families").document(famId).collection("tasks").document(task.id)
                    .set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitResult()
            } else {
                payload["userId"] = currentUserId
                db.collection("tasks").document(task.id)
                    .set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitResult()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val path = if (famId.isNotEmpty()) "families/$famId/tasks/${task.id}" else "tasks/${task.id}"
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteTaskFromCloud(taskId: String, familyId: String? = null): Result<Unit> {
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        return try {
            if (!familyId.isNullOrEmpty()) {
                db.collection("families").document(familyId).collection("tasks").document(taskId).delete().awaitResult()
            } else {
                db.collection("tasks").document(taskId).delete().awaitResult()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val path = if (!familyId.isNullOrEmpty()) "families/$familyId/tasks/$taskId" else "tasks/$taskId"
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun createInviteInCloud(inviteCode: String, familyId: String): Result<String> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        return try {
            val payload = hashMapOf(
                "inviteCode" to inviteCode,
                "familyId" to familyId,
                "createdById" to currentUserId,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("family_invites").document(inviteCode).set(payload).awaitResult()
            Result.success(inviteCode)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "family_invites/$inviteCode")
            Result.failure(e)
        }
    }

    suspend fun joinFamilyWithInviteInCloud(
        inviteCode: String,
        familyId: String? = null,
        memberName: String = "Family Member"
    ): Result<String> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        val cleanCode = inviteCode.trim()
        if (cleanCode.isEmpty()) {
            return Result.failure(IllegalArgumentException("Invite code cannot be blank"))
        }
        return try {
            val inviteDocRef = db.collection("family_invites").document(cleanCode)
            val resolvedFamilyId = if (!familyId.isNullOrBlank()) {
                familyId
            } else {
                val inviteSnapshot = inviteDocRef.get().awaitResult()
                if (!inviteSnapshot.exists()) {
                    throw IllegalArgumentException("Invalid invitation code: $cleanCode")
                }
                inviteSnapshot.getString("familyId")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Invitation missing familyId")
            }

            val famDocRef = db.collection("families").document(resolvedFamilyId)
            val memberDocRef = famDocRef.collection("members").document(currentUserId)

            // 1. Atomic invitation redemption MUST succeed first to register member doc and consume invite
            val memPayload = hashMapOf<String, Any?>(
                "id" to currentUserId,
                "name" to memberName,
                "email" to (getSafeAuth()?.currentUser?.email ?: ""),
                "role" to UserRole.MEMBER_KID.name,
                "avatarEmoji" to "🧒",
                "colorHex" to 0xFF1A73E8L,
                "earnedPoints" to 0,
                "spentPoints" to 0,
                "completedTasksCount" to 0,
                "inviteCode" to cleanCode
            )
            val batch = db.batch()
            batch.set(memberDocRef, memPayload)
            batch.update(
                inviteDocRef,
                mapOf(
                    "isUsed" to true,
                    "usedByUid" to currentUserId,
                    "usedAtMillis" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().awaitResult()

            // 2. Only AFTER atomic invitation redemption succeeds, add UID to family.memberIds
            famDocRef.update("memberIds", FieldValue.arrayUnion(currentUserId)).awaitResult()

            Result.success(resolvedFamilyId)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "family_invites/$cleanCode")
            Result.failure(e)
        }
    }

    suspend fun reconnectExistingMemberInCloud(
        familyId: String,
        memberName: String = "Family Member"
    ): Result<FamilyMember> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        return try {
            val famDocRef = db.collection("families").document(familyId)
            val memberDocRef = famDocRef.collection("members").document(currentUserId)
            val memSnapshot = memberDocRef.get().awaitResult()
            if (memSnapshot.exists()) {
                val famSnapshot = famDocRef.get().awaitResult()
                if (famSnapshot.exists()) {
                    val membersList = (famSnapshot.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    if (!membersList.contains(currentUserId)) {
                        famDocRef.update("memberIds", FieldValue.arrayUnion(currentUserId)).awaitResult()
                    }
                }
                val parsed = parseMemberDoc(memSnapshot, currentUserId)
                    ?: FamilyMember(
                        id = currentUserId,
                        name = memberName,
                        email = getSafeAuth()?.currentUser?.email ?: "",
                        role = UserRole.MEMBER_KID,
                        avatarEmoji = "🧒",
                        colorHex = 0xFF1A73E8L,
                        isCurrentUser = true
                    )
                Result.success(parsed)
            } else {
                val famSnapshot = famDocRef.get().awaitResult()
                if (!famSnapshot.exists()) {
                    throw IllegalStateException("Family $familyId does not exist")
                }
                val membersList = (famSnapshot.get("memberIds") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val isCreator = famSnapshot.getString("createdById") == currentUserId
                if (!isCreator && !membersList.contains(currentUserId)) {
                    throw SecurityException("User $currentUserId is not an existing member of $familyId")
                }
                val role = if (isCreator) UserRole.PRIMARY_ADMIN else UserRole.MEMBER_KID
                val memPayload = hashMapOf<String, Any?>(
                    "id" to currentUserId,
                    "name" to memberName,
                    "email" to (getSafeAuth()?.currentUser?.email ?: ""),
                    "role" to role.name,
                    "avatarEmoji" to if (isCreator) "👩‍💻" else "🧒",
                    "colorHex" to 0xFF1A73E8L,
                    "earnedPoints" to 0,
                    "spentPoints" to 0,
                    "completedTasksCount" to 0
                )
                memberDocRef.set(memPayload).awaitResult()
                if (isCreator && !membersList.contains(currentUserId)) {
                    famDocRef.update("memberIds", FieldValue.arrayUnion(currentUserId)).awaitResult()
                }
                Result.success(
                    FamilyMember(
                        id = currentUserId,
                        name = memberName,
                        email = getSafeAuth()?.currentUser?.email ?: "",
                        role = role,
                        avatarEmoji = if (isCreator) "👩‍💻" else "🧒",
                        colorHex = 0xFF1A73E8L,
                        earnedPoints = 0,
                        completedTasksCount = 0,
                        isCurrentUser = true
                    )
                )
            }
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "families/$familyId/members/$currentUserId")
            Result.failure(e)
        }
    }

    suspend fun getTasksFromCloud(familyId: String? = null): Result<List<FamilyTask>> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        return try {
            val snapshot = if (!familyId.isNullOrEmpty()) {
                db.collection("families").document(familyId).collection("tasks")
                    .get()
                    .awaitResult()
            } else {
                db.collection("tasks")
                    .whereEqualTo("userId", currentUserId)
                    .get()
                    .awaitResult()
            }
            val list = snapshot.documents.mapNotNull { doc ->
                parseTaskDoc(doc, familyId ?: doc.getString("familyId") ?: "")
            }
            Result.success(list)
        } catch (e: Exception) {
            val path = if (!familyId.isNullOrEmpty()) "families/$familyId/tasks" else "tasks"
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun createDocumentInCloud(doc: FamilyDocument): Result<String> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        return try {
            val payload = hashMapOf(
                "id" to doc.id,
                "userId" to currentUserId,
                "folderId" to doc.folderId,
                "name" to doc.name,
                "extension" to doc.extension,
                "sizeFormatted" to doc.sizeFormatted,
                "uploadedByName" to doc.uploadedByName,
                "uploadDateFormatted" to doc.uploadDateFormatted,
                "isDriveSynced" to doc.isDriveSynced,
                "driveFileUrl" to doc.driveFileUrl,
                "createdAt" to FieldValue.serverTimestamp()
            )
            db.collection("documents").document(doc.id).set(payload).awaitResult()
            Result.success(doc.id)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "documents/${doc.id}")
            Result.failure(e)
        }
    }

    suspend fun getDocumentsFromCloud(): Result<List<FamilyDocument>> {
        val currentUserId = getCurrentUserId()
            ?: return Result.failure(IllegalStateException("User not authenticated"))
        val db = firestore
            ?: return Result.failure(IllegalStateException("Firestore not initialized"))
        return try {
            val snapshot = db.collection("documents")
                .whereEqualTo("userId", currentUserId)
                .get()
                .awaitResult()
            val list = snapshot.documents.mapNotNull { doc ->
                val id = doc.getString("id") ?: doc.id
                val folderId = doc.getString("folderId") ?: "f_school"
                val name = doc.getString("name") ?: return@mapNotNull null
                val extension = doc.getString("extension") ?: "PDF"
                val size = doc.getString("sizeFormatted") ?: "1.0 MB"
                val uploadedBy = doc.getString("uploadedByName") ?: "Member"
                val uploadDate = doc.getString("uploadDateFormatted") ?: "Today"
                val isDriveSynced = doc.getBoolean("isDriveSynced") ?: true
                val driveFileUrl = doc.getString("driveFileUrl") ?: "https://drive.google.com/family-hub"
                FamilyDocument(
                    id = id,
                    folderId = folderId,
                    name = name,
                    extension = extension,
                    sizeFormatted = size,
                    uploadedByName = uploadedBy,
                    uploadDateFormatted = uploadDate,
                    isDriveSynced = isDriveSynced,
                    driveFileUrl = driveFileUrl
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "documents")
            Result.failure(e)
        }
    }

    fun sendInvitationNotification(inviteeName: String, email: String? = null) {
        val isHeb = _settings.value.language == AppLanguage.HEBREW
        val famTitle = _settings.value.getFamilyTitle(isHeb)
        val code = _settings.value.getDynamicInviteCode()
        val title = if (isHeb) "הזמנה חדשה לבית! 💌" else "Family Invitation Sent! 💌"
        val msg = if (isHeb)
            "הזמנה נשלחה ל-$inviteeName להצטרף ל-$famTitle (קוד: $code)"
        else
            "Invitation sent to $inviteeName to join $famTitle (Code: $code)"

        pushNotification(
            AppNotification(
                id = "notif_invite_${System.currentTimeMillis()}",
                title = title,
                message = msg,
                timestampMillis = System.currentTimeMillis(),
                type = NotificationType.MEMBER_INVITED,
                targetMemberName = inviteeName
            )
        )
    }

    private fun pushNotification(notification: AppNotification) {
        _notifications.value = listOf(notification) + _notifications.value
        _latestAlert.value = notification
        appContext?.let { ctx ->
            com.example.util.NotificationHelper.showPushNotification(
                context = ctx,
                channelId = if (notification.type == NotificationType.MEMBER_INVITED)
                    com.example.util.NotificationHelper.CHANNEL_INVITATIONS_ID
                else
                    com.example.util.NotificationHelper.CHANNEL_TASKS_ID,
                title = notification.title,
                message = notification.message
            )
        }
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
        val currentUid = getSafeAuth()?.currentUser?.uid ?: "user_admin_${System.currentTimeMillis() % 10000}"
        val resolvedEmail = adminEmail.trim().ifBlank { getSafeAuth()?.currentUser?.email ?: "Moranw@gmail.com" }
        val resolvedName = adminName.trim().ifBlank {
            getSafeAuth()?.currentUser?.displayName ?: if (_settings.value.language == AppLanguage.HEBREW) "מורן" else "Moran"
        }
        val primaryAdmin = FamilyMember(
            id = currentUid,
            name = resolvedName,
            email = resolvedEmail,
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
            googleAccountEmail = resolvedEmail
        )
        val uid = getCurrentUserId()
        if (uid != null) {
            attachFirestoreSync(uid)
        }
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
