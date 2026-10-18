package com.example.data

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.OfflineSyncDao
import com.example.data.local.OfflineSyncQueueEntity
import com.example.data.local.TaskDao
import com.example.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID

/**
 * Production Hybrid Repository (V1.6):
 * - Enforces a strict two-role permission model: PARENT and CHILD
 * - Preserves personal family identity (FATHER / MOTHER) distinct from authorization role (PARENT / CHILD)
 * - Implements an explicit startup state machine (LoadingAuthentication -> LoadingUserProfile -> LoadingFamilyMembership -> OnboardingRequired / ReadyToEnterDashboard / RecoverableError)
 * - Validates actual Firestore membership on Google Sign-In and session restoration without overwriting existing profiles or families
 * - Implements role-specific, domain-free invitations (/family_invites/{code}) with expiration & atomic redemption
 * - Maintains real-time Firestore snapshot listeners for active family members and pending invitations
 * - Protects the last remaining PARENT from removal or demotion
 */
class FamilyRepository(
    context: Context? = null,
    private val taskDao: TaskDao? = context?.let { AppDatabase.getInstance(it).taskDao() },
    private val offlineSyncDao: OfflineSyncDao? = context?.let { AppDatabase.getInstance(it).offlineSyncDao() },
    private val firestoreProvider: () -> FirebaseFirestore? = {
        runCatching { FirebaseFirestore.getInstance() }.getOrNull()
    },
    private val authProvider: () -> FirebaseAuth? = {
        runCatching { FirebaseAuth.getInstance() }.getOrNull()
    }
) {
    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ) : this(
        context = null,
        taskDao = null,
        offlineSyncDao = null,
        firestoreProvider = { firestore },
        authProvider = { auth }
    )

    companion object {
        private const val TAG = "FamilyRepository"
        private const val INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val INVITE_CODE_LENGTH = 6
        private val secureRandom = SecureRandom()

        /**
         * Generates a cryptographically random 6-character alphanumeric invitation code
         * excluding ambiguous characters (0, O, 1, I).
         */
        fun generateSecureInviteCode(): String {
            val sb = StringBuilder(INVITE_CODE_LENGTH)
            for (i in 0 until INVITE_CODE_LENGTH) {
                sb.append(INVITE_CODE_ALPHABET[secureRandom.nextInt(INVITE_CODE_ALPHABET.length)])
            }
            return sb.toString()
        }
    }

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupMutex = Mutex()
    @Volatile
    private var verifiedStartupUid: String? = null

    /** Configurable hooks for testing delayed Firestore responses and offline startup conditions. */
    @Volatile
    var simulatedFirestoreLatencyMs: Long = 0L

    @Volatile
    var simulateOfflineNetworkFailure: Boolean = false

    private var tasksListenerRegistration: ListenerRegistration? = null
    private var membersListenerRegistration: ListenerRegistration? = null
    private var rewardsListenerRegistration: ListenerRegistration? = null
    private var invitesListenerRegistration: ListenerRegistration? = null
    private var familyDocListenerRegistration: ListenerRegistration? = null

    private val _activeFamilyId = MutableStateFlow("fam_israeli_8492")
    val activeFamilyId: StateFlow<String> = _activeFamilyId.asStateFlow()

    // Explicit V1.6 Startup State Machine
    private val _startupState = MutableStateFlow<StartupState>(StartupState.LoadingAuthentication)
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    // V1.6 Membership validation state for authenticated Google users
    private val _membershipState = MutableStateFlow<FamilyMembershipState>(FamilyMembershipState.Checking)
    val membershipState: StateFlow<FamilyMembershipState> = _membershipState.asStateFlow()

    // V1.6 Pending Invitations (stored separately from active members)
    private val _pendingInvitations = MutableStateFlow<List<FamilyInvitation>>(emptyList())
    val pendingInvitations: StateFlow<List<FamilyInvitation>> = _pendingInvitations.asStateFlow()

    // Local invitation cache for immediate validation & offline/unit-test support
    private val invitationsByCode = mutableMapOf<String, FamilyInvitation>()

    // Offline cache of verified user profiles & families for seamless offline startup fallback
    private val localCachedProfilesByUid = mutableMapOf<String, Map<String, Any?>>()
    private val localCachedFamiliesById = mutableMapOf<String, Map<String, Any?>>()
    private val localCachedMembersByFamilyAndUid = mutableMapOf<String, Map<String, Any?>>()

    // 1. Family Display Name (editable by PARENT)
    private val _familyName = MutableStateFlow("משפחת ישראלי")
    val familyName: StateFlow<String> = _familyName.asStateFlow()

    // 2. Active Family Members (PARENT and CHILD only — never includes unaccepted pending invites)
    private val _members = MutableStateFlow(
        listOf(
            FamilyMember(
                id = "m_dad",
                name = "אבא (מנהל ראשי)",
                nameEn = "Dad (Parent)",
                familyIdentity = FamilyIdentity.FATHER,
                avatarEmoji = "👨",
                role = UserRole.PARENT,
                colorHex = 0xFF1A73E8,
                googleEmail = "aba.family@gmail.com",
                earnedPoints = 185,
                invitationAccepted = true
            ),
            FamilyMember(
                id = "m_mom",
                name = "אמא (מנהלת משנית)",
                nameEn = "Mom (Parent)",
                familyIdentity = FamilyIdentity.MOTHER,
                avatarEmoji = "👩",
                role = UserRole.PARENT,
                colorHex = 0xFF9334E6,
                googleEmail = "ima.family@gmail.com",
                earnedPoints = 210,
                invitationAccepted = true
            ),
            FamilyMember(
                id = "m_kid1",
                name = "נועם",
                nameEn = "Noam",
                familyIdentity = FamilyIdentity.SON,
                avatarEmoji = "👦",
                role = UserRole.CHILD,
                colorHex = 0xFF34A853,
                googleEmail = "noam.kid@gmail.com",
                schoolGrade = "כיתה ז'",
                earnedPoints = 340,
                invitationAccepted = true
            ),
            FamilyMember(
                id = "m_kid2",
                name = "מאיה",
                nameEn = "Maya",
                familyIdentity = FamilyIdentity.DAUGHTER,
                avatarEmoji = "👧",
                role = UserRole.CHILD,
                colorHex = 0xFFEA4335,
                googleEmail = "maya.kid@gmail.com",
                schoolGrade = "כיתה ד'",
                earnedPoints = 290,
                invitationAccepted = true
            ),
            FamilyMember(
                id = "m_kid3",
                name = "דניאל",
                nameEn = "Daniel",
                familyIdentity = FamilyIdentity.CHILD,
                avatarEmoji = "🧒",
                role = UserRole.CHILD,
                colorHex = 0xFFFBBC04,
                googleEmail = "daniel.kid@gmail.com",
                schoolGrade = "כיתה ב'",
                earnedPoints = 160,
                invitationAccepted = true
            )
        )
    )
    val members: StateFlow<List<FamilyMember>> = _members.asStateFlow()

    private val _currentMember = MutableStateFlow(_members.value.first())
    val currentMember: StateFlow<FamilyMember> = _currentMember.asStateFlow()

    // 3. Google Workspace & Cloud Family Group Synchronization State
    private val _isGoogleSignedIn = MutableStateFlow(false)
    val isGoogleSignedIn: StateFlow<Boolean> = _isGoogleSignedIn.asStateFlow()

    private val _googleUserEmail = MutableStateFlow("aba.family@gmail.com")
    val googleUserEmail: StateFlow<String> = _googleUserEmail.asStateFlow()

    private val _googleUserDisplayName = MutableStateFlow("אבא (משפחת ישראלי)")
    val googleUserDisplayName: StateFlow<String> = _googleUserDisplayName.asStateFlow()

    private val _googleUserPhotoUrl = MutableStateFlow<String?>(null)
    val googleUserPhotoUrl: StateFlow<String?> = _googleUserPhotoUrl.asStateFlow()

    private val _cloudFamilyGroup = MutableStateFlow(
        CloudFamilyGroup(
            familyId = "fam_israeli_8492",
            familyName = "משפחת ישראלי",
            inviteCode = "ISR849",
            ownerEmail = "aba.family@gmail.com",
            ownerName = "אבא (מנהל ראשי)",
            syncedMembersCount = 5
        )
    )
    val cloudFamilyGroup: StateFlow<CloudFamilyGroup> = _cloudFamilyGroup.asStateFlow()

    private val _autoSyncCalendar = MutableStateFlow(true)
    val autoSyncCalendar: StateFlow<Boolean> = _autoSyncCalendar.asStateFlow()

    private val _autoSyncDrive = MutableStateFlow(true)
    val autoSyncDrive: StateFlow<Boolean> = _autoSyncDrive.asStateFlow()

    // 4. App Preferences (RTL, Theme, First Day of Week, Views)
    private val _appLanguage = MutableStateFlow(AppLanguage.HEBREW)
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.SYSTEM)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _firstDayOfWeek = MutableStateFlow(FirstDayOfWeek.SUNDAY)
    val firstDayOfWeek: StateFlow<FirstDayOfWeek> = _firstDayOfWeek.asStateFlow()

    private val _biometricLockEnabled = MutableStateFlow(false)
    val biometricLockEnabled: StateFlow<Boolean> = _biometricLockEnabled.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(true)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _tenMinuteClassAlertsEnabled = MutableStateFlow(true)
    val tenMinuteClassAlertsEnabled: StateFlow<Boolean> = _tenMinuteClassAlertsEnabled.asStateFlow()

    private val _timetableViewMode = MutableStateFlow(TimetableViewMode.DAILY_LIST)
    val timetableViewMode: StateFlow<TimetableViewMode> = _timetableViewMode.asStateFlow()

    private val _filesViewMode = MutableStateFlow(FilesViewMode.FOLDERS)
    val filesViewMode: StateFlow<FilesViewMode> = _filesViewMode.asStateFlow()

    // 5. Real-Time Rewards Catalog
    private val _rewards = MutableStateFlow(
        listOf(
            RewardItem(
                id = "rew_1",
                familyId = "fam_israeli_8492",
                title = "שעת משחק נוספת במחשב / קונסולה",
                pointsCost = 50,
                iconEmoji = "🎮",
                description = "שעה שלמה של זמן מסך נוסף לבחירה"
            ),
            RewardItem(
                id = "rew_2",
                familyId = "fam_israeli_8492",
                title = "בחירת ארוחת ערב משפחתית (פיצה / המבורגר)",
                pointsCost = 100,
                iconEmoji = "🍕",
                description = "הילד בוחר את התפריט לארוחת ערב שישי או חמישי"
            ),
            RewardItem(
                id = "rew_3",
                familyId = "fam_israeli_8492",
                title = "יציאה לסרט בקולנוע עם פופקורן",
                pointsCost = 200,
                iconEmoji = "🍿",
                description = "כרטיס לסרט לבחירה כולל פופקורן ושתייה"
            ),
            RewardItem(
                id = "rew_4",
                familyId = "fam_israeli_8492",
                title = "פטור חד-פעמי משטיפת כלים / פינוי מדיח",
                pointsCost = 40,
                iconEmoji = "🎟️",
                description = "שובר דילוג על תורנות מטבח אחת"
            )
        )
    )
    val rewards: StateFlow<List<RewardItem>> = _rewards.asStateFlow()

    // 6. Household Tasks & Chores
    private val todayEpoch = LocalDate.now().toEpochDay()

    private val _tasks = MutableStateFlow(
        listOf(
            FamilyTask(
                id = "t1",
                title = "לסדר את החדר ואת שולחן הכתיבה",
                description = "לארגן מחברות, ספרים ומצעים לפני ארוחת ערב",
                category = TaskCategory.BEDROOM,
                assignedToMemberId = "m_kid1",
                dateEpochDay = todayEpoch,
                dueTimeFormatted = "17:00",
                isCompleted = false,
                priority = TaskPriority.HIGH,
                recurrence = TaskRecurrence.DAILY,
                points = 20
            ),
            FamilyTask(
                id = "t2",
                title = "פינוי מדיח כלים וסידור במטבח",
                description = "להחזיר צלחות וכוסות לארונות",
                category = TaskCategory.DISHES,
                assignedToMemberId = "m_kid2",
                dateEpochDay = todayEpoch,
                dueTimeFormatted = "18:30",
                isCompleted = true,
                priority = TaskPriority.MEDIUM,
                recurrence = TaskRecurrence.DAILY,
                points = 15
            ),
            FamilyTask(
                id = "t3",
                title = "שיעורי בית בחשבון ואנגלית",
                description = "עמודים 42-45 בחוברת ותרגול אוצר מילים",
                category = TaskCategory.HOMEWORK,
                assignedToMemberId = "m_kid1",
                dateEpochDay = todayEpoch,
                dueTimeFormatted = "16:00",
                isCompleted = false,
                priority = TaskPriority.HIGH,
                recurrence = TaskRecurrence.ONCE,
                points = 25
            ),
            FamilyTask(
                id = "t4",
                title = "טיול ערב עם הכלב וملוי מים",
                description = "סיבוב של 20 דקות בגינה הציבורית",
                category = TaskCategory.PETS,
                assignedToMemberId = "m_kid3",
                dateEpochDay = todayEpoch,
                dueTimeFormatted = "19:00",
                isCompleted = false,
                priority = TaskPriority.MEDIUM,
                recurrence = TaskRecurrence.DAILY,
                points = 15
            ),
            FamilyTask(
                id = "t5",
                title = "עריכת שולחן לארוחת ערב משפחתית",
                description = "צלחות, סכו\"ם, מפיות וקנקן מים",
                category = TaskCategory.TABLE,
                assignedToMemberId = "m_kid2",
                dateEpochDay = todayEpoch,
                dueTimeFormatted = "19:30",
                isCompleted = false,
                priority = TaskPriority.LOW,
                recurrence = TaskRecurrence.DAILY,
                points = 10
            ),
            FamilyTask(
                id = "t6",
                title = "הכנת תיק בית ספר ובגדים למחר",
                description = "לבדוק מערכת שעות וציוד ספורט",
                category = TaskCategory.HOMEWORK,
                assignedToMemberId = "m_kid3",
                dateEpochDay = todayEpoch + 1,
                dueTimeFormatted = "20:00",
                isCompleted = false,
                priority = TaskPriority.MEDIUM,
                recurrence = TaskRecurrence.DAILY,
                points = 15
            )
        )
    )
    val tasks: StateFlow<List<FamilyTask>> = _tasks.asStateFlow()

    val localRoomTasksFlow: Flow<List<Task>> = taskDao?.getAllTasksFlow() ?: emptyFlow()

    // 7. Kids School Timetable
    private val _schedulePeriods = MutableStateFlow(
        listOf(
            SchedulePeriod("p1", "m_kid1", SchoolDay.SUNDAY, 1, "מתמטיקה מוגבר", "חדר 204", "המורה רחל", "08:00", "08:45", 0xFF1A73E8),
            SchedulePeriod("p2", "m_kid1", SchoolDay.SUNDAY, 2, "אנגלית", "חדר 204", "המורה דיוויד", "08:50", "09:35", 0xFF34A853),
            SchedulePeriod("p3", "m_kid1", SchoolDay.SUNDAY, 3, "מדעים ופיזיקה", "מעבדה 2", "המורה יוסי", "09:50", "10:35", 0xFF9334E6),
            SchedulePeriod("p4", "m_kid1", SchoolDay.SUNDAY, 4, "היסטוריה", "חדר 204", "המורה מיכל", "10:45", "11:30", 0xFFFBBC04),
            SchedulePeriod("p5", "m_kid1", SchoolDay.SUNDAY, 5, "חינוך גופני", "אולם ספורט", "המורה דני", "11:45", "12:30", 0xFFEA4335),
            SchedulePeriod("p6", "m_kid1", SchoolDay.MONDAY, 1, "שפה והבעה", "חדר 204", "המורה גלית", "08:00", "08:45", 0xFF00ACC1),
            SchedulePeriod("p7", "m_kid1", SchoolDay.MONDAY, 2, "מתמטיקה מוגבר", "חדר 204", "המורה רחל", "08:50", "09:35", 0xFF1A73E8),
            SchedulePeriod("p8", "m_kid1", SchoolDay.MONDAY, 3, "מחשבים ורובוטיקה", "חדר מחשבים", "המורה אלון", "09:50", "10:35", 0xFF3F51B5),
            SchedulePeriod("p9", "m_kid1", SchoolDay.TUESDAY, 1, "אנגלית", "חדר 204", "המורה דיוויד", "08:00", "08:45", 0xFF34A853),
            SchedulePeriod("p10", "m_kid1", SchoolDay.WEDNESDAY, 1, "מדעים ופיזיקה", "מעבדה 2", "המורה יוסי", "08:00", "08:45", 0xFF9334E6),
            SchedulePeriod("p11", "m_kid1", SchoolDay.THURSDAY, 1, "מתמטיקה מוגבר", "חדר 204", "המורה רחל", "08:00", "08:45", 0xFF1A73E8),
            SchedulePeriod("p12", "m_kid1", SchoolDay.FRIDAY, 1, "שעה חברתית", "חדר 204", "המורה רחל", "08:00", "08:45", 0xFF00897B),
            SchedulePeriod("p13", "m_kid2", SchoolDay.SUNDAY, 1, "שפה וקריאה", "כיתה ד'2", "המורה שירה", "08:00", "08:45", 0xFFEA4335),
            SchedulePeriod("p14", "m_kid2", SchoolDay.SUNDAY, 2, "חשבון והנדסה", "כיתה ד'2", "המורה שירה", "08:50", "09:35", 0xFF1A73E8),
            SchedulePeriod("p15", "m_kid2", SchoolDay.SUNDAY, 3, "אמנות ויצירה", "חדר אמנות", "המורה נורית", "09:50", "10:35", 0xFF9334E6),
            SchedulePeriod("p16", "m_kid2", SchoolDay.MONDAY, 1, "אנגלית", "כיתה ד'2", "המורה תמר", "08:00", "08:45", 0xFF34A853),
            SchedulePeriod("p17", "m_kid2", SchoolDay.TUESDAY, 1, "מוזיקה ונגינה", "חדר מוזיקה", "המורה רון", "08:00", "08:45", 0xFFFBBC04),
            SchedulePeriod("p18", "m_kid3", SchoolDay.SUNDAY, 1, "קריאה וכתיבה", "כיתה ב'1", "המורה ענת", "08:00", "08:45", 0xFFFBBC04),
            SchedulePeriod("p19", "m_kid3", SchoolDay.SUNDAY, 2, "חשבון", "כיתה ב'1", "המורה ענת", "08:50", "09:35", 0xFF1A73E8),
            SchedulePeriod("p20", "m_kid3", SchoolDay.MONDAY, 1, "ספורט ומשחקי כדור", "מגרש", "המורה דני", "08:00", "08:45", 0xFF34A853)
        )
    )
    val schedulePeriods: StateFlow<List<SchedulePeriod>> = _schedulePeriods.asStateFlow()

    // 8. Google Drive Shared Family Folders & Documents
    private val _folders = MutableStateFlow(
        listOf(
            DocumentFolder("f_school", "לימודים ובית ספר", "School & Homework", "🎒", 0xFF1A73E8),
            DocumentFolder("f_medical", "רפואה ובריאות", "Medical & Health", "🩺", 0xFFEA4335),
            DocumentFolder("f_Artwork", "ציורים ויצירות ילדים", "Kids Artwork", "🎨", 0xFF9334E6),
            DocumentFolder("f_home", "מסמכי הבית וחשבונות", "Household & Bills", "🏠", 0xFF34A853),
            DocumentFolder("f_trips", "חופשות וטיולים", "Family Trips", "✈️", 0xFFFBBC04)
        )
    )
    val folders: StateFlow<List<DocumentFolder>> = _folders.asStateFlow()

    private val _documents = MutableStateFlow(
        listOf(
            FamilyDocument("d1", "f_school", "חוזר_הורים_טיול_שנתי_נועם.pdf", "PDF", "1.2 MB", "m_mom", "12/05/2025"),
            FamilyDocument("d2", "f_school", "רשימת_ספרי_לימוד_תשפו.pdf", "PDF", "840 KB", "m_dad", "10/05/2025"),
            FamilyDocument("d3", "f_medical", "פנקס_חיסונים_מעודכן_דניאל.pdf", "PDF", "2.1 MB", "m_mom", "04/05/2025"),
            FamilyDocument("d4", "f_Artwork", "ציור_משפחה_מאיה_שיעור_אמנות.jpg", "JPG", "3.4 MB", "m_kid2", "14/05/2025"),
            FamilyDocument("d5", "f_home", "חשבון_חשמל_וארנונה_מאי.pdf", "PDF", "620 KB", "m_dad", "01/05/2025"),
            FamilyDocument("d6", "f_trips", "כרטיסי_טיסה_ומלון_חופשת_קיץ.pdf", "PDF", "4.0 MB", "m_dad", "15/05/2025")
        )
    )
    val documents: StateFlow<List<FamilyDocument>> = _documents.asStateFlow()

    // 9. Real-time Notification Banner & History Feed
    private val _notifications = MutableStateFlow(
        listOf(
            FamilyNotificationAlert(
                id = "n1",
                title = "התראת שיעור (10 דקות לפני) 🔔",
                message = "נועם: שיעור מתמטיקה מוגבר מתחיל ב-08:00 בחדר 204",
                targetMemberName = "נועם",
                targetMemberEmoji = "👦",
                reason = AlertReason.UPCOMING_CLASS,
                timestampFormatted = "07:50"
            ),
            FamilyNotificationAlert(
                id = "n2",
                title = "משימה הושלמה בהצלחה! 🎉",
                message = "מאיה סיימה: פינוי מדיח כלים וסידור במטבח (+15 נק')",
                targetMemberName = "מאיה",
                targetMemberEmoji = "👧",
                reason = AlertReason.TASK_COMPLETED,
                timestampFormatted = "18:15"
            )
        )
    )
    val notifications: StateFlow<List<FamilyNotificationAlert>> = _notifications.asStateFlow()

    private val _activeBannerAlert = MutableStateFlow<FamilyNotificationAlert?>(null)
    val activeBannerAlert: StateFlow<FamilyNotificationAlert?> = _activeBannerAlert.asStateFlow()

    init {
        seedLocalRoomIfNeeded()
        observeAuthAndAttachFirestore()
    }

    private fun currentUid(): String {
        return authProvider()?.currentUser?.uid ?: _currentMember.value.id
    }

    private fun seedLocalRoomIfNeeded() {
        val dao = taskDao ?: return
        repoScope.launch {
            runCatching {
                val existing = dao.getAllTasksOnce()
                if (existing.isEmpty()) {
                    val coreTasks = _tasks.value.map { it.toCoreTask(_activeFamilyId.value) }
                    dao.upsertTasks(coreTasks)
                }
            }.onFailure { e ->
                Log.w(TAG, "Local Room initial seed skipped: ${e.message}")
            }
        }
    }

    /**
     * Observes FirebaseAuth state.
     * V1.6 CRITICAL FIX:
     * When a user signs in with Google, we NEVER automatically create a family or assign PARENT.
     * Instead, we verify actual membership in Firestore (`checkAuthenticatedUserMembership`).
     */
    private fun observeAuthAndAttachFirestore() {
        val auth = authProvider()
        if (auth == null) {
            _startupState.value = StartupState.OnboardingRequired()
            _membershipState.value = FamilyMembershipState.Unauthenticated
            return
        }

        _startupState.value = StartupState.LoadingAuthentication
        val initialUser = auth.currentUser
        if (initialUser != null) {
            _isGoogleSignedIn.value = true
            _googleUserEmail.value = initialUser.email ?: _googleUserEmail.value
            _googleUserDisplayName.value = initialUser.displayName ?: _googleUserDisplayName.value
            _googleUserPhotoUrl.value = initialUser.photoUrl?.toString() ?: _googleUserPhotoUrl.value
            repoScope.launch {
                checkAuthenticatedUserMembership(
                    uid = initialUser.uid,
                    email = initialUser.email ?: _googleUserEmail.value,
                    displayName = initialUser.displayName ?: _googleUserDisplayName.value,
                    photoUrl = initialUser.photoUrl?.toString()
                )
            }
        } else {
            _startupState.value = StartupState.OnboardingRequired()
            _membershipState.value = FamilyMembershipState.Unauthenticated
        }

        runCatching {
            auth.addAuthStateListener { firebaseAuth ->
                val currentUser = firebaseAuth.currentUser
                if (currentUser != null) {
                    _isGoogleSignedIn.value = true
                    _googleUserEmail.value = currentUser.email ?: _googleUserEmail.value
                    _googleUserDisplayName.value = currentUser.displayName ?: _googleUserDisplayName.value
                    _googleUserPhotoUrl.value = currentUser.photoUrl?.toString() ?: _googleUserPhotoUrl.value
                    repoScope.launch {
                        checkAuthenticatedUserMembership(
                            uid = currentUser.uid,
                            email = currentUser.email ?: _googleUserEmail.value,
                            displayName = currentUser.displayName ?: _googleUserDisplayName.value,
                            photoUrl = currentUser.photoUrl?.toString()
                        )
                    }
                } else {
                    detachFirestoreListeners()
                    _isGoogleSignedIn.value = false
                    _membershipState.value = FamilyMembershipState.Unauthenticated
                    _startupState.value = StartupState.OnboardingRequired()
                }
            }
        }
    }

    /**
     * Reads a Firestore document with automatic fallback to local offline cache (`Source.CACHE`)
     * when the network is temporarily unavailable. Throws if both server and cache fail.
     */
    private suspend fun getDocumentWithCacheFallback(docRef: DocumentReference): DocumentSnapshot {
        return try {
            docRef.get().await()
        } catch (networkError: Exception) {
            Log.w(TAG, "Primary get() failed for ${docRef.path}, attempting Source.CACHE: ${networkError.message}")
            docRef.get(Source.CACHE).await()
        }
    }

    /**
     * Retries startup verification after a recoverable network/Firestore error without resetting local state.
     */
    suspend fun retryStartupLoading(): FamilyMembershipState {
        val user = authProvider()?.currentUser
        return if (user != null) {
            checkAuthenticatedUserMembership(
                uid = user.uid,
                email = user.email ?: _googleUserEmail.value,
                displayName = user.displayName ?: _googleUserDisplayName.value,
                photoUrl = user.photoUrl?.toString() ?: _googleUserPhotoUrl.value,
                forceRefresh = true
            )
        } else {
            _startupState.value = StartupState.OnboardingRequired()
            _membershipState.value = FamilyMembershipState.Unauthenticated
            FamilyMembershipState.Unauthenticated
        }
    }

    /**
     * V1.6 Requirement 2 + Safe Startup State Machine:
     * Checks actual user profile and family membership in Firestore after Google authentication.
     * - Serialized via [startupMutex] so concurrent auth callbacks never race.
     * - Reads `/users/{uid}` and `/families/{familyId}/members/{uid}` without EVER overwriting
     *   existing profile fields (`displayName`, `familyIdentity`, `role`, `avatarEmoji`, `familyName`).
     * - Preserves `FATHER` vs `MOTHER` identity (never defaults an existing user to `MOTHER`).
     * - Distinguishes genuinely missing membership (`OnboardingRequired` / `NoMembership`) from
     *   transient/offline errors (`RecoverableError`).
     */
    suspend fun checkAuthenticatedUserMembership(
        uid: String,
        email: String,
        displayName: String,
        photoUrl: String? = null,
        forceRefresh: Boolean = false
    ): FamilyMembershipState = startupMutex.withLock {
        val currentMembership = _membershipState.value
        val currentStartup = _startupState.value
        if (!forceRefresh &&
            currentMembership is FamilyMembershipState.ActiveMember &&
            currentStartup is StartupState.ReadyToEnterDashboard &&
            currentStartup.uid == uid &&
            _currentMember.value.id == uid
        ) {
            return@withLock currentMembership
        }

        _membershipState.value = FamilyMembershipState.Checking
        _startupState.value = StartupState.LoadingUserProfile(uid)

        val db = firestoreProvider()
        if (db == null) {
            _startupState.value = StartupState.OnboardingRequired(
                uid = uid,
                email = email,
                displayName = displayName
            )
            _membershipState.value = FamilyMembershipState.NoMembership
            return@withLock FamilyMembershipState.NoMembership
        }

        return@withLock try {
            val userDocRef = db.collection("users").document(uid)
            val userSnap = getDocumentWithCacheFallback(userDocRef)

            val storedUserDisplayName = userSnap.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: userSnap.getString("name")?.takeIf { it.isNotBlank() }
            val storedUserRoleStr = userSnap.getString("role")?.takeIf { it.isNotBlank() }
            val storedUserIdentityStr = userSnap.getString("familyIdentity")?.takeIf { it.isNotBlank() }
            val storedUserEmoji = userSnap.getString("avatarEmoji")?.takeIf { it.isNotBlank() }
            val storedUserPhotoUrl = userSnap.getString("photoUrl")?.takeIf { it.isNotBlank() }
            val storedUserFamilyName = userSnap.getString("familyName")?.takeIf { it.isNotBlank() }

            var existingFamilyId = userSnap.getString("familyId")?.takeIf { it.isNotBlank() }

            // Fallback discovery: if /users/{uid} has no familyId, check if a family in /families already lists this uid
            if (existingFamilyId == null) {
                val discoveredFamilyDoc = runCatching {
                    db.collection("families")
                        .whereArrayContains("memberUids", uid)
                        .limit(1)
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                }.getOrNull() ?: runCatching {
                    db.collection("families")
                        .whereEqualTo("ownerUid", uid)
                        .limit(1)
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                }.getOrNull()

                if (discoveredFamilyDoc != null) {
                    existingFamilyId = discoveredFamilyDoc.id
                }
            }

            if (existingFamilyId != null) {
                _startupState.value = StartupState.LoadingFamilyMembership(
                    uid = uid,
                    familyId = existingFamilyId
                )

                val familyRef = db.collection("families").document(existingFamilyId)
                val memberRef = familyRef.collection("members").document(uid)

                val memberSnap = getDocumentWithCacheFallback(memberRef)
                val familySnap = runCatching { getDocumentWithCacheFallback(familyRef) }.getOrNull()

                val isOwnerOfFamily = familySnap?.exists() == true &&
                    (familySnap.getString("ownerUid") == uid ||
                        (familySnap.get("memberUids") as? List<*>)?.contains(uid) == true)

                if (memberSnap.exists() || isOwnerOfFamily) {
                    val rawRole = memberSnap.getString("role")?.takeIf { it.isNotBlank() }
                        ?: storedUserRoleStr
                        ?: if (familySnap?.getString("ownerUid") == uid) UserRole.PARENT.name else null
                    val validatedRole = UserRole.fromFirestoreValue(rawRole)

                    val resolvedFamilyName = familySnap?.getString("familyName")?.takeIf { it.isNotBlank() }
                        ?: storedUserFamilyName
                        ?: _familyName.value

                    val persistedName = memberSnap.getString("name")?.takeIf { it.isNotBlank() }
                        ?: storedUserDisplayName
                        ?: displayName.takeIf { it.isNotBlank() }
                        ?: if (validatedRole == UserRole.PARENT) "אבא" else "ילד/ה"

                    val rawIdentity = memberSnap.getString("familyIdentity")?.takeIf { it.isNotBlank() }
                        ?: storedUserIdentityStr
                    val rawEmoji = memberSnap.getString("avatarEmoji")?.takeIf { it.isNotBlank() }
                        ?: storedUserEmoji

                    // Resolve FamilyIdentity (FATHER / MOTHER / CHILD) without EVER defaulting an existing FATHER to MOTHER
                    val previousLocalIdentity = _currentMember.value.takeIf { it.id == uid }?.familyIdentity
                    val fallbackParent = if (previousLocalIdentity?.isParentIdentity == true) {
                        previousLocalIdentity
                    } else {
                        FamilyIdentity.FATHER
                    }

                    val validatedIdentity = FamilyIdentity.fromFirestoreValue(
                        raw = rawIdentity,
                        role = validatedRole,
                        avatarEmoji = rawEmoji,
                        nameHint = persistedName,
                        memberId = uid,
                        fallbackParentIdentity = fallbackParent
                    )

                    val resolvedEmoji = rawEmoji ?: validatedIdentity.defaultEmoji
                    val resolvedPhotoUrl = memberSnap.getString("photoUrl")?.takeIf { it.isNotBlank() }
                        ?: storedUserPhotoUrl
                        ?: photoUrl
                    val resolvedEmail = memberSnap.getString("googleEmail")?.takeIf { it.isNotBlank() }
                        ?: userSnap.getString("email")?.takeIf { it.isNotBlank() }
                        ?: email

                    // Self-heal missing canonical fields using non-destructive merge ONLY if missing
                    val memberMissingFields = mutableMapOf<String, Any>()
                    if (!memberSnap.exists()) {
                        memberMissingFields["id"] = uid
                        memberMissingFields["uid"] = uid
                        memberMissingFields["familyId"] = existingFamilyId
                        memberMissingFields["name"] = persistedName
                        memberMissingFields["nameEn"] = persistedName
                        memberMissingFields["role"] = validatedRole.toFirestoreValue()
                        memberMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        memberMissingFields["avatarEmoji"] = resolvedEmoji
                        memberMissingFields["invitationPending"] = false
                        memberMissingFields["invitationAccepted"] = true
                    } else {
                        if (memberSnap.getString("familyIdentity").isNullOrBlank()) {
                            memberMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        }
                        if (memberSnap.getString("googleEmail").isNullOrBlank() && resolvedEmail.isNotBlank()) {
                            memberMissingFields["googleEmail"] = resolvedEmail
                        }
                        if (memberSnap.getString("photoUrl").isNullOrBlank() && !resolvedPhotoUrl.isNullOrBlank()) {
                            memberMissingFields["photoUrl"] = resolvedPhotoUrl
                        }
                    }
                    if (memberMissingFields.isNotEmpty()) {
                        runCatching {
                            memberRef.set(memberMissingFields, SetOptions.merge()).await()
                        }
                    }

                    val userMissingFields = mutableMapOf<String, Any>()
                    if (!userSnap.exists() || userSnap.getString("familyId").isNullOrBlank()) {
                        userMissingFields["uid"] = uid
                        userMissingFields["familyId"] = existingFamilyId
                        userMissingFields["role"] = validatedRole.toFirestoreValue()
                        userMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        userMissingFields["displayName"] = persistedName
                        userMissingFields["avatarEmoji"] = resolvedEmoji
                        userMissingFields["familyName"] = resolvedFamilyName
                        if (resolvedEmail.isNotBlank()) userMissingFields["email"] = resolvedEmail
                    } else if (userSnap.getString("familyIdentity").isNullOrBlank()) {
                        userMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                    }
                    if (userMissingFields.isNotEmpty()) {
                        runCatching {
                            userDocRef.set(userMissingFields, SetOptions.merge()).await()
                        }
                    }

                    val restoredMember = FamilyMember(
                        id = uid,
                        name = persistedName,
                        nameEn = memberSnap.getString("nameEn")?.takeIf { it.isNotBlank() } ?: persistedName,
                        avatarEmoji = resolvedEmoji,
                        role = validatedRole,
                        familyIdentity = validatedIdentity,
                        colorHex = memberSnap.getLong("colorHex")
                            ?: if (validatedRole == UserRole.PARENT) 0xFF1A73E8 else 0xFF34A853,
                        googleEmail = resolvedEmail,
                        photoUrl = resolvedPhotoUrl,
                        schoolGrade = memberSnap.getString("schoolGrade"),
                        earnedPoints = (memberSnap.getLong("earnedPoints") ?: 0L).toInt(),
                        invitationPending = false,
                        invitationAccepted = true
                    )

                    _activeFamilyId.value = existingFamilyId
                    _familyName.value = resolvedFamilyName
                    _googleUserDisplayName.value = persistedName
                    if (resolvedEmail.isNotBlank()) _googleUserEmail.value = resolvedEmail
                    if (!resolvedPhotoUrl.isNullOrBlank()) _googleUserPhotoUrl.value = resolvedPhotoUrl
                    _currentMember.value = restoredMember

                    val currentList = _members.value.filter { it.id == uid }
                        .map { restoredMember }
                        .ifEmpty { listOf(restoredMember) }
                    _members.value = currentList

                    attachFirestoreRealtimeListeners(existingFamilyId)
                    flushOfflineSyncQueue(existingFamilyId)

                    val activeState = FamilyMembershipState.ActiveMember(
                        familyId = existingFamilyId,
                        familyName = resolvedFamilyName,
                        role = validatedRole,
                        familyIdentity = validatedIdentity,
                        member = restoredMember
                    )
                    _membershipState.value = activeState
                    _startupState.value = StartupState.ReadyToEnterDashboard(
                        uid = uid,
                        familyId = existingFamilyId,
                        familyName = resolvedFamilyName,
                        role = validatedRole,
                        familyIdentity = validatedIdentity
                    )
                    return@withLock activeState
                }
            }

            // Create-if-absent semantics ONLY when /users/{uid} genuinely does not exist yet
            if (!userSnap.exists()) {
                val initialUserDoc = mutableMapOf<String, Any>(
                    "uid" to uid,
                    "email" to email,
                    "createdAt" to System.currentTimeMillis()
                )
                if (displayName.isNotBlank()) {
                    initialUserDoc["displayName"] = displayName
                }
                if (!photoUrl.isNullOrBlank()) {
                    initialUserDoc["photoUrl"] = photoUrl
                }
                runCatching {
                    userDocRef.set(initialUserDoc, SetOptions.merge()).await()
                }
            }

            // Genuinely no family membership in Firestore -> require onboarding ("Create" or "Join")
            _startupState.value = StartupState.OnboardingRequired(
                uid = uid,
                email = email,
                displayName = storedUserDisplayName ?: displayName
            )
            _membershipState.value = FamilyMembershipState.NoMembership
            FamilyMembershipState.NoMembership
        } catch (e: Exception) {
            Log.w(TAG, "checkAuthenticatedUserMembership recoverable error: ${e.message}")
            val errHe = "לא ניתן לטעון את נתוני המשפחה מהענן כרגע. בדקו את החיבור ונסו שוב."
            val errEn = "Unable to load family data from the cloud right now. Please check your connection and retry."
            _startupState.value = StartupState.RecoverableError(
                uid = uid,
                messageHe = errHe,
                messageEn = errEn
            )
            val recoverable = FamilyMembershipState.RecoverableError(
                messageHe = errHe,
                messageEn = errEn
            )
            _membershipState.value = recoverable
            recoverable
        }
    }

    /**
     * V1.6 Requirement 3:
     * Explicitly creates a new family when the authenticated user selects "Create a family".
     * - Protects against accidentally overwriting an existing family if `/users/{uid}` already belongs to one.
     * - Persists `familyIdentity` (`FATHER` or `MOTHER`) alongside `role = PARENT` in both
     *   `/families/{familyId}/members/{uid}` and `/users/{uid}` using `SetOptions.merge()`.
     */
    suspend fun createFamilyAsParent(
        familyNameInput: String,
        parentDisplayName: String,
        familyIdentity: FamilyIdentity = FamilyIdentity.FATHER,
        allowCreateIfAlreadyInFamily: Boolean = false,
        creatorUidOverride: String? = null,
        creatorEmailOverride: String? = null
    ): Result<FamilyMembershipState.ActiveMember> {
        val resolvedParentIdentity = if (familyIdentity.isParentIdentity) {
            familyIdentity
        } else {
            FamilyIdentity.FATHER
        }
        val cleanFamilyName = familyNameInput.trim().ifBlank { "המשפחה שלי" }
        val cleanParentName = parentDisplayName.trim().ifBlank {
            _googleUserDisplayName.value.ifBlank { resolvedParentIdentity.labelHe }
        }
        val uid = creatorUidOverride?.takeIf { it.isNotBlank() }
            ?: authProvider()?.currentUser?.uid
            ?: "usr_${UUID.randomUUID().toString().take(8)}"
        val email = creatorEmailOverride?.takeIf { it.isNotBlank() }
            ?: authProvider()?.currentUser?.email
            ?: _googleUserEmail.value
        val photoUrl = authProvider()?.currentUser?.photoUrl?.toString() ?: _googleUserPhotoUrl.value

        val currentActive = _membershipState.value
        if (!allowCreateIfAlreadyInFamily &&
            currentActive is FamilyMembershipState.ActiveMember &&
            currentActive.member.id == uid
        ) {
            return Result.success(currentActive)
        }

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null && !allowCreateIfAlreadyInFamily) {
            // Create-if-absent guard: check if user already has a valid family in Firestore before creating a replacement
            val existingUserSnap = runCatching {
                getDocumentWithCacheFallback(db.collection("users").document(uid))
            }.getOrNull()
            val existingFamId = existingUserSnap?.getString("familyId")?.takeIf { it.isNotBlank() }
            if (existingFamId != null) {
                val existingMemberSnap = runCatching {
                    getDocumentWithCacheFallback(
                        db.collection("families").document(existingFamId).collection("members").document(uid)
                    )
                }.getOrNull()
                if (existingMemberSnap?.exists() == true) {
                    Log.i(TAG, "createFamilyAsParent guard: user $uid already belongs to family $existingFamId; restoring instead of overwriting.")
                    val restored = checkAuthenticatedUserMembership(
                        uid = uid,
                        email = email,
                        displayName = cleanParentName,
                        photoUrl = photoUrl,
                        forceRefresh = true
                    )
                    if (restored is FamilyMembershipState.ActiveMember) {
                        return Result.success(restored)
                    }
                }
            }
        }

        val newFamilyId = "fam_${uid.take(8)}_${System.currentTimeMillis() % 10000}"
        val now = System.currentTimeMillis()

        val creatorMember = FamilyMember(
            id = uid,
            name = cleanParentName,
            nameEn = cleanParentName,
            avatarEmoji = resolvedParentIdentity.defaultEmoji,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity,
            colorHex = if (resolvedParentIdentity == FamilyIdentity.FATHER) 0xFF1A73E8 else 0xFF9334E6,
            googleEmail = email,
            photoUrl = photoUrl,
            earnedPoints = 0,
            invitationPending = false,
            invitationAccepted = true
        )

        if (db != null && authProvider()?.currentUser != null) {
            try {
                val batch = db.batch()
                val familyRef = db.collection("families").document(newFamilyId)
                val memberRef = familyRef.collection("members").document(uid)
                val userRef = db.collection("users").document(uid)

                batch.set(
                    familyRef,
                    mapOf(
                        "familyId" to newFamilyId,
                        "familyName" to cleanFamilyName,
                        "ownerUid" to uid,
                        "ownerEmail" to email,
                        "adminUids" to listOf(uid),
                        "memberUids" to listOf(uid),
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )

                val memberData = mutableMapOf<String, Any>(
                    "id" to uid,
                    "uid" to uid,
                    "familyId" to newFamilyId,
                    "name" to cleanParentName,
                    "nameEn" to cleanParentName,
                    "avatarEmoji" to creatorMember.avatarEmoji,
                    "role" to UserRole.PARENT.toFirestoreValue(), // "PARENT"
                    "familyIdentity" to resolvedParentIdentity.toFirestoreValue(), // "FATHER" or "MOTHER"
                    "colorHex" to creatorMember.colorHex,
                    "googleEmail" to email,
                    "earnedPoints" to 0,
                    "invitationPending" to false,
                    "invitationAccepted" to true,
                    "joinedAt" to now
                )
                if (!photoUrl.isNullOrBlank()) {
                    memberData["photoUrl"] = photoUrl
                }
                batch.set(memberRef, memberData, SetOptions.merge())

                val userData = mutableMapOf<String, Any>(
                    "uid" to uid,
                    "email" to email,
                    "displayName" to cleanParentName,
                    "name" to cleanParentName,
                    "familyId" to newFamilyId,
                    "familyName" to cleanFamilyName,
                    "role" to UserRole.PARENT.toFirestoreValue(), // "PARENT"
                    "familyIdentity" to resolvedParentIdentity.toFirestoreValue(), // "FATHER" or "MOTHER"
                    "avatarEmoji" to creatorMember.avatarEmoji,
                    "updatedAt" to now
                )
                if (!photoUrl.isNullOrBlank()) {
                    userData["photoUrl"] = photoUrl
                }
                batch.set(userRef, userData, SetOptions.merge())

                batch.commit().await()
            } catch (e: Exception) {
                Log.e(TAG, "createFamilyAsParent Firestore write failed: ${e.message}", e)
                return Result.failure(e)
            }
        }

        _activeFamilyId.value = newFamilyId
        _familyName.value = cleanFamilyName
        _googleUserDisplayName.value = cleanParentName
        _members.value = listOf(creatorMember)
        _currentMember.value = creatorMember
        _tasks.value = emptyList()
        _schedulePeriods.value = emptyList()
        _pendingInvitations.value = emptyList()

        _cloudFamilyGroup.value = CloudFamilyGroup(
            familyId = newFamilyId,
            familyName = cleanFamilyName,
            inviteCode = "",
            ownerEmail = email,
            ownerName = cleanParentName,
            syncedMembersCount = 1,
            lastSyncedEpochMillis = now
        )

        attachFirestoreRealtimeListeners(newFamilyId)

        val activeState = FamilyMembershipState.ActiveMember(
            familyId = newFamilyId,
            familyName = cleanFamilyName,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity,
            member = creatorMember
        )
        _membershipState.value = activeState
        _startupState.value = StartupState.ReadyToEnterDashboard(
            uid = uid,
            familyId = newFamilyId,
            familyName = cleanFamilyName,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity
        )
        return Result.success(activeState)
    }

    /**
     * V1.6 Requirement 4:
     * Generates and persists a unique, role-specific invitation in `/family_invites/{code}`.
     * - Only a PARENT can create invitations.
     * - The invited role (`PARENT` or `CHILD`) is stored in Firestore and is immutable after creation.
     * - Pending invitations are stored in `_pendingInvitations` and NEVER mixed into active `_members`.
     */
    suspend fun createRoleSpecificInvitation(
        invitedRole: UserRole,
        invitedNickname: String = "",
        customExpirationMs: Long = FamilyInvitation.DEFAULT_EXPIRATION_MS
    ): Result<FamilyInvitation> {
        val current = _currentMember.value
        if (!current.isParent) {
            return Result.failure(SecurityException("רק הורה (מנהל משפחה) רשאי ליצור הזמנות למשפחה."))
        }

        val canonicalRole = if (invitedRole.isParentRole) UserRole.PARENT else UserRole.CHILD
        val code = generateSecureInviteCode()
        val now = System.currentTimeMillis()
        val expiresAt = now + customExpirationMs
        val familyId = _activeFamilyId.value
        val familyName = _familyName.value
        val parentUid = currentUid()

        val invitation = FamilyInvitation(
            code = code,
            familyId = familyId,
            familyName = familyName,
            invitedRole = canonicalRole,
            invitingParentUid = parentUid,
            invitingParentName = current.name,
            invitedNickname = invitedNickname.trim(),
            createdAt = now,
            expiresAt = expiresAt,
            status = InvitationStatus.PENDING
        )

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            try {
                val inviteMap = mapOf(
                    "code" to invitation.code,
                    "familyId" to invitation.familyId,
                    "familyName" to invitation.familyName,
                    "invitedRole" to invitation.invitedRole.toFirestoreValue(), // "PARENT" or "CHILD"
                    "invitingParentUid" to invitation.invitingParentUid,
                    "invitingParentName" to invitation.invitingParentName,
                    "invitedNickname" to invitation.invitedNickname,
                    "createdAt" to invitation.createdAt,
                    "expiresAt" to invitation.expiresAt,
                    "status" to InvitationStatus.PENDING.name
                )
                db.collection("family_invites").document(code).set(inviteMap).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist invitation to Firestore: ${e.message}", e)
                return Result.failure(e)
            }
        }

        invitationsByCode[code] = invitation
        _pendingInvitations.value = (_pendingInvitations.value.filterNot { it.code == code } + invitation)
            .filter { it.status == InvitationStatus.PENDING && !it.isExpired(now) }
            .sortedByDescending { it.createdAt }

        val roleLabelHe = if (canonicalRole == UserRole.PARENT) "הורה" else "ילד/ה"
        pushAlert(
            title = "נוצר קוד הזמנה למשפחה ($roleLabelHe) 🔑",
            message = "קוד הזמנה $code נוצר עבור תפקיד $roleLabelHe. שתפו אותו ב-WhatsApp!",
            targetName = invitedNickname.ifBlank { roleLabelHe },
            targetEmoji = if (canonicalRole == UserRole.PARENT) "👩" else "🧒",
            reason = AlertReason.MEMBER_INVITED
        )

        return Result.success(invitation)
    }

    /**
     * V1.6 Requirement 5:
     * Validates an invitation code entered by a user before joining.
     * Checks existence, status (PENDING vs REDEEMED / REVOKED), expiration, and duplicate membership.
     */
    suspend fun validateInvitationCode(
        rawCode: String,
        joiningUid: String = currentUid(),
        nowMillis: Long = System.currentTimeMillis()
    ): InvitationValidationResult {
        val normalized = rawCode.trim().uppercase()
        if (normalized.length < 4) {
            return InvitationValidationResult.Invalid(
                errorCode = "INVALID_FORMAT",
                messageHe = "נא להזין קוד הזמנה חוקי (6 תווים).",
                messageEn = "Please enter a valid 6-character invitation code."
            )
        }

        var invitation: FamilyInvitation? = null
        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            try {
                val snap = db.collection("family_invites").document(normalized).get().await()
                if (snap.exists()) {
                    invitation = parseInvitationDocument(normalized, snap.data ?: emptyMap())
                    invitationsByCode[normalized] = invitation
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firestore lookup for invite $normalized failed: ${e.message}")
            }
        }

        if (invitation == null) {
            invitation = invitationsByCode[normalized]
        }

        if (invitation == null) {
            return InvitationValidationResult.Invalid(
                errorCode = "NOT_FOUND",
                messageHe = "קוד ההזמנה \"$normalized\" אינו קיים במערכת. ודאו שהקלדתם נכון.",
                messageEn = "Invitation code \"$normalized\" was not found."
            )
        }

        if (invitation.status == InvitationStatus.REDEEMED) {
            return InvitationValidationResult.Invalid(
                errorCode = "ALREADY_REDEEMED",
                messageHe = "קוד הזמנה זה כבר נוצל ולא ניתן להשתמש בו שוב.",
                messageEn = "This invitation code has already been redeemed."
            )
        }

        if (invitation.status == InvitationStatus.REVOKED) {
            return InvitationValidationResult.Invalid(
                errorCode = "REVOKED",
                messageHe = "קוד הזמנה זה בוטל על ידי הורה במשפחה.",
                messageEn = "This invitation code was revoked by a parent."
            )
        }

        if (invitation.isExpired(nowMillis)) {
            return InvitationValidationResult.Invalid(
                errorCode = "EXPIRED",
                messageHe = "פג תוקפו של קוד ההזמנה. בקשו מההורה ליצור קוד הזמנה חדש.",
                messageEn = "This invitation code has expired. Ask a parent to generate a new code."
            )
        }

        // Check if user is already an active member of this family
        if (db != null && authProvider()?.currentUser != null) {
            val existingMemberSnap = runCatching {
                db.collection("families")
                    .document(invitation.familyId)
                    .collection("members")
                    .document(joiningUid)
                    .get()
                    .await()
            }.getOrNull()
            if (existingMemberSnap?.exists() == true) {
                return InvitationValidationResult.Invalid(
                    errorCode = "ALREADY_MEMBER",
                    messageHe = "אתם כבר רשומים כחברים במשפחת \"${invitation.familyName}\"!",
                    messageEn = "You are already a member of \"${invitation.familyName}\"!"
                )
            }
        } else if (_activeFamilyId.value == invitation.familyId &&
            _members.value.any { it.id == joiningUid && it.invitationAccepted && !it.invitationPending }
        ) {
            return InvitationValidationResult.Invalid(
                errorCode = "ALREADY_MEMBER",
                messageHe = "אתם כבר רשומים כחברים במשפחת \"${invitation.familyName}\"!",
                messageEn = "You are already a member of \"${invitation.familyName}\"!"
            )
        }

        return InvitationValidationResult.Valid(invitation)
    }

    /**
     * V1.6 Requirement 5:
     * Redeems a validated invitation code and joins the existing family with the exact immutable
     * role specified by the invitation (`PARENT` or `CHILD`).
     * - Marks the invitation as `REDEEMED` so it cannot be reused
     * - Creates the member document in `/families/{familyId}/members/{uid}` with `role = invitation.invitedRole`
     * - Updates `/families/{familyId}` and `/users/{uid}`
     * - Attaches real-time listeners so both child and parent immediately see the updated family
     */
    suspend fun redeemInvitationAndJoinFamily(
        rawCode: String,
        memberDisplayName: String,
        joiningUidOverride: String? = null,
        joiningEmailOverride: String? = null,
        joiningPhotoUrlOverride: String? = null,
        familyIdentity: FamilyIdentity? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<FamilyMembershipState.ActiveMember> {
        val uid = joiningUidOverride ?: authProvider()?.currentUser?.uid ?: "usr_${UUID.randomUUID().toString().take(8)}"
        val email = joiningEmailOverride ?: authProvider()?.currentUser?.email ?: _googleUserEmail.value
        val photoUrl = joiningPhotoUrlOverride ?: authProvider()?.currentUser?.photoUrl?.toString() ?: _googleUserPhotoUrl.value

        val validation = validateInvitationCode(rawCode, joiningUid = uid, nowMillis = nowMillis)
        if (validation is InvitationValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.messageHe))
        }

        val invite = (validation as InvitationValidationResult.Valid).invitation
        // CRITICAL: The role is strictly determined by the invitation, NEVER by client input
        val assignedRole: UserRole = if (invite.invitedRole.isParentRole) UserRole.PARENT else UserRole.CHILD
        val cleanName = memberDisplayName.trim().ifBlank {
            invite.invitedNickname.ifBlank {
                _googleUserDisplayName.value.ifBlank {
                    if (assignedRole == UserRole.PARENT) "הורה" else "ילד/ה"
                }
            }
        }

        val resolvedIdentity: FamilyIdentity = if (assignedRole == UserRole.PARENT) {
            familyIdentity?.takeIf { it.isParentIdentity }
                ?: FamilyIdentity.fromFirestoreValue(
                    raw = null,
                    role = UserRole.PARENT,
                    nameHint = cleanName,
                    memberId = uid,
                    fallbackParentIdentity = FamilyIdentity.MOTHER
                )
        } else {
            familyIdentity?.takeIf { !it.isParentIdentity }
                ?: FamilyIdentity.fromFirestoreValue(
                    raw = null,
                    role = UserRole.CHILD,
                    nameHint = cleanName,
                    memberId = uid
                )
        }

        val joinedMember = FamilyMember(
            id = uid,
            name = cleanName,
            nameEn = cleanName,
            avatarEmoji = resolvedIdentity.defaultEmoji,
            role = assignedRole,
            familyIdentity = resolvedIdentity,
            colorHex = if (assignedRole == UserRole.PARENT) {
                if (resolvedIdentity == FamilyIdentity.FATHER) 0xFF1A73E8 else 0xFF9334E6
            } else {
                0xFF34A853
            },
            googleEmail = email,
            photoUrl = photoUrl,
            earnedPoints = 0,
            invitationPending = false,
            invitationAccepted = true,
            inviteToken = invite.code,
            invitedByMemberId = invite.invitingParentUid
        )

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            try {
                val inviteRef = db.collection("family_invites").document(invite.code)
                val familyRef = db.collection("families").document(invite.familyId)
                val memberRef = familyRef.collection("members").document(uid)
                val userRef = db.collection("users").document(uid)

                db.runTransaction { transaction ->
                    val freshInviteSnap = transaction.get(inviteRef)
                    if (!freshInviteSnap.exists()) {
                        throw IllegalStateException("קוד ההזמנה אינו קיים.")
                    }
                    val freshStatus = InvitationStatus.fromFirestoreValue(freshInviteSnap.getString("status"))
                    val freshExpiresAt = freshInviteSnap.getLong("expiresAt") ?: 0L
                    if (freshStatus != InvitationStatus.PENDING) {
                        throw IllegalStateException("קוד הזמנה זה כבר נוצל או בוטל.")
                    }
                    if (nowMillis > freshExpiresAt) {
                        throw IllegalStateException("פג תוקפו של קוד ההזמנה.")
                    }

                    val existingMemberSnap = transaction.get(memberRef)
                    if (existingMemberSnap.exists()) {
                        throw IllegalStateException("אתם כבר חברים במשפחה זו.")
                    }

                    val lockedRole = UserRole.fromFirestoreValue(freshInviteSnap.getString("invitedRole"))
                    val roleValue = lockedRole.toFirestoreValue() // "PARENT" or "CHILD"
                    val txIdentity = if (lockedRole == UserRole.PARENT) {
                        if (resolvedIdentity.isParentIdentity) resolvedIdentity else FamilyIdentity.MOTHER
                    } else {
                        if (!resolvedIdentity.isParentIdentity) resolvedIdentity else FamilyIdentity.CHILD
                    }

                    // 1. Mark invitation as REDEEMED
                    transaction.update(
                        inviteRef,
                        mapOf(
                            "status" to InvitationStatus.REDEEMED.name,
                            "redeemedByUid" to uid,
                            "redeemedByName" to cleanName,
                            "redeemedAt" to nowMillis
                        )
                    )

                    // 2. Create validated member document with exact invitedRole & familyIdentity
                    val memberDocData = mutableMapOf<String, Any>(
                        "id" to uid,
                        "uid" to uid,
                        "familyId" to invite.familyId,
                        "name" to cleanName,
                        "nameEn" to cleanName,
                        "avatarEmoji" to txIdentity.defaultEmoji,
                        "role" to roleValue,
                        "familyIdentity" to txIdentity.toFirestoreValue(),
                        "colorHex" to if (lockedRole == UserRole.PARENT) 0xFF9334E6L else 0xFF34A853L,
                        "googleEmail" to email,
                        "earnedPoints" to 0,
                        "invitationPending" to false,
                        "invitationAccepted" to true,
                        "inviteCode" to invite.code,
                        "invitedByMemberId" to invite.invitingParentUid,
                        "joinedAt" to nowMillis
                    )
                    if (!photoUrl.isNullOrBlank()) {
                        memberDocData["photoUrl"] = photoUrl
                    }
                    transaction.set(memberRef, memberDocData, SetOptions.merge())

                    // 3. Update root family document memberUids (and adminUids ONLY if PARENT)
                    val familyUpdates = mutableMapOf<String, Any>(
                        "memberUids" to FieldValue.arrayUnion(uid),
                        "updatedAt" to nowMillis
                    )
                    if (lockedRole == UserRole.PARENT) {
                        familyUpdates["adminUids"] = FieldValue.arrayUnion(uid)
                    }
                    transaction.update(familyRef, familyUpdates)

                    // 4. Save user's active familyId, validated role, and familyIdentity
                    val userDocData = mutableMapOf<String, Any>(
                        "uid" to uid,
                        "email" to email,
                        "displayName" to cleanName,
                        "name" to cleanName,
                        "familyId" to invite.familyId,
                        "familyName" to invite.familyName,
                        "role" to roleValue,
                        "familyIdentity" to txIdentity.toFirestoreValue(),
                        "avatarEmoji" to txIdentity.defaultEmoji,
                        "updatedAt" to nowMillis
                    )
                    if (!photoUrl.isNullOrBlank()) {
                        userDocData["photoUrl"] = photoUrl
                    }
                    transaction.set(userRef, userDocData, SetOptions.merge())
                }.await()
            } catch (e: Exception) {
                Log.e(TAG, "redeemInvitationAndJoinFamily transaction failed: ${e.message}", e)
                return Result.failure(e)
            }
        }

        // Update local state & cache
        val redeemedInvite = invite.copy(
            status = InvitationStatus.REDEEMED,
            redeemedByUid = uid,
            redeemedByName = cleanName,
            redeemedAt = nowMillis
        )
        invitationsByCode[invite.code] = redeemedInvite
        _pendingInvitations.value = _pendingInvitations.value.filterNot { it.code == invite.code }

        _activeFamilyId.value = invite.familyId
        _familyName.value = invite.familyName
        _googleUserDisplayName.value = cleanName
        _currentMember.value = joinedMember
        _members.value = (_members.value.filterNot { it.id == uid } + joinedMember)

        _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
            familyId = invite.familyId,
            familyName = invite.familyName,
            inviteCode = invite.code,
            syncedMembersCount = _members.value.size,
            lastSyncedEpochMillis = nowMillis
        )

        attachFirestoreRealtimeListeners(invite.familyId)

        val activeState = FamilyMembershipState.ActiveMember(
            familyId = invite.familyId,
            familyName = invite.familyName,
            role = assignedRole,
            familyIdentity = resolvedIdentity,
            member = joinedMember
        )
        _membershipState.value = activeState
        _startupState.value = StartupState.ReadyToEnterDashboard(
            uid = uid,
            familyId = invite.familyId,
            familyName = invite.familyName,
            role = assignedRole,
            familyIdentity = resolvedIdentity
        )
        return Result.success(activeState)
    }

    /**
     * Revokes a pending invitation (PARENT only).
     */
    fun revokeInvitation(code: String): Boolean {
        if (!_currentMember.value.isParent) return false
        val normalized = code.trim().uppercase()
        invitationsByCode[normalized]?.let { existing ->
            invitationsByCode[normalized] = existing.copy(status = InvitationStatus.REVOKED)
        }
        _pendingInvitations.value = _pendingInvitations.value.filterNot { it.code == normalized }

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            repoScope.launch {
                runCatching {
                    db.collection("family_invites")
                        .document(normalized)
                        .update("status", InvitationStatus.REVOKED.name)
                        .await()
                }
            }
        }
        return true
    }

    private fun parseInvitationDocument(docId: String, data: Map<String, Any>): FamilyInvitation {
        val code = (data["code"] as? String)?.trim()?.uppercase()?.ifBlank { docId } ?: docId
        val familyId = (data["familyId"] as? String) ?: _activeFamilyId.value
        val familyName = (data["familyName"] as? String) ?: _familyName.value
        val invitedRole = UserRole.fromFirestoreValue(data["invitedRole"] as? String)
        val invitingParentUid = (data["invitingParentUid"] as? String) ?: ""
        val invitingParentName = (data["invitingParentName"] as? String) ?: ""
        val invitedNickname = (data["invitedNickname"] as? String) ?: ""
        val createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val expiresAt = (data["expiresAt"] as? Number)?.toLong() ?: (createdAt + FamilyInvitation.DEFAULT_EXPIRATION_MS)
        val status = InvitationStatus.fromFirestoreValue(data["status"] as? String)
        val redeemedByUid = data["redeemedByUid"] as? String
        val redeemedByName = data["redeemedByName"] as? String
        val redeemedAt = (data["redeemedAt"] as? Number)?.toLong()

        return FamilyInvitation(
            code = code,
            familyId = familyId,
            familyName = familyName,
            invitedRole = invitedRole,
            invitingParentUid = invitingParentUid,
            invitingParentName = invitingParentName,
            invitedNickname = invitedNickname,
            createdAt = createdAt,
            expiresAt = expiresAt,
            status = status,
            redeemedByUid = redeemedByUid,
            redeemedByName = redeemedByName,
            redeemedAt = redeemedAt
        )
    }

    /**
     * V1.6 Requirement 6:
     * Real-time Firestore snapshot listeners for:
     * 1) `/families/{familyId}` (family name & metadata)
     * 2) `/families/{familyId}/members` (active family members with name, photoUrl, familyIdentity, and PARENT/CHILD role)
     * 3) `/family_invites` where `familyId == familyId` (pending invitations, kept separate from active members)
     * 4) `/families/{familyId}/tasks` (real-time chores)
     * 5) `/families/{familyId}/rewards` (rewards catalog)
     */
    fun attachFirestoreRealtimeListeners(familyId: String) {
        val db = firestoreProvider() ?: return
        detachFirestoreListeners()

        val familyRef = db.collection("families").document(familyId)

        // 0. Real-time Family Document Listener
        runCatching {
            familyDocListenerRegistration = familyRef.addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val remoteFamilyName = snapshot.getString("familyName")?.takeIf { it.isNotBlank() }
                if (remoteFamilyName != null) {
                    _familyName.value = remoteFamilyName
                    _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
                        familyId = familyId,
                        familyName = remoteFamilyName,
                        lastSyncedEpochMillis = System.currentTimeMillis()
                    )
                    val curMembership = _membershipState.value
                    if (curMembership is FamilyMembershipState.ActiveMember && curMembership.familyId == familyId) {
                        _membershipState.value = curMembership.copy(familyName = remoteFamilyName)
                    }
                    val curStartup = _startupState.value
                    if (curStartup is StartupState.ReadyToEnterDashboard && curStartup.familyId == familyId) {
                        _startupState.value = curStartup.copy(familyName = remoteFamilyName)
                    }
                }
            }
        }

        // 1. Real-time Tasks Listener
        runCatching {
            tasksListenerRegistration = familyRef.collection("tasks")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteTasks = snapshot.documents.mapNotNull { doc ->
                        val title = doc.getString("title") ?: return@mapNotNull null
                        val description = doc.getString("description") ?: ""
                        val assignedUid = doc.getString("assignedUid") ?: "m_kid1"
                        val points = (doc.getLong("pointsValue") ?: 10L).toInt()
                        val completed = doc.getBoolean("isCompleted") ?: false
                        val dueMillis = doc.getLong("dueDate") ?: (todayEpoch * 86_400_000L)
                        val epochDay = dueMillis / 86_400_000L
                        val categoryStr = doc.getString("category") ?: TaskCategory.OTHER.name
                        val priorityStr = doc.getString("priority") ?: TaskPriority.MEDIUM.name
                        val recurrenceStr = doc.getString("recurrence") ?: TaskRecurrence.ONCE.name
                        val dueTime = doc.getString("dueTimeFormatted") ?: "17:00"

                        FamilyTask(
                            id = doc.id,
                            title = title,
                            description = description,
                            category = runCatching { TaskCategory.valueOf(categoryStr) }.getOrDefault(TaskCategory.OTHER),
                            assignedToMemberId = assignedUid,
                            dateEpochDay = epochDay,
                            dueTimeFormatted = dueTime,
                            isCompleted = completed,
                            priority = runCatching { TaskPriority.valueOf(priorityStr) }.getOrDefault(TaskPriority.MEDIUM),
                            recurrence = runCatching { TaskRecurrence.valueOf(recurrenceStr) }.getOrDefault(TaskRecurrence.ONCE),
                            points = points
                        )
                    }
                    if (remoteTasks.isNotEmpty() || _isGoogleSignedIn.value) {
                        _tasks.value = remoteTasks
                        repoScope.launch {
                            runCatching {
                                taskDao?.upsertTasks(remoteTasks.map { it.toCoreTask(familyId) })
                            }
                        }
                    }
                }
        }

        // 2. Real-time Active Members Listener (Preserves FATHER / MOTHER identity, never defaults to MOTHER)
        runCatching {
            membersListenerRegistration = familyRef.collection("members")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteMembers = snapshot.documents.mapNotNull { doc ->
                        val isPending = doc.getBoolean("invitationPending") ?: false
                        val isAccepted = doc.getBoolean("invitationAccepted") ?: true
                        // V1.6 Requirement 6: Never show pending invitations as accepted members
                        if (isPending && !isAccepted) return@mapNotNull null

                        val name = doc.getString("name") ?: return@mapNotNull null
                        val roleStr = doc.getString("role") ?: UserRole.CHILD.name
                        val parsedRole = UserRole.fromFirestoreValue(roleStr)
                        val points = (doc.getLong("earnedPoints") ?: 0L).toInt()
                        val rawIdentity = doc.getString("familyIdentity")
                        val rawEmoji = doc.getString("avatarEmoji")?.takeIf { it.isNotBlank() }

                        // Preserve existing local identity for this member if Firestore field was omitted
                        val existingLocalMember = _members.value.find { it.id == doc.id }
                            ?: _currentMember.value.takeIf { it.id == doc.id }
                        val fallbackParent = existingLocalMember?.familyIdentity?.takeIf { it.isParentIdentity }
                            ?: FamilyIdentity.FATHER

                        val parsedIdentity = FamilyIdentity.fromFirestoreValue(
                            raw = rawIdentity,
                            role = parsedRole,
                            avatarEmoji = rawEmoji,
                            nameHint = name,
                            memberId = doc.id,
                            fallbackParentIdentity = fallbackParent
                        )
                        val emoji = rawEmoji ?: parsedIdentity.defaultEmoji
                        val email = doc.getString("googleEmail") ?: doc.getString("email")
                        val photoUrl = doc.getString("photoUrl")
                        val colorHex = doc.getLong("colorHex")
                            ?: if (parsedRole == UserRole.PARENT) 0xFF1A73E8L else 0xFF34A853L

                        FamilyMember(
                            id = doc.id,
                            name = name,
                            nameEn = doc.getString("nameEn") ?: name,
                            avatarEmoji = emoji,
                            role = parsedRole,
                            familyIdentity = parsedIdentity,
                            colorHex = colorHex,
                            googleEmail = email,
                            photoUrl = photoUrl,
                            schoolGrade = doc.getString("schoolGrade"),
                            earnedPoints = points,
                            invitationPending = false,
                            invitationAccepted = true,
                            inviteToken = doc.getString("inviteCode") ?: doc.getString("inviteToken"),
                            invitedByMemberId = doc.getString("invitedByMemberId")
                        )
                    }

                    if (remoteMembers.isNotEmpty()) {
                        _members.value = remoteMembers
                        val signedInUid = authProvider()?.currentUser?.uid
                        val updatedSelf = remoteMembers.find {
                            it.id == signedInUid || it.id == _currentMember.value.id
                        }
                        if (updatedSelf != null) {
                            _currentMember.value = updatedSelf
                            _googleUserDisplayName.value = updatedSelf.name
                            if (_membershipState.value is FamilyMembershipState.ActiveMember) {
                                _membershipState.value = FamilyMembershipState.ActiveMember(
                                    familyId = familyId,
                                    familyName = _familyName.value,
                                    role = updatedSelf.role,
                                    familyIdentity = updatedSelf.familyIdentity,
                                    member = updatedSelf
                                )
                            }
                            if (_startupState.value is StartupState.ReadyToEnterDashboard) {
                                _startupState.value = StartupState.ReadyToEnterDashboard(
                                    uid = updatedSelf.id,
                                    familyId = familyId,
                                    familyName = _familyName.value,
                                    role = updatedSelf.role,
                                    familyIdentity = updatedSelf.familyIdentity
                                )
                            }
                        }
                        _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
                            familyId = familyId,
                            syncedMembersCount = remoteMembers.size,
                            lastSyncedEpochMillis = System.currentTimeMillis()
                        )
                    }
                }
        }

        // 3. Real-time Pending Invitations Listener (/family_invites where familyId == familyId)
        runCatching {
            invitesListenerRegistration = db.collection("family_invites")
                .whereEqualTo("familyId", familyId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val now = System.currentTimeMillis()
                    val allInvites = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        parseInvitationDocument(doc.id, data).also { inv ->
                            invitationsByCode[inv.code] = inv
                        }
                    }
                    _pendingInvitations.value = allInvites
                        .filter { it.status == InvitationStatus.PENDING && !it.isExpired(now) }
                        .sortedByDescending { it.createdAt }
                }
        }

        // 4. Real-time Rewards Listener
        runCatching {
            rewardsListenerRegistration = familyRef.collection("rewards")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteRewards = snapshot.documents.mapNotNull { doc ->
                        val title = doc.getString("title") ?: return@mapNotNull null
                        val cost = (doc.getLong("pointsCost") ?: 50L).toInt()
                        val emoji = doc.getString("iconEmoji") ?: "🎁"
                        val desc = doc.getString("description") ?: ""
                        RewardItem(
                            id = doc.id,
                            familyId = familyId,
                            title = title,
                            pointsCost = cost,
                            iconEmoji = emoji,
                            description = desc
                        )
                    }
                    if (remoteRewards.isNotEmpty()) {
                        _rewards.value = remoteRewards
                    }
                }
        }
    }

    fun detachFirestoreListeners() {
        tasksListenerRegistration?.remove()
        membersListenerRegistration?.remove()
        rewardsListenerRegistration?.remove()
        invitesListenerRegistration?.remove()
        familyDocListenerRegistration?.remove()
        tasksListenerRegistration = null
        membersListenerRegistration = null
        rewardsListenerRegistration = null
        invitesListenerRegistration = null
        familyDocListenerRegistration = null
    }

    private suspend fun flushOfflineSyncQueue(familyId: String) {
        val syncDao = offlineSyncDao ?: return
        val db = firestoreProvider() ?: return
        runCatching {
            val pending = syncDao.getPendingActions()
            for (item in pending) {
                if (item.actionType == "DELETE_TASK") {
                    db.collection("families").document(familyId)
                        .collection("tasks").document(item.taskId).delete().await()
                }
                syncDao.removeActionById(item.id)
            }
        }
    }

    private fun pushTaskToFirestoreOrQueue(task: FamilyTask, isDelete: Boolean = false) {
        val familyId = _activeFamilyId.value
        val coreTask = task.toCoreTask(familyId)

        repoScope.launch {
            runCatching {
                if (isDelete) {
                    taskDao?.deleteTaskById(task.id)
                } else {
                    taskDao?.upsertTask(coreTask)
                }
            }

            val db = firestoreProvider()
            val user = authProvider()?.currentUser
            if (db != null && user != null) {
                try {
                    val docRef = db.collection("families").document(familyId)
                        .collection("tasks").document(task.id)
                    if (isDelete) {
                        docRef.delete().await()
                    } else {
                        val payload = mapOf(
                            "id" to task.id,
                            "title" to task.title,
                            "description" to task.description,
                            "assignedUid" to task.assignedToMemberId,
                            "pointsValue" to task.points,
                            "isCompleted" to task.isCompleted,
                            "dueDate" to (task.dateEpochDay * 86_400_000L),
                            "familyId" to familyId,
                            "approvedByParent" to task.isCompleted,
                            "category" to task.category.name,
                            "priority" to task.priority.name,
                            "recurrence" to task.recurrence.name,
                            "dueTimeFormatted" to task.dueTimeFormatted
                        )
                        docRef.set(payload, SetOptions.merge()).await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cloud write deferred to offline queue: ${e.message}")
                    offlineSyncDao?.enqueueAction(
                        OfflineSyncQueueEntity(
                            taskId = task.id,
                            familyId = familyId,
                            actionType = if (isDelete) "DELETE_TASK" else "UPSERT_TASK",
                            payloadJson = "${task.title}|${task.points}|${task.isCompleted}"
                        )
                    )
                }
            }
        }
    }

    // --- Family Name, Personal Profile & Cloud Group Actions ---

    fun updateFamilyName(newName: String) {
        if (!_currentMember.value.isParent) return
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            val now = System.currentTimeMillis()
            _familyName.value = trimmed
            _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
                familyName = trimmed,
                lastSyncedEpochMillis = now
            )
            val curMembership = _membershipState.value
            if (curMembership is FamilyMembershipState.ActiveMember) {
                _membershipState.value = curMembership.copy(familyName = trimmed)
            }
            val curStartup = _startupState.value
            if (curStartup is StartupState.ReadyToEnterDashboard) {
                _startupState.value = curStartup.copy(familyName = trimmed)
            }
            val db = firestoreProvider()
            val uid = authProvider()?.currentUser?.uid
            if (db != null && uid != null) {
                val activeFamId = _activeFamilyId.value
                repoScope.launch {
                    runCatching {
                        db.collection("families").document(activeFamId)
                            .set(
                                mapOf(
                                    "familyName" to trimmed,
                                    "updatedAt" to now
                                ),
                                SetOptions.merge()
                            ).await()
                        db.collection("users").document(uid)
                            .set(
                                mapOf(
                                    "familyName" to trimmed,
                                    "updatedAt" to now
                                ),
                                SetOptions.merge()
                            ).await()
                    }
                }
            }
        }
    }

    /**
     * Updates the current user's personal profile (`name`, `familyIdentity` such as FATHER or MOTHER,
     * and `avatarEmoji`) and persists it non-destructively to both `/users/{uid}` and
     * `/families/{familyId}/members/{uid}`.
     * - Preserves the user's authorization [UserRole] (`PARENT` or `CHILD`).
     * - Both [FamilyIdentity.FATHER] and [FamilyIdentity.MOTHER] retain [UserRole.PARENT].
     */
    fun updateCurrentUserProfile(
        newName: String,
        newIdentity: FamilyIdentity? = null,
        newAvatarEmoji: String? = null
    ): Boolean {
        val current = _currentMember.value
        val cleanName = newName.trim().ifBlank { current.name }
        val resolvedIdentity = when {
            newIdentity == null -> current.familyIdentity
            current.isParent && newIdentity.isParentIdentity -> newIdentity
            !current.isParent && !newIdentity.isParentIdentity -> newIdentity
            else -> current.familyIdentity
        }
        val resolvedEmoji = newAvatarEmoji?.trim()?.takeIf { it.isNotBlank() }
            ?: if (newIdentity != null && newIdentity != current.familyIdentity) {
                resolvedIdentity.defaultEmoji
            } else {
                current.avatarEmoji.ifBlank { resolvedIdentity.defaultEmoji }
            }

        val updatedMember = current.copy(
            name = cleanName,
            nameEn = cleanName,
            familyIdentity = resolvedIdentity,
            avatarEmoji = resolvedEmoji
        )
        _currentMember.value = updatedMember
        _googleUserDisplayName.value = cleanName
        _members.value = _members.value.map { if (it.id == current.id) updatedMember else it }

        val curMembership = _membershipState.value
        if (curMembership is FamilyMembershipState.ActiveMember) {
            _membershipState.value = curMembership.copy(
                familyIdentity = resolvedIdentity,
                member = updatedMember
            )
        }
        val curStartup = _startupState.value
        if (curStartup is StartupState.ReadyToEnterDashboard) {
            _startupState.value = curStartup.copy(
                familyIdentity = resolvedIdentity
            )
        }

        val db = firestoreProvider()
        val uid = authProvider()?.currentUser?.uid
        if (db != null && uid != null && current.id == uid) {
            val activeFamId = _activeFamilyId.value
            val now = System.currentTimeMillis()
            repoScope.launch {
                runCatching {
                    val memberRef = db.collection("families").document(activeFamId)
                        .collection("members").document(uid)
                    val userRef = db.collection("users").document(uid)
                    val batch = db.batch()
                    batch.set(
                        memberRef,
                        mapOf(
                            "name" to cleanName,
                            "nameEn" to cleanName,
                            "familyIdentity" to resolvedIdentity.toFirestoreValue(),
                            "avatarEmoji" to resolvedEmoji,
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                    batch.set(
                        userRef,
                        mapOf(
                            "displayName" to cleanName,
                            "name" to cleanName,
                            "familyIdentity" to resolvedIdentity.toFirestoreValue(),
                            "avatarEmoji" to resolvedEmoji,
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                    batch.commit().await()
                }
            }
        }
        return true
    }

    /**
     * Called when Google authentication completes so we can record auth metadata
     * and check Firestore membership before opening the dashboard.
     * NEVER overwrites `_familyName` or `_currentMember` before Firestore profile loading completes.
     */
    fun onGoogleSignInSuccess(
        email: String,
        displayName: String,
        familyGroupName: String? = null,
        photoUrl: String? = null
    ) {
        _isGoogleSignedIn.value = true
        if (email.isNotBlank()) {
            _googleUserEmail.value = email
        }
        if (!photoUrl.isNullOrBlank()) {
            _googleUserPhotoUrl.value = photoUrl
        }
        if (_googleUserDisplayName.value.isBlank() && displayName.isNotBlank()) {
            _googleUserDisplayName.value = displayName
        }

        val uid = authProvider()?.currentUser?.uid ?: email
        repoScope.launch {
            checkAuthenticatedUserMembership(
                uid = uid,
                email = email,
                displayName = displayName,
                photoUrl = photoUrl
            )
        }
    }

    /**
     * Explicit family creation helper (also supports synchronous calls from ViewModel/tests).
     * Creates a new family with the creator assigned the `PARENT` role and preserved [FamilyIdentity].
     */
    fun initializeCleanFamily(
        familyNameInput: String,
        adminNameInput: String,
        emailInput: String,
        familyIdentity: FamilyIdentity = FamilyIdentity.FATHER
    ) {
        val resolvedParentIdentity = if (familyIdentity.isParentIdentity) {
            familyIdentity
        } else {
            FamilyIdentity.FATHER
        }
        val cleanFamilyName = familyNameInput.trim().ifBlank { "המשפחה שלי" }
        val cleanAdminName = adminNameInput.trim().ifBlank { resolvedParentIdentity.labelHe }
        val cleanEmail = emailInput.trim().ifBlank { _googleUserEmail.value }

        _isGoogleSignedIn.value = true
        _googleUserEmail.value = cleanEmail
        _googleUserDisplayName.value = cleanAdminName

        repoScope.launch {
            createFamilyAsParent(
                familyNameInput = cleanFamilyName,
                parentDisplayName = cleanAdminName,
                familyIdentity = resolvedParentIdentity
            )
        }

        // Also update synchronous state immediately for UI/test responsiveness
        val uid = authProvider()?.currentUser?.uid ?: "m_parent_${System.currentTimeMillis()}"
        val primaryAdmin = FamilyMember(
            id = uid,
            name = cleanAdminName,
            nameEn = cleanAdminName,
            avatarEmoji = resolvedParentIdentity.defaultEmoji,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity,
            colorHex = if (resolvedParentIdentity == FamilyIdentity.FATHER) 0xFF1A73E8 else 0xFF9334E6,
            googleEmail = cleanEmail,
            photoUrl = _googleUserPhotoUrl.value,
            earnedPoints = 0,
            invitationPending = false,
            invitationAccepted = true
        )

        _familyName.value = cleanFamilyName
        _members.value = listOf(primaryAdmin)
        _currentMember.value = primaryAdmin
        _tasks.value = emptyList()
        _schedulePeriods.value = emptyList()
        _pendingInvitations.value = emptyList()
        val activeState = FamilyMembershipState.ActiveMember(
            familyId = _activeFamilyId.value,
            familyName = cleanFamilyName,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity,
            member = primaryAdmin
        )
        _membershipState.value = activeState
        _startupState.value = StartupState.ReadyToEnterDashboard(
            uid = uid,
            familyId = _activeFamilyId.value,
            familyName = cleanFamilyName,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity
        )
    }

    /**
     * Legacy synchronous join helper mapped to the V1.6 invitation system.
     */
    fun joinExistingCloudFamilyByCode(
        inviteCode: String,
        memberName: String,
        memberEmail: String,
        familyIdentity: FamilyIdentity? = null
    ): Boolean {
        val cleanCode = inviteCode.trim().uppercase()
        if (cleanCode.length < 4) return false

        val cleanName = memberName.trim().ifBlank { _googleUserDisplayName.value }
        _isGoogleSignedIn.value = true
        _googleUserEmail.value = memberEmail
        _googleUserDisplayName.value = cleanName

        // Check if we have a matching invitation in local cache; default to CHILD (never PARENT!)
        val cachedInvite = invitationsByCode[cleanCode]
        if (cachedInvite != null && !cachedInvite.isRedeemable()) {
            return false
        }
        val assignedRole = cachedInvite?.invitedRole ?: UserRole.CHILD
        val resolvedIdentity = if (assignedRole == UserRole.PARENT) {
            familyIdentity?.takeIf { it.isParentIdentity }
                ?: FamilyIdentity.fromFirestoreValue(
                    raw = null,
                    role = UserRole.PARENT,
                    nameHint = cleanName,
                    fallbackParentIdentity = FamilyIdentity.MOTHER
                )
        } else {
            familyIdentity?.takeIf { !it.isParentIdentity }
                ?: FamilyIdentity.fromFirestoreValue(
                    raw = null,
                    role = UserRole.CHILD,
                    nameHint = cleanName
                )
        }
        val targetFamilyId = cachedInvite?.familyId ?: "fam_${cleanCode.lowercase()}"
        val targetFamilyName = cachedInvite?.familyName ?: _familyName.value
        val uid = authProvider()?.currentUser?.uid ?: "m_joined_${System.currentTimeMillis()}"

        val joinedMember = FamilyMember(
            id = uid,
            name = cleanName,
            nameEn = cleanName,
            avatarEmoji = resolvedIdentity.defaultEmoji,
            role = assignedRole,
            familyIdentity = resolvedIdentity,
            colorHex = if (assignedRole == UserRole.PARENT) 0xFF9334E6 else 0xFF34A853,
            googleEmail = memberEmail,
            photoUrl = _googleUserPhotoUrl.value,
            earnedPoints = 0,
            invitationPending = false,
            invitationAccepted = true,
            inviteToken = cleanCode
        )

        _activeFamilyId.value = targetFamilyId
        _familyName.value = targetFamilyName
        _members.value = (_members.value.filterNot { it.id == uid } + joinedMember)
        _currentMember.value = joinedMember
        _membershipState.value = FamilyMembershipState.ActiveMember(
            familyId = targetFamilyId,
            familyName = targetFamilyName,
            role = assignedRole,
            familyIdentity = resolvedIdentity,
            member = joinedMember
        )
        _startupState.value = StartupState.ReadyToEnterDashboard(
            uid = uid,
            familyId = targetFamilyId,
            familyName = targetFamilyName,
            role = assignedRole,
            familyIdentity = resolvedIdentity
        )

        repoScope.launch {
            redeemInvitationAndJoinFamily(
                rawCode = cleanCode,
                memberDisplayName = cleanName,
                familyIdentity = resolvedIdentity
            )
        }
        return true
    }

    fun signOutGoogle() {
        runCatching { authProvider()?.signOut() }
        detachFirestoreListeners()
        _isGoogleSignedIn.value = false
        _googleUserPhotoUrl.value = null
        _membershipState.value = FamilyMembershipState.Unauthenticated
        _startupState.value = StartupState.OnboardingRequired()
    }

    fun switchCurrentMember(member: FamilyMember) {
        _currentMember.value = member
    }

    /**
     * V1.6 Role-Specific Invitation Generator (synchronous wrapper for ViewModel/Dialogs).
     * Creates a `FamilyInvitation` in `_pendingInvitations` and persists it to `/family_invites/{code}`.
     * Does NOT add unaccepted invites into `_members`.
     */
    fun inviteNewMember(
        name: String,
        emoji: String,
        role: UserRole,
        googleEmail: String?,
        schoolGrade: String?
    ): FamilyInvitation? {
        if (!_currentMember.value.isParent) return null
        val canonicalRole = if (role.isParentRole) UserRole.PARENT else UserRole.CHILD
        val code = generateSecureInviteCode()
        val now = System.currentTimeMillis()
        val invitation = FamilyInvitation(
            code = code,
            familyId = _activeFamilyId.value,
            familyName = _familyName.value,
            invitedRole = canonicalRole,
            invitingParentUid = currentUid(),
            invitingParentName = _currentMember.value.name,
            invitedNickname = name.trim(),
            createdAt = now,
            expiresAt = now + FamilyInvitation.DEFAULT_EXPIRATION_MS,
            status = InvitationStatus.PENDING
        )

        invitationsByCode[code] = invitation
        _pendingInvitations.value = (_pendingInvitations.value.filterNot { it.code == code } + invitation)
            .sortedByDescending { it.createdAt }

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            repoScope.launch {
                runCatching {
                    db.collection("family_invites").document(code).set(
                        mapOf(
                            "code" to invitation.code,
                            "familyId" to invitation.familyId,
                            "familyName" to invitation.familyName,
                            "invitedRole" to invitation.invitedRole.toFirestoreValue(),
                            "invitingParentUid" to invitation.invitingParentUid,
                            "invitingParentName" to invitation.invitingParentName,
                            "invitedNickname" to invitation.invitedNickname,
                            "createdAt" to invitation.createdAt,
                            "expiresAt" to invitation.expiresAt,
                            "status" to InvitationStatus.PENDING.name
                        )
                    ).await()
                }
            }
        }

        val roleLabelHe = if (canonicalRole == UserRole.PARENT) "הורה" else "ילד/ה"
        pushAlert(
            title = "נוצרה הזמנה למשפחה ($roleLabelHe) ✉️",
            message = "קוד הזמנה $code נוצר עבור ${name.ifBlank { roleLabelHe }} בתפקיד $roleLabelHe.",
            targetName = name.ifBlank { roleLabelHe },
            targetEmoji = emoji,
            reason = AlertReason.MEMBER_INVITED
        )
        return invitation
    }

    /**
     * Approves/redeems a pending invitation code in local state (for testing / manual approval).
     */
    fun acceptMemberInvitation(memberIdOrCode: String) {
        val normalized = memberIdOrCode.trim().uppercase()
        val invite = invitationsByCode[normalized]
            ?: _pendingInvitations.value.find { it.code == normalized }
        if (invite != null && invite.isRedeemable()) {
            val newMember = FamilyMember(
                id = "m_${invite.code.lowercase()}",
                name = invite.invitedNickname.ifBlank {
                    if (invite.invitedRole == UserRole.PARENT) "הורה שהצטרף" else "ילד/ה שהצטרף/ה"
                },
                nameEn = invite.invitedNickname.ifBlank { "Joined Member" },
                avatarEmoji = if (invite.invitedRole == UserRole.PARENT) "👩" else "🧒",
                role = invite.invitedRole,
                colorHex = if (invite.invitedRole == UserRole.PARENT) 0xFF9334E6 else 0xFF34A853,
                invitationPending = false,
                invitationAccepted = true,
                inviteToken = invite.code,
                invitedByMemberId = invite.invitingParentUid
            )
            invitationsByCode[invite.code] = invite.copy(status = InvitationStatus.REDEEMED)
            _pendingInvitations.value = _pendingInvitations.value.filterNot { it.code == invite.code }
            _members.value = _members.value + newMember
            _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
                syncedMembersCount = _members.value.size,
                lastSyncedEpochMillis = System.currentTimeMillis()
            )
        }
    }

    /**
     * V1.6 Requirement 7:
     * Generates a readable Hebrew/English WhatsApp invitation message WITHOUT any domain or broken links.
     * Contains:
     * - Family name
     * - Invited role (PARENT or CHILD)
     * - Invitation code
     * - Simple step-by-step instructions to sign in and enter the code in FamilyTasks
     */
    fun getWhatsAppInviteShareText(
        invitation: FamilyInvitation,
        isHebrew: Boolean
    ): String {
        val roleHe = if (invitation.invitedRole == UserRole.PARENT) "הורה (מנהל משפחה)" else "ילד/ה (חבר משפחה)"
        val roleEn = if (invitation.invitedRole == UserRole.PARENT) "Parent (Family Admin)" else "Child (Family Member)"
        return if (isHebrew) {
            buildString {
                appendLine("שלום! הוזמנת להצטרף למשפחת \"${invitation.familyName}\" באפליקציית FamilyTasks 🏠✨")
                appendLine()
                appendLine("👤 תפקיד במשפחה: $roleHe")
                appendLine("🔑 קוד הזמנה אישי: ${invitation.code}")
                appendLine()
                appendLine("איך מצטרפים?")
                appendLine("1. פותחים את אפליקציית FamilyTasks במכשיר.")
                appendLine("2. מתחברים עם חשבון Google.")
                appendLine("3. בוחרים ב-\"הצטרפות למשפחה קיימת\".")
                append("4. מזינים את קוד ההזמנה: ${invitation.code} ומאשרים הצטרפות!")
            }
        } else {
            buildString {
                appendLine("Hi! You are invited to join \"${invitation.familyName}\" in the FamilyTasks app 🏠✨")
                appendLine()
                appendLine("👤 Invited Role: $roleEn")
                appendLine("🔑 Invitation Code: ${invitation.code}")
                appendLine()
                appendLine("How to join:")
                appendLine("1. Open the FamilyTasks app on your device.")
                appendLine("2. Sign in with your Google account.")
                appendLine("3. Select \"Join an existing family\".")
                append("4. Enter invitation code: ${invitation.code} and confirm!")
            }
        }
    }

    /**
     * Legacy share text helper updated for V1.6 so it never includes broken domains or Dynamic Links.
     */
    fun getDynamicInviteShareText(memberName: String, role: UserRole, isHebrew: Boolean): String {
        val canonicalRole = if (role.isParentRole) UserRole.PARENT else UserRole.CHILD
        val latestInvite = _pendingInvitations.value.firstOrNull { it.invitedRole == canonicalRole }
            ?: FamilyInvitation(
                code = _cloudFamilyGroup.value.inviteCode.ifBlank { generateSecureInviteCode() },
                familyId = _activeFamilyId.value,
                familyName = _familyName.value,
                invitedRole = canonicalRole,
                invitingParentUid = currentUid(),
                invitingParentName = _currentMember.value.name,
                invitedNickname = memberName
            )
        return getWhatsAppInviteShareText(latestInvite, isHebrew)
    }

    /**
     * V1.6 Requirement 1 & 8:
     * Changes a member's role between PARENT and CHILD with strict security checks:
     * - Only a PARENT can change member roles.
     * - Cannot demote the last remaining PARENT in the family.
     */
    fun changeMemberRole(memberId: String, newRole: UserRole): Boolean {
        if (!_currentMember.value.isParent) return false
        val canonicalNewRole = if (newRole.isParentRole) UserRole.PARENT else UserRole.CHILD
        val targetMember = _members.value.find { it.id == memberId } ?: return false

        // Prevent demoting the last remaining PARENT
        if (targetMember.isParent && canonicalNewRole == UserRole.CHILD) {
            val activeParentCount = _members.value.count { it.isParent && it.invitationAccepted && !it.invitationPending }
            if (activeParentCount <= 1) {
                Log.w(TAG, "Blocked demoting the last remaining PARENT ($memberId)")
                return false
            }
        }

        _members.value = _members.value.map {
            if (it.id == memberId) it.copy(role = canonicalNewRole) else it
        }
        if (_currentMember.value.id == memberId) {
            _currentMember.value = _currentMember.value.copy(role = canonicalNewRole)
        }

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            val familyId = _activeFamilyId.value
            repoScope.launch {
                runCatching {
                    val familyRef = db.collection("families").document(familyId)
                    val memberRef = familyRef.collection("members").document(memberId)
                    val batch = db.batch()
                    batch.update(memberRef, "role", canonicalNewRole.toFirestoreValue())
                    if (canonicalNewRole == UserRole.PARENT) {
                        batch.update(familyRef, "adminUids", FieldValue.arrayUnion(memberId))
                    } else {
                        batch.update(familyRef, "adminUids", FieldValue.arrayRemove(memberId))
                    }
                    batch.commit().await()
                }
            }
        }
        return true
    }

    /**
     * V1.6 Requirement 1 & 8:
     * Removes a member from the family (PARENT only).
     * Prevents removing the last remaining PARENT in a family.
     */
    fun removeFamilyMember(memberId: String): Boolean {
        if (!_currentMember.value.isParent) return false
        if (_members.value.size <= 1) return false

        val targetMember = _members.value.find { it.id == memberId } ?: return false
        if (targetMember.isParent) {
            val activeParentCount = _members.value.count { it.isParent && it.invitationAccepted && !it.invitationPending }
            if (activeParentCount <= 1) {
                Log.w(TAG, "Blocked removing the last remaining PARENT ($memberId)")
                return false
            }
        }

        _members.value = _members.value.filterNot { it.id == memberId }
        if (_currentMember.value.id == memberId) {
            _currentMember.value = _members.value.first()
        }

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            val familyId = _activeFamilyId.value
            repoScope.launch {
                runCatching {
                    val familyRef = db.collection("families").document(familyId)
                    val memberRef = familyRef.collection("members").document(memberId)
                    val batch = db.batch()
                    batch.delete(memberRef)
                    batch.update(
                        familyRef,
                        mapOf(
                            "memberUids" to FieldValue.arrayRemove(memberId),
                            "adminUids" to FieldValue.arrayRemove(memberId)
                        )
                    )
                    batch.commit().await()
                }
            }
        }
        return true
    }

    // --- Task Operations (V1.6 Strict Role Enforcement) ---

    /**
     * V1.6 Requirement 1:
     * - PARENT can toggle any task.
     * - CHILD can only mark tasks assigned to them as completed.
     */
    fun toggleTaskCompletion(taskId: String): Boolean {
        val current = _currentMember.value
        val targetTask = _tasks.value.find { it.id == taskId } ?: return false

        if (!current.isParent) {
            val isAssignedToCurrentChild = targetTask.assignedToMemberId == current.id ||
                targetTask.assignedToMemberId == currentUid()
            if (!isAssignedToCurrentChild) {
                Log.w(TAG, "Blocked CHILD ${current.id} from toggling task assigned to ${targetTask.assignedToMemberId}")
                return false
            }
        }

        var completedTask: FamilyTask? = null
        var becameCompleted = false

        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) {
                val newStatus = !task.isCompleted
                becameCompleted = newStatus
                val updated = task.copy(isCompleted = newStatus)
                completedTask = updated
                updated
            } else {
                task
            }
        }

        completedTask?.let { task ->
            val delta = if (becameCompleted) task.points else -task.points
            _members.value = _members.value.map { member ->
                if (member.id == task.assignedToMemberId) {
                    val newPts = (member.earnedPoints + delta).coerceAtLeast(0)
                    val updatedMember = member.copy(earnedPoints = newPts)
                    if (_currentMember.value.id == member.id) {
                        _currentMember.value = updatedMember
                    }
                    updatedMember
                } else {
                    member
                }
            }

            if (becameCompleted) {
                val assignee = _members.value.find { it.id == task.assignedToMemberId }
                if (assignee != null) {
                    pushAlert(
                        title = "משימה הושלמה! +${task.points} נק' 🎉",
                        message = "${assignee.name} השלים/ה את המשימה: ${task.title}",
                        targetName = assignee.name,
                        targetEmoji = assignee.avatarEmoji,
                        reason = AlertReason.TASK_COMPLETED
                    )
                }
            }

            pushTaskToFirestoreOrQueue(task, isDelete = false)
        }
        return true
    }

    /**
     * V1.6 Requirement 1: Only a PARENT can create tasks.
     */
    fun addTask(task: FamilyTask): Boolean {
        if (!_currentMember.value.isParent) {
            Log.w(TAG, "Blocked CHILD from creating a task")
            return false
        }
        _tasks.value = listOf(task) + _tasks.value
        val assignee = _members.value.find { it.id == task.assignedToMemberId }
        if (assignee != null) {
            pushAlert(
                title = "משימה חדשה נוספה ליומן 📅",
                message = "ל-${assignee.name}: ${task.title} (שעה ${task.dueTimeFormatted})",
                targetName = assignee.name,
                targetEmoji = assignee.avatarEmoji,
                reason = AlertReason.TASK_REMINDER
            )
        }
        pushTaskToFirestoreOrQueue(task, isDelete = false)
        return true
    }

    /**
     * V1.6 Requirement 1: Only a PARENT can edit or reassign tasks.
     */
    fun updateTask(updatedTask: FamilyTask): Boolean {
        if (!_currentMember.value.isParent) {
            Log.w(TAG, "Blocked CHILD from editing a task")
            return false
        }
        _tasks.value = _tasks.value.map { if (it.id == updatedTask.id) updatedTask else it }
        pushTaskToFirestoreOrQueue(updatedTask, isDelete = false)
        return true
    }

    /**
     * V1.6 Requirement 1: Only a PARENT can delete tasks.
     */
    fun deleteTask(taskId: String): Boolean {
        if (!_currentMember.value.isParent) {
            Log.w(TAG, "Blocked CHILD from deleting a task")
            return false
        }
        val target = _tasks.value.find { it.id == taskId }
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        if (target != null) {
            pushTaskToFirestoreOrQueue(target, isDelete = true)
        }
        return true
    }

    fun sendParentNudgeForTask(task: FamilyTask) {
        if (!_currentMember.value.isParent) return
        val assignee = _members.value.find { it.id == task.assignedToMemberId } ?: return
        pushAlert(
            title = "תזכורת מהורה לביצוע משימה 👋",
            message = "${assignee.name}, אל תשכח/י להשלים: \"${task.title}\" (${task.points} נקודות!)",
            targetName = assignee.name,
            targetEmoji = assignee.avatarEmoji,
            reason = AlertReason.PARENT_NUDGE
        )
    }

    // --- Rewards Store Operations ---

    fun redeemReward(reward: RewardItem): Boolean {
        val current = _currentMember.value
        if (current.earnedPoints < reward.pointsCost) return false
        val newPoints = (current.earnedPoints - reward.pointsCost).coerceAtLeast(0)
        val updatedMember = current.copy(earnedPoints = newPoints)
        _currentMember.value = updatedMember
        _members.value = _members.value.map { if (it.id == current.id) updatedMember else it }

        pushAlert(
            title = "פרס מומש בהצלחה! 🎁",
            message = "${current.name} מימש/ה את הפרס: \"${reward.title}\" (-${reward.pointsCost} נק')",
            targetName = current.name,
            targetEmoji = current.avatarEmoji,
            reason = AlertReason.TASK_COMPLETED
        )
        return true
    }

    // --- School Timetable Operations (PARENT only for mutations) ---

    fun addSchedulePeriod(period: SchedulePeriod): Boolean {
        if (!_currentMember.value.isParent) return false
        _schedulePeriods.value = (_schedulePeriods.value + period)
            .sortedWith(compareBy({ it.day.index }, { it.periodIndex }))
        return true
    }

    fun updateSchedulePeriod(period: SchedulePeriod): Boolean {
        if (!_currentMember.value.isParent) return false
        _schedulePeriods.value = _schedulePeriods.value.map {
            if (it.id == period.id) period else it
        }.sortedWith(compareBy({ it.day.index }, { it.periodIndex }))
        return true
    }

    fun deleteSchedulePeriod(periodId: String): Boolean {
        if (!_currentMember.value.isParent) return false
        _schedulePeriods.value = _schedulePeriods.value.filterNot { it.id == periodId }
        return true
    }

    fun triggerTenMinuteClassAlertPreview(period: SchedulePeriod, childName: String) {
        val child = _members.value.find { it.id == period.childId }
        pushAlert(
            title = "התראת שיעור בעוד 10 דקות! 🔔",
            message = "$childName: השיעור \"${period.subjectName}\" מתחיל ב-${period.startTime} (${period.roomNumber})",
            targetName = childName,
            targetEmoji = child?.avatarEmoji ?: "🎒",
            reason = AlertReason.UPCOMING_CLASS
        )
    }

    // --- Google Drive Folders & Files ---

    fun addFolder(folder: DocumentFolder) {
        _folders.value = _folders.value + folder
    }

    fun addDocument(doc: FamilyDocument) {
        _documents.value = listOf(doc) + _documents.value
        val uploader = _members.value.find { it.id == doc.uploadedByMemberId }
        pushAlert(
            title = "קובץ חדש סונכרן ל-Google Drive ☁️",
            message = "${doc.name} הועלה ע\"י ${uploader?.name ?: "חבר משפחה"}",
            targetName = uploader?.name ?: "משפחה",
            targetEmoji = uploader?.avatarEmoji ?: "📁",
            reason = AlertReason.DRIVE_SYNC
        )
    }

    fun renameDocument(documentId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        _documents.value = _documents.value.map { doc ->
            if (doc.id == documentId) doc.copy(name = clean) else doc
        }
    }

    fun deleteDocument(documentId: String) {
        _documents.value = _documents.value.filterNot { it.id == documentId }
    }

    // --- Notification Banner & History Management ---

    fun pushAlert(
        title: String,
        message: String,
        targetName: String,
        targetEmoji: String,
        reason: AlertReason
    ) {
        val nowStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val alert = FamilyNotificationAlert(
            title = title,
            message = message,
            targetMemberName = targetName,
            targetMemberEmoji = targetEmoji,
            reason = reason,
            timestampFormatted = nowStr
        )
        _notifications.value = listOf(alert) + _notifications.value
        _activeBannerAlert.value = alert
    }

    fun dismissActiveBanner() {
        _activeBannerAlert.value = null
    }

    fun markAllNotificationsRead() {
        _notifications.value = _notifications.value.map { it.copy(isRead = true) }
    }

    // --- Settings & Preferences ---

    fun setLanguage(lang: AppLanguage) {
        _appLanguage.value = lang
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
    }

    fun setFirstDayOfWeek(day: FirstDayOfWeek) {
        _firstDayOfWeek.value = day
    }

    fun setTimetableViewMode(mode: TimetableViewMode) {
        _timetableViewMode.value = mode
    }

    fun setFilesViewMode(mode: FilesViewMode) {
        _filesViewMode.value = mode
    }

    fun setBiometricLock(enabled: Boolean) {
        _biometricLockEnabled.value = enabled
        if (!enabled) {
            _isAppUnlocked.value = true
        }
    }

    fun lockAppSession() {
        if (_biometricLockEnabled.value) {
            _isAppUnlocked.value = false
        }
    }

    fun unlockAppSession() {
        _isAppUnlocked.value = true
    }

    fun setTenMinuteClassAlerts(enabled: Boolean) {
        _tenMinuteClassAlertsEnabled.value = enabled
    }

    fun setAutoSyncCalendar(enabled: Boolean) {
        _autoSyncCalendar.value = enabled
    }

    fun setAutoSyncDrive(enabled: Boolean) {
        _autoSyncDrive.value = enabled
    }

    fun detachAll() {
        detachFirestoreListeners()
    }

    // --- Direct Cloud Helpers (for Firestore Integration & Security Rule Verification) ---

    suspend fun createFamilyInCloud(
        familyName: String,
        creatorName: String,
        creatorEmail: String = "",
        photoUrl: String? = null,
        familyIdentity: FamilyIdentity = FamilyIdentity.FATHER
    ): Result<FamilyMembershipState.ActiveMember> {
        if (creatorEmail.isNotBlank()) _googleUserEmail.value = creatorEmail
        if (!photoUrl.isNullOrBlank()) _googleUserPhotoUrl.value = photoUrl
        return createFamilyAsParent(
            familyNameInput = familyName,
            parentDisplayName = creatorName,
            familyIdentity = familyIdentity
        )
    }

    suspend fun createRoleInvitationInCloud(
        invitedRole: UserRole,
        inviteeNameHint: String = "",
        familyIdOverride: String? = null
    ): Result<FamilyInvitation> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        val targetFamilyId = familyIdOverride?.takeIf { it.isNotBlank() } ?: _activeFamilyId.value
        val canonicalRole = if (invitedRole.isParentRole) UserRole.PARENT else UserRole.CHILD
        val code = generateSecureInviteCode()
        val now = System.currentTimeMillis()
        val expiresAt = now + FamilyInvitation.DEFAULT_EXPIRATION_MS

        val invitation = FamilyInvitation(
            code = code,
            familyId = targetFamilyId,
            familyName = _familyName.value,
            invitedRole = canonicalRole,
            invitingParentUid = uid,
            invitingParentName = _currentMember.value.name,
            invitedNickname = inviteeNameHint.trim(),
            createdAt = now,
            expiresAt = expiresAt,
            status = InvitationStatus.PENDING
        )

        return try {
            val inviteMap = mapOf(
                "code" to invitation.code,
                "inviteCode" to invitation.code,
                "familyId" to invitation.familyId,
                "familyName" to invitation.familyName,
                "invitedRole" to invitation.invitedRole.toFirestoreValue(),
                "invitingParentUid" to invitation.invitingParentUid,
                "createdById" to invitation.invitingParentUid,
                "invitingParentName" to invitation.invitingParentName,
                "invitedNickname" to invitation.invitedNickname,
                "createdAt" to invitation.createdAt,
                "expiresAt" to invitation.expiresAt,
                "expiresAtMillis" to invitation.expiresAt,
                "status" to InvitationStatus.PENDING.name,
                "isUsed" to false
            )
            db.collection("family_invites").document(code).set(inviteMap).await()
            invitationsByCode[code] = invitation
            Result.success(invitation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateInvitationCodeInCloud(inviteCode: String): InvitationValidationResult {
        return validateInvitationCode(inviteCode)
    }

    suspend fun redeemInvitationAndJoinFamilyInCloud(
        inviteCode: String,
        memberDisplayName: String,
        photoUrl: String? = null,
        familyIdentity: FamilyIdentity? = null
    ): Result<FamilyMembershipState.ActiveMember> {
        return redeemInvitationAndJoinFamily(
            rawCode = inviteCode,
            memberDisplayName = memberDisplayName,
            joiningPhotoUrlOverride = photoUrl,
            familyIdentity = familyIdentity
        )
    }

    suspend fun createTaskInCloud(task: FamilyTask): Result<String> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            val payload = hashMapOf<String, Any?>(
                "id" to task.id,
                "userId" to uid,
                "familyId" to task.familyId,
                "title" to task.title,
                "description" to task.description,
                "category" to task.category.name,
                "assignedUid" to task.assignedToMemberId,
                "assignedToMemberId" to task.assignedToMemberId,
                "dateEpochDay" to task.dateEpochDay,
                "dueDate" to (task.dateEpochDay * 86_400_000L),
                "dueTimeFormatted" to task.dueTimeFormatted,
                "isCompleted" to task.isCompleted,
                "priority" to task.priority.name,
                "recurrence" to task.recurrence.name,
                "points" to task.points,
                "pointsValue" to task.points,
                "approvedByParent" to task.approvedByParent,
                "completedAtMillis" to task.completedAtMillis,
                "completedByMemberId" to task.completedByMemberId,
                "updatedAt" to System.currentTimeMillis()
            )
            if (task.familyId.isNotBlank()) {
                db.collection("families").document(task.familyId)
                    .collection("tasks").document(task.id)
                    .set(payload).await()
            } else {
                db.collection("tasks").document(task.id)
                    .set(payload).await()
            }
            Result.success(task.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTasksFromCloud(familyId: String = ""): Result<List<FamilyTask>> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            val docs = if (familyId.isNotBlank()) {
                db.collection("families").document(familyId).collection("tasks").get().await().documents
            } else {
                db.collection("tasks").whereEqualTo("userId", uid).get().await().documents
            }
            val parsed = docs.mapNotNull { doc ->
                val title = doc.getString("title") ?: return@mapNotNull null
                val desc = doc.getString("description") ?: ""
                val assigned = doc.getString("assignedUid") ?: doc.getString("assignedToMemberId") ?: ""
                val epochDay = doc.getLong("dateEpochDay") ?: ((doc.getLong("dueDate") ?: 0L) / 86_400_000L)
                val pts = (doc.getLong("points") ?: doc.getLong("pointsValue") ?: 10L).toInt()
                val prioStr = doc.getString("priority") ?: TaskPriority.MEDIUM.name
                val catStr = doc.getString("category") ?: TaskCategory.OTHER.name
                val recStr = doc.getString("recurrence") ?: TaskRecurrence.ONCE.name
                FamilyTask(
                    id = doc.id,
                    familyId = doc.getString("familyId") ?: familyId,
                    title = title,
                    description = desc,
                    category = runCatching { TaskCategory.valueOf(catStr) }.getOrDefault(TaskCategory.OTHER),
                    assignedToMemberId = assigned,
                    dateEpochDay = epochDay,
                    dueTimeFormatted = doc.getString("dueTimeFormatted") ?: "16:00",
                    isCompleted = doc.getBoolean("isCompleted") ?: false,
                    priority = runCatching { TaskPriority.valueOf(prioStr) }.getOrDefault(TaskPriority.MEDIUM),
                    recurrence = runCatching { TaskRecurrence.valueOf(recStr) }.getOrDefault(TaskRecurrence.ONCE),
                    points = pts,
                    approvedByParent = doc.getBoolean("approvedByParent") ?: false,
                    completedAtMillis = doc.getLong("completedAtMillis"),
                    completedByMemberId = doc.getString("completedByMemberId")
                )
            }
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateTaskInCloud(task: FamilyTask): Result<Unit> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            if (task.familyId.isNotBlank()) {
                val familyRef = db.collection("families").document(task.familyId)
                val memberSnap = runCatching {
                    familyRef.collection("members").document(uid).get().await()
                }.getOrNull()
                val memberRole = UserRole.fromFirestoreValue(memberSnap?.getString("role"))
                val isParentInCloud = memberSnap?.exists() == true && memberRole == UserRole.PARENT

                val taskRef = familyRef.collection("tasks").document(task.id)
                if (!isParentInCloud && task.assignedToMemberId == uid) {
                    // Child completion-only update path: preserves parent's title/description/priority/points edits
                    taskRef.update(
                        mapOf(
                            "isCompleted" to task.isCompleted,
                            "completedAtMillis" to (task.completedAtMillis ?: System.currentTimeMillis()),
                            "completedByMemberId" to (task.completedByMemberId ?: uid)
                        )
                    ).await()
                } else {
                    taskRef.update(
                        mapOf(
                            "title" to task.title,
                            "description" to task.description,
                            "category" to task.category.name,
                            "assignedUid" to task.assignedToMemberId,
                            "assignedToMemberId" to task.assignedToMemberId,
                            "dateEpochDay" to task.dateEpochDay,
                            "dueDate" to (task.dateEpochDay * 86_400_000L),
                            "isCompleted" to task.isCompleted,
                            "priority" to task.priority.name,
                            "recurrence" to task.recurrence.name,
                            "points" to task.points,
                            "pointsValue" to task.points,
                            "approvedByParent" to task.approvedByParent,
                            "completedAtMillis" to task.completedAtMillis,
                            "completedByMemberId" to task.completedByMemberId
                        )
                    ).await()
                }
            } else {
                db.collection("tasks").document(task.id).update(
                    mapOf(
                        "title" to task.title,
                        "isCompleted" to task.isCompleted,
                        "points" to task.points,
                        "pointsValue" to task.points
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeTaskInCloud(
        taskId: String,
        familyId: String,
        isCompleted: Boolean,
        completedByMemberId: String
    ): Result<Unit> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            db.collection("families").document(familyId)
                .collection("tasks").document(taskId)
                .update(
                    mapOf(
                        "isCompleted" to isCompleted,
                        "completedAtMillis" to System.currentTimeMillis(),
                        "completedByMemberId" to completedByMemberId
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createDocumentInCloud(doc: FamilyDocument): Result<String> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            val payload = hashMapOf(
                "id" to doc.id,
                "userId" to uid,
                "folderId" to doc.folderId,
                "name" to doc.name,
                "extension" to doc.extension,
                "sizeFormatted" to doc.sizeFormatted,
                "uploadedByName" to doc.uploadedByName,
                "uploadDateFormatted" to doc.uploadDateFormatted,
                "isDriveSynced" to doc.isDriveSynced,
                "driveFileUrl" to doc.driveFileUrl
            )
            db.collection("documents").document(doc.id).set(payload).await()
            Result.success(doc.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDocumentsFromCloud(): Result<List<FamilyDocument>> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            val snap = db.collection("documents").whereEqualTo("userId", uid).get().await()
            val docs = snap.documents.mapNotNull { d ->
                val name = d.getString("name") ?: return@mapNotNull null
                FamilyDocument(
                    id = d.id,
                    folderId = d.getString("folderId") ?: "f_school",
                    name = name,
                    extension = d.getString("extension") ?: "PDF",
                    sizeFormatted = d.getString("sizeFormatted") ?: "1.0 MB",
                    uploadedByMemberId = d.getString("uploadedByName") ?: "",
                    uploadedDateFormatted = d.getString("uploadDateFormatted") ?: "Today",
                    isDriveSynced = d.getBoolean("isDriveSynced") ?: true,
                    driveUrl = d.getString("driveFileUrl") ?: ""
                )
            }
            Result.success(docs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createInviteInCloud(
        inviteCode: String,
        familyId: String,
        invitedRole: UserRole = UserRole.CHILD
    ): Result<String> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            val now = System.currentTimeMillis()
            val expires = now + FamilyInvitation.DEFAULT_EXPIRATION_MS
            val payload = hashMapOf(
                "code" to inviteCode,
                "inviteCode" to inviteCode,
                "familyId" to familyId,
                "invitedRole" to invitedRole.toFirestoreValue(),
                "invitingParentUid" to uid,
                "createdById" to uid,
                "status" to InvitationStatus.PENDING.name,
                "isUsed" to false,
                "createdAt" to now,
                "expiresAt" to expires,
                "expiresAtMillis" to expires
            )
            db.collection("family_invites").document(inviteCode).set(payload).await()
            Result.success(inviteCode)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinFamilyWithInviteInCloud(
        inviteCode: String,
        familyId: String,
        memberName: String,
        familyIdentity: FamilyIdentity? = null
    ): Result<String> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            val inviteRef = db.collection("family_invites").document(inviteCode)
            val familyRef = db.collection("families").document(familyId)
            val memberRef = familyRef.collection("members").document(uid)
            val now = System.currentTimeMillis()

            db.runTransaction { tx ->
                val inviteSnap = tx.get(inviteRef)
                if (!inviteSnap.exists()) {
                    throw IllegalStateException("Invitation does not exist")
                }
                val isUsed = inviteSnap.getBoolean("isUsed") ?: false
                val statusStr = inviteSnap.getString("status") ?: "PENDING"
                if (isUsed || statusStr == "REDEEMED" || statusStr == "REVOKED" || statusStr == "EXPIRED") {
                    throw IllegalStateException("Invitation already redeemed or revoked")
                }
                val expiresAt = inviteSnap.getLong("expiresAt") ?: inviteSnap.getLong("expiresAtMillis") ?: 0L
                if (now > expiresAt) {
                    throw IllegalStateException("Invitation expired")
                }
                val parsedRole = UserRole.fromFirestoreValue(inviteSnap.getString("invitedRole"))
                val invitedRole = parsedRole.toFirestoreValue()
                val resolvedIdentity = if (parsedRole == UserRole.PARENT) {
                    familyIdentity?.takeIf { it.isParentIdentity }
                        ?: FamilyIdentity.fromFirestoreValue(
                            raw = null,
                            role = UserRole.PARENT,
                            nameHint = memberName,
                            fallbackParentIdentity = FamilyIdentity.MOTHER
                        )
                } else {
                    familyIdentity?.takeIf { !it.isParentIdentity }
                        ?: FamilyIdentity.fromFirestoreValue(
                            raw = null,
                            role = UserRole.CHILD,
                            nameHint = memberName
                        )
                }

                tx.update(
                    inviteRef,
                    mapOf(
                        "status" to InvitationStatus.REDEEMED.name,
                        "isUsed" to true,
                        "redeemedByUid" to uid,
                        "usedByUid" to uid,
                        "redeemedAt" to now
                    )
                )

                tx.set(
                    memberRef,
                    mapOf(
                        "id" to uid,
                        "uid" to uid,
                        "familyId" to familyId,
                        "name" to memberName,
                        "role" to invitedRole,
                        "familyIdentity" to resolvedIdentity.toFirestoreValue(),
                        "avatarEmoji" to resolvedIdentity.defaultEmoji,
                        "earnedPoints" to 0,
                        "spentPoints" to 0,
                        "inviteCode" to inviteCode
                    ),
                    SetOptions.merge()
                )

                val familyUpdates = mutableMapOf<String, Any>(
                    "memberIds" to FieldValue.arrayUnion(uid),
                    "memberUids" to FieldValue.arrayUnion(uid)
                )
                if (invitedRole == "PARENT") {
                    familyUpdates["adminUids"] = FieldValue.arrayUnion(uid)
                }
                tx.update(familyRef, familyUpdates)
            }.await()
            Result.success(familyId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reconnectExistingMemberInCloud(
        familyId: String,
        memberName: String
    ): Result<FamilyMember> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return try {
            val memberRef = db.collection("families").document(familyId).collection("members").document(uid)
            val snap = memberRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalStateException("Existing member document not found"))
            }
            val role = UserRole.fromFirestoreValue(snap.getString("role"))
            val persistedName = snap.getString("name")?.takeIf { it.isNotBlank() } ?: memberName
            val rawIdentity = snap.getString("familyIdentity")
            val rawEmoji = snap.getString("avatarEmoji")?.takeIf { it.isNotBlank() }
            val identity = FamilyIdentity.fromFirestoreValue(
                raw = rawIdentity,
                role = role,
                avatarEmoji = rawEmoji,
                nameHint = persistedName,
                memberId = uid,
                fallbackParentIdentity = FamilyIdentity.FATHER
            )
            Result.success(
                FamilyMember(
                    id = uid,
                    name = persistedName,
                    avatarEmoji = rawEmoji ?: identity.defaultEmoji,
                    role = role,
                    familyIdentity = identity,
                    earnedPoints = (snap.getLong("earnedPoints") ?: 0L).toInt()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
