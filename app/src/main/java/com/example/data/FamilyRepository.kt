package com.example.data

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.OfflineSyncDao
import com.example.data.local.OfflineSyncQueueEntity
import com.example.data.local.TaskDao
import com.example.model.*
import com.example.notifications.FcmTokenManager
import com.example.util.FirestoreErrorHandler
import com.example.util.FirestoreErrorHandler.StartupFailureCategory
import com.example.util.FirestoreErrorHandler.StartupStage
import com.example.util.NotificationHelper
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
 * - Always targets the provisioned named Firestore database (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`) and never silently falls back to `(default)`
 * - Validates actual Firestore membership on Google Sign-In and session restoration without overwriting existing profiles or families
 * - Explicitly migrates legacy V1.5 family/member/user records to the V1.6 PARENT/CHILD + FamilyIdentity schema
 * - Implements role-specific, domain-free invitations (/family_invites/{code}) with expiration & atomic redemption
 * - Maintains real-time Firestore snapshot listeners for active family members and pending invitations
 * - Protects the last remaining PARENT from removal or demotion
 */
class FamilyRepository(
    private val context: Context? = null,
    private val taskDao: TaskDao? = context?.let { AppDatabase.getInstance(it).taskDao() },
    private val offlineSyncDao: OfflineSyncDao? = context?.let { AppDatabase.getInstance(it).offlineSyncDao() },
    val configuredDatabaseId: String = resolveConfiguredDatabaseId(context),
    private val firestoreProvider: () -> FirebaseFirestore? = {
        resolveNamedFirestore(context)
    },
    private val authProvider: () -> FirebaseAuth? = {
        runCatching { FirebaseAuth.getInstance() }.getOrNull()
    }
) {
    private val appContext: Context?
        get() = context

    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ) : this(
        context = null,
        taskDao = null,
        offlineSyncDao = null,
        configuredDatabaseId = NAMED_FIRESTORE_DATABASE_ID,
        firestoreProvider = { firestore },
        authProvider = { auth }
    )

    companion object {
        private const val TAG = "FamilyRepository"
        const val NAMED_FIRESTORE_DATABASE_ID = FirestoreErrorHandler.NAMED_DATABASE_ID
        private const val INVITE_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val INVITE_CODE_LENGTH = 6
        private val secureRandom = SecureRandom()

        /**
         * Resolves the canonical named Firestore database ID from `R.string.firebase_database_id`
         * or [NAMED_FIRESTORE_DATABASE_ID]. Rejects blank or `(default)` values so the repository
         * never silently falls back to the unprovisioned `(default)` database.
         */
        fun resolveConfiguredDatabaseId(context: Context? = null): String {
            val fromRes = runCatching {
                context?.getString(com.example.R.string.firestore_database_id)?.trim()
            }.getOrNull()
            return if (!fromRes.isNullOrBlank() && fromRes != "(default)") {
                fromRes
            } else {
                NAMED_FIRESTORE_DATABASE_ID
            }
        }

        /**
         * Obtains the [FirebaseFirestore] instance for the provisioned named database
         * (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`).
         * Never calls `FirebaseFirestore.getInstance()` without the named database ID.
         */
        fun resolveNamedFirestore(context: Context? = null): FirebaseFirestore? {
            val databaseId = resolveConfiguredDatabaseId(context)
            return runCatching {
                FirebaseFirestore.getInstance(databaseId)
            }.onFailure { err ->
                Log.w(TAG, "FirebaseFirestore.getInstance($databaseId) unavailable in current environment: ${err.message}")
            }.getOrNull()
        }

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

    /** Last classified startup error for diagnostics and automated tests. */
    @Volatile
    var lastClassifiedStartupFailure: FirestoreErrorHandler.ClassifiedStartupFailure? = null
        private set

    /** Last classified invitation error for diagnostics and automated tests. */
    @Volatile
    var lastClassifiedInvitationFailure: FirestoreErrorHandler.ClassifiedInvitationFailure? = null
        private set

    /** Last classified task error for diagnostics and automated tests. */
    @Volatile
    var lastClassifiedTaskFailure: FirestoreErrorHandler.ClassifiedTaskFailure? = null
        private set

    /** Deduplication set of task completion/approval notification keys to prevent duplicate alerts from snapshot listeners or retries. */
    private val notifiedTaskCompletionKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

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

    // Explicit per-task synchronization & approval states (IDLE, SAVING, PENDING_APPROVAL, SAVED_APPROVED, FAILED)
    private val _taskSyncStates = MutableStateFlow<Map<String, TaskCompletionSyncState>>(emptyMap())
    val taskSyncStates: StateFlow<Map<String, TaskCompletionSyncState>> = _taskSyncStates.asStateFlow()

    // User-facing Hebrew error message when a task completion/approval/edit fails in Firestore
    private val _taskOperationError = MutableStateFlow<String?>(null)
    val taskOperationError: StateFlow<String?> = _taskOperationError.asStateFlow()

    fun clearTaskOperationError() {
        _taskOperationError.value = null
    }

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
     * Reads a Firestore document with diagnostic logging and selective fallback to `Source.CACHE`
     * ONLY when the error is `UNAVAILABLE` (offline / network timeout).
     * Never falls back to `Source.CACHE` on `PERMISSION_DENIED` or `NOT_FOUND`, which previously
     * masked security rule and database-ID errors with a misleading cache miss exception.
     */
    private suspend fun getDocumentWithCacheFallback(
        docRef: DocumentReference,
        stage: StartupStage = StartupStage.LOAD_USER_PROFILE,
        collectionPath: String = docRef.parent.path,
        documentId: String = docRef.id
    ): DocumentSnapshot {
        return try {
            docRef.get().await()
        } catch (primaryError: Exception) {
            val classified = FirestoreErrorHandler.classifyStartupFailure(
                error = primaryError,
                stage = stage,
                collectionPath = collectionPath,
                documentId = documentId,
                databaseId = configuredDatabaseId
            )
            lastClassifiedStartupFailure = classified

            if (classified.category == StartupFailureCategory.UNAVAILABLE) {
                Log.w(TAG, "Network UNAVAILABLE at stage=${stage.stageName} collection=$collectionPath, attempting Source.CACHE fallback")
                return try {
                    docRef.get(Source.CACHE).await()
                } catch (_: Exception) {
                    // Rethrow the original server/network error so the caller sees UNAVAILABLE rather than a cache error
                    throw primaryError
                }
            }
            throw primaryError
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
     * Resolves a user's canonical V1.6 [UserRole] (`PARENT` or `CHILD`) strictly from authoritative
     * family documents (`/families/{familyId}/members/{uid}` and `/families/{familyId}`).
     *
     * SECURITY INVARIANT (V1.6 Task 6):
     * - NEVER grants [UserRole.PARENT] based only on a user-controlled `/users/{uid}` profile field.
     * - Grants [UserRole.PARENT] ONLY when:
     *   1. `/families/{familyId}/members/{uid}` exists and has `role` in `['PARENT', 'ADMIN', 'PARENT_ADMIN', 'FATHER', 'MOTHER']`, OR
     *   2. `/families/{familyId}` verifies the user is the family creator/admin (`createdBy == uid`, `ownerUid == uid`, or `adminUids` contains `uid`).
     * - Otherwise returns [UserRole.CHILD].
     */
    internal fun resolveAuthoritativeMembershipRole(
        uid: String,
        memberSnap: DocumentSnapshot?,
        familySnap: DocumentSnapshot?
    ): UserRole {
        val memberRoleRaw = memberSnap?.takeIf { it.exists() }
            ?.getString("role")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (memberRoleRaw != null) {
            return UserRole.fromFirestoreValue(memberRoleRaw)
        }

        val isVerifiedCreatorOrAdminOnFamily = familySnap?.exists() == true && (
            familySnap.getString("createdBy") == uid ||
                familySnap.getString("ownerUid") == uid ||
                (familySnap.get("adminUids") as? List<*>)?.contains(uid) == true
            )

        return if (isVerifiedCreatorOrAdminOnFamily) {
            UserRole.PARENT
        } else {
            UserRole.CHILD
        }
    }

    /**
     * Checks whether a canonical V1.6 role string (`"PARENT"` or `"CHILD"`) is already stored.
     */
    private fun isCanonicalV16RoleString(rawRole: String?): Boolean {
        return rawRole == "PARENT" || rawRole == "CHILD"
    }

    /**
     * V1.6 Requirement 2 + Safe Startup State Machine + V1.5 -> V1.6 Migration:
     * Checks actual user profile and family membership in Firestore after Google authentication.
     * - Serialized via [startupMutex] so concurrent auth callbacks never race.
     * - Reads `/users/{uid}`, `/families/{familyId}`, and `/families/{familyId}/members/{uid}` from the
     *   provisioned named database (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`)
     *   without EVER overwriting existing profile fields (`displayName`, `familyIdentity`, `role`, `avatarEmoji`, `familyName`).
     * - Preserves `FATHER` vs `MOTHER` identity (never defaults an existing user to `MOTHER`).
     * - Safely migrates legacy V1.5 records (`ADMIN`, `FATHER`, `MOTHER`, `MEMBER`, `name`, `createdBy`, `ownerUid`)
     *   to the V1.6 `PARENT` / `CHILD` + `FamilyIdentity` schema without granting `PARENT` from user-controlled `/users/{uid}` fields.
     * - Distinguishes `PERMISSION_DENIED`, `UNAVAILABLE`, `NOT_FOUND`, missing membership, legacy profile, and unexpected exceptions.
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

        var currentStage = StartupStage.LOAD_USER_PROFILE
        var currentCollectionPath = "users"
        var currentDocumentId = uid

        return@withLock try {
            if (simulatedFirestoreLatencyMs > 0L) {
                delay(simulatedFirestoreLatencyMs)
            }
            if (simulateOfflineNetworkFailure) {
                throw FirebaseFirestoreException(
                    "Simulated offline network failure",
                    FirebaseFirestoreException.Code.UNAVAILABLE
                )
            }

            FirestoreErrorHandler.logStartupMilestone(
                stage = StartupStage.LOAD_USER_PROFILE,
                collectionPath = "users",
                databaseId = configuredDatabaseId,
                note = "Reading user profile document"
            )

            val userDocRef = db.collection("users").document(uid)
            val userSnap = getDocumentWithCacheFallback(
                docRef = userDocRef,
                stage = StartupStage.LOAD_USER_PROFILE,
                collectionPath = "users",
                documentId = uid
            )

            val storedUserDisplayName = userSnap.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: userSnap.getString("name")?.takeIf { it.isNotBlank() }
            val storedUserRoleStr = (userSnap.getString("role") ?: userSnap.getString("userRole"))
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val storedUserIdentityStr = (
                userSnap.getString("familyIdentity")
                    ?: userSnap.getString("parentIdentity")
                    ?: userSnap.getString("identity")
                )?.trim()?.takeIf { it.isNotBlank() }
                ?: storedUserRoleStr?.takeIf {
                    val upper = it.uppercase()
                    upper == "FATHER" || upper == "MOTHER" || upper == "SON" || upper == "DAUGHTER"
                }
            val storedUserEmoji = userSnap.getString("avatarEmoji")?.takeIf { it.isNotBlank() }
            val storedUserPhotoUrl = userSnap.getString("photoUrl")?.takeIf { it.isNotBlank() }
            val storedUserFamilyName = (
                userSnap.getString("familyName")
                    ?: userSnap.getString("groupName")
                )?.takeIf { it.isNotBlank() }

            var existingFamilyId = (
                userSnap.getString("familyId")
                    ?: userSnap.getString("activeFamilyId")
                    ?: userSnap.getString("groupId")
                )?.takeIf { it.isNotBlank() }

            // Fallback discovery for legacy V1.5 users whose /users/{uid} document had no familyId
            if (existingFamilyId == null) {
                currentStage = StartupStage.LOAD_FAMILY_DOCUMENT
                currentCollectionPath = "families"
                val discoveredFamilyDoc = runCatching {
                    db.collection("families")
                        .whereEqualTo("createdBy", uid)
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
                }.getOrNull() ?: runCatching {
                    db.collection("families")
                        .whereArrayContains("memberUids", uid)
                        .limit(1)
                        .get()
                        .await()
                        .documents
                        .firstOrNull()
                }.getOrNull() ?: runCatching {
                    db.collection("families")
                        .whereArrayContains("memberIds", uid)
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

                // Step 1: Read /families/{familyId} FIRST (permitted for all signed-in users in V1.6 rules)
                currentStage = StartupStage.LOAD_FAMILY_DOCUMENT
                currentCollectionPath = "families"
                currentDocumentId = existingFamilyId
                FirestoreErrorHandler.logStartupMilestone(
                    stage = StartupStage.LOAD_FAMILY_DOCUMENT,
                    collectionPath = "families",
                    databaseId = configuredDatabaseId,
                    note = "Reading family metadata document"
                )
                val familySnap = try {
                    getDocumentWithCacheFallback(
                        docRef = familyRef,
                        stage = StartupStage.LOAD_FAMILY_DOCUMENT,
                        collectionPath = "families",
                        documentId = existingFamilyId
                    )
                } catch (familyErr: FirebaseFirestoreException) {
                    if (familyErr.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                        throw familyErr
                    }
                    null
                }

                // Step 2: Read /families/{familyId}/members/{uid}
                // Note: Under the live V1.6 rules where `allow get: if isFamilyMember(familyId)`,
                // if `/families/{familyId}/members/{uid}` does not exist yet (e.g., legacy V1.5 family),
                // `memberRef.get()` throws PERMISSION_DENIED instead of returning exists() == false.
                // We distinguish PERMISSION_DENIED (missing member doc -> check familySnap for verified V1.5 migration)
                // from UNAVAILABLE (transient offline failure -> rethrow to RecoverableError).
                currentStage = StartupStage.LOAD_FAMILY_MEMBER
                currentCollectionPath = "families/$existingFamilyId/members"
                currentDocumentId = uid
                FirestoreErrorHandler.logStartupMilestone(
                    stage = StartupStage.LOAD_FAMILY_MEMBER,
                    collectionPath = currentCollectionPath,
                    databaseId = configuredDatabaseId,
                    note = "Reading family member document"
                )
                val memberSnap: DocumentSnapshot? = try {
                    getDocumentWithCacheFallback(
                        docRef = memberRef,
                        stage = StartupStage.LOAD_FAMILY_MEMBER,
                        collectionPath = currentCollectionPath,
                        documentId = uid
                    )
                } catch (memberErr: FirebaseFirestoreException) {
                    if (memberErr.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                        throw memberErr
                    }
                    if (memberErr.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                        memberErr.code == FirebaseFirestoreException.Code.NOT_FOUND
                    ) {
                        Log.i(
                            TAG,
                            "Member document not directly readable or absent at families/$existingFamilyId/members (code=${memberErr.code}); checking verified family ownership/membership for V1.5 migration."
                        )
                        null
                    } else {
                        throw memberErr
                    }
                }

                val isVerifiedInFamilyDoc = familySnap?.exists() == true && (
                    familySnap.getString("createdBy") == uid ||
                        familySnap.getString("ownerUid") == uid ||
                        (familySnap.get("adminUids") as? List<*>)?.contains(uid) == true ||
                        (familySnap.get("memberUids") as? List<*>)?.contains(uid) == true ||
                        (familySnap.get("memberIds") as? List<*>)?.contains(uid) == true
                    )

                val memberExists = memberSnap?.exists() == true

                if (memberExists || isVerifiedInFamilyDoc) {
                    currentStage = StartupStage.VERIFY_MEMBERSHIP

                    // Authoritative role resolution: NEVER grants PARENT based only on /users/{uid}
                    val validatedRole = resolveAuthoritativeMembershipRole(
                        uid = uid,
                        memberSnap = memberSnap,
                        familySnap = familySnap
                    )

                    val resolvedFamilyName = familySnap?.getString("familyName")?.takeIf { it.isNotBlank() }
                        ?: familySnap?.getString("name")?.takeIf { it.isNotBlank() }
                        ?: storedUserFamilyName
                        ?: _familyName.value

                    val persistedName = memberSnap?.getString("name")?.takeIf { it.isNotBlank() }
                        ?: memberSnap?.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: storedUserDisplayName
                        ?: displayName.takeIf { it.isNotBlank() }
                        ?: if (validatedRole == UserRole.PARENT) "אבא" else "ילד/ה"

                    val memberRoleStr = memberSnap?.getString("role")?.trim()?.takeIf { it.isNotBlank() }
                    val rawIdentity = memberSnap?.getString("familyIdentity")?.takeIf { it.isNotBlank() }
                        ?: memberSnap?.getString("parentIdentity")?.takeIf { it.isNotBlank() }
                        ?: memberRoleStr?.takeIf {
                            val upper = it.uppercase()
                            upper == "FATHER" || upper == "MOTHER" || upper == "SON" || upper == "DAUGHTER"
                        }
                        ?: storedUserIdentityStr
                    val rawEmoji = memberSnap?.getString("avatarEmoji")?.takeIf { it.isNotBlank() }
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
                    val resolvedPhotoUrl = memberSnap?.getString("photoUrl")?.takeIf { it.isNotBlank() }
                        ?: storedUserPhotoUrl
                        ?: photoUrl
                    val resolvedEmail = memberSnap?.getString("googleEmail")?.takeIf { it.isNotBlank() }
                        ?: userSnap.getString("email")?.takeIf { it.isNotBlank() }
                        ?: email
                    val preservedEarnedPoints = (memberSnap?.getLong("earnedPoints") ?: 0L).toInt()
                    val preservedSpentPoints = (memberSnap?.getLong("spentPoints") ?: 0L).toInt()

                    // Detect whether this record is a legacy V1.5 profile/family/member needing V1.6 normalization
                    val needsFamilyMigration = familySnap?.exists() == true && (
                        familySnap.getString("familyName").isNullOrBlank() ||
                            familySnap.getString("createdBy").isNullOrBlank() ||
                            familySnap.getString("name").isNullOrBlank()
                        )
                    val needsMemberMigration = !memberExists ||
                        !isCanonicalV16RoleString(memberRoleStr) ||
                        memberSnap?.getString("familyIdentity").isNullOrBlank()
                    val needsUserMigration = !userSnap.exists() ||
                        userSnap.getString("familyId").isNullOrBlank() ||
                        !isCanonicalV16RoleString(storedUserRoleStr) ||
                        userSnap.getString("familyIdentity").isNullOrBlank() ||
                        userSnap.getString("familyName").isNullOrBlank()

                    if (needsFamilyMigration || needsMemberMigration || needsUserMigration) {
                        currentStage = StartupStage.MIGRATE_LEGACY_V15_DATA
                        FirestoreErrorHandler.logLegacyMigration(
                            databaseId = configuredDatabaseId,
                            legacyRole = memberRoleStr ?: storedUserRoleStr,
                            migratedRole = validatedRole.toFirestoreValue(),
                            preservedIdentity = validatedIdentity.toFirestoreValue(),
                            hadMemberDoc = memberExists,
                            hadUserDoc = userSnap.exists()
                        )
                    }

                    // 1. Safe V1.5 -> V1.6 family document normalization (only if user is PARENT and fields are missing)
                    if (validatedRole == UserRole.PARENT && familySnap?.exists() == true && needsFamilyMigration) {
                        val familyUpdates = mutableMapOf<String, Any>()
                        if (familySnap.getString("familyName").isNullOrBlank()) {
                            familyUpdates["familyName"] = resolvedFamilyName
                        }
                        if (familySnap.getString("name").isNullOrBlank()) {
                            familyUpdates["name"] = resolvedFamilyName
                        }
                        if (familySnap.getString("createdBy").isNullOrBlank()) {
                            val creator = familySnap.getString("ownerUid")?.takeIf { it.isNotBlank() } ?: uid
                            familyUpdates["createdBy"] = creator
                        }
                        if (familySnap.getString("ownerUid").isNullOrBlank()) {
                            val owner = familySnap.getString("createdBy")?.takeIf { it.isNotBlank() } ?: uid
                            familyUpdates["ownerUid"] = owner
                        }
                        if (familyUpdates.isNotEmpty()) {
                            runCatching {
                                familyRef.set(familyUpdates, SetOptions.merge()).await()
                            }
                        }
                    }

                    // 2. Safe V1.5 -> V1.6 member document migration using non-destructive merge
                    val memberMissingFields = mutableMapOf<String, Any>()
                    if (!memberExists) {
                        memberMissingFields["id"] = uid
                        memberMissingFields["uid"] = uid
                        memberMissingFields["familyId"] = existingFamilyId
                        memberMissingFields["name"] = persistedName
                        memberMissingFields["nameEn"] = persistedName
                        memberMissingFields["role"] = validatedRole.toFirestoreValue()
                        memberMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        memberMissingFields["avatarEmoji"] = resolvedEmoji
                        memberMissingFields["earnedPoints"] = preservedEarnedPoints
                        memberMissingFields["spentPoints"] = preservedSpentPoints
                        memberMissingFields["invitationPending"] = false
                        memberMissingFields["invitationAccepted"] = true
                        if (resolvedEmail.isNotBlank()) memberMissingFields["googleEmail"] = resolvedEmail
                        if (!resolvedPhotoUrl.isNullOrBlank()) memberMissingFields["photoUrl"] = resolvedPhotoUrl
                    } else {
                        if (!isCanonicalV16RoleString(memberRoleStr)) {
                            memberMissingFields["role"] = validatedRole.toFirestoreValue()
                        }
                        if (memberSnap?.getString("familyIdentity").isNullOrBlank()) {
                            memberMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        }
                        if (memberSnap?.getString("googleEmail").isNullOrBlank() && resolvedEmail.isNotBlank()) {
                            memberMissingFields["googleEmail"] = resolvedEmail
                        }
                        if (memberSnap?.getString("photoUrl").isNullOrBlank() && !resolvedPhotoUrl.isNullOrBlank()) {
                            memberMissingFields["photoUrl"] = resolvedPhotoUrl
                        }
                    }
                    if (memberMissingFields.isNotEmpty()) {
                        runCatching {
                            memberRef.set(memberMissingFields, SetOptions.merge()).await()
                        }
                    }

                    // 3. Safe V1.5 -> V1.6 user profile migration using non-destructive merge
                    val userMissingFields = mutableMapOf<String, Any>()
                    if (!userSnap.exists() || userSnap.getString("familyId").isNullOrBlank()) {
                        userMissingFields["uid"] = uid
                        userMissingFields["familyId"] = existingFamilyId
                        userMissingFields["role"] = validatedRole.toFirestoreValue()
                        userMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        userMissingFields["displayName"] = persistedName
                        userMissingFields["name"] = persistedName
                        userMissingFields["avatarEmoji"] = resolvedEmoji
                        userMissingFields["familyName"] = resolvedFamilyName
                        if (resolvedEmail.isNotBlank()) userMissingFields["email"] = resolvedEmail
                        if (!resolvedPhotoUrl.isNullOrBlank()) userMissingFields["photoUrl"] = resolvedPhotoUrl
                    } else {
                        // Also normalize user profile role to match the authoritative family membership role
                        if (storedUserRoleStr != validatedRole.toFirestoreValue()) {
                            userMissingFields["role"] = validatedRole.toFirestoreValue()
                        }
                        if (userSnap.getString("familyIdentity").isNullOrBlank()) {
                            userMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        }
                        if (userSnap.getString("familyName").isNullOrBlank()) {
                            userMissingFields["familyName"] = resolvedFamilyName
                        }
                    }
                    if (userMissingFields.isNotEmpty()) {
                        runCatching {
                            userDocRef.set(userMissingFields, SetOptions.merge()).await()
                        }
                    }

                    val restoredMember = FamilyMember(
                        id = uid,
                        name = persistedName,
                        nameEn = memberSnap?.getString("nameEn")?.takeIf { it.isNotBlank() } ?: persistedName,
                        avatarEmoji = resolvedEmoji,
                        role = validatedRole,
                        familyIdentity = validatedIdentity,
                        colorHex = memberSnap?.getLong("colorHex")
                            ?: if (validatedRole == UserRole.PARENT) {
                                if (validatedIdentity == FamilyIdentity.FATHER) 0xFF1A73E8 else 0xFF9334E6
                            } else {
                                0xFF34A853
                            },
                        googleEmail = resolvedEmail,
                        photoUrl = resolvedPhotoUrl,
                        schoolGrade = memberSnap?.getString("schoolGrade"),
                        earnedPoints = preservedEarnedPoints,
                        invitationPending = false,
                        invitationAccepted = true
                    )

                    _activeFamilyId.value = existingFamilyId
                    _familyName.value = resolvedFamilyName
                    _googleUserDisplayName.value = persistedName
                    if (resolvedEmail.isNotBlank()) _googleUserEmail.value = resolvedEmail
                    if (!resolvedPhotoUrl.isNullOrBlank()) _googleUserPhotoUrl.value = resolvedPhotoUrl
                    _currentMember.value = restoredMember

                    val loadedMembers = if (db != null && authProvider()?.currentUser != null) {
                        val allDocs = runCatching {
                            db.collection("families").document(existingFamilyId)
                                .collection("members").get().await().documents
                        }.getOrNull().orEmpty()
                        val parsedMembers = allDocs.mapNotNull { doc ->
                            if (doc.id == uid) {
                                restoredMember
                            } else {
                                val mName = doc.getString("name") ?: doc.getString("displayName") ?: return@mapNotNull null
                                val mRoleRaw = doc.getString("role") ?: doc.getString("userRole") ?: doc.getString("familyIdentity")
                                val mRole = UserRole.fromFirestoreValue(mRoleRaw)
                                val mPoints = (doc.getLong("earnedPoints") ?: 0L).toInt()
                                val mIdentityRaw = doc.getString("familyIdentity")
                                val mEmojiRaw = doc.getString("avatarEmoji")?.takeIf { it.isNotBlank() }
                                val mIdentity = FamilyIdentity.fromFirestoreValue(
                                    raw = mIdentityRaw,
                                    role = mRole,
                                    avatarEmoji = mEmojiRaw,
                                    nameHint = mName,
                                    memberId = doc.id
                                )
                                FamilyMember(
                                    id = doc.id,
                                    name = mName,
                                    nameEn = doc.getString("nameEn") ?: mName,
                                    avatarEmoji = mEmojiRaw ?: mIdentity.defaultEmoji,
                                    role = mRole,
                                    familyIdentity = mIdentity,
                                    colorHex = doc.getLong("colorHex")
                                        ?: if (mRole == UserRole.PARENT) 0xFF1A73E8L else 0xFF34A853L,
                                    googleEmail = doc.getString("googleEmail") ?: doc.getString("email"),
                                    photoUrl = doc.getString("photoUrl"),
                                    schoolGrade = doc.getString("schoolGrade"),
                                    earnedPoints = mPoints,
                                    invitationPending = false,
                                    invitationAccepted = true,
                                    inviteToken = doc.getString("inviteCode") ?: doc.getString("inviteToken"),
                                    invitedByMemberId = doc.getString("invitedByMemberId")
                                )
                            }
                        }
                        if (parsedMembers.any { it.id == uid }) {
                            parsedMembers
                        } else {
                            listOf(restoredMember) + parsedMembers
                        }
                    } else {
                        val localOtherMembers = _members.value.filter { it.id != uid && !it.id.startsWith("m_") }
                        listOf(restoredMember) + localOtherMembers
                    }
                    _members.value = loadedMembers

                    val confirmedPendingInvites = if (db != null && authProvider()?.currentUser != null && validatedRole == UserRole.PARENT) {
                        val now = System.currentTimeMillis()
                        val inviteDocs = runCatching {
                            db.collection("family_invites")
                                .whereEqualTo("familyId", existingFamilyId)
                                .get()
                                .await()
                                .documents
                        }.getOrNull().orEmpty()
                        inviteDocs.mapNotNull { doc ->
                            val data = doc.data ?: return@mapNotNull null
                            parseInvitationDocument(doc.id, data).also { inv ->
                                invitationsByCode[inv.code] = inv
                            }
                        }.filter { it.status == InvitationStatus.PENDING && !it.isExpired(now) }
                            .sortedByDescending { it.createdAt }
                    } else {
                        _pendingInvitations.value.filter {
                            it.familyId == existingFamilyId && it.status == InvitationStatus.PENDING && !it.isExpired()
                        }
                    }
                    _pendingInvitations.value = confirmedPendingInvites

                    _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
                        familyId = existingFamilyId,
                        familyName = resolvedFamilyName,
                        inviteCode = confirmedPendingInvites.firstOrNull()?.code.orEmpty(),
                        ownerEmail = resolvedEmail.ifBlank { email },
                        ownerName = persistedName,
                        syncedMembersCount = loadedMembers.size,
                        lastSyncedEpochMillis = System.currentTimeMillis()
                    )

                    if (db != null && authProvider()?.currentUser != null) {
                        runCatching {
                            val taskDocs = db.collection("families")
                                .document(existingFamilyId)
                                .collection("tasks")
                                .get()
                                .await()
                                .documents
                            val loadedTasks = taskDocs.mapNotNull { doc ->
                                val data = doc.data ?: return@mapNotNull null
                                parseTaskDocument(doc.id, data, existingFamilyId)
                            }
                            _tasks.value = loadedTasks
                            val initialSyncMap = mutableMapOf<String, TaskCompletionSyncState>()
                            for (t in loadedTasks) {
                                if (t.isCompleted) {
                                    notifiedTaskCompletionKeys.add("${t.id}:completed")
                                    if (t.approvedByParent) {
                                        notifiedTaskCompletionKeys.add("${t.id}:approved")
                                        initialSyncMap[t.id] = TaskCompletionSyncState.SAVED_APPROVED
                                    } else {
                                        initialSyncMap[t.id] = TaskCompletionSyncState.PENDING_APPROVAL
                                    }
                                } else {
                                    initialSyncMap[t.id] = TaskCompletionSyncState.IDLE
                                }
                            }
                            _taskSyncStates.value = initialSyncMap
                        }
                    }

                    attachFirestoreRealtimeListeners(existingFamilyId)
                    flushOfflineSyncQueue(existingFamilyId)

                    val activeState = FamilyMembershipState.ActiveMember(
                        familyId = existingFamilyId,
                        familyName = resolvedFamilyName,
                        role = validatedRole,
                        familyIdentity = validatedIdentity,
                        member = restoredMember
                    )
                    lastClassifiedStartupFailure = null
                    _membershipState.value = activeState
                    _startupState.value = StartupState.ReadyToEnterDashboard(
                        uid = uid,
                        familyId = existingFamilyId,
                        familyName = resolvedFamilyName,
                        role = validatedRole,
                        familyIdentity = validatedIdentity
                    )
                    return@withLock activeState
                } else {
                    FirestoreErrorHandler.logStartupMilestone(
                        stage = StartupStage.VERIFY_MEMBERSHIP,
                        collectionPath = "families/$existingFamilyId/members",
                        databaseId = configuredDatabaseId,
                        note = "No active membership found for user in referenced familyId; routing to onboarding"
                    )
                }
            }

            // Genuinely no family membership in Firestore -> require onboarding ("Create" or "Join")
            lastClassifiedStartupFailure = null
            _startupState.value = StartupState.OnboardingRequired(
                uid = uid,
                email = email,
                displayName = storedUserDisplayName ?: displayName
            )
            _membershipState.value = FamilyMembershipState.NoMembership
            FamilyMembershipState.NoMembership
        } catch (e: Exception) {
            val classified = FirestoreErrorHandler.classifyStartupFailure(
                error = e,
                stage = currentStage,
                collectionPath = currentCollectionPath,
                documentId = currentDocumentId,
                databaseId = configuredDatabaseId
            )
            lastClassifiedStartupFailure = classified

            _startupState.value = StartupState.RecoverableError(
                uid = uid,
                messageHe = classified.messageHe,
                messageEn = classified.messageEn,
                errorCode = classified.errorCode,
                stage = classified.stage.stageName,
                databaseId = classified.databaseId
            )
            val recoverable = FamilyMembershipState.RecoverableError(
                messageHe = classified.messageHe,
                messageEn = classified.messageEn,
                errorCode = classified.errorCode,
                stage = classified.stage.stageName,
                databaseId = classified.databaseId
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
     * - Writes both `createdBy` and `ownerUid`, and both `name` and `familyName`, for full V1.6 Firestore rule compliance.
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
                getDocumentWithCacheFallback(
                    docRef = db.collection("users").document(uid),
                    stage = StartupStage.LOAD_USER_PROFILE,
                    collectionPath = "users",
                    documentId = uid
                )
            }.getOrNull()
            val existingFamId = existingUserSnap?.getString("familyId")?.takeIf { it.isNotBlank() }
            if (existingFamId != null) {
                val existingFamilySnap = runCatching {
                    getDocumentWithCacheFallback(
                        docRef = db.collection("families").document(existingFamId),
                        stage = StartupStage.LOAD_FAMILY_DOCUMENT,
                        collectionPath = "families",
                        documentId = existingFamId
                    )
                }.getOrNull()
                val existingMemberSnap = runCatching {
                    getDocumentWithCacheFallback(
                        docRef = db.collection("families").document(existingFamId).collection("members").document(uid),
                        stage = StartupStage.LOAD_FAMILY_MEMBER,
                        collectionPath = "families/$existingFamId/members",
                        documentId = uid
                    )
                }.getOrNull()
                val isExistingOwnerOrMember = existingMemberSnap?.exists() == true || (
                    existingFamilySnap?.exists() == true && (
                        existingFamilySnap.getString("createdBy") == uid ||
                            existingFamilySnap.getString("ownerUid") == uid
                        )
                    )
                if (isExistingOwnerOrMember) {
                    Log.i(TAG, "createFamilyAsParent guard: user already belongs to an existing family; restoring instead of overwriting.")
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
                        "id" to newFamilyId,
                        "familyId" to newFamilyId,
                        "name" to cleanFamilyName,
                        "familyName" to cleanFamilyName,
                        "createdBy" to uid,
                        "createdById" to uid,
                        "ownerUid" to uid,
                        "ownerEmail" to email,
                        "adminUids" to listOf(uid),
                        "memberIds" to listOf(uid),
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
                    "spentPoints" to 0,
                    "invitationPending" to false,
                    "invitationAccepted" to true,
                    "joinedAt" to now
                )
                if (!photoUrl.isNullOrBlank()) {
                    memberData["photoUrl"] = photoUrl
                }
                batch.set(memberRef, memberData, SetOptions.merge())
                batch.commit().await()

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
                userRef.set(userData, SetOptions.merge()).await()
            } catch (e: Exception) {
                FirestoreErrorHandler.classifyStartupFailure(
                    error = e,
                    stage = StartupStage.LOAD_FAMILY_DOCUMENT,
                    collectionPath = "families",
                    documentId = newFamilyId,
                    databaseId = configuredDatabaseId
                )
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
    /**
     * Ensures that an authenticated PARENT has a valid, persisted `/families/{familyId}` and
     * `/families/{familyId}/members/{parentUid}` (`role = "PARENT"`) in the named Firestore database
     * before creating an invitation in `/family_invites/{code}`.
     *
     * SECURITY INVARIANT:
     * - If `/families/{familyId}/members/{parentUid}` exists in Firestore and has a CHILD role,
     *   immediately throws [SecurityException] so a CHILD can NEVER create invitations.
     */
    private suspend fun ensureParentAuthorizedInFirestoreForInvite(
        db: FirebaseFirestore,
        parentUid: String,
        parentEmail: String,
        parentMember: FamilyMember
    ): Pair<String, String> {
        var resolvedFamilyId = _activeFamilyId.value.trim()
        var resolvedFamilyName = _familyName.value.trim().ifBlank { "המשפחה שלי" }

        // If activeFamilyId is blank or still on the offline demo placeholder, check /users/{parentUid} first
        if (resolvedFamilyId.isBlank() || resolvedFamilyId == "fam_israeli_8492") {
            val userSnap = runCatching {
                db.collection("users").document(parentUid).get().await()
            }.getOrNull()
            val userFamId = (
                userSnap?.getString("familyId")
                    ?: userSnap?.getString("activeFamilyId")
                    ?: userSnap?.getString("groupId")
                )?.trim()?.takeIf { it.isNotBlank() && it != "fam_israeli_8492" }
            val userFamName = userSnap?.getString("familyName")?.trim()?.takeIf { it.isNotBlank() }

            if (userFamId != null) {
                resolvedFamilyId = userFamId
                if (userFamName != null) {
                    resolvedFamilyName = userFamName
                }
                _activeFamilyId.value = resolvedFamilyId
                _familyName.value = resolvedFamilyName
            } else if (resolvedFamilyId.isBlank() || resolvedFamilyId == "fam_israeli_8492") {
                resolvedFamilyId = "fam_${parentUid.take(8)}_${System.currentTimeMillis() % 10000}"
                _activeFamilyId.value = resolvedFamilyId
            }
        }

        val familyRef = db.collection("families").document(resolvedFamilyId)
        val memberRef = familyRef.collection("members").document(parentUid)

        // Check existing member document in Firestore
        val memberSnap: DocumentSnapshot? = try {
            memberRef.get().await()
        } catch (memberErr: FirebaseFirestoreException) {
            if (memberErr.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                memberErr.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
            ) {
                throw memberErr
            }
            null
        }

        if (memberSnap != null && memberSnap.exists()) {
            val rawRole = (memberSnap.getString("role") ?: memberSnap.getString("userRole"))?.trim()
            if (!rawRole.isNullOrBlank() && !UserRole.fromFirestoreValue(rawRole).isParentRole) {
                throw SecurityException("רק הורה (מנהל משפחה) רשאי ליצור קודי הזמנה למשפחה.")
            }
            // If legacy parent role (e.g. PRIMARY_ADMIN / ADMIN / FATHER / MOTHER), normalize to canonical "PARENT"
            if (rawRole != "PARENT") {
                val parentIdentity = if (parentMember.familyIdentity.isParentIdentity) {
                    parentMember.familyIdentity
                } else {
                    FamilyIdentity.FATHER
                }
                memberRef.set(
                    mapOf(
                        "role" to UserRole.PARENT.toFirestoreValue(),
                        "familyIdentity" to parentIdentity.toFirestoreValue()
                    ),
                    SetOptions.merge()
                ).await()
            }
            return resolvedFamilyId to resolvedFamilyName
        }

        // Member doc does not exist yet in Firestore; inspect /families/{familyId}
        val familySnap = familyRef.get().await()
        if (familySnap.exists()) {
            val remoteName = (familySnap.getString("familyName") ?: familySnap.getString("name"))
                ?.trim()?.takeIf { it.isNotBlank() }
            if (remoteName != null) {
                resolvedFamilyName = remoteName
                _familyName.value = remoteName
            }
            val isCreatorOrAdmin = familySnap.getString("createdBy") == parentUid ||
                familySnap.getString("createdById") == parentUid ||
                familySnap.getString("ownerUid") == parentUid ||
                (familySnap.get("adminUids") as? List<*>)?.contains(parentUid) == true

            if (!isCreatorOrAdmin) {
                throw SecurityException("החשבון המחובר אינו רשום כהורה במשפחה זו בענן.")
            }

            // Self-heal missing /families/{familyId}/members/{parentUid} document for verified family creator/admin
            val parentIdentity = if (parentMember.familyIdentity.isParentIdentity) {
                parentMember.familyIdentity
            } else {
                FamilyIdentity.FATHER
            }
            memberRef.set(
                mapOf(
                    "id" to parentUid,
                    "uid" to parentUid,
                    "familyId" to resolvedFamilyId,
                    "name" to parentMember.name.ifBlank { parentIdentity.labelHe },
                    "nameEn" to parentMember.nameEn.ifBlank { parentMember.name.ifBlank { parentIdentity.labelEn } },
                    "avatarEmoji" to parentMember.avatarEmoji.ifBlank { parentIdentity.defaultEmoji },
                    "role" to UserRole.PARENT.toFirestoreValue(),
                    "familyIdentity" to parentIdentity.toFirestoreValue(),
                    "googleEmail" to parentEmail,
                    "earnedPoints" to parentMember.earnedPoints,
                    "spentPoints" to 0,
                    "invitationPending" to false,
                    "invitationAccepted" to true
                ),
                SetOptions.merge()
            ).await()
        } else {
            // Family document was not yet persisted to Firestore (e.g. created in local/clean session); bootstrap atomically
            val now = System.currentTimeMillis()
            val parentIdentity = if (parentMember.familyIdentity.isParentIdentity) {
                parentMember.familyIdentity
            } else {
                FamilyIdentity.FATHER
            }
            val batch = db.batch()
            batch.set(
                familyRef,
                mapOf(
                    "id" to resolvedFamilyId,
                    "familyId" to resolvedFamilyId,
                    "name" to resolvedFamilyName,
                    "familyName" to resolvedFamilyName,
                    "createdBy" to parentUid,
                    "createdById" to parentUid,
                    "ownerUid" to parentUid,
                    "ownerEmail" to parentEmail,
                    "adminUids" to listOf(parentUid),
                    "memberIds" to listOf(parentUid),
                    "memberUids" to listOf(parentUid),
                    "updatedAt" to now
                ),
                SetOptions.merge()
            )
            batch.set(
                memberRef,
                mapOf(
                    "id" to parentUid,
                    "uid" to parentUid,
                    "familyId" to resolvedFamilyId,
                    "name" to parentMember.name.ifBlank { parentIdentity.labelHe },
                    "nameEn" to parentMember.nameEn.ifBlank { parentMember.name.ifBlank { parentIdentity.labelEn } },
                    "avatarEmoji" to parentMember.avatarEmoji.ifBlank { parentIdentity.defaultEmoji },
                    "role" to UserRole.PARENT.toFirestoreValue(),
                    "familyIdentity" to parentIdentity.toFirestoreValue(),
                    "googleEmail" to parentEmail,
                    "earnedPoints" to parentMember.earnedPoints,
                    "spentPoints" to 0,
                    "invitationPending" to false,
                    "invitationAccepted" to true,
                    "joinedAt" to now
                ),
                SetOptions.merge()
            )
            batch.commit().await()

            db.collection("users").document(parentUid).set(
                mapOf(
                    "uid" to parentUid,
                    "email" to parentEmail,
                    "displayName" to parentMember.name.ifBlank { parentIdentity.labelHe },
                    "name" to parentMember.name.ifBlank { parentIdentity.labelHe },
                    "familyId" to resolvedFamilyId,
                    "familyName" to resolvedFamilyName,
                    "role" to UserRole.PARENT.toFirestoreValue(),
                    "familyIdentity" to parentIdentity.toFirestoreValue(),
                    "avatarEmoji" to parentMember.avatarEmoji.ifBlank { parentIdentity.defaultEmoji },
                    "updatedAt" to now
                ),
                SetOptions.merge()
            ).await()
        }

        return resolvedFamilyId to resolvedFamilyName
    }

    /**
     * V1.6 Requirement 4:
     * Generates and persists a unique, role-specific invitation in `/family_invites/{code}`
     * in the named Firestore database (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`).
     * - Strictly enforces that ONLY a PARENT can create invitations (both locally and in Firestore).
     * - Writes document ID = `code` (6-character uppercase alphanumeric) with all required V1.6 fields.
     * - Confirms the Firestore document commit before adding the invitation to `_pendingInvitations`
     *   or returning success to the UI, so a failed write NEVER produces a shareable code.
     */
    suspend fun createRoleSpecificInvitation(
        invitedRole: UserRole,
        invitedNickname: String = "",
        customExpirationMs: Long = FamilyInvitation.DEFAULT_EXPIRATION_MS,
        invitedEmail: String? = null,
        intendedEmail: String? = null,
        schoolGrade: String? = null,
        customCodeOverride: String? = null
    ): Result<FamilyInvitation> {
        val current = _currentMember.value
        val code = customCodeOverride?.trim()?.uppercase()?.takeIf { it.length >= 4 }
            ?: generateSecureInviteCode()
        val invitePath = "family_invites/$code"

        if (!current.isParent) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = SecurityException("רק הורה (מנהל משפחה) רשאי ליצור קודי הזמנה למשפחה."),
                stage = FirestoreErrorHandler.InvitationStage.VERIFY_PARENT_AUTHORIZATION,
                path = invitePath,
                rawInviteCode = code,
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.UNAUTHORIZED_ROLE
            )
            lastClassifiedInvitationFailure = classified
            return Result.failure(FirestoreErrorHandler.InvitationFirestoreException(classified))
        }

        if (simulateOfflineNetworkFailure) {
            val offlineErr = FirebaseFirestoreException(
                "Simulated offline network failure during invitation creation",
                FirebaseFirestoreException.Code.UNAVAILABLE
            )
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = offlineErr,
                stage = FirestoreErrorHandler.InvitationStage.CREATE_INVITATION_WRITE,
                path = invitePath,
                rawInviteCode = code,
                databaseId = configuredDatabaseId
            )
            lastClassifiedInvitationFailure = classified
            return Result.failure(FirestoreErrorHandler.InvitationFirestoreException(classified, offlineErr))
        }

        val canonicalRole = if (invitedRole.isParentRole) UserRole.PARENT else UserRole.CHILD
        val now = System.currentTimeMillis()
        val expiresAt = now + customExpirationMs
        var familyId = _activeFamilyId.value
        var familyName = _familyName.value
        val parentUid = currentUid()
        val parentEmail = authProvider()?.currentUser?.email ?: _googleUserEmail.value

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        var currentInviteStage = FirestoreErrorHandler.InvitationStage.VERIFY_PARENT_AUTHORIZATION

        if (db != null && authUser != null) {
            try {
                FirestoreErrorHandler.logInvitationMilestone(
                    stage = FirestoreErrorHandler.InvitationStage.VERIFY_PARENT_AUTHORIZATION,
                    path = "families/$familyId/members/$parentUid",
                    databaseId = configuredDatabaseId,
                    rawInviteCode = code,
                    note = "Verifying parent authorization before creating invitation"
                )
                val (verifiedFamilyId, verifiedFamilyName) = ensureParentAuthorizedInFirestoreForInvite(
                    db = db,
                    parentUid = authUser.uid,
                    parentEmail = parentEmail,
                    parentMember = current
                )
                familyId = verifiedFamilyId
                familyName = verifiedFamilyName

                currentInviteStage = FirestoreErrorHandler.InvitationStage.CREATE_INVITATION_WRITE
                FirestoreErrorHandler.logInvitationMilestone(
                    stage = FirestoreErrorHandler.InvitationStage.CREATE_INVITATION_WRITE,
                    path = invitePath,
                    databaseId = configuredDatabaseId,
                    rawInviteCode = code,
                    note = "Writing role=${canonicalRole.toFirestoreValue()} invitation to family_invites"
                )

                val inviteMap = mutableMapOf<String, Any>(
                    "code" to code,
                    "inviteCode" to code,
                    "familyId" to familyId,
                    "familyName" to familyName,
                    "role" to canonicalRole.toFirestoreValue(),
                    "invitedRole" to canonicalRole.toFirestoreValue(), // "PARENT" or "CHILD"
                    "invitingParentUid" to authUser.uid,
                    "createdById" to authUser.uid,
                    "createdByUid" to authUser.uid,
                    "invitingParentName" to current.name,
                    "createdByName" to current.name,
                    "invitedNickname" to invitedNickname.trim(),
                    "invitedName" to invitedNickname.trim(),
                    "createdAt" to now,
                    "expiresAt" to expiresAt,
                    "expiresAtMillis" to expiresAt,
                    "status" to InvitationStatus.PENDING.name,
                    "isUsed" to false,
                    "isRevoked" to false,
                    "revoked" to false
                )
                val effectiveEmail = intendedEmail?.takeIf { it.isNotBlank() } ?: invitedEmail?.takeIf { it.isNotBlank() }
                if (!effectiveEmail.isNullOrBlank()) {
                    inviteMap["intendedEmail"] = effectiveEmail.trim()
                }
                if (!schoolGrade.isNullOrBlank()) {
                    inviteMap["schoolGrade"] = schoolGrade.trim()
                }

                val inviteDocRef = db.collection("family_invites").document(code)
                inviteDocRef.set(inviteMap).await()

                currentInviteStage = FirestoreErrorHandler.InvitationStage.CONFIRM_INVITATION_COMMIT
                val verifySnap = inviteDocRef.get().await()
                if (!verifySnap.exists()) {
                    throw FirebaseFirestoreException(
                        "Invitation document was not found in family_invites after write",
                        FirebaseFirestoreException.Code.NOT_FOUND
                    )
                }

                FirestoreErrorHandler.logInvitationMilestone(
                    stage = FirestoreErrorHandler.InvitationStage.CONFIRM_INVITATION_COMMIT,
                    path = invitePath,
                    databaseId = configuredDatabaseId,
                    rawInviteCode = code,
                    note = "Confirmed invitation document committed in family_invites"
                )
            } catch (e: Exception) {
                val classified = FirestoreErrorHandler.classifyInvitationFailure(
                    error = e,
                    stage = currentInviteStage,
                    path = invitePath,
                    rawInviteCode = code,
                    databaseId = configuredDatabaseId
                )
                lastClassifiedInvitationFailure = classified
                invitationsByCode.remove(code)
                _pendingInvitations.value = _pendingInvitations.value.filterNot { it.code == code }
                return Result.failure(FirestoreErrorHandler.InvitationFirestoreException(classified, e))
            }
        }

        val invitation = FamilyInvitation(
            code = code,
            familyId = familyId,
            familyName = familyName,
            invitedRole = canonicalRole,
            invitingParentUid = authUser?.uid ?: parentUid,
            invitingParentName = current.name,
            invitedNickname = invitedNickname.trim(),
            createdAt = now,
            expiresAt = expiresAt,
            status = InvitationStatus.PENDING
        )

        lastClassifiedInvitationFailure = null
        invitationsByCode[code] = invitation
        _pendingInvitations.value = (_pendingInvitations.value.filterNot { it.code == code } + invitation)
            .filter { it.status == InvitationStatus.PENDING && !it.isExpired(now) }
            .sortedByDescending { it.createdAt }
        _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
            familyId = familyId,
            familyName = familyName,
            inviteCode = code,
            lastSyncedEpochMillis = now
        )

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
     * Checks existence in `/family_invites/{code}` on the named Firestore database,
     * status (PENDING vs REDEEMED / REVOKED), expiration, and duplicate membership.
     */
    suspend fun validateInvitationCode(
        rawCode: String,
        joiningUid: String = currentUid(),
        nowMillis: Long = System.currentTimeMillis()
    ): InvitationValidationResult {
        val normalized = rawCode.trim().uppercase()
        val invitePath = "family_invites/$normalized"

        if (normalized.length < 4) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = IllegalArgumentException("Invalid invitation code format"),
                stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                path = invitePath,
                rawInviteCode = normalized,
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.INVALID_FORMAT
            )
            lastClassifiedInvitationFailure = classified
            return InvitationValidationResult.Invalid(
                errorCode = "INVALID_FORMAT",
                messageHe = classified.messageHe,
                messageEn = classified.messageEn
            )
        }

        if (simulateOfflineNetworkFailure) {
            val offlineErr = FirebaseFirestoreException(
                "Simulated offline network failure during invitation validation",
                FirebaseFirestoreException.Code.UNAVAILABLE
            )
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = offlineErr,
                stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                path = invitePath,
                rawInviteCode = normalized,
                databaseId = configuredDatabaseId
            )
            lastClassifiedInvitationFailure = classified
            return InvitationValidationResult.Invalid(
                errorCode = "UNAVAILABLE",
                messageHe = classified.messageHe,
                messageEn = classified.messageEn
            )
        }

        var invitation: FamilyInvitation? = null
        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            FirestoreErrorHandler.logInvitationMilestone(
                stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                path = invitePath,
                databaseId = configuredDatabaseId,
                rawInviteCode = normalized,
                note = "Validating invitation document in family_invites"
            )
            try {
                val snap = db.collection("family_invites").document(normalized).get().await()
                if (snap.exists()) {
                    var parsed = parseInvitationDocument(normalized, snap.data ?: emptyMap())
                    if (parsed.familyName.isBlank() && parsed.familyId.isNotBlank()) {
                        val famSnap = runCatching {
                            db.collection("families").document(parsed.familyId).get().await()
                        }.getOrNull()
                        val resolvedFamName = (famSnap?.getString("familyName") ?: famSnap?.getString("name"))
                            ?.takeIf { it.isNotBlank() }
                        if (resolvedFamName != null) {
                            parsed = parsed.copy(familyName = resolvedFamName)
                        }
                    }
                    invitation = parsed
                    invitationsByCode[normalized] = parsed
                }
            } catch (e: Exception) {
                val (rawCat, _) = com.example.util.classifyFirestoreException(e)
                if (rawCat == com.example.util.FirestoreFailureCategory.UNAVAILABLE) {
                    val classified = FirestoreErrorHandler.classifyInvitationFailure(
                        error = e,
                        stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                        path = invitePath,
                        rawInviteCode = normalized,
                        databaseId = configuredDatabaseId,
                        overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.UNAVAILABLE
                    )
                    lastClassifiedInvitationFailure = classified
                    return InvitationValidationResult.Invalid(
                        errorCode = "UNAVAILABLE",
                        messageHe = classified.messageHe,
                        messageEn = classified.messageEn
                    )
                }
                if (rawCat == com.example.util.FirestoreFailureCategory.PERMISSION_DENIED) {
                    // In V1.6 security rules, resource == null is allowed for all signed-in users,
                    // whereas an already-used or revoked invitation denies get() to non-parents.
                    val cached = invitationsByCode[normalized]
                    if (cached?.status == InvitationStatus.REVOKED) {
                        val classified = FirestoreErrorHandler.classifyInvitationFailure(
                            error = e,
                            stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                            path = invitePath,
                            rawInviteCode = normalized,
                            databaseId = configuredDatabaseId,
                            overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.REVOKED
                        )
                        lastClassifiedInvitationFailure = classified
                        return InvitationValidationResult.Invalid(
                            errorCode = "REVOKED",
                            messageHe = classified.messageHe,
                            messageEn = classified.messageEn
                        )
                    }
                    val classified = FirestoreErrorHandler.classifyInvitationFailure(
                        error = e,
                        stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                        path = invitePath,
                        rawInviteCode = normalized,
                        databaseId = configuredDatabaseId,
                        overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.ALREADY_REDEEMED
                    )
                    lastClassifiedInvitationFailure = classified
                    return InvitationValidationResult.Invalid(
                        errorCode = "ALREADY_REDEEMED",
                        messageHe = "קוד הזמנה זה כבר נוצל או בוטל ולא ניתן להשתמש בו שוב.",
                        messageEn = "This invitation code has already been redeemed or revoked."
                    )
                }
                FirestoreErrorHandler.classifyInvitationFailure(
                    error = e,
                    stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                    path = invitePath,
                    rawInviteCode = normalized,
                    databaseId = configuredDatabaseId
                )
            }
        }

        if (invitation == null) {
            invitation = invitationsByCode[normalized]
        }

        if (invitation == null) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = NoSuchElementException("Invitation code not found in family_invites"),
                stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                path = invitePath,
                rawInviteCode = normalized,
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.NOT_FOUND
            )
            lastClassifiedInvitationFailure = classified
            return InvitationValidationResult.Invalid(
                errorCode = "NOT_FOUND",
                messageHe = "קוד ההזמנה \"$normalized\" אינו קיים במערכת. ודאו שהקלדתם נכון.",
                messageEn = "Invitation code \"$normalized\" was not found."
            )
        }

        if (invitation.status == InvitationStatus.REDEEMED) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = IllegalStateException("Invitation already redeemed"),
                stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                path = invitePath,
                rawInviteCode = normalized,
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.ALREADY_REDEEMED
            )
            lastClassifiedInvitationFailure = classified
            return InvitationValidationResult.Invalid(
                errorCode = "ALREADY_REDEEMED",
                messageHe = "קוד הזמנה זה כבר נוצל ולא ניתן להשתמש בו שוב.",
                messageEn = "This invitation code has already been redeemed."
            )
        }

        if (invitation.status == InvitationStatus.REVOKED) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = IllegalStateException("Invitation revoked"),
                stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                path = invitePath,
                rawInviteCode = normalized,
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.REVOKED
            )
            lastClassifiedInvitationFailure = classified
            return InvitationValidationResult.Invalid(
                errorCode = "REVOKED",
                messageHe = "קוד הזמנה זה בוטל על ידי הורה במשפחה.",
                messageEn = "This invitation code was revoked by a parent."
            )
        }

        if (invitation.isExpired(nowMillis)) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = IllegalStateException("Invitation expired"),
                stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                path = invitePath,
                rawInviteCode = normalized,
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.EXPIRED
            )
            lastClassifiedInvitationFailure = classified
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
                val classified = FirestoreErrorHandler.classifyInvitationFailure(
                    error = IllegalStateException("User is already a member of target family"),
                    stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
                    path = "families/${invitation.familyId}/members/$joiningUid",
                    rawInviteCode = normalized,
                    databaseId = configuredDatabaseId,
                    overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.ALREADY_MEMBER
                )
                lastClassifiedInvitationFailure = classified
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

        lastClassifiedInvitationFailure = null
        FirestoreErrorHandler.logInvitationMilestone(
            stage = FirestoreErrorHandler.InvitationStage.VALIDATE_INVITATION_READ,
            path = invitePath,
            databaseId = configuredDatabaseId,
            rawInviteCode = normalized,
            note = "Invitation code validated successfully for role=${invitation.invitedRole.toFirestoreValue()}"
        )
        return InvitationValidationResult.Valid(invitation)
    }

    /**
     * V1.6 Requirement 5:
     * Redeems a validated invitation code and joins the existing family with the exact immutable
     * role specified by the invitation (`PARENT` or `CHILD`).
     * - Marks the invitation as `REDEEMED` (`isUsed = true`) so it cannot be reused
     * - Creates the member document in `/families/{familyId}/members/{uid}` with `role = invitation.invitedRole`
     * - Updates `/users/{uid}` and `/families/{familyId}`
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
        val invitePath = "family_invites/${invite.code}"
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
            FirestoreErrorHandler.logInvitationMilestone(
                stage = FirestoreErrorHandler.InvitationStage.REDEEM_INVITATION_TRANSACTION,
                path = invitePath,
                databaseId = configuredDatabaseId,
                rawInviteCode = invite.code,
                note = "Starting atomic invitation redemption transaction"
            )
            try {
                val inviteRef = db.collection("family_invites").document(invite.code)
                val familyRef = db.collection("families").document(invite.familyId)
                val memberRef = familyRef.collection("members").document(uid)
                val userRef = db.collection("users").document(uid)

                db.runTransaction { transaction ->
                    // All reads MUST precede writes in a Firestore transaction
                    val freshInviteSnap = transaction.get(inviteRef)
                    val familySnap = transaction.get(familyRef)

                    if (!freshInviteSnap.exists()) {
                        throw IllegalStateException("קוד ההזמנה אינו קיים.")
                    }
                    val freshStatus = InvitationStatus.fromFirestoreValue(freshInviteSnap.getString("status"))
                    val isAlreadyUsed = freshInviteSnap.getBoolean("isUsed") ?: false
                    val isRevoked = (freshInviteSnap.getBoolean("isRevoked") ?: false) ||
                        (freshInviteSnap.getBoolean("revoked") ?: false)
                    val freshExpiresAt = freshInviteSnap.getLong("expiresAt")
                        ?: freshInviteSnap.getLong("expiresAtMillis")
                        ?: 0L
                    if (freshStatus != InvitationStatus.PENDING || isAlreadyUsed || isRevoked) {
                        throw IllegalStateException("קוד הזמנה זה כבר נוצל או בוטל.")
                    }
                    if (freshExpiresAt > 0L && nowMillis > freshExpiresAt) {
                        throw IllegalStateException("פג תוקפו של קוד ההזמנה.")
                    }

                    val lockedRoleRaw = freshInviteSnap.getString("invitedRole")
                        ?: freshInviteSnap.getString("role")
                    val lockedRole = UserRole.fromFirestoreValue(lockedRoleRaw)
                    val roleValue = lockedRole.toFirestoreValue() // "PARENT" or "CHILD"
                    val txIdentity = if (lockedRole == UserRole.PARENT) {
                        if (resolvedIdentity.isParentIdentity) resolvedIdentity else FamilyIdentity.MOTHER
                    } else {
                        if (!resolvedIdentity.isParentIdentity) resolvedIdentity else FamilyIdentity.CHILD
                    }

                    // 1. Mark invitation as REDEEMED (with isUsed = true for V1.6 security rules)
                    transaction.update(
                        inviteRef,
                        mapOf(
                            "status" to InvitationStatus.REDEEMED.name,
                            "isUsed" to true,
                            "redeemedByUid" to uid,
                            "usedByUid" to uid,
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
                        "spentPoints" to 0,
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

                    // 3. Atomically append joining user's UID to /families/{familyId} memberIds & memberUids
                    if (familySnap.exists()) {
                        val familyUpdates = mutableMapOf<String, Any>(
                            "memberIds" to FieldValue.arrayUnion(uid),
                            "memberUids" to FieldValue.arrayUnion(uid),
                            "updatedAt" to nowMillis
                        )
                        if (lockedRole == UserRole.PARENT) {
                            familyUpdates["adminUids"] = FieldValue.arrayUnion(uid)
                        }
                        transaction.update(familyRef, familyUpdates)
                    }
                    Pair(roleValue, txIdentity)
                }.await().let { (committedRoleValue, committedIdentity) ->
                    // 4. Save user's active familyId, validated role, and familyIdentity now that memberRef is committed
                    val userDocData = mutableMapOf<String, Any>(
                        "uid" to uid,
                        "email" to email,
                        "displayName" to cleanName,
                        "name" to cleanName,
                        "familyId" to invite.familyId,
                        "familyName" to invite.familyName,
                        "role" to committedRoleValue,
                        "familyIdentity" to committedIdentity.toFirestoreValue(),
                        "avatarEmoji" to committedIdentity.defaultEmoji,
                        "updatedAt" to nowMillis
                    )
                    if (!photoUrl.isNullOrBlank()) {
                        userDocData["photoUrl"] = photoUrl
                    }
                    userRef.set(userDocData, SetOptions.merge()).await()
                }

                FirestoreErrorHandler.logInvitationMilestone(
                    stage = FirestoreErrorHandler.InvitationStage.REDEEM_INVITATION_TRANSACTION,
                    path = "families/${invite.familyId}/members/$uid",
                    databaseId = configuredDatabaseId,
                    rawInviteCode = invite.code,
                    note = "Completed atomic invitation redemption and joined family as ${assignedRole.toFirestoreValue()}"
                )
            } catch (e: Exception) {
                val classified = FirestoreErrorHandler.classifyInvitationFailure(
                    error = e,
                    stage = FirestoreErrorHandler.InvitationStage.REDEEM_INVITATION_TRANSACTION,
                    path = invitePath,
                    rawInviteCode = invite.code,
                    databaseId = configuredDatabaseId
                )
                lastClassifiedInvitationFailure = classified
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
                try {
                    db.collection("family_invites")
                        .document(normalized)
                        .update(
                            mapOf(
                                "status" to InvitationStatus.REVOKED.name,
                                "isRevoked" to true,
                                "revoked" to true
                            )
                        )
                        .await()
                    FirestoreErrorHandler.logInvitationMilestone(
                        stage = FirestoreErrorHandler.InvitationStage.REVOKE_INVITATION_WRITE,
                        path = "family_invites/$normalized",
                        databaseId = configuredDatabaseId,
                        rawInviteCode = normalized,
                        note = "Invitation revoked in family_invites"
                    )
                } catch (e: Exception) {
                    FirestoreErrorHandler.classifyInvitationFailure(
                        error = e,
                        stage = FirestoreErrorHandler.InvitationStage.REVOKE_INVITATION_WRITE,
                        path = "family_invites/$normalized",
                        rawInviteCode = normalized,
                        databaseId = configuredDatabaseId
                    )
                }
            }
        }
        return true
    }

    private fun extractTimestampMillis(rawValue: Any?): Long? {
        return when (rawValue) {
            is Number -> rawValue.toLong()
            is com.google.firebase.Timestamp -> rawValue.toDate().time
            is Date -> rawValue.time
            else -> null
        }
    }

    private fun parseInvitationDocument(docId: String, data: Map<String, Any?>): FamilyInvitation {
        val rawCode = (data["code"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: (data["inviteCode"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: docId.trim().uppercase()
        val familyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() } ?: _activeFamilyId.value
        val familyName = (data["familyName"] as? String) ?: ""
        val roleRaw = (data["invitedRole"] as? String) ?: (data["role"] as? String)
        val invitedRole = UserRole.fromFirestoreValue(roleRaw)
        val invitingParentUid = (data["invitingParentUid"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["createdById"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["createdByUid"] as? String)?.takeIf { it.isNotBlank() }
            ?: ""
        val invitingParentName = (data["invitingParentName"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["createdByName"] as? String)
            ?: ""
        val invitedNickname = (data["invitedNickname"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["invitedName"] as? String)
            ?: ""
        val createdAt = extractTimestampMillis(data["createdAt"]) ?: System.currentTimeMillis()
        val expiresAt = extractTimestampMillis(data["expiresAt"])
            ?: extractTimestampMillis(data["expiresAtMillis"])
            ?: (createdAt + FamilyInvitation.DEFAULT_EXPIRATION_MS)
        val isUsed = (data["isUsed"] as? Boolean) ?: false
        val isRevoked = (data["isRevoked"] as? Boolean) ?: (data["revoked"] as? Boolean) ?: false
        val status = when {
            isRevoked -> InvitationStatus.REVOKED
            isUsed -> InvitationStatus.REDEEMED
            else -> InvitationStatus.fromFirestoreValue(data["status"] as? String)
        }
        val redeemedByUid = (data["redeemedByUid"] as? String) ?: (data["usedByUid"] as? String)
        val redeemedByName = data["redeemedByName"] as? String
        val redeemedAt = extractTimestampMillis(data["redeemedAt"])

        return FamilyInvitation(
            code = rawCode,
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
     * Parses a Firestore task document from `/families/{familyId}/tasks/{taskId}` (or legacy `/tasks/{taskId}`)
     * into a canonical [FamilyTask], supporting both V1.6 and legacy V1.5 field names and preserving
     * `isCompleted`, `approvedByParent`, `completedAtMillis`, and `completedByMemberId`.
     */
    private fun parseTaskDocument(
        docId: String,
        data: Map<String, Any?>,
        fallbackFamilyId: String
    ): FamilyTask? {
        val title = (data["title"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["task_name"] as? String)?.takeIf { it.isNotBlank() }
            ?: return null
        val description = (data["description"] as? String) ?: ""
        val rawAssigned = (data["assignedUid"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assignedToMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assignedMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assigned_member_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assigned_family_member"] as? String)?.takeIf { it.isNotBlank() }
            ?: "m_kid1"

        val resolvedAssignedId = _members.value.find { m ->
            m.id == rawAssigned ||
                m.name.equals(rawAssigned, ignoreCase = true) ||
                (!m.googleEmail.isNullOrBlank() && m.googleEmail.equals(rawAssigned, ignoreCase = true))
        }?.id ?: rawAssigned

        val points = ((data["points"] as? Number)?.toInt())
            ?: ((data["pointsValue"] as? Number)?.toInt())
            ?: 10
        val completed = (data["isCompleted"] as? Boolean)
            ?: (data["is_completed"] as? Boolean)
            ?: false
        val approvedByParent = (data["approvedByParent"] as? Boolean)
            ?: (data["approved_by_parent"] as? Boolean)
            ?: false
        val completedAtMillis = extractTimestampMillis(data["completedAtMillis"])
            ?: extractTimestampMillis(data["completed_at_millis"])
        val completedByMemberId = (data["completedByMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["completed_by_member_id"] as? String)?.takeIf { it.isNotBlank() }
        val epochDay = ((data["dateEpochDay"] as? Number)?.toLong())
            ?: ((data["due_date_epoch_day"] as? Number)?.toLong()?.takeIf { it != 0L })
            ?: (((data["dueDate"] as? Number)?.toLong() ?: (todayEpoch * 86_400_000L)) / 86_400_000L)
        val categoryStr = (data["category"] as? String) ?: TaskCategory.OTHER.name
        val priorityStr = (data["priority"] as? String) ?: TaskPriority.MEDIUM.name
        val recurrenceStr = (data["recurrence"] as? String) ?: TaskRecurrence.ONCE.name
        val dueTime = (data["dueTimeFormatted"] as? String)
            ?: (data["due_time"] as? String)
            ?: "17:00"
        val resolvedFamilyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["family_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: fallbackFamilyId
        val updatedAtMillis = extractTimestampMillis(data["updatedAt"])
            ?: extractTimestampMillis(data["updated_at"])
            ?: System.currentTimeMillis()

        return FamilyTask(
            id = docId,
            familyId = resolvedFamilyId,
            title = title,
            description = description,
            category = runCatching { TaskCategory.valueOf(categoryStr) }.getOrDefault(TaskCategory.OTHER),
            assignedToMemberId = resolvedAssignedId,
            dateEpochDay = epochDay,
            dueTimeFormatted = dueTime,
            isCompleted = completed,
            priority = runCatching { TaskPriority.valueOf(priorityStr) }.getOrDefault(TaskPriority.MEDIUM),
            recurrence = runCatching { TaskRecurrence.valueOf(recurrenceStr) }.getOrDefault(TaskRecurrence.ONCE),
            points = points,
            approvedByParent = approvedByParent,
            completedAtMillis = completedAtMillis,
            completedByMemberId = completedByMemberId,
            updatedAtMillis = updatedAtMillis
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

        // Synchronize the authenticated user's FCM token for this device in the named database
        FcmTokenManager.syncCurrentDeviceTokenAsync(
            context = appContext,
            dbOverride = db,
            uidOverride = authProvider()?.currentUser?.uid,
            familyId = familyId,
            role = _currentMember.value.role
        )

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
            var hasProcessedInitialTasksSnapshot = false
            tasksListenerRegistration = familyRef.collection("tasks")
                .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val currentSyncStates = _taskSyncStates.value
                    val previousTasksById = _tasks.value.associateBy { it.id }
                    val pendingWriteTaskIds = mutableSetOf<String>()

                    val remoteTasks = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        if (doc.metadata.hasPendingWrites()) {
                            pendingWriteTaskIds.add(doc.id)
                        }
                        val parsed = parseTaskDocument(doc.id, data, familyId) ?: return@mapNotNull null
                        // If a local completion/approval write is currently in flight (SAVING),
                        // or if a local write has not yet been committed by the server,
                        // preserve the in-flight state until the server confirms or rejects the write.
                        if (currentSyncStates[doc.id] == TaskCompletionSyncState.SAVING || doc.metadata.hasPendingWrites()) {
                            previousTasksById[doc.id] ?: parsed
                        } else {
                            parsed
                        }
                    }

                    if (remoteTasks.isNotEmpty() || _isGoogleSignedIn.value) {
                        val updatedSyncStates = currentSyncStates.toMutableMap()
                        val selfUid = currentUid()
                        for (remoteTask in remoteTasks) {
                            val prev = previousTasksById[remoteTask.id]
                            val isPendingServerCommit = remoteTask.id in pendingWriteTaskIds
                            if (currentSyncStates[remoteTask.id] != TaskCompletionSyncState.SAVING && !isPendingServerCommit) {
                                updatedSyncStates[remoteTask.id] = when {
                                    remoteTask.isCompleted && remoteTask.approvedByParent ->
                                        TaskCompletionSyncState.SAVED_APPROVED
                                    remoteTask.isCompleted && !remoteTask.approvedByParent ->
                                        TaskCompletionSyncState.PENDING_APPROVAL
                                    currentSyncStates[remoteTask.id] == TaskCompletionSyncState.FAILED ->
                                        TaskCompletionSyncState.FAILED
                                    else ->
                                        TaskCompletionSyncState.IDLE
                                }
                            }

                            if (!hasProcessedInitialTasksSnapshot) {
                                if (!isPendingServerCommit) {
                                    if (remoteTask.assignedToMemberId.isNotBlank()) {
                                        notifiedTaskCompletionKeys.add("assign:${remoteTask.id}:${remoteTask.assignedToMemberId}")
                                    }
                                    if (remoteTask.isCompleted) {
                                        notifiedTaskCompletionKeys.add("${remoteTask.id}:completed")
                                        if (remoteTask.approvedByParent) {
                                            notifiedTaskCompletionKeys.add("${remoteTask.id}:approved")
                                        }
                                    }
                                }
                            } else if (!isPendingServerCommit) {
                                val assignee = _members.value.find { it.id == remoteTask.assignedToMemberId }
                                val assigneeName = assignee?.name ?: "חבר משפחה"
                                val assigneeEmoji = assignee?.avatarEmoji ?: "🎉"

                                // Event A: Parent assigned a new task to this user
                                if (prev == null && remoteTask.assignedToMemberId.isNotBlank() && remoteTask.assignedToMemberId == selfUid) {
                                    val assignKey = "assign:${remoteTask.id}:$selfUid"
                                    if (notifiedTaskCompletionKeys.add(assignKey) && FcmTokenManager.markEventDispatchedIfNew(assignKey)) {
                                        val title = "משימה חדשה שובצה עבורך 📋"
                                        val body = "שובצה לך משימה חדשה: \"${remoteTask.title}\" (שעה ${remoteTask.dueTimeFormatted} • +${remoteTask.points} נק')"
                                        pushAlert(
                                            title = title,
                                            message = body,
                                            targetName = assigneeName,
                                            targetEmoji = assigneeEmoji,
                                            reason = AlertReason.TASK_REMINDER,
                                            showBanner = true
                                        )
                                        appContext?.let { ctx ->
                                            NotificationHelper.showIncomingFcmNotification(
                                                context = ctx,
                                                title = title,
                                                message = body,
                                                eventKey = assignKey,
                                                taskId = remoteTask.id
                                            )
                                        }
                                    }
                                }

                                // Event D: Task was reassigned to this user
                                if (prev != null && prev.assignedToMemberId != remoteTask.assignedToMemberId && remoteTask.assignedToMemberId == selfUid) {
                                    val reassignKey = "reassign:${remoteTask.id}:$selfUid:${remoteTask.updatedAtMillis}"
                                    if (notifiedTaskCompletionKeys.add(reassignKey) && FcmTokenManager.markEventDispatchedIfNew(reassignKey)) {
                                        val title = "משימה הועברה אליך 🔄"
                                        val body = "המשימה \"${remoteTask.title}\" שובצה אליך (שעה ${remoteTask.dueTimeFormatted} • +${remoteTask.points} נק')"
                                        pushAlert(
                                            title = title,
                                            message = body,
                                            targetName = assigneeName,
                                            targetEmoji = assigneeEmoji,
                                            reason = AlertReason.TASK_REMINDER,
                                            showBanner = true
                                        )
                                        appContext?.let { ctx ->
                                            NotificationHelper.showIncomingFcmNotification(
                                                context = ctx,
                                                title = title,
                                                message = body,
                                                eventKey = reassignKey,
                                                taskId = remoteTask.id
                                            )
                                        }
                                    }
                                }

                                // Event B & C: Real-time cross-device completion / parent approval notification ONLY for server-committed writes
                                if (remoteTask.isCompleted && (prev == null || !prev.isCompleted)) {
                                    val completedByOther = !remoteTask.completedByMemberId.isNullOrBlank() &&
                                        remoteTask.completedByMemberId != selfUid
                                    if (completedByOther && notifiedTaskCompletionKeys.add("${remoteTask.id}:completed")) {
                                        if (remoteTask.approvedByParent) {
                                            val approveKey = "approve:${remoteTask.id}"
                                            if (FcmTokenManager.markEventDispatchedIfNew(approveKey)) {
                                                val title = "משימה הושלמה ואושרה! +${remoteTask.points} נק' 🎉"
                                                val body = "$assigneeName השלים/ה את המשימה: ${remoteTask.title}"
                                                pushAlert(
                                                    title = title,
                                                    message = body,
                                                    targetName = assigneeName,
                                                    targetEmoji = assigneeEmoji,
                                                    reason = AlertReason.TASK_COMPLETED,
                                                    showBanner = true
                                                )
                                                if (remoteTask.assignedToMemberId == selfUid) {
                                                    appContext?.let { ctx ->
                                                        NotificationHelper.showIncomingFcmNotification(
                                                            context = ctx,
                                                            title = title,
                                                            message = body,
                                                            eventKey = approveKey,
                                                            taskId = remoteTask.id
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            val submitKey = "submit:${remoteTask.id}:${remoteTask.completedAtMillis ?: remoteTask.updatedAtMillis}"
                                            if (FcmTokenManager.markEventDispatchedIfNew(submitKey)) {
                                                val title = "משימה הושלמה וממתינה לאישור הורה ⏳"
                                                val body = "$assigneeName סימן/ה כבוצע: ${remoteTask.title} (+${remoteTask.points} נק')"
                                                pushAlert(
                                                    title = title,
                                                    message = body,
                                                    targetName = assigneeName,
                                                    targetEmoji = assigneeEmoji,
                                                    reason = AlertReason.TASK_COMPLETED,
                                                    showBanner = _currentMember.value.isParent
                                                )
                                                if (_currentMember.value.isParent) {
                                                    appContext?.let { ctx ->
                                                        NotificationHelper.showIncomingFcmNotification(
                                                            context = ctx,
                                                            title = title,
                                                            message = body,
                                                            eventKey = submitKey,
                                                            taskId = remoteTask.id
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (remoteTask.isCompleted && remoteTask.approvedByParent && prev != null && !prev.approvedByParent) {
                                    // Event C: Parent approved a previously submitted task -> notify the assigned child
                                    val approveKey = "approve:${remoteTask.id}"
                                    if (notifiedTaskCompletionKeys.add("${remoteTask.id}:approved") && FcmTokenManager.markEventDispatchedIfNew(approveKey)) {
                                        val title = "המשימה שלך אושרה! +${remoteTask.points} נק' 🎉"
                                        val body = "כל הכבוד $assigneeName! ביצוע המשימה \"${remoteTask.title}\" אושר וקיבלת +${remoteTask.points} נקודות!"
                                        pushAlert(
                                            title = title,
                                            message = body,
                                            targetName = assigneeName,
                                            targetEmoji = assigneeEmoji,
                                            reason = AlertReason.TASK_COMPLETED,
                                            showBanner = remoteTask.assignedToMemberId == selfUid
                                        )
                                        if (remoteTask.assignedToMemberId == selfUid) {
                                            appContext?.let { ctx ->
                                                NotificationHelper.showIncomingFcmNotification(
                                                    context = ctx,
                                                    title = title,
                                                    message = body,
                                                    eventKey = approveKey,
                                                    taskId = remoteTask.id
                                                )
                                            }
                                        }
                                    }
                                } else if (!remoteTask.isCompleted) {
                                    notifiedTaskCompletionKeys.remove("${remoteTask.id}:completed")
                                    notifiedTaskCompletionKeys.remove("${remoteTask.id}:approved")
                                }
                            }
                        }
                        if (!snapshot.metadata.hasPendingWrites()) {
                            hasProcessedInitialTasksSnapshot = true
                        }
                        _taskSyncStates.value = updatedSyncStates
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

        // 3. Real-time Pending Invitations Listener (/family_invites where familyId == familyId, PARENT only)
        if (_currentMember.value.isParent) {
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
                        val usablePending = allInvites
                            .filter { it.status == InvitationStatus.PENDING && !it.isExpired(now) }
                            .sortedByDescending { it.createdAt }
                        _pendingInvitations.value = usablePending
                        _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
                            inviteCode = usablePending.firstOrNull()?.code.orEmpty()
                        )
                    }
            }
        } else {
            _pendingInvitations.value = emptyList()
            _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(inviteCode = "")
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

    private fun buildFullTaskFirestoreMap(
        task: FamilyTask,
        familyId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Map<String, Any?> {
        return mapOf(
            "id" to task.id,
            "familyId" to familyId,
            "title" to task.title,
            "description" to task.description,
            "assignedUid" to task.assignedToMemberId,
            "assignedToMemberId" to task.assignedToMemberId,
            "assignedMemberId" to task.assignedToMemberId,
            "points" to task.points,
            "pointsValue" to task.points,
            "isCompleted" to task.isCompleted,
            "approvedByParent" to task.approvedByParent,
            "completedAtMillis" to task.completedAtMillis,
            "completedByMemberId" to task.completedByMemberId,
            "dateEpochDay" to task.dateEpochDay,
            "dueDate" to (task.dateEpochDay * 86_400_000L),
            "category" to task.category.name,
            "priority" to task.priority.name,
            "recurrence" to task.recurrence.name,
            "dueTimeFormatted" to task.dueTimeFormatted,
            "updatedAt" to nowMillis
        )
    }

    private fun pushTaskToFirestoreOrQueue(task: FamilyTask, isDelete: Boolean = false) {
        val familyId = task.familyId.ifBlank { _activeFamilyId.value }
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
                val path = "families/$familyId/tasks/${task.id}"
                val stage = when {
                    isDelete -> FirestoreErrorHandler.TaskStage.DELETE_TASK_WRITE
                    else -> FirestoreErrorHandler.TaskStage.UPDATE_TASK_WRITE
                }
                try {
                    val docRef = db.collection("families").document(familyId)
                        .collection("tasks").document(task.id)
                    if (isDelete) {
                        FirestoreErrorHandler.logTaskMilestone(
                            stage = FirestoreErrorHandler.TaskStage.DELETE_TASK_WRITE,
                            path = path,
                            databaseId = configuredDatabaseId,
                            note = "Deleting task document from named database"
                        )
                        docRef.delete().await()
                    } else if (!_currentMember.value.isParent) {
                        // Safety guard: CHILD must NEVER send full-document set() with protected fields
                        val nowMillis = System.currentTimeMillis()
                        FirestoreErrorHandler.logTaskMilestone(
                            stage = FirestoreErrorHandler.TaskStage.CHILD_COMPLETE_TASK_WRITE,
                            path = path,
                            databaseId = configuredDatabaseId,
                            note = "Updating child completion-only fields in named database"
                        )
                        docRef.update(
                            mapOf(
                                "isCompleted" to task.isCompleted,
                                "completedAtMillis" to (if (task.isCompleted) (task.completedAtMillis ?: nowMillis) else null),
                                "completedByMemberId" to (if (task.isCompleted) (task.completedByMemberId ?: user.uid) else null),
                                "updatedAt" to nowMillis
                            )
                        ).await()
                    } else {
                        val nowMillis = System.currentTimeMillis()
                        FirestoreErrorHandler.logTaskMilestone(
                            stage = FirestoreErrorHandler.TaskStage.CREATE_TASK_WRITE,
                            path = path,
                            databaseId = configuredDatabaseId,
                            note = "Writing parent task definition to named database"
                        )
                        val payload = buildFullTaskFirestoreMap(task, familyId, nowMillis)
                        docRef.set(payload, SetOptions.merge()).await()
                    }
                } catch (e: Exception) {
                    val classified = FirestoreErrorHandler.classifyTaskFailure(
                        error = e,
                        stage = stage,
                        path = path,
                        databaseId = configuredDatabaseId
                    )
                    lastClassifiedTaskFailure = classified
                    _taskOperationError.value = classified.messageHe
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

        val uid = authProvider()?.currentUser?.uid ?: "m_parent_${System.currentTimeMillis()}"
        val newFamilyId = "fam_${uid.take(8)}_${System.currentTimeMillis() % 10000}"
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

        _activeFamilyId.value = newFamilyId
        _familyName.value = cleanFamilyName
        _members.value = listOf(primaryAdmin)
        _currentMember.value = primaryAdmin
        _tasks.value = emptyList()
        _schedulePeriods.value = emptyList()
        _pendingInvitations.value = emptyList()
        val activeState = FamilyMembershipState.ActiveMember(
            familyId = newFamilyId,
            familyName = cleanFamilyName,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity,
            member = primaryAdmin
        )
        _membershipState.value = activeState
        _startupState.value = StartupState.ReadyToEnterDashboard(
            uid = uid,
            familyId = newFamilyId,
            familyName = cleanFamilyName,
            role = UserRole.PARENT,
            familyIdentity = resolvedParentIdentity
        )

        repoScope.launch {
            createFamilyAsParent(
                familyNameInput = cleanFamilyName,
                parentDisplayName = cleanAdminName,
                familyIdentity = resolvedParentIdentity,
                allowCreateIfAlreadyInFamily = true
            )
        }
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
        val currentUidBeforeLogout = runCatching { authProvider()?.currentUser?.uid }.getOrNull()
        FcmTokenManager.unregisterCurrentDeviceTokenAsync(
            context = appContext,
            dbOverride = firestoreProvider(),
            uidOverride = currentUidBeforeLogout
        )
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
        if (!_currentMember.value.isParent) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = SecurityException("רק הורה (מנהל משפחה) רשאי ליצור קודי הזמנה למשפחה."),
                stage = FirestoreErrorHandler.InvitationStage.VERIFY_PARENT_AUTHORIZATION,
                path = "family_invites/{inviteCode}",
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.InvitationFailureCategory.UNAUTHORIZED_ROLE
            )
            lastClassifiedInvitationFailure = classified
            return null
        }
        if (simulateOfflineNetworkFailure) {
            val offlineErr = FirebaseFirestoreException(
                "Simulated offline network failure during invitation creation",
                FirebaseFirestoreException.Code.UNAVAILABLE
            )
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = offlineErr,
                stage = FirestoreErrorHandler.InvitationStage.CREATE_INVITATION_WRITE,
                path = "family_invites/{inviteCode}",
                databaseId = configuredDatabaseId
            )
            lastClassifiedInvitationFailure = classified
            return null
        }

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

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            // When connected to Firebase Auth/Firestore, persist via createRoleSpecificInvitation
            // so all required V1.6 security rule fields (including createdById and isUsed=false) are written
            // and verified before exposing the invitation in _pendingInvitations.
            repoScope.launch {
                createRoleSpecificInvitation(
                    invitedRole = canonicalRole,
                    invitedNickname = name.trim(),
                    invitedEmail = googleEmail,
                    schoolGrade = schoolGrade,
                    customCodeOverride = code
                )
            }
            return invitation
        }

        invitationsByCode[code] = invitation
        _pendingInvitations.value = (_pendingInvitations.value.filterNot { it.code == code } + invitation)
            .sortedByDescending { it.createdAt }
        _cloudFamilyGroup.value = _cloudFamilyGroup.value.copy(
            inviteCode = code,
            lastSyncedEpochMillis = now
        )

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
    /**
     * Emits a deduplicated task completion notification ONLY after the completion/approval write
     * has been confirmed in Firestore (or immediately in pure in-memory unit-test mode).
     */
    private fun emitTaskCompletionNotificationIfNeeded(
        task: FamilyTask,
        isParentApprovalEvent: Boolean = false
    ) {
        if (!task.isCompleted) {
            notifiedTaskCompletionKeys.remove("${task.id}:completed")
            notifiedTaskCompletionKeys.remove("${task.id}:approved")
            return
        }
        val assignee = _members.value.find { it.id == task.assignedToMemberId }
        val assigneeName = assignee?.name ?: _currentMember.value.name.ifBlank { "חבר משפחה" }
        val assigneeEmoji = assignee?.avatarEmoji ?: _currentMember.value.avatarEmoji.ifBlank { "🎉" }

        if (isParentApprovalEvent) {
            if (notifiedTaskCompletionKeys.add("${task.id}:approved")) {
                pushAlert(
                    title = "ביצוע המשימה אושר! +${task.points} נק' 🎉",
                    message = "אושרה המשימה של $assigneeName: ${task.title}",
                    targetName = assigneeName,
                    targetEmoji = assigneeEmoji,
                    reason = AlertReason.TASK_COMPLETED
                )
            }
        } else {
            if (notifiedTaskCompletionKeys.add("${task.id}:completed")) {
                if (task.approvedByParent) {
                    notifiedTaskCompletionKeys.add("${task.id}:approved")
                    pushAlert(
                        title = "משימה הושלמה! +${task.points} נק' 🎉",
                        message = "$assigneeName השלים/ה את המשימה: ${task.title}",
                        targetName = assigneeName,
                        targetEmoji = assigneeEmoji,
                        reason = AlertReason.TASK_COMPLETED
                    )
                } else {
                    pushAlert(
                        title = "משימה הושלמה! ממתינה לאישור הורה ⏳",
                        message = "$assigneeName השלים/ה את המשימה: ${task.title} (+${task.points} נק')",
                        targetName = assigneeName,
                        targetEmoji = assigneeEmoji,
                        reason = AlertReason.TASK_COMPLETED
                    )
                }
            }
        }
    }

    /**
     * V1.6 Requirement 1, 4, 6, 7, 8:
     * - PARENT can toggle any task (and marks `approvedByParent = newStatus`).
     * - CHILD can only mark tasks assigned to their authenticated UID as completed,
     *   updating ONLY completion-related fields (`isCompleted`, `completedAtMillis`, `completedByMemberId`, `updatedAt`)
     *   and setting `approvedByParent = false` (pending parent approval).
     * - Tracks `TaskCompletionSyncState` (`SAVING`, `PENDING_APPROVAL`, `SAVED_APPROVED`, `FAILED`).
     * - Sends push notifications ONLY after Firestore confirms the write.
     * - On failure, restores the previous state and sets a clear Hebrew error message in `taskOperationError`.
     */
    suspend fun toggleTaskCompletionAsync(taskId: String): Result<FamilyTask> {
        val current = _currentMember.value
        val uid = currentUid()
        val targetTask = _tasks.value.find { it.id == taskId }
            ?: return Result.failure(IllegalArgumentException("המשימה לא נמצאה"))
        val resolvedFamilyId = targetTask.familyId.ifBlank { _activeFamilyId.value }
        val taskPath = "families/$resolvedFamilyId/tasks/$taskId"

        if (!current.isParent) {
            val isAssignedToCurrentChild = targetTask.assignedToMemberId == current.id ||
                (uid.isNotBlank() && targetTask.assignedToMemberId == uid)
            if (!isAssignedToCurrentChild) {
                val classified = FirestoreErrorHandler.classifyTaskFailure(
                    error = SecurityException("Blocked CHILD ${current.id} from toggling task assigned to ${targetTask.assignedToMemberId}"),
                    stage = FirestoreErrorHandler.TaskStage.VERIFY_TASK_AUTHORIZATION,
                    path = taskPath,
                    databaseId = configuredDatabaseId,
                    overrideCategory = FirestoreErrorHandler.TaskFailureCategory.ASSIGNEE_MISMATCH
                )
                lastClassifiedTaskFailure = classified
                _taskOperationError.value = classified.messageHe
                return Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified))
            }
        }

        val previousTask = targetTask
        val previousMembers = _members.value
        val previousCurrentMember = _currentMember.value

        val newStatus = !targetTask.isCompleted
        val nowMillis = System.currentTimeMillis()
        val actorId = uid.ifBlank { current.id }
        val newApprovedByParent = if (current.isParent) newStatus else false

        val updatedTask = targetTask.copy(
            familyId = resolvedFamilyId,
            isCompleted = newStatus,
            approvedByParent = newApprovedByParent,
            completedAtMillis = if (newStatus) nowMillis else null,
            completedByMemberId = if (newStatus) actorId else null,
            updatedAtMillis = nowMillis
        )

        _taskOperationError.value = null
        _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVING)

        // Optimistically update local task & points state while write is in flight
        _tasks.value = _tasks.value.map { if (it.id == taskId) updatedTask else it }

        var updatedAssigneePoints: Int? = null
        if (current.isParent) {
            val delta = if (newStatus) updatedTask.points else -updatedTask.points
            _members.value = _members.value.map { member ->
                if (member.id == updatedTask.assignedToMemberId) {
                    val newPts = (member.earnedPoints + delta).coerceAtLeast(0)
                    updatedAssigneePoints = newPts
                    val updatedMember = member.copy(earnedPoints = newPts)
                    if (_currentMember.value.id == member.id) {
                        _currentMember.value = updatedMember
                    }
                    updatedMember
                } else {
                    member
                }
            }
        }

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        val writeStage = if (current.isParent) {
            FirestoreErrorHandler.TaskStage.PARENT_COMPLETE_TASK_WRITE
        } else {
            FirestoreErrorHandler.TaskStage.CHILD_COMPLETE_TASK_WRITE
        }

        if (simulateOfflineNetworkFailure) {
            val offlineErr = FirebaseFirestoreException(
                "Simulated offline network failure during task completion",
                FirebaseFirestoreException.Code.UNAVAILABLE
            )
            // Revert optimistic UI state on failure
            _tasks.value = _tasks.value.map { if (it.id == taskId) previousTask else it }
            _members.value = previousMembers
            _currentMember.value = previousCurrentMember
            _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.FAILED)
            val classified = FirestoreErrorHandler.classifyTaskFailure(
                error = offlineErr,
                stage = writeStage,
                path = taskPath,
                databaseId = configuredDatabaseId
            )
            lastClassifiedTaskFailure = classified
            _taskOperationError.value = classified.messageHe
            return Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified, offlineErr))
        }

        if (db != null && authUser != null) {
            try {
                FirestoreErrorHandler.logTaskMilestone(
                    stage = writeStage,
                    path = taskPath,
                    databaseId = configuredDatabaseId,
                    note = "Persisting task completion (isCompleted=$newStatus, isParent=${current.isParent})"
                )
                val docRef = db.collection("families").document(resolvedFamilyId)
                    .collection("tasks").document(updatedTask.id)

                if (!current.isParent) {
                    // CHILD completion-only update: strictly modifies ONLY fields allowed by isChildTaskCompletionOnly()
                    val childUpdateMap = mapOf<String, Any?>(
                        "isCompleted" to updatedTask.isCompleted,
                        "completedAtMillis" to updatedTask.completedAtMillis,
                        "completedByMemberId" to if (updatedTask.isCompleted) (updatedTask.completedByMemberId ?: authUser.uid) else null,
                        "updatedAt" to nowMillis
                    )
                    docRef.update(childUpdateMap).await()
                } else {
                    // PARENT update: updates completion & approval status and persists assignee earnedPoints
                    val parentUpdateMap = mapOf<String, Any?>(
                        "isCompleted" to updatedTask.isCompleted,
                        "approvedByParent" to updatedTask.approvedByParent,
                        "completedAtMillis" to updatedTask.completedAtMillis,
                        "completedByMemberId" to (updatedTask.completedByMemberId ?: authUser.uid),
                        "updatedAt" to nowMillis
                    )
                    try {
                        docRef.update(parentUpdateMap).await()
                    } catch (_: Exception) {
                        docRef.set(buildFullTaskFirestoreMap(updatedTask, resolvedFamilyId, nowMillis), SetOptions.merge()).await()
                    }

                    if (updatedAssigneePoints != null && updatedTask.assignedToMemberId.isNotBlank()) {
                        runCatching {
                            db.collection("families").document(resolvedFamilyId)
                                .collection("members").document(updatedTask.assignedToMemberId)
                                .update(
                                    mapOf(
                                        "earnedPoints" to updatedAssigneePoints,
                                        "updatedAt" to nowMillis
                                    )
                                ).await()
                        }
                    }
                }

                FirestoreErrorHandler.logTaskMilestone(
                    stage = writeStage,
                    path = taskPath,
                    databaseId = configuredDatabaseId,
                    note = "Confirmed task completion commit in Firestore"
                )
            } catch (e: Exception) {
                // Restore previous state so UI never silently diverges from Firestore
                _tasks.value = _tasks.value.map { if (it.id == taskId) previousTask else it }
                _members.value = previousMembers
                _currentMember.value = previousCurrentMember
                _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.FAILED)

                val classified = FirestoreErrorHandler.classifyTaskFailure(
                    error = e,
                    stage = writeStage,
                    path = taskPath,
                    databaseId = configuredDatabaseId
                )
                lastClassifiedTaskFailure = classified
                _taskOperationError.value = classified.messageHe
                return Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified, e))
            }
        }

        // Write succeeded (or pure local unit-test mode): update sync state, Room cache, and emit notification
        val finalSyncState = when {
            !updatedTask.isCompleted -> TaskCompletionSyncState.IDLE
            updatedTask.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
            else -> TaskCompletionSyncState.PENDING_APPROVAL
        }
        _taskSyncStates.value = _taskSyncStates.value + (taskId to finalSyncState)
        _tasks.value = _tasks.value.map { if (it.id == taskId) updatedTask else it }

        repoScope.launch {
            runCatching {
                taskDao?.upsertTask(updatedTask.toCoreTask(resolvedFamilyId))
            }
        }

        emitTaskCompletionNotificationIfNeeded(updatedTask, isParentApprovalEvent = false)
        return Result.success(updatedTask)
    }

    fun toggleTaskCompletion(taskId: String): Boolean {
        val current = _currentMember.value
        val uid = currentUid()
        val targetTask = _tasks.value.find { it.id == taskId } ?: return false
        val resolvedFamilyId = targetTask.familyId.ifBlank { _activeFamilyId.value }
        val taskPath = "families/$resolvedFamilyId/tasks/$taskId"

        if (!current.isParent) {
            val isAssignedToCurrentChild = targetTask.assignedToMemberId == current.id ||
                (uid.isNotBlank() && targetTask.assignedToMemberId == uid)
            if (!isAssignedToCurrentChild) {
                val classified = FirestoreErrorHandler.classifyTaskFailure(
                    error = SecurityException("Blocked CHILD ${current.id} from toggling task assigned to ${targetTask.assignedToMemberId}"),
                    stage = FirestoreErrorHandler.TaskStage.VERIFY_TASK_AUTHORIZATION,
                    path = taskPath,
                    databaseId = configuredDatabaseId,
                    overrideCategory = FirestoreErrorHandler.TaskFailureCategory.ASSIGNEE_MISMATCH
                )
                lastClassifiedTaskFailure = classified
                _taskOperationError.value = classified.messageHe
                return false
            }
        }

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db == null && authUser == null && !simulateOfflineNetworkFailure) {
            // Synchronous in-memory execution for local unit tests
            return kotlinx.coroutines.runBlocking {
                toggleTaskCompletionAsync(taskId).isSuccess
            }
        }

        if (simulateOfflineNetworkFailure) {
            kotlinx.coroutines.runBlocking {
                toggleTaskCompletionAsync(taskId)
            }
            return false
        }

        // Apply immediate optimistic state and launch cloud commit on repoScope
        val previousTask = targetTask
        val previousMembers = _members.value
        val previousCurrentMember = _currentMember.value
        val newStatus = !targetTask.isCompleted
        val nowMillis = System.currentTimeMillis()
        val actorId = uid.ifBlank { current.id }
        val newApprovedByParent = if (current.isParent) newStatus else false

        val updatedTask = targetTask.copy(
            familyId = resolvedFamilyId,
            isCompleted = newStatus,
            approvedByParent = newApprovedByParent,
            completedAtMillis = if (newStatus) nowMillis else null,
            completedByMemberId = if (newStatus) actorId else null,
            updatedAtMillis = nowMillis
        )

        _taskOperationError.value = null
        _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVING)
        _tasks.value = _tasks.value.map { if (it.id == taskId) updatedTask else it }

        var updatedAssigneePoints: Int? = null
        if (current.isParent) {
            val delta = if (newStatus) updatedTask.points else -updatedTask.points
            _members.value = _members.value.map { member ->
                if (member.id == updatedTask.assignedToMemberId) {
                    val newPts = (member.earnedPoints + delta).coerceAtLeast(0)
                    updatedAssigneePoints = newPts
                    val updatedMember = member.copy(earnedPoints = newPts)
                    if (_currentMember.value.id == member.id) {
                        _currentMember.value = updatedMember
                    }
                    updatedMember
                } else {
                    member
                }
            }
        }

        val isParentCaller = current.isParent
        val writeStage = if (isParentCaller) {
            FirestoreErrorHandler.TaskStage.PARENT_COMPLETE_TASK_WRITE
        } else {
            FirestoreErrorHandler.TaskStage.CHILD_COMPLETE_TASK_WRITE
        }

        repoScope.launch {
            try {
                if (db != null && authUser != null) {
                    FirestoreErrorHandler.logTaskMilestone(
                        stage = writeStage,
                        path = taskPath,
                        databaseId = configuredDatabaseId,
                        note = "Persisting task completion (isCompleted=$newStatus, isParent=$isParentCaller)"
                    )
                    val docRef = db.collection("families").document(resolvedFamilyId)
                        .collection("tasks").document(updatedTask.id)

                    if (!isParentCaller) {
                        val childUpdateMap = mapOf<String, Any?>(
                            "isCompleted" to updatedTask.isCompleted,
                            "completedAtMillis" to updatedTask.completedAtMillis,
                            "completedByMemberId" to if (updatedTask.isCompleted) (updatedTask.completedByMemberId ?: authUser.uid) else null,
                            "updatedAt" to nowMillis
                        )
                        docRef.update(childUpdateMap).await()
                    } else {
                        val parentUpdateMap = mapOf<String, Any?>(
                            "isCompleted" to updatedTask.isCompleted,
                            "approvedByParent" to updatedTask.approvedByParent,
                            "completedAtMillis" to updatedTask.completedAtMillis,
                            "completedByMemberId" to if (updatedTask.isCompleted) (updatedTask.completedByMemberId ?: authUser.uid) else null,
                            "updatedAt" to nowMillis
                        )
                        try {
                            docRef.update(parentUpdateMap).await()
                        } catch (_: Exception) {
                            docRef.set(buildFullTaskFirestoreMap(updatedTask, resolvedFamilyId, nowMillis), SetOptions.merge()).await()
                        }

                        if (updatedAssigneePoints != null && updatedTask.assignedToMemberId.isNotBlank()) {
                            runCatching {
                                db.collection("families").document(resolvedFamilyId)
                                    .collection("members").document(updatedTask.assignedToMemberId)
                                    .update(
                                        mapOf(
                                            "earnedPoints" to updatedAssigneePoints,
                                            "updatedAt" to nowMillis
                                        )
                                    ).await()
                            }
                        }
                    }
                }

                val finalSyncState = when {
                    !updatedTask.isCompleted -> TaskCompletionSyncState.IDLE
                    updatedTask.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
                    else -> TaskCompletionSyncState.PENDING_APPROVAL
                }
                _taskSyncStates.value = _taskSyncStates.value + (taskId to finalSyncState)
                _tasks.value = _tasks.value.map { if (it.id == taskId) updatedTask else it }
                runCatching {
                    taskDao?.upsertTask(updatedTask.toCoreTask(resolvedFamilyId))
                }
                emitTaskCompletionNotificationIfNeeded(updatedTask, isParentApprovalEvent = false)
            } catch (e: Exception) {
                // Revert optimistic state on Firestore write failure
                _tasks.value = _tasks.value.map { if (it.id == taskId) previousTask else it }
                _members.value = previousMembers
                _currentMember.value = previousCurrentMember
                _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.FAILED)

                val classified = FirestoreErrorHandler.classifyTaskFailure(
                    error = e,
                    stage = writeStage,
                    path = taskPath,
                    databaseId = configuredDatabaseId
                )
                lastClassifiedTaskFailure = classified
                _taskOperationError.value = classified.messageHe
            }
        }
        return true
    }

    /**
     * V1.6 Requirement 6 & 9:
     * Allows an authenticated PARENT to approve a child's completed task (`approvedByParent = true`),
     * persisting the approval to `/families/{familyId}/tasks/{taskId}` and awarding points to the
     * child's member document in Firestore.
     */
    suspend fun approveTaskCompletionAsync(taskId: String): Result<FamilyTask> {
        val current = _currentMember.value
        val targetTask = _tasks.value.find { it.id == taskId }
            ?: return Result.failure(IllegalArgumentException("המשימה לא נמצאה"))
        val resolvedFamilyId = targetTask.familyId.ifBlank { _activeFamilyId.value }
        val taskPath = "families/$resolvedFamilyId/tasks/$taskId"

        if (!current.isParent) {
            val classified = FirestoreErrorHandler.classifyTaskFailure(
                error = SecurityException("Only a PARENT can approve task completion"),
                stage = FirestoreErrorHandler.TaskStage.VERIFY_TASK_AUTHORIZATION,
                path = taskPath,
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.TaskFailureCategory.UNAUTHORIZED_ROLE
            )
            lastClassifiedTaskFailure = classified
            _taskOperationError.value = classified.messageHe
            return Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified))
        }

        val previousTask = targetTask
        val previousMembers = _members.value
        val previousCurrentMember = _currentMember.value
        val nowMillis = System.currentTimeMillis()
        val wasAlreadyApproved = targetTask.isCompleted && targetTask.approvedByParent

        val approvedTask = targetTask.copy(
            familyId = resolvedFamilyId,
            isCompleted = true,
            approvedByParent = true,
            completedAtMillis = targetTask.completedAtMillis ?: nowMillis,
            completedByMemberId = targetTask.completedByMemberId ?: targetTask.assignedToMemberId,
            updatedAtMillis = nowMillis
        )

        _taskOperationError.value = null
        _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVING)
        _tasks.value = _tasks.value.map { if (it.id == taskId) approvedTask else it }

        var updatedAssigneePoints: Int? = null
        _members.value = _members.value.map { member ->
            if (member.id == approvedTask.assignedToMemberId) {
                val pointsToAward = if (!wasAlreadyApproved) approvedTask.points else 0
                val newPts = (member.earnedPoints + pointsToAward).coerceAtLeast(0)
                updatedAssigneePoints = newPts
                val updatedMember = member.copy(earnedPoints = newPts)
                if (_currentMember.value.id == member.id) {
                    _currentMember.value = updatedMember
                }
                updatedMember
            } else {
                member
            }
        }

        if (simulateOfflineNetworkFailure) {
            val offlineErr = FirebaseFirestoreException(
                "Simulated offline network failure during parent task approval",
                FirebaseFirestoreException.Code.UNAVAILABLE
            )
            _tasks.value = _tasks.value.map { if (it.id == taskId) previousTask else it }
            _members.value = previousMembers
            _currentMember.value = previousCurrentMember
            _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.FAILED)
            val classified = FirestoreErrorHandler.classifyTaskFailure(
                error = offlineErr,
                stage = FirestoreErrorHandler.TaskStage.PARENT_APPROVE_TASK_WRITE,
                path = taskPath,
                databaseId = configuredDatabaseId
            )
            lastClassifiedTaskFailure = classified
            _taskOperationError.value = classified.messageHe
            return Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified, offlineErr))
        }

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                FirestoreErrorHandler.logTaskMilestone(
                    stage = FirestoreErrorHandler.TaskStage.PARENT_APPROVE_TASK_WRITE,
                    path = taskPath,
                    databaseId = configuredDatabaseId,
                    note = "Parent approving task completion in named database"
                )
                val docRef = db.collection("families").document(resolvedFamilyId)
                    .collection("tasks").document(approvedTask.id)
                docRef.update(
                    mapOf(
                        "isCompleted" to true,
                        "approvedByParent" to true,
                        "completedAtMillis" to approvedTask.completedAtMillis,
                        "completedByMemberId" to approvedTask.completedByMemberId,
                        "updatedAt" to nowMillis
                    )
                ).await()

                if (approvedTask.assignedToMemberId.isNotBlank()) {
                    val memberRef = db.collection("families").document(resolvedFamilyId)
                        .collection("members").document(approvedTask.assignedToMemberId)
                    runCatching {
                        val memberSnap = memberRef.get().await()
                        if (memberSnap.exists()) {
                            val currentCloudPts = (memberSnap.getLong("earnedPoints") ?: 0L).toInt()
                            val newCloudPts = (currentCloudPts + approvedTask.points).coerceAtLeast(updatedAssigneePoints ?: 0)
                            memberRef.update(
                                mapOf(
                                    "earnedPoints" to newCloudPts,
                                    "updatedAt" to nowMillis
                                )
                            ).await()
                            _members.value = _members.value.map { m ->
                                if (m.id == approvedTask.assignedToMemberId) m.copy(earnedPoints = newCloudPts) else m
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _tasks.value = _tasks.value.map { if (it.id == taskId) previousTask else it }
                _members.value = previousMembers
                _currentMember.value = previousCurrentMember
                _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.FAILED)
                val classified = FirestoreErrorHandler.classifyTaskFailure(
                    error = e,
                    stage = FirestoreErrorHandler.TaskStage.PARENT_APPROVE_TASK_WRITE,
                    path = taskPath,
                    databaseId = configuredDatabaseId
                )
                lastClassifiedTaskFailure = classified
                _taskOperationError.value = classified.messageHe
                return Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified, e))
            }
        }

        _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVED_APPROVED)
        _tasks.value = _tasks.value.map { if (it.id == taskId) approvedTask else it }
        emitTaskCompletionNotificationIfNeeded(approvedTask, isParentApprovalEvent = true)
        return Result.success(approvedTask)
    }

    fun approveTaskCompletion(taskId: String): Boolean {
        if (!_currentMember.value.isParent) {
            val classified = FirestoreErrorHandler.classifyTaskFailure(
                error = SecurityException("Only a PARENT can approve task completion"),
                stage = FirestoreErrorHandler.TaskStage.VERIFY_TASK_AUTHORIZATION,
                path = "families/${_activeFamilyId.value}/tasks/$taskId",
                databaseId = configuredDatabaseId,
                overrideCategory = FirestoreErrorHandler.TaskFailureCategory.UNAUTHORIZED_ROLE
            )
            lastClassifiedTaskFailure = classified
            _taskOperationError.value = classified.messageHe
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db == null && authUser == null && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                approveTaskCompletionAsync(taskId).isSuccess
            }
        }
        repoScope.launch {
            approveTaskCompletionAsync(taskId)
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
        reason: AlertReason,
        showBanner: Boolean = false
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
        if (showBanner) {
            _activeBannerAlert.value = alert
        }
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
            FirestoreErrorHandler.logInvitationMilestone(
                stage = FirestoreErrorHandler.InvitationStage.CREATE_INVITATION_WRITE,
                path = "family_invites/$code",
                databaseId = configuredDatabaseId,
                rawInviteCode = code,
                note = "Writing role=${canonicalRole.toFirestoreValue()} invitation in cloud"
            )
            val inviteMap = mapOf(
                "code" to invitation.code,
                "inviteCode" to invitation.code,
                "familyId" to invitation.familyId,
                "familyName" to invitation.familyName,
                "role" to invitation.invitedRole.toFirestoreValue(),
                "invitedRole" to invitation.invitedRole.toFirestoreValue(),
                "invitingParentUid" to invitation.invitingParentUid,
                "createdById" to invitation.invitingParentUid,
                "createdByUid" to invitation.invitingParentUid,
                "invitingParentName" to invitation.invitingParentName,
                "createdByName" to invitation.invitingParentName,
                "invitedNickname" to invitation.invitedNickname,
                "invitedName" to invitation.invitedNickname,
                "createdAt" to invitation.createdAt,
                "expiresAt" to invitation.expiresAt,
                "expiresAtMillis" to invitation.expiresAt,
                "status" to InvitationStatus.PENDING.name,
                "isUsed" to false,
                "isRevoked" to false,
                "revoked" to false
            )
            val docRef = db.collection("family_invites").document(code)
            docRef.set(inviteMap).await()
            val committed = docRef.get().await()
            if (!committed.exists()) {
                throw FirebaseFirestoreException(
                    "Invitation document was not found after write",
                    FirebaseFirestoreException.Code.NOT_FOUND
                )
            }
            FirestoreErrorHandler.logInvitationMilestone(
                stage = FirestoreErrorHandler.InvitationStage.CONFIRM_INVITATION_COMMIT,
                path = "family_invites/$code",
                databaseId = configuredDatabaseId,
                rawInviteCode = code,
                note = "Confirmed invitation document committed in family_invites"
            )
            invitationsByCode[code] = invitation
            _pendingInvitations.value = (_pendingInvitations.value.filterNot { it.code == code } + invitation)
                .filter { it.status == InvitationStatus.PENDING && !it.isExpired(now) }
                .sortedByDescending { it.createdAt }
            Result.success(invitation)
        } catch (e: Exception) {
            val classified = FirestoreErrorHandler.classifyInvitationFailure(
                error = e,
                stage = FirestoreErrorHandler.InvitationStage.CREATE_INVITATION_WRITE,
                path = "family_invites/$code",
                rawInviteCode = code,
                databaseId = configuredDatabaseId
            )
            lastClassifiedInvitationFailure = classified
            invitationsByCode.remove(code)
            _pendingInvitations.value = _pendingInvitations.value.filterNot { it.code == code }
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
        val path = if (task.familyId.isNotBlank()) "families/${task.familyId}/tasks/${task.id}" else "tasks/${task.id}"
        return try {
            if (task.familyId.isNotBlank()) {
                val familyRef = db.collection("families").document(task.familyId)
                val memberSnap = runCatching {
                    familyRef.collection("members").document(uid).get().await()
                }.getOrNull()
                val memberRole = UserRole.fromFirestoreValue(memberSnap?.getString("role"))
                val isParentInCloud = memberSnap?.exists() == true && memberRole == UserRole.PARENT

                val taskRef = familyRef.collection("tasks").document(task.id)
                val nowMillis = System.currentTimeMillis()
                if (!isParentInCloud) {
                    // Child completion-only update path: preserves parent's title/description/priority/points edits
                    // and strictly sends ONLY fields permitted by isChildTaskCompletionOnly() in firestore.rules
                    FirestoreErrorHandler.logTaskMilestone(
                        stage = FirestoreErrorHandler.TaskStage.CHILD_COMPLETE_TASK_WRITE,
                        path = path,
                        databaseId = configuredDatabaseId,
                        note = "Executing child completion-only update in updateTaskInCloud"
                    )
                    taskRef.update(
                        mapOf(
                            "isCompleted" to task.isCompleted,
                            "completedAtMillis" to (if (task.isCompleted) (task.completedAtMillis ?: nowMillis) else null),
                            "completedByMemberId" to (if (task.isCompleted) (task.completedByMemberId ?: uid) else null),
                            "updatedAt" to nowMillis
                        )
                    ).await()
                } else {
                    FirestoreErrorHandler.logTaskMilestone(
                        stage = FirestoreErrorHandler.TaskStage.UPDATE_TASK_WRITE,
                        path = path,
                        databaseId = configuredDatabaseId,
                        note = "Executing parent task update in updateTaskInCloud"
                    )
                    taskRef.update(
                        mapOf(
                            "title" to task.title,
                            "description" to task.description,
                            "category" to task.category.name,
                            "assignedUid" to task.assignedToMemberId,
                            "assignedToMemberId" to task.assignedToMemberId,
                            "assignedMemberId" to task.assignedToMemberId,
                            "dateEpochDay" to task.dateEpochDay,
                            "dueDate" to (task.dateEpochDay * 86_400_000L),
                            "isCompleted" to task.isCompleted,
                            "priority" to task.priority.name,
                            "recurrence" to task.recurrence.name,
                            "points" to task.points,
                            "pointsValue" to task.points,
                            "approvedByParent" to task.approvedByParent,
                            "completedAtMillis" to task.completedAtMillis,
                            "completedByMemberId" to task.completedByMemberId,
                            "updatedAt" to nowMillis
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
            val classified = FirestoreErrorHandler.classifyTaskFailure(
                error = e,
                stage = FirestoreErrorHandler.TaskStage.UPDATE_TASK_WRITE,
                path = path,
                databaseId = configuredDatabaseId
            )
            lastClassifiedTaskFailure = classified
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
        val path = "families/$familyId/tasks/$taskId"
        return try {
            val nowMillis = System.currentTimeMillis()
            FirestoreErrorHandler.logTaskMilestone(
                stage = FirestoreErrorHandler.TaskStage.CHILD_COMPLETE_TASK_WRITE,
                path = path,
                databaseId = configuredDatabaseId,
                note = "Executing completeTaskInCloud (isCompleted=$isCompleted)"
            )
            db.collection("families").document(familyId)
                .collection("tasks").document(taskId)
                .update(
                    mapOf(
                        "isCompleted" to isCompleted,
                        "completedAtMillis" to (if (isCompleted) nowMillis else null),
                        "completedByMemberId" to (if (isCompleted) completedByMemberId else null),
                        "updatedAt" to nowMillis
                    )
                ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            val classified = FirestoreErrorHandler.classifyTaskFailure(
                error = e,
                stage = FirestoreErrorHandler.TaskStage.CHILD_COMPLETE_TASK_WRITE,
                path = path,
                databaseId = configuredDatabaseId
            )
            lastClassifiedTaskFailure = classified
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

    /**
     * Registers or refreshes an FCM device token in the named Firestore database under
     * `/users/{uid}/fcm_tokens/{tokenId}` for the currently authenticated user.
     */
    suspend fun registerDeviceFcmTokenInCloud(
        token: String,
        familyId: String = _activeFamilyId.value,
        role: UserRole = _currentMember.value.role
    ): Result<String> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return FcmTokenManager.registerTokenInFirestore(
            db = db,
            uid = uid,
            token = token,
            familyId = familyId,
            role = role
        )
    }

    /**
     * Removes a specific device's FCM token from `/users/{uid}/fcm_tokens/{tokenId}` on logout
     * or invalid-token cleanup.
     */
    suspend fun unregisterDeviceFcmTokenInCloud(token: String): Result<Unit> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = authProvider()?.currentUser?.uid ?: return Result.failure(SecurityException("Not authenticated"))
        return FcmTokenManager.unregisterTokenFromFirestore(
            db = db,
            uid = uid,
            token = token
        )
    }

    suspend fun deleteDeviceFcmTokenInCloud(token: String): Result<Unit> =
        unregisterDeviceFcmTokenInCloud(token)

    /**
     * Reads all registered device FCM tokens under `/users/{targetUid}/fcm_tokens`.
     * Enforced by Firestore security rules so only the authenticated owner can read their own tokens.
     */
    suspend fun getDeviceFcmTokensFromCloud(targetUid: String): Result<List<String>> {
        val db = firestoreProvider() ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            val snap = db.collection("users").document(targetUid)
                .collection("fcm_tokens")
                .get()
                .await()
            val tokens = snap.documents.mapNotNull { it.getString("token")?.takeIf { t -> t.isNotBlank() } }
            Result.success(tokens)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
