package com.example.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.OfflineSyncDao
import com.example.data.local.OfflineSyncQueueEntity
import com.example.data.local.TaskDao
import com.example.files.FamilyCloudFileTransferEngine
import com.example.files.FamilyFileManagerHelper
import com.example.files.GoogleDriveFamilyService
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    @Volatile
    private var dynamicAppContext: Context? = context?.applicationContext ?: context

    private val appContext: Context?
        get() = dynamicAppContext
            ?: context?.applicationContext
            ?: context
            ?: runCatching { com.google.firebase.FirebaseApp.getInstance().applicationContext }.getOrNull()

    private val effectiveTaskDao: TaskDao?
        get() = taskDao ?: dynamicAppContext?.let {
            runCatching { AppDatabase.getInstance(it).taskDao() }.getOrNull()
        }

    private val effectiveOfflineSyncDao: OfflineSyncDao?
        get() = offlineSyncDao ?: dynamicAppContext?.let {
            runCatching { AppDatabase.getInstance(it).offlineSyncDao() }.getOrNull()
        }

    fun attachApplicationContext(ctx: Context) {
        val appCtx = ctx.applicationContext ?: ctx
        val wasNull = dynamicAppContext == null && context == null
        dynamicAppContext = appCtx
        loadUiPreferences(appCtx)

        val persistedDeleted = com.example.notifications.FamilyAlarmScheduler.getPersistedDeletedAlarmIds(appCtx)
        explicitlyDeletedAlarmIds.addAll(persistedDeleted)

        val persistedAlarms = com.example.notifications.FamilyAlarmScheduler.loadPersistedAlarms(appCtx)
            .filterNot { it.id in explicitlyDeletedAlarmIds || it.isDeleted || it.scheduleStatus == FamilyAlarm.STATUS_DELETED }
        if (persistedAlarms.isNotEmpty()) {
            val existingById = allFamilyAlarms.associateBy { it.id }.toMutableMap()
            persistedAlarms.forEach { diskAlarm ->
                if (diskAlarm.id !in knownRemoteAlarmIds) {
                    locallyManagedAlarms.putIfAbsent(diskAlarm.id, diskAlarm)
                }
                existingById[diskAlarm.id] = diskAlarm
            }
            allFamilyAlarms = existingById.values.sortedWith(compareBy({ it.hour }, { it.minute }))
        }

        if (wasNull || persistedAlarms.isNotEmpty() || _familyAlarms.value.isNotEmpty()) {
            resyncAlarmsForActiveMember()
        }
        if (wasNull || _tasks.value.isNotEmpty()) {
            resyncTaskRemindersForActiveMember()
        }
        if (wasNull) {
            seedLocalRoomIfNeeded()
            com.example.notifications.FamilyAlarmCloudSyncWorker.reconcileAlarmsFromFirestoreAsync(
                context = appCtx,
                reason = "app_startup"
            )
        }
    }

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

        private const val FILE_MANAGER_PREFS = "family_tasks_file_manager_prefs"
        private const val UI_PREFS_NAME = "family_tasks_ui_prefs"
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_TEXT_SCALE = "pref_text_scale"
        private const val KEY_APP_LANGUAGE = "pref_app_language"
        private const val KEY_FIRST_DAY_OF_WEEK = "pref_first_day_of_week"
        private const val KEY_TASKS_VIEW_MODE = "pref_tasks_view_mode"
        private val persistedSelectedFolderByFamily = ConcurrentHashMap<String, String?>()
        private val persistedFoldersByFamily = ConcurrentHashMap<String, List<DocumentFolder>>()
        private val persistedDocumentsByFamily = ConcurrentHashMap<String, List<FamilyDocument>>()
        private val persistedDriveConfigByFamily = ConcurrentHashMap<String, FamilyDriveConfig>()

        private val LEGACY_PREDEFINED_FOLDER_IDS = setOf(
            "f_school", "f_medical", "f_Artwork", "f_artwork",
            "f_home", "f_trips", "f_drawings", "f_general"
        )

        fun defaultStarterFolders(familyId: String = ""): List<DocumentFolder> = emptyList()
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
    private var foldersListenerRegistration: ListenerRegistration? = null
    private var filesListenerRegistration: ListenerRegistration? = null
    private var alarmsListenerRegistration: ListenerRegistration? = null
    private var pointsHistoryListenerRegistration: ListenerRegistration? = null
    private var taskProposalsListenerRegistration: ListenerRegistration? = null
    private var redemptionsListenerRegistration: ListenerRegistration? = null

    private val pointsMutex = Mutex()
    private val processedPointsOperationIds: MutableSet<String> =
        ConcurrentHashMap.newKeySet()
    private val syncedServerMemberBalancesForFamilies: MutableSet<String> =
        ConcurrentHashMap.newKeySet()
    private val processedCollaborationOperationIds: MutableSet<String> =
        ConcurrentHashMap.newKeySet()
    private val notifiedCollaborationEventKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _pointsHistory = MutableStateFlow<List<PointsHistoryEntry>>(emptyList())
    val pointsHistory: StateFlow<List<PointsHistoryEntry>> = _pointsHistory.asStateFlow()

    private val _isProcessingPointsOperation = MutableStateFlow(false)
    val isProcessingPointsOperation: StateFlow<Boolean> = _isProcessingPointsOperation.asStateFlow()

    private val _pointsOperationError = MutableStateFlow<String?>(null)
    val pointsOperationError: StateFlow<String?> = _pointsOperationError.asStateFlow()

    private val _taskProposals = MutableStateFlow<List<TaskCollaborationProposal>>(emptyList())
    val taskProposals: StateFlow<List<TaskCollaborationProposal>> = _taskProposals.asStateFlow()

    private val _rewardRedemptions = MutableStateFlow<List<RewardRedemptionRequest>>(emptyList())
    val rewardRedemptions: StateFlow<List<RewardRedemptionRequest>> = _rewardRedemptions.asStateFlow()

    private val _collaborationError = MutableStateFlow<String?>(null)
    val collaborationError: StateFlow<String?> = _collaborationError.asStateFlow()

    private val locallyManagedAlarms = ConcurrentHashMap<String, FamilyAlarm>()
    private val explicitlyDeletedAlarmIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val knownRemoteAlarmIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val confirmedCancelledAlarmIdsByMember = ConcurrentHashMap<String, Set<String>>()

    /** Configurable hooks for testing file upload cancellation, progress, and retry behavior. */
    @Volatile
    var simulatedFileUploadStepDelayMs: Long = 0L

    @Volatile
    var simulateNextFileUploadFailure: Boolean = false

    private val activeUploadJobs = ConcurrentHashMap<String, Job>()
    private val _activeUploads = MutableStateFlow<List<FileUploadTaskState>>(emptyList())
    val activeUploads: StateFlow<List<FileUploadTaskState>> = _activeUploads.asStateFlow()

    private val _fileOperationError = MutableStateFlow<String?>(null)
    val fileOperationError: StateFlow<String?> = _fileOperationError.asStateFlow()

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

    // 4. App Preferences (RTL, Theme, Text Scale, First Day of Week, Views)
    private val _appLanguage = MutableStateFlow(AppLanguage.HEBREW)
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.LIGHT)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _appTextScale = MutableStateFlow(AppTextScale.NORMAL)
    val appTextScale: StateFlow<AppTextScale> = _appTextScale.asStateFlow()

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

    private val _tasksViewMode = MutableStateFlow(TasksViewMode.STANDARD)
    val tasksViewMode: StateFlow<TasksViewMode> = _tasksViewMode.asStateFlow()

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

    // 7b. Shared Family Alarm Clocks (Wake-up, School Departure, Reminders)
    private var allFamilyAlarms: List<FamilyAlarm> = listOf(
        FamilyAlarm(
            id = "alarm_wakeup",
            title = "השכמה לבית הספר",
            hour = 6,
            minute = 45,
            assignedMemberId = "",
            assignedMemberName = "כל המשפחה",
            daysOfWeek = listOf(
                SchoolDay.SUNDAY,
                SchoolDay.MONDAY,
                SchoolDay.TUESDAY,
                SchoolDay.WEDNESDAY,
                SchoolDay.THURSDAY,
                SchoolDay.FRIDAY
            ),
            isEnabled = true,
            ringtoneEmoji = "⏰"
        ),
        FamilyAlarm(
            id = "alarm_school_bus",
            title = "יציאה להסעה / לבית הספר",
            hour = 7,
            minute = 30,
            assignedMemberId = "",
            assignedMemberName = "כל המשפחה",
            daysOfWeek = listOf(
                SchoolDay.SUNDAY,
                SchoolDay.MONDAY,
                SchoolDay.TUESDAY,
                SchoolDay.WEDNESDAY,
                SchoolDay.THURSDAY,
                SchoolDay.FRIDAY
            ),
            isEnabled = true,
            ringtoneEmoji = "🎒"
        )
    )
    private val _familyAlarms = MutableStateFlow(allFamilyAlarms)
    val familyAlarms: StateFlow<List<FamilyAlarm>> = _familyAlarms.asStateFlow()

    private fun refreshVisibleFamilyAlarms() {
        val current = _currentMember.value
        val authUid = authProvider()?.currentUser?.uid.orEmpty()
        val membersList = _members.value
        _familyAlarms.value = allFamilyAlarms.filter { alarm ->
            alarm.isVisibleToMember(
                currentMember = current,
                authUid = authUid,
                members = membersList
            )
        }
    }

    private val _deletedAlarmNotices = MutableStateFlow<List<DeletedAlarmSyncNotice>>(emptyList())
    val deletedAlarmNotices: StateFlow<List<DeletedAlarmSyncNotice>> = _deletedAlarmNotices.asStateFlow()

    // 8. Google Drive Shared Family Folders & Documents (starts empty with no predefined folders or sample files)
    private val _folders = MutableStateFlow<List<DocumentFolder>>(emptyList())
    val folders: StateFlow<List<DocumentFolder>> = _folders.asStateFlow()

    private val _documents = MutableStateFlow<List<FamilyDocument>>(emptyList())
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

    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    private val _fileSortOption = MutableStateFlow(FileSortOption.DATE)
    val fileSortOption: StateFlow<FileSortOption> = _fileSortOption.asStateFlow()

    private val _fileSortAscending = MutableStateFlow(false)
    val fileSortAscending: StateFlow<Boolean> = _fileSortAscending.asStateFlow()

    private val _fileSearchQuery = MutableStateFlow("")
    val fileSearchQuery: StateFlow<String> = _fileSearchQuery.asStateFlow()

    // Google Drive FamilyTasks Shared Folder State
    private val _familyDriveConfig = MutableStateFlow(FamilyDriveConfig(familyId = _activeFamilyId.value))
    val familyDriveConfig: StateFlow<FamilyDriveConfig> = _familyDriveConfig.asStateFlow()

    private val _driveConsentPendingIntent = MutableStateFlow<PendingIntent?>(null)
    val driveConsentPendingIntent: StateFlow<PendingIntent?> = _driveConsentPendingIntent.asStateFlow()

    private val _driveConsentIntent = MutableStateFlow<Intent?>(null)
    val driveConsentIntent: StateFlow<Intent?> = _driveConsentIntent.asStateFlow()

    private val _driveOperationMessage = MutableStateFlow<String?>(null)
    val driveOperationMessage: StateFlow<String?> = _driveOperationMessage.asStateFlow()

    private val _pendingDrivePickerRequest = MutableStateFlow<DrivePickerAuthorizationRequest?>(null)
    val pendingDrivePickerRequest: StateFlow<DrivePickerAuthorizationRequest?> = _pendingDrivePickerRequest.asStateFlow()

    private val _drivePickerVerificationError = MutableStateFlow<String?>(null)
    val drivePickerVerificationError: StateFlow<String?> = _drivePickerVerificationError.asStateFlow()

    fun clearDriveOperationMessage() {
        _driveOperationMessage.value = null
    }

    fun clearDriveConsentPrompts() {
        _driveConsentPendingIntent.value = null
        _driveConsentIntent.value = null
    }

    fun dismissDrivePickerAuthorizationDialog() {
        _pendingDrivePickerRequest.value = null
        _drivePickerVerificationError.value = null
    }

    private fun handleSharedFolderPickerException(
        err: GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException
    ) {
        val currentCfg = _familyDriveConfig.value
        val expectedRoot = err.expectedDriveRootFolderId.ifBlank { currentCfg.rootFolderId }
        val req = DrivePickerAuthorizationRequest(
            familyId = err.familyId.ifBlank { _activeFamilyId.value },
            expectedDriveRootFolderId = expectedRoot,
            expectedItemDriveId = err.requiredDriveItemId.ifBlank { expectedRoot },
            expectedItemName = err.requiredItemName.ifBlank { GOOGLE_DRIVE_ROOT_FOLDER_NAME },
            isRootFolderSelection = err.isRootFolder,
            ownerEmail = currentCfg.ownerEmail,
            reasonMessageHe = err.messageHe,
            reasonMessageEn = err.messageEn
        )
        _pendingDrivePickerRequest.value = req
        _drivePickerVerificationError.value = null
        _familyDriveConfig.value = currentCfg.copy(
            driveRootFolderId = expectedRoot.ifBlank { currentCfg.driveRootFolderId },
            connectionStatus = DriveConnectionStatus.NEEDS_SHARED_FOLDER_PICKER,
            errorMessageHe = err.messageHe,
            errorMessageEn = err.messageEn
        )
        _fileOperationError.value = err.messageHe
    }

    fun requestSharedFolderPickerAuthorization() {
        val cfg = _familyDriveConfig.value
        val expectedRoot = cfg.rootFolderId.trim()
            .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(_activeFamilyId.value).orEmpty() }
        if (expectedRoot.isBlank()) {
            _fileOperationError.value = "תיקיית המשפחה המשותפת ($GOOGLE_DRIVE_ROOT_FOLDER_NAME) טרם הוגדרה על ידי ההורה."
            return
        }
        _drivePickerVerificationError.value = null
        _pendingDrivePickerRequest.value = DrivePickerAuthorizationRequest(
            familyId = cfg.familyId.ifBlank { _activeFamilyId.value },
            expectedDriveRootFolderId = expectedRoot,
            expectedItemDriveId = expectedRoot,
            expectedItemName = GOOGLE_DRIVE_ROOT_FOLDER_NAME,
            isRootFolderSelection = true,
            ownerEmail = cfg.ownerEmail,
            reasonMessageHe = "יש לבחור ולאשר את תיקיית המשפחה המשותפת ($GOOGLE_DRIVE_ROOT_FOLDER_NAME) בבורר הקבצים של Google Drive.",
            reasonMessageEn = "Please select and authorize the shared family folder ($GOOGLE_DRIVE_ROOT_FOLDER_NAME) in Google Picker."
        )
    }

    fun verifyAndConnectPickedSharedFolder(
        pickedDriveId: String,
        pickedName: String = GOOGLE_DRIVE_ROOT_FOLDER_NAME,
        onComplete: ((Result<FamilyDriveConfig>) -> Unit)? = null
    ) {
        repoScope.launch {
            val res = verifyAndConnectPickedSharedFolderSuspend(
                pickedDriveId = pickedDriveId,
                pickedName = pickedName,
                targetFamilyId = _activeFamilyId.value
            )
            onComplete?.invoke(res)
        }
    }

    suspend fun verifyAndConnectPickedSharedFolderSuspend(
        pickedDriveId: String,
        pickedName: String = GOOGLE_DRIVE_ROOT_FOLDER_NAME,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDriveConfig> {
        val famId = targetFamilyId.ifBlank { _activeFamilyId.value }
        val currentCfg = _familyDriveConfig.value
        val pendingReq = _pendingDrivePickerRequest.value
        val expectedRootId = pendingReq?.expectedDriveRootFolderId
            ?.takeIf { it.isNotBlank() }
            ?: currentCfg.rootFolderId.trim()
                .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(famId).orEmpty() }
        val expectedItemId = pendingReq?.expectedItemDriveId
            ?.takeIf { it.isNotBlank() }
            ?: expectedRootId

        val verifyRes = GoogleDriveFamilyService.verifyAndAuthorizePickedDriveItem(
            familyId = famId,
            expectedDriveRootFolderId = expectedRootId,
            pickedDriveId = pickedDriveId,
            pickedName = pickedName,
            expectedDriveItemId = expectedItemId,
            callerUid = currentUid(),
            callerName = _currentMember.value.name
        )
        return verifyRes.fold(
            onSuccess = {
                _pendingDrivePickerRequest.value = null
                _drivePickerVerificationError.value = null
                _fileOperationError.value = null
                setupOrReconnectFamilyGoogleDriveSuspend(famId)
            },
            onFailure = { err ->
                val msgHe = err.localizedMessage
                    ?: "התיקייה שנבחרה אינה תואמת לתיקיית המשפחה המשותפת (FamilyTasks). יש לבחור את התיקייה המשותפת של ההורה בלבד."
                _drivePickerVerificationError.value = msgHe
                _fileOperationError.value = msgHe
                Result.failure(err)
            }
        )
    }

    init {
        restorePersistedFileNavigationState(_activeFamilyId.value)
        restorePersistedPointsState(_activeFamilyId.value, allowOverwritingMemberBalances = true)
        seedLocalRoomIfNeeded()
        observeAuthAndAttachFirestore()
        observeIncomingPushEvents()
    }

    private fun currentUid(): String {
        return authProvider()?.currentUser?.uid ?: _currentMember.value.id
    }

    private fun seedLocalRoomIfNeeded() {
        val dao = effectiveTaskDao ?: return
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
     * Automatically obtains the signed-in user's verified Google email from the active
     * authentication session (`FirebaseAuth.currentUser.email` / `GoogleAuthHelper` / `_googleUserEmail`)
     * so parents never have to type family members' email addresses manually when they are already available.
     */
    fun resolveVerifiedGoogleEmail(explicitEmail: String? = null): String {
        val candidates = listOfNotNull(
            explicitEmail?.trim()?.lowercase(),
            authProvider()?.currentUser?.email?.trim()?.lowercase(),
            _googleUserEmail.value.trim().lowercase(),
            runCatching { com.example.auth.GoogleAuthHelper.currentFirebaseUser?.email?.trim()?.lowercase() }.getOrNull(),
            _currentMember.value.googleEmail?.trim()?.lowercase()
        )
        return candidates.firstOrNull { it.isNotBlank() && it.contains("@") && it != "user@gmail.com" }
            ?: candidates.firstOrNull { it.isNotBlank() && it.contains("@") }
            ?: ""
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
                    val verifiedAuthEmail = resolveVerifiedGoogleEmail(email)
                    val resolvedEmail = verifiedAuthEmail.ifBlank {
                        memberSnap?.getString("googleEmail")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                            ?: memberSnap?.getString("email")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                            ?: userSnap.getString("email")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                            ?: email.trim().lowercase()
                    }
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

                    // 2. Safe V1.5 -> V1.6 member document migration + automatic verified Google email backfill
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
                        if (resolvedEmail.isNotBlank()) {
                            memberMissingFields["googleEmail"] = resolvedEmail
                            memberMissingFields["email"] = resolvedEmail
                        }
                        if (!resolvedPhotoUrl.isNullOrBlank()) memberMissingFields["photoUrl"] = resolvedPhotoUrl
                    } else {
                        if (!isCanonicalV16RoleString(memberRoleStr)) {
                            memberMissingFields["role"] = validatedRole.toFirestoreValue()
                        }
                        if (memberSnap?.getString("familyIdentity").isNullOrBlank()) {
                            memberMissingFields["familyIdentity"] = validatedIdentity.toFirestoreValue()
                        }
                        if (resolvedEmail.isNotBlank() && (
                            memberSnap?.getString("googleEmail")?.trim()?.lowercase() != resolvedEmail ||
                                memberSnap.getString("email")?.trim()?.lowercase() != resolvedEmail
                            )
                        ) {
                            memberMissingFields["googleEmail"] = resolvedEmail
                            memberMissingFields["email"] = resolvedEmail
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

                    // 3. Safe V1.5 -> V1.6 user profile migration + automatic verified Google email backfill
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
                        if (resolvedEmail.isNotBlank()) {
                            userMissingFields["email"] = resolvedEmail
                            userMissingFields["googleEmail"] = resolvedEmail
                        }
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
                        if (resolvedEmail.isNotBlank() && (
                            userSnap.getString("email")?.trim()?.lowercase() != resolvedEmail ||
                                userSnap.getString("googleEmail")?.trim()?.lowercase() != resolvedEmail
                            )
                        ) {
                            userMissingFields["email"] = resolvedEmail
                            userMissingFields["googleEmail"] = resolvedEmail
                        }
                    }
                    if (userMissingFields.isNotEmpty()) {
                        runCatching {
                            userDocRef.set(userMissingFields, SetOptions.merge()).await()
                        }
                    }

                    val resolvedAvatarId = memberSnap?.getString("avatarId")?.trim()?.takeIf { it.isNotBlank() }
                        ?: userSnap.getString("avatarId")?.trim()?.takeIf { it.isNotBlank() }
                        ?: ""
                    val resolvedAvatarOption = FamilyAvatarCatalog.findById(resolvedAvatarId)
                    val effectiveRestoredEmoji = resolvedAvatarOption?.emoji ?: resolvedEmoji

                    val restoredMember = FamilyMember(
                        id = uid,
                        name = persistedName,
                        nameEn = memberSnap?.getString("nameEn")?.takeIf { it.isNotBlank() } ?: persistedName,
                        avatarEmoji = effectiveRestoredEmoji,
                        avatarId = resolvedAvatarOption?.id ?: resolvedAvatarId,
                        role = validatedRole,
                        familyIdentity = validatedIdentity,
                        colorHex = resolvedAvatarOption?.primaryColorHex
                            ?: memberSnap?.getLong("colorHex")
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
                                val mAvatarId = doc.getString("avatarId")?.trim().orEmpty()
                                val mAvatarOption = FamilyAvatarCatalog.findById(mAvatarId)
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
                                    avatarEmoji = mAvatarOption?.emoji ?: mEmojiRaw ?: mIdentity.defaultEmoji,
                                    avatarId = mAvatarOption?.id ?: mAvatarId,
                                    role = mRole,
                                    familyIdentity = mIdentity,
                                    colorHex = mAvatarOption?.primaryColorHex
                                        ?: doc.getLong("colorHex")
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
                    if (db != null && authProvider()?.currentUser != null && loadedMembers.isNotEmpty()) {
                        syncedServerMemberBalancesForFamilies.add(existingFamilyId)
                        loadedMembers.forEach { member ->
                            persistMemberPointsBalanceLocally(existingFamilyId, member.id, member.earnedPoints)
                        }
                    }

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
                            resyncTaskRemindersForActiveMember()
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
        val email = resolveVerifiedGoogleEmail(creatorEmailOverride)
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
                    "email" to email,
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
                    "googleEmail" to email,
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
        _folders.value = emptyList()
        _selectedFolderId.value = null
        _documents.value = emptyList()
        _familyDriveConfig.value = FamilyDriveConfig(familyId = newFamilyId)
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
        val email = resolveVerifiedGoogleEmail(joiningEmailOverride)
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
                            "redeemedByEmail" to email,
                            "redeemedAt" to nowMillis
                        )
                    )

                    // 2. Create validated member document with exact invitedRole, familyIdentity, and verified Google email
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
                        "email" to email,
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
                    // 4. Save user's active familyId, validated role, familyIdentity, and verified Google email
                    val userDocData = mutableMapOf<String, Any>(
                        "uid" to uid,
                        "email" to email,
                        "googleEmail" to email,
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
        resyncAlarmsForActiveMember()
        resyncTaskRemindersForActiveMember()

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
        val isOpenForVolunteering = (data["isOpenForVolunteering"] as? Boolean)
            ?: (data["is_open_for_volunteering"] as? Boolean)
            ?: false
        val rawAssignedOrNull = (data["assignedUid"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assignedToMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assignedMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assigned_member_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["assigned_family_member"] as? String)?.takeIf { it.isNotBlank() }

        val resolvedAssignedId = if (rawAssignedOrNull == null) {
            if (isOpenForVolunteering) "" else "m_kid1"
        } else {
            _members.value.find { m ->
                m.id == rawAssignedOrNull ||
                    m.name.equals(rawAssignedOrNull, ignoreCase = true) ||
                    (!m.googleEmail.isNullOrBlank() && m.googleEmail.equals(rawAssignedOrNull, ignoreCase = true))
            }?.id ?: rawAssignedOrNull
        }

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
        val dueTime = (data["dueTimeFormatted"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["due_time"] as? String)?.takeIf { it.isNotBlank() }
            ?: "17:00"
        val reminderOffsetRaw = (data["reminderOffset"] as? String)
            ?: (data["reminder_offset"] as? String)
        val parsedReminderOffset = com.example.model.TaskReminderOffset.fromRaw(reminderOffsetRaw)
        val explicitHasDueTime = (data["hasDueTime"] as? Boolean)
            ?: (data["has_due_time"] as? Boolean)
            ?: (data["hasDueTime"] as? String)?.toBooleanStrictOrNull()
            ?: (data["has_due_time"] as? String)?.toBooleanStrictOrNull()
        val hasDueTime = (explicitHasDueTime ?: false) ||
            parsedReminderOffset != com.example.model.TaskReminderOffset.NONE
        val resolvedFamilyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["family_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: fallbackFamilyId
        val updatedAtMillis = extractTimestampMillis(data["updatedAt"])
            ?: extractTimestampMillis(data["updated_at"])
            ?: System.currentTimeMillis()
        val originalAssignedToMemberId = (data["originalAssignedToMemberId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["original_assigned_member_id"] as? String)?.takeIf { it.isNotBlank() }
        val originalAssignedMemberName = (data["originalAssignedMemberName"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["original_assigned_member_name"] as? String)?.takeIf { it.isNotBlank() }
        val transferProposalId = (data["transferProposalId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["transfer_proposal_id"] as? String)?.takeIf { it.isNotBlank() }
        val pendingProposalId = (data["pendingProposalId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (data["pending_proposal_id"] as? String)?.takeIf { it.isNotBlank() }

        return FamilyTask(
            id = docId,
            familyId = resolvedFamilyId,
            title = title,
            description = description,
            category = runCatching { TaskCategory.valueOf(categoryStr) }.getOrDefault(TaskCategory.OTHER),
            assignedToMemberId = resolvedAssignedId,
            isOpenForVolunteering = isOpenForVolunteering && resolvedAssignedId.isBlank(),
            dateEpochDay = epochDay,
            dueTimeFormatted = dueTime,
            hasDueTime = hasDueTime,
            reminderOffset = parsedReminderOffset,
            reminderEnabled = hasDueTime && parsedReminderOffset != com.example.model.TaskReminderOffset.NONE,
            isCompleted = completed,
            priority = runCatching { TaskPriority.valueOf(priorityStr) }.getOrDefault(TaskPriority.MEDIUM),
            recurrence = runCatching { TaskRecurrence.valueOf(recurrenceStr) }.getOrDefault(TaskRecurrence.ONCE),
            points = points,
            approvedByParent = approvedByParent,
            completedAtMillis = completedAtMillis,
            completedByMemberId = completedByMemberId,
            originalAssignedToMemberId = originalAssignedToMemberId,
            originalAssignedMemberName = originalAssignedMemberName,
            transferProposalId = transferProposalId,
            pendingProposalId = pendingProposalId,
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

        // 0. Real-time Family Document Listener (including Google Drive FamilyTasks folder mapping)
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
                val parsedDriveConfig = GoogleDriveFamilyService.parseDriveConfigFromFamilySnapshot(
                    familyId = familyId,
                    data = snapshot.data
                )
                if (parsedDriveConfig.isConfigured) {
                    _familyDriveConfig.value = parsedDriveConfig
                    persistedDriveConfigByFamily[familyId] = parsedDriveConfig
                }
                val familyDeletedAlarmIds = (snapshot.get("deletedAlarmIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                if (familyDeletedAlarmIds.isNotEmpty()) {
                    reconcileRemoteDeletedAlarmIds(familyId, familyDeletedAlarmIds)
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
                                    if (remoteTask.isUnassignedOpenTask) {
                                        val initOpenKey = "open_task:${remoteTask.id}"
                                        notifiedTaskCompletionKeys.add(initOpenKey)
                                        FcmTokenManager.markEventDispatchedIfNew(initOpenKey, appContext)
                                    }
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

                                // Event O: Parent published a new open task for volunteering -> notify children (deduplicated with FCM)
                                if (prev == null && remoteTask.isUnassignedOpenTask && !_currentMember.value.isParent) {
                                    val openKey = if (selfUid.isNotBlank()) {
                                        "open_task:${remoteTask.id}:$selfUid"
                                    } else {
                                        "open_task:${remoteTask.id}"
                                    }
                                    val isNewLocalKey = notifiedTaskCompletionKeys.add("open_task:${remoteTask.id}")
                                    val isNewGlobalFcmKey = FcmTokenManager.markEventDispatchedIfNew(openKey, appContext)
                                    if (isNewLocalKey && isNewGlobalFcmKey) {
                                        val title = "📢 משימה פתוחה להתנדבות!"
                                        val body = "משימה פתוחה חדשה: \"${remoteTask.title}\" (+${remoteTask.points} נק') — מי מתנדב?"
                                        dispatchTaskNotificationOrForegroundBanner(
                                            title = title,
                                            message = body,
                                            targetName = _currentMember.value.name.ifBlank { "ילדי המשפחה" },
                                            targetEmoji = "🙋",
                                            reason = AlertReason.TASK_REMINDER,
                                            shouldNotifyThisUser = true,
                                            eventKey = openKey,
                                            taskId = remoteTask.id,
                                            eventType = FcmTokenManager.EVENT_OPEN_TASK_CREATED,
                                            isOpenTaskAlert = true
                                        )
                                    }
                                }

                                // Event A: Parent assigned a new task to this user
                                if (prev == null && remoteTask.assignedToMemberId.isNotBlank() && remoteTask.assignedToMemberId == selfUid) {
                                    val assignKey = "assign:${remoteTask.id}:$selfUid"
                                    if (notifiedTaskCompletionKeys.add(assignKey) && FcmTokenManager.markEventDispatchedIfNew(assignKey, appContext)) {
                                        val title = "משימה חדשה שובצה עבורך 📋"
                                        val body = "שובצה לך משימה חדשה: \"${remoteTask.title}\" (שעה ${remoteTask.dueTimeFormatted} • +${remoteTask.points} נק')"
                                        dispatchTaskNotificationOrForegroundBanner(
                                            title = title,
                                            message = body,
                                            targetName = assigneeName,
                                            targetEmoji = assigneeEmoji,
                                            reason = AlertReason.TASK_REMINDER,
                                            shouldNotifyThisUser = true,
                                            eventKey = assignKey,
                                            taskId = remoteTask.id,
                                            eventType = FcmTokenManager.EVENT_TASK_ASSIGNED
                                        )
                                    }
                                }

                                // Event D: Task was reassigned (or open task was assigned) to this user
                                if (prev != null && prev.assignedToMemberId != remoteTask.assignedToMemberId && remoteTask.assignedToMemberId == selfUid) {
                                    val reassignKey = "reassign:${remoteTask.id}:$selfUid:${remoteTask.updatedAtMillis}"
                                    if (notifiedTaskCompletionKeys.add(reassignKey) && FcmTokenManager.markEventDispatchedIfNew(reassignKey, appContext)) {
                                        val title = "משימה הועברה אליך 🔄"
                                        val body = "המשימה \"${remoteTask.title}\" שובצה אליך (שעה ${remoteTask.dueTimeFormatted} • +${remoteTask.points} נק')"
                                        dispatchTaskNotificationOrForegroundBanner(
                                            title = title,
                                            message = body,
                                            targetName = assigneeName,
                                            targetEmoji = assigneeEmoji,
                                            reason = AlertReason.TASK_REMINDER,
                                            shouldNotifyThisUser = true,
                                            eventKey = reassignKey,
                                            taskId = remoteTask.id,
                                            eventType = FcmTokenManager.EVENT_TASK_REASSIGNED
                                        )
                                    }
                                }

                                // Event B & C: Real-time cross-device completion / parent approval notification ONLY for server-committed writes
                                if (remoteTask.isCompleted && (prev == null || !prev.isCompleted)) {
                                    val completedByOther = !remoteTask.completedByMemberId.isNullOrBlank() &&
                                        remoteTask.completedByMemberId != selfUid
                                    if (completedByOther && notifiedTaskCompletionKeys.add("${remoteTask.id}:completed")) {
                                        if (remoteTask.approvedByParent) {
                                            val approveKey = "approve:${remoteTask.id}"
                                            if (FcmTokenManager.markEventDispatchedIfNew(approveKey, appContext)) {
                                                val title = "משימה הושלמה ואושרה! +${remoteTask.points} נק' 🎉"
                                                val body = "$assigneeName השלים/ה את המשימה: ${remoteTask.title}"
                                                dispatchTaskNotificationOrForegroundBanner(
                                                    title = title,
                                                    message = body,
                                                    targetName = assigneeName,
                                                    targetEmoji = assigneeEmoji,
                                                    reason = AlertReason.TASK_COMPLETED,
                                                    shouldNotifyThisUser = true,
                                                    shouldPostOsNotificationWhenBackground = remoteTask.assignedToMemberId == selfUid,
                                                    eventKey = approveKey,
                                                    taskId = remoteTask.id,
                                                    eventType = FcmTokenManager.EVENT_TASK_APPROVED
                                                )
                                            }
                                        } else {
                                            val submitKey = "submit:${remoteTask.id}:${remoteTask.completedAtMillis ?: remoteTask.updatedAtMillis}"
                                            if (FcmTokenManager.markEventDispatchedIfNew(submitKey, appContext)) {
                                                val title = "משימה הושלמה וממתינה לאישור הורה ⏳"
                                                val body = "$assigneeName סימן/ה כבוצע: ${remoteTask.title} (+${remoteTask.points} נק')"
                                                dispatchTaskNotificationOrForegroundBanner(
                                                    title = title,
                                                    message = body,
                                                    targetName = assigneeName,
                                                    targetEmoji = assigneeEmoji,
                                                    reason = AlertReason.TASK_COMPLETED,
                                                    shouldNotifyThisUser = _currentMember.value.isParent,
                                                    eventKey = submitKey,
                                                    taskId = remoteTask.id,
                                                    eventType = FcmTokenManager.EVENT_TASK_SUBMITTED_FOR_APPROVAL
                                                )
                                            }
                                        }
                                    }
                                } else if (remoteTask.isCompleted && remoteTask.approvedByParent && prev != null && !prev.approvedByParent) {
                                    // Event C: Parent approved a previously submitted task -> notify the assigned child
                                    val approveKey = "approve:${remoteTask.id}"
                                    if (notifiedTaskCompletionKeys.add("${remoteTask.id}:approved") && FcmTokenManager.markEventDispatchedIfNew(approveKey, appContext)) {
                                        val title = "המשימה שלך אושרה! +${remoteTask.points} נק' 🎉"
                                        val body = "כל הכבוד $assigneeName! ביצוע המשימה \"${remoteTask.title}\" אושר וקיבלת +${remoteTask.points} נקודות!"
                                        dispatchTaskNotificationOrForegroundBanner(
                                            title = title,
                                            message = body,
                                            targetName = assigneeName,
                                            targetEmoji = assigneeEmoji,
                                            reason = AlertReason.TASK_COMPLETED,
                                            shouldNotifyThisUser = remoteTask.assignedToMemberId == selfUid,
                                            eventKey = approveKey,
                                            taskId = remoteTask.id,
                                            eventType = FcmTokenManager.EVENT_TASK_APPROVED
                                        )
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
                        reconcileActiveBannerAgainstTasks(remoteTasks)
                        resyncTaskRemindersForActiveMember()
                        repoScope.launch {
                            runCatching {
                                effectiveTaskDao?.upsertTasks(remoteTasks.map { it.toCoreTask(familyId) })
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
                        val rawAvatarId = if (doc.contains("avatarId")) {
                            doc.getString("avatarId")?.trim().orEmpty()
                        } else {
                            existingLocalMember?.avatarId.orEmpty()
                        }
                        val resolvedAvatarOption = FamilyAvatarCatalog.findById(rawAvatarId)
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
                        val emoji = resolvedAvatarOption?.emoji ?: rawEmoji ?: parsedIdentity.defaultEmoji
                        val signedInUidForMember = authProvider()?.currentUser?.uid
                        val rawStoredEmail = (doc.getString("googleEmail") ?: doc.getString("email"))
                            ?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it.contains("@") }
                        val email = rawStoredEmail
                            ?: if (doc.id == signedInUidForMember) {
                                resolveVerifiedGoogleEmail().takeIf { it.isNotBlank() }?.also { verifiedSelfEmail ->
                                    // Automatically backfill the signed-in member's verified Google email
                                    // so parents never have to type it manually.
                                    repoScope.launch {
                                        runCatching {
                                            doc.reference.set(
                                                mapOf(
                                                    "googleEmail" to verifiedSelfEmail,
                                                    "email" to verifiedSelfEmail
                                                ),
                                                SetOptions.merge()
                                            ).await()
                                        }
                                    }
                                }
                            } else {
                                null
                            }
                        val photoUrl = doc.getString("photoUrl")
                        val colorHex = resolvedAvatarOption?.primaryColorHex
                            ?: doc.getLong("colorHex")
                            ?: if (parsedRole == UserRole.PARENT) 0xFF1A73E8L else 0xFF34A853L

                        val cancelledAlarmIds = (doc.get("cancelledAlarmIds") as? List<*>)
                            ?.mapNotNull { it as? String }
                            ?.filter { it.isNotBlank() }
                            ?.toSet()
                            .orEmpty()
                        if (cancelledAlarmIds.isNotEmpty()) {
                            confirmedCancelledAlarmIdsByMember[doc.id] = cancelledAlarmIds
                            _deletedAlarmNotices.value = _deletedAlarmNotices.value.map { notice ->
                                if (!notice.isConfirmedCancelledOnTarget && notice.alarmId in cancelledAlarmIds) {
                                    notice.copy(
                                        isConfirmedCancelledOnTarget = true,
                                        confirmedAtMillis = System.currentTimeMillis()
                                    )
                                } else {
                                    notice
                                }
                            }
                        }

                        val targetRewardId = if (doc.contains("targetRewardId")) {
                            doc.getString("targetRewardId")?.trim()?.takeIf { it.isNotBlank() }
                        } else {
                            existingLocalMember?.targetRewardId
                        }

                        FamilyMember(
                            id = doc.id,
                            name = name,
                            nameEn = doc.getString("nameEn") ?: name,
                            avatarEmoji = emoji,
                            avatarId = resolvedAvatarOption?.id ?: rawAvatarId,
                            role = parsedRole,
                            familyIdentity = parsedIdentity,
                            colorHex = colorHex,
                            googleEmail = email,
                            photoUrl = photoUrl,
                            schoolGrade = doc.getString("schoolGrade"),
                            earnedPoints = points,
                            targetRewardId = targetRewardId,
                            invitationPending = false,
                            invitationAccepted = true,
                            inviteToken = doc.getString("inviteCode") ?: doc.getString("inviteToken"),
                            invitedByMemberId = doc.getString("invitedByMemberId")
                        )
                    }

                    if (remoteMembers.isNotEmpty()) {
                        _members.value = remoteMembers
                        syncedServerMemberBalancesForFamilies.add(familyId)
                        remoteMembers.forEach { member ->
                            persistMemberPointsBalanceLocally(familyId, member.id, member.earnedPoints)
                            persistMemberTargetRewardLocally(familyId, member.id, member.targetRewardId)
                            persistMemberAvatarLocally(familyId, member.id, member.avatarId)
                        }
                        val signedInUid = authProvider()?.currentUser?.uid
                        val updatedSelf = remoteMembers.find {
                            it.id == signedInUid || it.id == _currentMember.value.id
                        }
                        if (updatedSelf != null) {
                            val previousMemberId = _currentMember.value.id
                            val previousRole = _currentMember.value.role
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
                            if (previousMemberId != updatedSelf.id || previousRole != updatedSelf.role || _familyAlarms.value.isNotEmpty()) {
                                resyncAlarmsForActiveMember()
                            }
                            if (previousMemberId != updatedSelf.id || _tasks.value.isNotEmpty()) {
                                resyncTaskRemindersForActiveMember()
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
                        val isDeleted = doc.getBoolean("isDeleted") ?: false
                        if (isDeleted) return@mapNotNull null
                        val title = doc.getString("title") ?: return@mapNotNull null
                        val cost = (doc.getLong("pointsCost") ?: 50L).toInt().coerceAtLeast(1)
                        val emoji = doc.getString("iconEmoji") ?: "🎁"
                        val desc = doc.getString("description") ?: ""
                        val isActive = doc.getBoolean("isActive") ?: true
                        val createdAt = doc.getLong("createdAtMillis") ?: 0L
                        val updatedAt = doc.getLong("updatedAtMillis") ?: createdAt
                        RewardItem(
                            id = doc.id,
                            familyId = familyId,
                            title = title,
                            pointsCost = cost,
                            iconEmoji = emoji,
                            description = desc,
                            isActive = isActive,
                            isDeleted = false,
                            createdAtMillis = createdAt,
                            updatedAtMillis = updatedAt
                        )
                    }
                    if (remoteRewards.isNotEmpty() || snapshot.documents.isNotEmpty()) {
                        _rewards.value = remoteRewards
                    }
                }
        }

        // 5. Real-time Shared Family Folders Listener (/families/{familyId}/folders)
        restorePersistedFileNavigationState(familyId)
        runCatching {
            foldersListenerRegistration = familyRef.collection("folders")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteFolders = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        FamilyCloudFileTransferEngine.parseFolderDocument(doc.id, data, familyId)
                    }.sortedBy { it.createdAtMillis }
                    _folders.value = remoteFolders
                    persistedFoldersByFamily[familyId] = remoteFolders
                    if (_selectedFolderId.value != null && remoteFolders.none { it.id == _selectedFolderId.value }) {
                        _selectedFolderId.value = null
                    }
                    if (remoteFolders.any {
                            GoogleDriveFamilyService.isKnownAutoGeneratedDefaultFolder(
                                folderId = it.id,
                                driveFolderId = it.driveFolderId,
                                driveRootFolderId = _familyDriveConfig.value.driveRootFolderId,
                                createdByUid = it.createdByUid
                            )
                        }
                    ) {
                        repoScope.launch {
                            cleanupEmptyAutoGeneratedDefaultFolders(familyId)
                        }
                    }
                }
        }

        // 6. Real-time Shared Family Files Listener (/families/{familyId}/files)
        runCatching {
            var hasProcessedInitialFilesSnapshot = false
            filesListenerRegistration = familyRef.collection("files")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val previousIds = _documents.value.map { it.id }.toSet()
                    val remoteFiles = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        FamilyCloudFileTransferEngine.parseFileDocument(doc.id, data, familyId)
                    }.sortedByDescending { it.uploadedAtMillis }

                    if (remoteFiles.isNotEmpty() || _isGoogleSignedIn.value || familyId != "fam_israeli_8492") {
                        if (hasProcessedInitialFilesSnapshot) {
                            val selfUid = currentUid()
                            for (rf in remoteFiles) {
                                if (rf.id !in previousIds && rf.uploadedByUid.isNotBlank() && rf.uploadedByUid != selfUid) {
                                    pushAlert(
                                        title = "קובץ משפחתי חדש הועלה 📁",
                                        message = "${rf.name} הועלה ע\"י ${rf.uploadedByName.ifBlank { "חבר משפחה" }}",
                                        targetName = rf.uploadedByName.ifBlank { "משפחה" },
                                        targetEmoji = "📄",
                                        reason = AlertReason.DRIVE_SYNC,
                                        showBanner = true
                                    )
                                }
                            }
                        }
                        hasProcessedInitialFilesSnapshot = true
                        _documents.value = remoteFiles
                        persistedDocumentsByFamily[familyId] = remoteFiles
                    }
                }
        }

        // 7. Real-time School Timetable Listener (/families/{familyId}/schedule)
        runCatching {
            familyRef.collection("schedule")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remotePeriods = snapshot.documents.mapNotNull { doc ->
                        val subjectName = doc.getString("subjectName") ?: return@mapNotNull null
                        val childId = doc.getString("childId").orEmpty()
                        val dayStr = doc.getString("day") ?: SchoolDay.SUNDAY.name
                        val day = runCatching { SchoolDay.valueOf(dayStr) }.getOrDefault(SchoolDay.SUNDAY)
                        val periodIndex = (doc.getLong("periodIndex") ?: 1L).toInt().coerceIn(1, 10)
                        val startTime = doc.getString("startTime") ?: "08:00"
                        val endTime = doc.getString("endTime") ?: "08:45"
                        val roomNumber = doc.getString("roomNumber").orEmpty()
                        val teacherName = doc.getString("teacherName").orEmpty()
                        val colorHex = doc.getLong("colorHex") ?: 0xFF1A73E8L
                        val iconEmoji = doc.getString("iconEmoji") ?: "📚"
                        val reminderMins = (doc.getLong("reminderMinutesBefore") ?: 10L).toInt()
                        val isAlertEnabled = doc.getBoolean("isAlertEnabled") ?: true
                        val equipmentNotes = doc.getString("equipmentNotes").orEmpty()
                        SchedulePeriod(
                            id = doc.id,
                            childId = childId,
                            day = day,
                            periodIndex = periodIndex,
                            subjectName = subjectName,
                            subjectNameEn = doc.getString("subjectNameEn") ?: subjectName,
                            roomNumber = roomNumber,
                            teacherName = teacherName,
                            startTime = startTime,
                            endTime = endTime,
                            colorHex = colorHex,
                            iconEmoji = iconEmoji,
                            reminderMinutesBefore = reminderMins,
                            isAlertEnabled = isAlertEnabled,
                            equipmentNotes = equipmentNotes
                        )
                    }.sortedWith(compareBy({ it.day.index }, { it.periodIndex }))
                    if (remotePeriods.isNotEmpty() || _isGoogleSignedIn.value) {
                        _schedulePeriods.value = remotePeriods
                    }
                }
        }

        // 8. Real-time Shared Family Alarms Listener (/families/{familyId}/alarms)
        runCatching {
            alarmsListenerRegistration?.remove()
            alarmsListenerRegistration = familyRef.collection("alarms")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val currentMember = _currentMember.value
                    val currentMemberId = currentMember.id
                    val authUid = authProvider()?.currentUser?.uid.orEmpty()
                    val effectiveDeviceMemberId = authUid.ifBlank { currentMemberId }
                    val deviceModelLabel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
                    val ctx = appContext

                    // 1. Detect explicitly removed documents from Firestore snapshot diff
                    val detectedDeletedIds = mutableSetOf<String>()
                    runCatching {
                        snapshot.documentChanges.forEach { change ->
                            if (change.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                                val removedId = change.document.id
                                if (removedId.isNotBlank()) {
                                    detectedDeletedIds.add(removedId)
                                }
                            }
                        }
                    }

                    val persistedLocalDeletedIds = if (ctx != null) {
                        com.example.notifications.FamilyAlarmScheduler.getPersistedDeletedAlarmIds(ctx)
                    } else {
                        emptySet()
                    }
                    explicitlyDeletedAlarmIds.addAll(persistedLocalDeletedIds)

                    val remoteAlarms = snapshot.documents.mapNotNull { doc ->
                        val isDocDeleted = (doc.getBoolean("isDeleted") ?: false) ||
                            doc.getString("scheduleStatus") == FamilyAlarm.STATUS_DELETED
                        if (isDocDeleted) {
                            detectedDeletedIds.add(doc.id)
                            return@mapNotNull null
                        }
                        if (doc.id in explicitlyDeletedAlarmIds || doc.id in detectedDeletedIds) {
                            return@mapNotNull null
                        }
                        val title = doc.getString("title") ?: return@mapNotNull null
                        val hour = (doc.getLong("hour") ?: 7L).toInt().coerceIn(0, 23)
                        val minute = (doc.getLong("minute") ?: 0L).toInt().coerceIn(0, 59)
                        val assignedToId = doc.getString("assignedToMemberId")
                            ?.takeIf { it.isNotBlank() }
                            ?: doc.getString("assignedMemberId").orEmpty()
                        val assignedMemberName = doc.getString("assignedMemberName").orEmpty()
                        val rawAssignedIds = (doc.get("assignedMemberIds") as? List<*>)
                            ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() } }
                            .orEmpty()
                        val rawAssignedNames = (doc.get("assignedMemberNames") as? List<*>)
                            ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() } }
                            .orEmpty()
                        val effectiveIds = if (rawAssignedIds.isNotEmpty()) {
                            rawAssignedIds.distinct()
                        } else if (assignedToId.isNotBlank()) {
                            listOf(assignedToId)
                        } else {
                            emptyList()
                        }
                        val effectiveNames = if (rawAssignedNames.isNotEmpty()) {
                            rawAssignedNames
                        } else if (assignedMemberName.isNotBlank()) {
                            assignedMemberName.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        } else {
                            emptyList()
                        }
                        val createdById = doc.getString("createdByMemberId").orEmpty()
                        val createdByName = doc.getString("createdByMemberName").orEmpty()
                        val rawCreatedByRole = doc.getString("createdByRole").orEmpty()
                        val migratedCreatedByRole = rawCreatedByRole.ifBlank {
                            val creatorMember = _members.value.find { it.id == createdById }
                            creatorMember?.role?.toFirestoreValue().orEmpty()
                        }
                        val rawDays = (doc.get("daysOfWeek") as? List<*>)?.mapNotNull { d ->
                            (d as? String)?.let { runCatching { SchoolDay.valueOf(it) }.getOrNull() }
                        }?.ifEmpty { SchoolDay.allWeekDays } ?: SchoolDay.allWeekDays
                        val isEnabled = doc.getBoolean("isEnabled") ?: true
                        val emoji = doc.getString("ringtoneEmoji") ?: "⏰"
                        FamilyAlarm(
                            id = doc.id,
                            familyId = familyId,
                            title = title,
                            hour = hour,
                            minute = minute,
                            assignedMemberId = effectiveIds.firstOrNull() ?: assignedToId,
                            assignedToMemberId = effectiveIds.firstOrNull() ?: assignedToId,
                            assignedMemberName = if (effectiveNames.isNotEmpty()) effectiveNames.joinToString(", ") else assignedMemberName,
                            assignedMemberIds = effectiveIds,
                            assignedMemberNames = effectiveNames,
                            daysOfWeek = rawDays,
                            isEnabled = isEnabled,
                            ringtoneEmoji = emoji,
                            createdAtMillis = doc.getLong("createdAtMillis") ?: 0L,
                            createdByMemberId = createdById,
                            createdByMemberName = createdByName,
                            createdByRole = migratedCreatedByRole,
                            isPersonal = doc.getBoolean("isPersonal") ?: (effectiveIds.size == 1 && effectiveIds.first() == createdById),
                            soundType = doc.getString("soundType") ?: "MELODY_ENERGETIC",
                            customSoundUri = doc.getString("customSoundUri").orEmpty(),
                            customSoundTitle = doc.getString("customSoundTitle").orEmpty(),
                            vibrateEnabled = doc.getBoolean("vibrateEnabled") ?: true,
                            snoozeDurationMinutes = (doc.getLong("snoozeDurationMinutes") ?: 5L).toInt().coerceIn(1, 60),
                            volumePercent = (doc.getLong("volumePercent") ?: 85L).toInt().coerceIn(10, 100),
                            scheduleStatus = doc.getString("scheduleStatus") ?: if (isEnabled) FamilyAlarm.STATUS_SCHEDULED_LOCALLY else FamilyAlarm.STATUS_DISABLED,
                            acknowledgedByMemberId = doc.getString("acknowledgedByMemberId").orEmpty(),
                            acknowledgedByDeviceName = doc.getString("acknowledgedByDeviceName").orEmpty(),
                            acknowledgedAtMillis = doc.getLong("acknowledgedAtMillis") ?: 0L,
                            nextTriggerAtMillis = doc.getLong("nextTriggerAtMillis") ?: 0L,
                            lastScheduleError = doc.getString("lastScheduleError").orEmpty()
                        )
                    }

                    val remoteIds = remoteAlarms.map { it.id }.toSet()

                    // 2. Any alarm ID that was previously observed in Firestore `remoteAlarms` (or persisted on disk from a previous session)
                    //    and is now absent from `remoteIds` (and not a brand-new unpushed local write) was deleted in Firestore!
                    synchronized(knownRemoteAlarmIds) {
                        knownRemoteAlarmIds.forEach { prevRemoteId ->
                            if (prevRemoteId !in remoteIds) {
                                detectedDeletedIds.add(prevRemoteId)
                            }
                        }
                        knownRemoteAlarmIds.clear()
                        knownRemoteAlarmIds.addAll(remoteIds)
                    }

                    if (ctx != null && !snapshot.metadata.isFromCache) {
                        val current = _currentMember.value
                        val authUid = authProvider()?.currentUser?.uid.orEmpty()
                        val persistedOnDisk = com.example.notifications.FamilyAlarmScheduler.loadPersistedAlarms(ctx)
                        persistedOnDisk.forEach { diskAlarm ->
                            val isCreatedByThisDeviceMember = diskAlarm.createdByMemberId.isBlank() ||
                                diskAlarm.createdByMemberId == current.id ||
                                (authUid.isNotBlank() && diskAlarm.createdByMemberId == authUid) ||
                                diskAlarm.isPersonal
                            if (
                                diskAlarm.id !in remoteIds &&
                                diskAlarm.id !in locallyManagedAlarms.keys &&
                                !isCreatedByThisDeviceMember
                            ) {
                                detectedDeletedIds.add(diskAlarm.id)
                            }
                        }
                    }

                    // Once an alarm is observed in Firestore `remoteIds`, it is no longer a pending local-only write
                    remoteIds.forEach { syncedId ->
                        locallyManagedAlarms.remove(syncedId)
                    }
                    // Also remove starter demo alarms once connected to Firestore
                    locallyManagedAlarms.remove("alarm_wakeup")
                    locallyManagedAlarms.remove("alarm_school_bus")

                    if (detectedDeletedIds.isNotEmpty()) {
                        reconcileRemoteDeletedAlarmIds(familyId, detectedDeletedIds)
                    }

                    // Merge ONLY brand-new locally created/modified alarms whose Firestore write is still in flight
                    val pendingLocalOnly = locallyManagedAlarms.values.filter { local ->
                        local.id !in remoteIds &&
                            local.id !in explicitlyDeletedAlarmIds &&
                            local.id !in detectedDeletedIds &&
                            (local.familyId.isBlank() || local.familyId == familyId)
                    }
                    val mergedAlarms = (remoteAlarms + pendingLocalOnly)
                        .sortedWith(compareBy({ it.hour }, { it.minute }))

                    val syncedAlarms = if (ctx != null) {
                        val localResults = com.example.notifications.FamilyAlarmScheduler.syncAlarmsForCurrentDevice(
                            context = ctx,
                            alarms = mergedAlarms,
                            currentMemberId = currentMemberId,
                            currentAuthUid = authUid,
                            currentMemberName = currentMember.name,
                            isChildDevice = !currentMember.isParent,
                            authoritativeRemoteSync = true
                        )
                        mergedAlarms.map { alarm ->
                            val localRes = localResults[alarm.id]
                            if (localRes != null && localRes.isScheduled) {
                                val needsAckWrite = alarm.scheduleStatus != localRes.statusCode ||
                                    alarm.acknowledgedByMemberId != effectiveDeviceMemberId ||
                                    (alarm.isTargetedToAnotherMember() && !alarm.isTargetDeviceAcknowledged())
                                val updatedAlarm = alarm.copy(
                                    scheduleStatus = localRes.statusCode,
                                    acknowledgedByMemberId = effectiveDeviceMemberId.ifBlank { alarm.acknowledgedByMemberId },
                                    acknowledgedByDeviceName = deviceModelLabel,
                                    acknowledgedAtMillis = if (alarm.acknowledgedAtMillis > 0L && !needsAckWrite) {
                                        alarm.acknowledgedAtMillis
                                    } else {
                                        System.currentTimeMillis()
                                    },
                                    nextTriggerAtMillis = localRes.nextTriggerAtMillis,
                                    lastScheduleError = localRes.errorMessage
                                )
                                // IMPORTANT: Do NOT put remote alarms back into locallyManagedAlarms here,
                                // otherwise deleting the alarm from another device would resurrect it from locallyManagedAlarms!
                                if (needsAckWrite) {
                                    acknowledgeAlarmOnTargetDeviceInFirestore(familyId, updatedAlarm)
                                }
                                updatedAlarm
                            } else {
                                alarm
                            }
                        }
                    } else {
                        mergedAlarms
                    }

                    allFamilyAlarms = syncedAlarms
                    refreshVisibleFamilyAlarms()
                }
        }

        // 9. Real-time Family Points History Listener (/families/{familyId}/points_history)
        restorePersistedPointsState(
            familyId = familyId,
            allowOverwritingMemberBalances = !syncedServerMemberBalancesForFamilies.contains(familyId)
        )
        runCatching {
            pointsHistoryListenerRegistration?.remove()
            pointsHistoryListenerRegistration = familyRef.collection("points_history")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteEntries = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        parsePointsHistoryDocument(doc.id, data, familyId)
                    }
                    mergeRemotePointsHistory(familyId, remoteEntries)
                }
        }

        // 10. Real-time Task Collaboration Proposals Listener (/families/{familyId}/task_proposals)
        runCatching {
            var hasProcessedInitialProposalsSnapshot = false
            taskProposalsListenerRegistration?.remove()
            taskProposalsListenerRegistration = familyRef.collection("task_proposals")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteProposals = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        parseTaskProposalDocument(doc.id, data, familyId)
                    }.sortedByDescending { it.updatedAtMillis }

                    val prevById = _taskProposals.value.associateBy { it.id }
                    val selfUid = currentUid()
                    val current = _currentMember.value

                    for (prop in remoteProposals) {
                        val statusEventKey = "prop:${prop.id}:${prop.status.name}"
                        if (!hasProcessedInitialProposalsSnapshot) {
                            notifiedCollaborationEventKeys.add(statusEventKey)
                        } else if (notifiedCollaborationEventKeys.add(statusEventKey)) {
                            val prev = prevById[prop.id]
                            val isSelfTarget = prop.targetChildId == current.id || (selfUid.isNotBlank() && prop.targetChildId == selfUid)
                            val isSelfRequester = prop.requesterChildId == current.id || (selfUid.isNotBlank() && prop.requesterChildId == selfUid)

                            when (prop.status) {
                                TaskProposalStatus.PENDING_CHILD_APPROVAL -> {
                                    if (prev == null && isSelfTarget) {
                                        val title = if (prop.type == TaskProposalType.SWAP_TASKS) {
                                            "הצעה חדשה להחלפת משימות 🔁"
                                        } else {
                                            "הצעה חדשה לביצוע משימה 🙋"
                                        }
                                        val msg = if (prop.type == TaskProposalType.SWAP_TASKS) {
                                            "${prop.requesterChildName} מציע/ה להחליף את \"${prop.offeredSwapTaskTitle}\" בתמורה ל-\"${prop.primaryTaskTitle}\""
                                        } else {
                                            "${prop.requesterChildName} מציע/ה לבצע במקומך את המשימה \"${prop.primaryTaskTitle}\""
                                        }
                                        pushAlert(
                                            title = title,
                                            message = msg,
                                            targetName = prop.targetChildName,
                                            targetEmoji = "📬",
                                            reason = AlertReason.TASK_REMINDER,
                                            showBanner = true
                                        )
                                    }
                                }
                                TaskProposalStatus.PENDING_PARENT_APPROVAL -> {
                                    if (current.isParent) {
                                        val msg = if (prop.isOpenTaskVolunteer) {
                                            "${prop.requesterChildName} מתנדב/ת לבצע את המשימה הפתוחה \"${prop.primaryTaskTitle}\" (+${prop.primaryTaskPoints} נק')"
                                        } else {
                                            "${prop.requesterChildName} ו-${prop.targetChildName} הסכימו על ${prop.type.labelHe} עבור \"${prop.primaryTaskTitle}\""
                                        }
                                        pushAlert(
                                            title = if (prop.isOpenTaskVolunteer) "🙋 התנדבות חדשה למשימה פתוחה" else "בקשת משימה ממתינה לאישור הורה ⏳",
                                            message = msg,
                                            targetName = current.name,
                                            targetEmoji = "👨‍👩‍👧‍👦",
                                            reason = AlertReason.TASK_REMINDER,
                                            showBanner = true
                                        )
                                    }
                                }
                                TaskProposalStatus.APPROVED_BY_PARENT -> {
                                    if (isSelfRequester || isSelfTarget) {
                                        pushAlert(
                                            title = "הבקשה אושרה ע\"י הורה! ✅",
                                            message = "${prop.type.labelHe} עבור \"${prop.primaryTaskTitle}\" אושרה ע\"י ${prop.parentDecidedByName ?: "הורה"}",
                                            targetName = current.name,
                                            targetEmoji = "✅",
                                            reason = AlertReason.TASK_COMPLETED,
                                            showBanner = true
                                        )
                                    }
                                }
                                TaskProposalStatus.REJECTED_BY_CHILD, TaskProposalStatus.REJECTED_BY_PARENT -> {
                                    if (isSelfRequester || isSelfTarget) {
                                        pushAlert(
                                            title = "הבקשה נדחתה ❌",
                                            message = "${prop.type.labelHe} עבור \"${prop.primaryTaskTitle}\" (${prop.status.labelHe})",
                                            targetName = current.name,
                                            targetEmoji = "❌",
                                            reason = AlertReason.TASK_REMINDER,
                                            showBanner = true
                                        )
                                    }
                                }
                                TaskProposalStatus.CLOSED_NOT_SELECTED,
                                TaskProposalStatus.CANCELLED -> Unit
                            }
                        }
                    }
                    hasProcessedInitialProposalsSnapshot = true
                    _taskProposals.value = remoteProposals
                }
        }

        // 11. Real-time Reward Redemptions Listener (/families/{familyId}/redemptions)
        runCatching {
            var hasProcessedInitialRedemptionsSnapshot = false
            redemptionsListenerRegistration?.remove()
            redemptionsListenerRegistration = familyRef.collection("redemptions")
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null) return@addSnapshotListener
                    val remoteRedemptions = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        parseRewardRedemptionDocument(doc.id, data, familyId)
                    }.sortedByDescending { it.requestedAtMillis }

                    val selfUid = currentUid()
                    val current = _currentMember.value
                    for (red in remoteRedemptions) {
                        val eventKey = "red:${red.id}:${red.status.name}"
                        if (!hasProcessedInitialRedemptionsSnapshot) {
                            notifiedCollaborationEventKeys.add(eventKey)
                        } else if (notifiedCollaborationEventKeys.add(eventKey)) {
                            val isSelfChild = red.childUid == current.id || (selfUid.isNotBlank() && red.childUid == selfUid)
                            when (red.status) {
                                RedemptionStatus.PENDING_PARENT_APPROVAL -> {
                                    if (current.isParent) {
                                        pushAlert(
                                            title = "בקשה חדשה למימוש פרס 🎁",
                                            message = "${red.childName} מבקש/ת לממש: \"${red.rewardTitle}\" (${red.pointsCost} נק')",
                                            targetName = red.childName,
                                            targetEmoji = red.rewardIconEmoji,
                                            reason = AlertReason.TASK_REMINDER,
                                            showBanner = true
                                        )
                                    }
                                }
                                RedemptionStatus.APPROVED -> {
                                    if (isSelfChild) {
                                        pushAlert(
                                            title = "בקשת המימוש אושרה! 🎉",
                                            message = "ההורה אישר את מימוש הפרס \"${red.rewardTitle}\" (-${red.pointsCost} נק')",
                                            targetName = red.childName,
                                            targetEmoji = red.rewardIconEmoji,
                                            reason = AlertReason.TASK_COMPLETED,
                                            showBanner = true
                                        )
                                    }
                                }
                                RedemptionStatus.REJECTED -> {
                                    if (isSelfChild) {
                                        pushAlert(
                                            title = "בקשת המימוש נדחתה ❌",
                                            message = "בקשת המימוש עבור \"${red.rewardTitle}\" נדחתה. הנקודות לא הופחתו.",
                                            targetName = red.childName,
                                            targetEmoji = red.rewardIconEmoji,
                                            reason = AlertReason.TASK_REMINDER,
                                            showBanner = true
                                        )
                                    }
                                }
                                RedemptionStatus.CANCELLED -> Unit
                            }
                        }
                    }
                    hasProcessedInitialRedemptionsSnapshot = true
                    _rewardRedemptions.value = remoteRedemptions
                }
        }
    }

    fun detachFirestoreListeners() {
        tasksListenerRegistration?.remove()
        membersListenerRegistration?.remove()
        rewardsListenerRegistration?.remove()
        invitesListenerRegistration?.remove()
        familyDocListenerRegistration?.remove()
        foldersListenerRegistration?.remove()
        filesListenerRegistration?.remove()
        alarmsListenerRegistration?.remove()
        pointsHistoryListenerRegistration?.remove()
        taskProposalsListenerRegistration?.remove()
        redemptionsListenerRegistration?.remove()
        tasksListenerRegistration = null
        membersListenerRegistration = null
        rewardsListenerRegistration = null
        invitesListenerRegistration = null
        familyDocListenerRegistration = null
        foldersListenerRegistration = null
        filesListenerRegistration = null
        alarmsListenerRegistration = null
        pointsHistoryListenerRegistration = null
        taskProposalsListenerRegistration = null
        redemptionsListenerRegistration = null
    }

    private suspend fun flushOfflineSyncQueue(familyId: String) {
        val syncDao = effectiveOfflineSyncDao ?: return
        val db = firestoreProvider() ?: return
        runCatching {
            val pending = syncDao.getPendingActions()
            for (item in pending) {
                val targetFamId = item.familyId.ifBlank { familyId }
                if (item.actionType == "DELETE_TASK") {
                    db.collection("families").document(targetFamId)
                        .collection("tasks").document(item.taskId).delete().await()
                } else if (item.actionType == "UPSERT_TASK") {
                    val taskToSync = _tasks.value.find { it.id == item.taskId }
                        ?: effectiveTaskDao?.getTaskById(item.taskId)?.toFamilyTask()
                    if (taskToSync != null && _currentMember.value.isParent) {
                        val payload = buildFullTaskFirestoreMap(taskToSync, targetFamId)
                        db.collection("families").document(targetFamId)
                            .collection("tasks").document(item.taskId)
                            .set(payload, SetOptions.merge()).await()
                    }
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
        val effectiveHasDueTime = task.hasDueTime || task.reminderOffset != TaskReminderOffset.NONE
        val effectiveReminderOffset = if (effectiveHasDueTime) task.reminderOffset else TaskReminderOffset.NONE
        val actorUid = authProvider()?.currentUser?.uid?.takeIf { it.isNotBlank() } ?: _currentMember.value.id
        return mapOf(
            "id" to task.id,
            "familyId" to familyId,
            "userId" to actorUid,
            "title" to task.title,
            "description" to task.description,
            "assignedUid" to task.assignedToMemberId,
            "assignedToMemberId" to task.assignedToMemberId,
            "assignedMemberId" to task.assignedToMemberId,
            "isOpenForVolunteering" to task.isOpenForVolunteering,
            "is_open_for_volunteering" to task.isOpenForVolunteering,
            "points" to task.points,
            "pointsValue" to task.points,
            "isCompleted" to task.isCompleted,
            "approvedByParent" to task.approvedByParent,
            "completedAtMillis" to task.completedAtMillis,
            "completedByMemberId" to task.completedByMemberId,
            "originalAssignedToMemberId" to task.originalAssignedToMemberId,
            "original_assigned_member_id" to task.originalAssignedToMemberId,
            "originalAssignedMemberName" to task.originalAssignedMemberName,
            "original_assigned_member_name" to task.originalAssignedMemberName,
            "transferProposalId" to task.transferProposalId,
            "transfer_proposal_id" to task.transferProposalId,
            "pendingProposalId" to task.pendingProposalId,
            "pending_proposal_id" to task.pendingProposalId,
            "dateEpochDay" to task.dateEpochDay,
            "dueDate" to (task.dateEpochDay * 86_400_000L),
            "category" to task.category.name,
            "priority" to task.priority.name,
            "recurrence" to task.recurrence.name,
            "dueTimeFormatted" to task.dueTimeFormatted,
            "due_time" to task.dueTimeFormatted,
            "hasDueTime" to effectiveHasDueTime,
            "has_due_time" to effectiveHasDueTime,
            "reminderOffset" to effectiveReminderOffset.name,
            "reminder_offset" to effectiveReminderOffset.name,
            "reminderEnabled" to (effectiveHasDueTime && effectiveReminderOffset != TaskReminderOffset.NONE),
            "updatedAt" to nowMillis
        )
    }

    private fun pushTaskToFirestoreOrQueue(task: FamilyTask, isDelete: Boolean = false) {
        val familyId = task.familyId.ifBlank { _activeFamilyId.value }
        val coreTask = task.toCoreTask(familyId)

        repoScope.launch {
            runCatching {
                if (isDelete) {
                    effectiveTaskDao?.deleteTaskById(task.id)
                } else {
                    effectiveTaskDao?.upsertTask(coreTask)
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
                    effectiveOfflineSyncDao?.enqueueAction(
                        OfflineSyncQueueEntity(
                            taskId = task.id,
                            familyId = familyId,
                            actionType = if (isDelete) "DELETE_TASK" else "UPSERT_TASK",
                            payloadJson = "${task.title}|${task.points}|${task.isCompleted}|${task.dateEpochDay}|${task.dueTimeFormatted}|${task.hasDueTime}|${task.reminderOffset.name}"
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
        refreshVisibleFamilyAlarms()
        resyncAlarmsForActiveMember()
        resyncTaskRemindersForActiveMember()
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

        val newFamilyIdentity = if (canonicalNewRole == UserRole.PARENT) {
            if (targetMember.familyIdentity.isParentIdentity) targetMember.familyIdentity else FamilyIdentity.FATHER
        } else {
            if (!targetMember.familyIdentity.isParentIdentity) targetMember.familyIdentity else FamilyIdentity.CHILD
        }

        _members.value = _members.value.map {
            if (it.id == memberId) it.copy(role = canonicalNewRole, familyIdentity = newFamilyIdentity) else it
        }
        if (_currentMember.value.id == memberId) {
            _currentMember.value = _currentMember.value.copy(role = canonicalNewRole, familyIdentity = newFamilyIdentity)
        }

        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null) {
            val familyId = _activeFamilyId.value
            repoScope.launch {
                runCatching {
                    val familyRef = db.collection("families").document(familyId)
                    val memberRef = familyRef.collection("members").document(memberId)
                    val batch = db.batch()
                    batch.update(
                        memberRef,
                        mapOf(
                            "role" to canonicalNewRole.toFirestoreValue(),
                            "familyIdentity" to newFamilyIdentity.toFirestoreValue(),
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
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

    private suspend fun resolveTaskFromStateOrFirestore(taskId: String): FamilyTask? {
        _tasks.value.find { it.id == taskId }?.let { return it }
        val db = firestoreProvider() ?: return null
        val familyId = _activeFamilyId.value.ifBlank { return null }
        val snap = runCatching {
            db.collection("families").document(familyId)
                .collection("tasks").document(taskId)
                .get().await()
        }.getOrNull() ?: return null
        val data = snap.data ?: return null
        val parsed = parseTaskDocument(snap.id, data, familyId) ?: return null
        if (_tasks.value.none { it.id == parsed.id }) {
            _tasks.value = listOf(parsed) + _tasks.value
        }
        return parsed
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
        val targetTask = resolveTaskFromStateOrFirestore(taskId)
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

        val newStatus = !targetTask.isCompleted

        // When a PARENT marks an uncompleted task as completed, execute the atomic single-transaction
        // approval + points credit + points_history write so duplicate points can never occur.
        if (current.isParent && newStatus) {
            return commitParentTaskApprovalTransactionAsync(
                taskId = taskId,
                writeStage = FirestoreErrorHandler.TaskStage.PARENT_COMPLETE_TASK_WRITE,
                isParentApprovalEvent = false
            )
        }

        val previousTask = targetTask
        val nowMillis = System.currentTimeMillis()
        val actorId = uid.ifBlank { current.id }
        val updatedTask = targetTask.copy(
            familyId = resolvedFamilyId,
            isCompleted = newStatus,
            approvedByParent = false,
            completedAtMillis = if (newStatus) nowMillis else null,
            completedByMemberId = if (newStatus) actorId else null,
            updatedAtMillis = nowMillis
        )

        _taskOperationError.value = null
        _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVING)
        _tasks.value = _tasks.value.map { if (it.id == taskId) updatedTask else it }

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
            _tasks.value = _tasks.value.map { if (it.id == taskId) previousTask else it }
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
                    // PARENT uncompleting a task: updates task completion state without altering earnedPoints or clearing rewardedOperationId
                    val parentUncompleteMap = mapOf<String, Any?>(
                        "isCompleted" to false,
                        "approvedByParent" to false,
                        "completedAtMillis" to null,
                        "completedByMemberId" to null,
                        "updatedAt" to nowMillis
                    )
                    try {
                        docRef.update(parentUncompleteMap).await()
                    } catch (_: Exception) {
                        docRef.set(buildFullTaskFirestoreMap(updatedTask, resolvedFamilyId, nowMillis), SetOptions.merge()).await()
                    }
                }

                FirestoreErrorHandler.logTaskMilestone(
                    stage = writeStage,
                    path = taskPath,
                    databaseId = configuredDatabaseId,
                    note = "Confirmed task completion commit in Firestore"
                )
            } catch (e: Exception) {
                _tasks.value = _tasks.value.map { if (it.id == taskId) previousTask else it }
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

        val finalSyncState = when {
            !updatedTask.isCompleted -> TaskCompletionSyncState.IDLE
            updatedTask.approvedByParent -> TaskCompletionSyncState.SAVED_APPROVED
            else -> TaskCompletionSyncState.PENDING_APPROVAL
        }
        _taskSyncStates.value = _taskSyncStates.value + (taskId to finalSyncState)
        _tasks.value = _tasks.value.map { if (it.id == taskId) updatedTask else it }
        resyncTaskRemindersForActiveMember()

        repoScope.launch {
            runCatching {
                effectiveTaskDao?.upsertTask(updatedTask.toCoreTask(resolvedFamilyId))
            }
        }

        emitTaskCompletionNotificationIfNeeded(updatedTask, isParentApprovalEvent = false)
        return Result.success(updatedTask)
    }

    /**
     * When a recurring task ([TaskRecurrence.isRecurring]) is completed and approved, automatically
     * generates and persists the next occurrence for its next scheduled date if one does not already exist.
     */
    private fun spawnNextRecurringOccurrenceIfNeeded(completedTask: FamilyTask) {
        if (!completedTask.recurrence.isRecurring) return
        val nextEpochDay = completedTask.recurrence.nextOccurrenceEpochDay(completedTask.dateEpochDay) ?: return
        val alreadyExists = _tasks.value.any { existing ->
            !existing.isCompleted &&
                existing.title == completedTask.title &&
                existing.assignedToMemberId == completedTask.assignedToMemberId &&
                existing.dateEpochDay == nextEpochDay
        }
        if (alreadyExists) return

        val nextTask = completedTask.copy(
            id = "t_${UUID.randomUUID().toString().take(8)}",
            dateEpochDay = nextEpochDay,
            isCompleted = false,
            approvedByParent = false,
            completedAtMillis = null,
            completedByMemberId = null,
            updatedAtMillis = System.currentTimeMillis()
        )
        _tasks.value = listOf(nextTask) + _tasks.value
        pushTaskToFirestoreOrQueue(nextTask, isDelete = false)
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
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
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

        repoScope.launch {
            toggleTaskCompletionAsync(taskId)
        }
        return true
    }

    /**
     * V1.6 Requirement 6 & 9 + Points Audit Fix:
     * Allows an authenticated PARENT to approve a child's completed task (`approvedByParent = true`),
     * executing task approval, member points credit, and points_history creation inside a single
     * atomic Firestore transaction (`db.runTransaction`) with deterministic operation ID `pts_task_{taskId}`.
     * Prevents duplicate point credits even under concurrent approval by two parents.
     */
    suspend fun approveTaskCompletionAsync(taskId: String): Result<FamilyTask> {
        val current = _currentMember.value
        val targetTask = resolveTaskFromStateOrFirestore(taskId)
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

        return commitParentTaskApprovalTransactionAsync(
            taskId = taskId,
            writeStage = FirestoreErrorHandler.TaskStage.PARENT_APPROVE_TASK_WRITE,
            isParentApprovalEvent = true
        )
    }

    private data class AtomicTaskApprovalTxOutcome(
        val approvedTask: FamilyTask,
        val wasAlreadyApprovedOrRewarded: Boolean,
        val historyEntry: PointsHistoryEntry?,
        val updatedBalance: Int?
    )

    private suspend fun commitParentTaskApprovalTransactionAsync(
        taskId: String,
        writeStage: FirestoreErrorHandler.TaskStage,
        isParentApprovalEvent: Boolean
    ): Result<FamilyTask> = pointsMutex.withLock {
        val current = _currentMember.value
        val targetTask = resolveTaskFromStateOrFirestore(taskId)
            ?: return@withLock Result.failure(IllegalArgumentException("המשימה לא נמצאה"))
        val resolvedFamilyId = targetTask.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
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
            return@withLock Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified))
        }

        val previousTask = targetTask
        val previousMembers = _members.value
        val previousCurrentMember = _currentMember.value
        val nowMillis = System.currentTimeMillis()
        val uid = currentUid()
        val actorId = uid.ifBlank { current.id }
        val taskOpId = "pts_task_$taskId"

        val wasAlreadyApprovedLocally = (targetTask.isCompleted && targetTask.approvedByParent) ||
            processedPointsOperationIds.contains(taskOpId) ||
            _pointsHistory.value.any {
                it.operationId == taskOpId ||
                    it.id == taskOpId ||
                    ((it.actionType == PointsActionType.TASK_EARNED || it.actionType == PointsActionType.TASK_SPLIT_EARNED) && it.taskId == taskId)
            }

        val completedBy = if (isParentApprovalEvent) {
            targetTask.completedByMemberId ?: targetTask.assignedToMemberId.ifBlank { actorId }
        } else {
            actorId
        }

        val approvedTask = targetTask.copy(
            familyId = resolvedFamilyId,
            isCompleted = true,
            approvedByParent = true,
            completedAtMillis = targetTask.completedAtMillis ?: nowMillis,
            completedByMemberId = completedBy,
            updatedAtMillis = nowMillis
        )

        _taskOperationError.value = null
        _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVING)

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
                stage = writeStage,
                path = taskPath,
                databaseId = configuredDatabaseId
            )
            lastClassifiedTaskFailure = classified
            _taskOperationError.value = classified.messageHe
            return@withLock Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified, offlineErr))
        }

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        val outcome: AtomicTaskApprovalTxOutcome = if (db != null && authUser != null) {
            try {
                FirestoreErrorHandler.logTaskMilestone(
                    stage = writeStage,
                    path = taskPath,
                    databaseId = configuredDatabaseId,
                    note = "Executing atomic Firestore transaction for task approval & points credit (opId=$taskOpId)"
                )
                val familyRef = db.collection("families").document(resolvedFamilyId)
                val taskDocRef = familyRef.collection("tasks").document(approvedTask.id)
                val historyDocRef = familyRef.collection("points_history").document(taskOpId)

                db.runTransaction { tx ->
                    // 1. All reads first
                    val taskSnap = tx.get(taskDocRef)
                    val existingHistSnap = tx.get(historyDocRef)

                    val assigneeId = (taskSnap.getString("assignedToMemberId")
                        ?: taskSnap.getString("assignedUid")
                        ?: taskSnap.getString("assignedMemberId")
                        ?: approvedTask.assignedToMemberId).trim()
                    val taskPoints = (taskSnap.getLong("points")
                        ?: taskSnap.getLong("pointsValue")
                        ?: approvedTask.points.toLong()).toInt().coerceAtLeast(0)
                    val taskTitle = taskSnap.getString("title")?.takeIf { it.isNotBlank() } ?: approvedTask.title

                    val memberRef = if (assigneeId.isNotBlank()) {
                        familyRef.collection("members").document(assigneeId)
                    } else {
                        null
                    }
                    val memberSnap = if (memberRef != null) tx.get(memberRef) else null

                    val cloudIsCompleted = taskSnap.getBoolean("isCompleted") ?: false
                    val cloudApprovedByParent = taskSnap.getBoolean("approvedByParent") ?: false
                    val cloudRewardedOpId = taskSnap.getString("rewardedOperationId").orEmpty()
                    val alreadyRewarded = existingHistSnap.exists() ||
                        (cloudIsCompleted && cloudApprovedByParent) ||
                        cloudRewardedOpId.isNotBlank() ||
                        wasAlreadyApprovedLocally

                    // 2. Write task approval state
                    if (taskSnap.exists()) {
                        val taskUpdateMap = mutableMapOf<String, Any?>(
                            "isCompleted" to true,
                            "approvedByParent" to true,
                            "completedAtMillis" to approvedTask.completedAtMillis,
                            "completedByMemberId" to (approvedTask.completedByMemberId ?: authUser.uid),
                            "updatedAt" to nowMillis
                        )
                        if (taskPoints > 0 && assigneeId.isNotBlank() && cloudRewardedOpId.isBlank()) {
                            taskUpdateMap["rewardedOperationId"] = taskOpId
                        }
                        tx.update(taskDocRef, taskUpdateMap)
                    } else {
                        val fullMap = buildFullTaskFirestoreMap(approvedTask, resolvedFamilyId, nowMillis).toMutableMap()
                        if (taskPoints > 0 && assigneeId.isNotBlank()) {
                            fullMap["rewardedOperationId"] = taskOpId
                        }
                        tx.set(taskDocRef, fullMap, SetOptions.merge())
                    }

                    // 3. Atomically credit member points & create points_history entry ONLY if not already rewarded
                    if (!alreadyRewarded && taskPoints > 0 && assigneeId.isNotBlank() && memberRef != null) {
                        val localAssignee = _members.value.find { it.id == assigneeId }
                        val previousBalance = if (memberSnap != null && memberSnap.exists()) {
                            (memberSnap.getLong("earnedPoints") ?: (localAssignee?.earnedPoints ?: 0).toLong()).toInt().coerceAtLeast(0)
                        } else {
                            (localAssignee?.earnedPoints ?: 0).coerceAtLeast(0)
                        }
                        val newBalance = previousBalance + taskPoints
                        val resolvedMemberName = memberSnap?.getString("name")?.takeIf { it.isNotBlank() }
                            ?: localAssignee?.name.orEmpty()

                        if (memberSnap != null && memberSnap.exists()) {
                            tx.update(
                                memberRef,
                                mapOf(
                                    "earnedPoints" to newBalance,
                                    "lastPointsOperationId" to taskOpId,
                                    "updatedAt" to nowMillis
                                )
                            )
                        } else if (localAssignee != null) {
                            tx.set(
                                memberRef,
                                mapOf(
                                    "id" to localAssignee.id,
                                    "uid" to localAssignee.id,
                                    "familyId" to resolvedFamilyId,
                                    "name" to localAssignee.name,
                                    "nameEn" to localAssignee.nameEn,
                                    "role" to localAssignee.role.toFirestoreValue(),
                                    "familyIdentity" to localAssignee.familyIdentity.toFirestoreValue(),
                                    "avatarEmoji" to localAssignee.avatarEmoji,
                                    "colorHex" to localAssignee.colorHex,
                                    "earnedPoints" to newBalance,
                                    "spentPoints" to localAssignee.spentPoints,
                                    "lastPointsOperationId" to taskOpId,
                                    "invitationAccepted" to true,
                                    "invitationPending" to false,
                                    "updatedAt" to nowMillis
                                ),
                                SetOptions.merge()
                            )
                        }

                        val origId = approvedTask.originalAssignedToMemberId?.takeIf { it.isNotBlank() }
                        val origName = approvedTask.originalAssignedMemberName?.takeIf { it.isNotBlank() }
                            ?: origId?.let { id -> _members.value.find { it.id == id }?.name }
                        val reason = if (!origName.isNullOrBlank() && origId != assigneeId) {
                            "השלמת משימה: $taskTitle (בוצע במקום $origName)"
                        } else {
                            "השלמת משימה: $taskTitle"
                        }
                        val performedByName = current.name.ifBlank { "הורה" }
                        val historyMap = mutableMapOf<String, Any?>(
                            "id" to taskOpId,
                            "operationId" to taskOpId,
                            "familyId" to resolvedFamilyId,
                            "memberId" to assigneeId,
                            "memberName" to resolvedMemberName,
                            "actionType" to PointsActionType.TASK_EARNED.firestoreValue,
                            "pointsDelta" to taskPoints,
                            "previousBalance" to previousBalance,
                            "newBalance" to newBalance,
                            "reason" to reason,
                            "performedByUid" to authUser.uid,
                            "performedByName" to performedByName,
                            "timestampMillis" to nowMillis,
                            "taskId" to approvedTask.id,
                            "performedTaskByMemberId" to assigneeId,
                            "performedTaskByMemberName" to resolvedMemberName,
                            "serverTimestamp" to FieldValue.serverTimestamp()
                        )
                        if (!origId.isNullOrBlank()) {
                            historyMap["relatedMemberId"] = origId
                        }
                        if (!origName.isNullOrBlank()) {
                            historyMap["relatedMemberName"] = origName
                        }
                        if (!approvedTask.transferProposalId.isNullOrBlank()) {
                            historyMap["proposalId"] = approvedTask.transferProposalId
                        }
                        tx.set(historyDocRef, historyMap)

                        val createdEntry = PointsHistoryEntry(
                            id = taskOpId,
                            operationId = taskOpId,
                            familyId = resolvedFamilyId,
                            memberId = assigneeId,
                            memberName = resolvedMemberName,
                            actionType = PointsActionType.TASK_EARNED,
                            pointsDelta = taskPoints,
                            previousBalance = previousBalance,
                            newBalance = newBalance,
                            reason = reason,
                            performedByUid = authUser.uid,
                            performedByName = performedByName,
                            timestampMillis = nowMillis,
                            taskId = approvedTask.id,
                            performedTaskByMemberId = assigneeId,
                            performedTaskByMemberName = resolvedMemberName,
                            relatedMemberId = origId,
                            relatedMemberName = origName,
                            proposalId = approvedTask.transferProposalId
                        )
                        AtomicTaskApprovalTxOutcome(
                            approvedTask = approvedTask.copy(assignedToMemberId = assigneeId, points = taskPoints),
                            wasAlreadyApprovedOrRewarded = false,
                            historyEntry = createdEntry,
                            updatedBalance = newBalance
                        )
                    } else {
                        val existingCloudPts = if (memberSnap != null && memberSnap.exists()) {
                            (memberSnap.getLong("earnedPoints") ?: 0L).toInt().coerceAtLeast(0)
                        } else {
                            null
                        }
                        AtomicTaskApprovalTxOutcome(
                            approvedTask = approvedTask,
                            wasAlreadyApprovedOrRewarded = true,
                            historyEntry = null,
                            updatedBalance = existingCloudPts
                        )
                    }
                }.await()
            } catch (e: Exception) {
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
                return@withLock Result.failure(FirestoreErrorHandler.TaskFirestoreException(classified, e))
            }
        } else {
            // Local / offline / Robolectric execution path (serialized inside pointsMutex)
            if (!wasAlreadyApprovedLocally && approvedTask.points > 0 && approvedTask.assignedToMemberId.isNotBlank()) {
                val localAssignee = _members.value.find { it.id == approvedTask.assignedToMemberId }
                val previousBalance = (localAssignee?.earnedPoints ?: 0).coerceAtLeast(0)
                val newBalance = previousBalance + approvedTask.points
                val origId = approvedTask.originalAssignedToMemberId?.takeIf { it.isNotBlank() }
                val origName = approvedTask.originalAssignedMemberName?.takeIf { it.isNotBlank() }
                    ?: origId?.let { id -> _members.value.find { it.id == id }?.name }
                val reason = if (!origName.isNullOrBlank() && origId != approvedTask.assignedToMemberId) {
                    "השלמת משימה: ${approvedTask.title} (בוצע במקום $origName)"
                } else {
                    "השלמת משימה: ${approvedTask.title}"
                }
                val performedByName = current.name.ifBlank { "הורה" }
                val entry = PointsHistoryEntry(
                    id = taskOpId,
                    operationId = taskOpId,
                    familyId = resolvedFamilyId,
                    memberId = approvedTask.assignedToMemberId,
                    memberName = localAssignee?.name.orEmpty(),
                    actionType = PointsActionType.TASK_EARNED,
                    pointsDelta = approvedTask.points,
                    previousBalance = previousBalance,
                    newBalance = newBalance,
                    reason = reason,
                    performedByUid = actorId,
                    performedByName = performedByName,
                    timestampMillis = nowMillis,
                    taskId = approvedTask.id,
                    performedTaskByMemberId = approvedTask.assignedToMemberId,
                    performedTaskByMemberName = localAssignee?.name.orEmpty(),
                    relatedMemberId = origId,
                    relatedMemberName = origName,
                    proposalId = approvedTask.transferProposalId
                )
                AtomicTaskApprovalTxOutcome(
                    approvedTask = approvedTask,
                    wasAlreadyApprovedOrRewarded = false,
                    historyEntry = entry,
                    updatedBalance = newBalance
                )
            } else {
                AtomicTaskApprovalTxOutcome(
                    approvedTask = approvedTask,
                    wasAlreadyApprovedOrRewarded = true,
                    historyEntry = null,
                    updatedBalance = null
                )
            }
        }

        // Apply committed state atomically in memory & local persistence
        val finalApprovedTask = outcome.approvedTask
        _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVED_APPROVED)
        _tasks.value = _tasks.value.map { if (it.id == taskId) finalApprovedTask else it }
        resyncTaskRemindersForActiveMember()

        if (outcome.updatedBalance != null && finalApprovedTask.assignedToMemberId.isNotBlank()) {
            val newBal = outcome.updatedBalance
            _members.value = _members.value.map { m ->
                if (m.id == finalApprovedTask.assignedToMemberId) {
                    val updated = m.copy(earnedPoints = newBal)
                    if (_currentMember.value.id == m.id) {
                        _currentMember.value = updated
                    }
                    updated
                } else {
                    m
                }
            }
            persistMemberPointsBalanceLocally(resolvedFamilyId, finalApprovedTask.assignedToMemberId, newBal)
        }

        if (outcome.historyEntry != null) {
            appendPointsHistoryEntryLocally(resolvedFamilyId, outcome.historyEntry)
        }

        if (!outcome.wasAlreadyApprovedOrRewarded) {
            spawnNextRecurringOccurrenceIfNeeded(finalApprovedTask)
        }

        repoScope.launch {
            runCatching {
                effectiveTaskDao?.upsertTask(finalApprovedTask.toCoreTask(resolvedFamilyId))
            }
        }

        emitTaskCompletionNotificationIfNeeded(finalApprovedTask, isParentApprovalEvent = isParentApprovalEvent)
        return@withLock Result.success(finalApprovedTask)
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
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
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
     * Synchronizes all local `AlarmManager` task due-time reminders for the active member on this device.
     */
    fun resyncTaskRemindersForActiveMember() {
        val ctx = appContext ?: return
        val current = _currentMember.value
        val authUid = authProvider()?.currentUser?.uid.orEmpty()
        val isVerifiedActiveMember = _membershipState.value is FamilyMembershipState.ActiveMember
        val effectiveMemberId = if (!isVerifiedActiveMember && authUid.isNotBlank() && current.id == "m_dad") {
            authUid
        } else {
            current.id
        }
        val effectiveMemberName = if (!isVerifiedActiveMember && authUid.isNotBlank() && current.id == "m_dad") {
            _googleUserDisplayName.value
        } else {
            current.name
        }
        com.example.notifications.TaskReminderScheduler.syncTaskRemindersForCurrentDevice(
            context = ctx,
            tasks = _tasks.value,
            currentMemberId = effectiveMemberId,
            currentAuthUid = authUid,
            currentMemberName = effectiveMemberName
        )
    }

    /**
     * V1.6 Requirement 1 + Part C Open Tasks:
     * Only a PARENT can create tasks (both assigned tasks and open tasks for volunteering).
     * When an open task (`isOpenForVolunteering == true`) is created, all active children in the family
     * are notified using the `FamilyTasks Signature Pulse` notification channel/sound (respecting personal sound preferences)
     * without duplicate alerts.
     */
    fun addTask(task: FamilyTask): Boolean {
        if (!_currentMember.value.isParent) {
            Log.w(TAG, "Blocked CHILD from creating a task")
            _taskOperationError.value = "רק הורה רשאי ליצור משימות חדשות או משימות פתוחות להתנדבות 🔒"
            return false
        }
        val normalizedTask = if (task.isOpenForVolunteering) {
            task.copy(assignedToMemberId = "", isOpenForVolunteering = true)
        } else {
            task
        }
        _tasks.value = listOf(normalizedTask) + _tasks.value.filterNot { it.id == normalizedTask.id }
        resyncTaskRemindersForActiveMember()

        if (normalizedTask.isUnassignedOpenTask) {
            val openEventKey = "open_task:${normalizedTask.id}"
            if (notifiedTaskCompletionKeys.add(openEventKey)) {
                val children = _members.value.filter { !it.isParent && it.invitationAccepted && !it.invitationPending }
                val targetLabel = if (children.isNotEmpty()) {
                    children.joinToString(", ") { it.name }
                } else {
                    "ילדי המשפחה"
                }
                val title = "📢 משימה פתוחה להתנדבות!"
                val body = "משימה פתוחה חדשה: \"${normalizedTask.title}\" (+${normalizedTask.points} נק') — מי מתנדב?"
                pushAlert(
                    title = title,
                    message = "$body (נשלח אל: $targetLabel)",
                    targetName = targetLabel,
                    targetEmoji = "🙋",
                    reason = AlertReason.TASK_REMINDER,
                    showBanner = true,
                    taskId = normalizedTask.id,
                    eventKey = openEventKey,
                    isOpenTaskAlert = true
                )
                // Record on the creating parent's device so an echoed snapshot does not trigger a self-notification.
                // Child devices receive the OS notification via onFamilyTaskWritten (when closed/background)
                // or the realtime tasks snapshot listener (when open), deduplicated via FcmTokenManager.
                val db = firestoreProvider()
                val authUser = authProvider()?.currentUser
                if (db != null && authUser != null) {
                    FcmTokenManager.markEventDispatchedIfNew(openEventKey, appContext)
                }
            }
        } else {
            val assignee = _members.value.find { it.id == normalizedTask.assignedToMemberId }
            if (assignee != null) {
                pushAlert(
                    title = "משימה חדשה נוספה ליומן 📅",
                    message = "ל-${assignee.name}: ${normalizedTask.title} (שעה ${normalizedTask.dueTimeFormatted})",
                    targetName = assignee.name,
                    targetEmoji = assignee.displayAvatarEmoji,
                    reason = AlertReason.TASK_REMINDER,
                    taskId = normalizedTask.id
                )
            }
        }
        pushTaskToFirestoreOrQueue(normalizedTask, isDelete = false)
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
        reconcileActiveBannerAgainstTasks(_tasks.value)
        resyncTaskRemindersForActiveMember()
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
        appContext?.let { ctx ->
            com.example.notifications.TaskReminderScheduler.cancelReminderForTask(ctx, taskId, clearActiveNotification = true)
        }
        resyncTaskRemindersForActiveMember()
        if (target != null) {
            reconcileActiveBannerAgainstTasks(_tasks.value)
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
            targetEmoji = assignee.displayAvatarEmoji,
            reason = AlertReason.PARENT_NUDGE,
            showBanner = true,
            taskId = task.id
        )
    }

    // --- Rewards Store Operations ---

    fun redeemReward(reward: RewardItem): Boolean {
        return requestRewardRedemption(reward.id)
    }

    // --- School Timetable Operations (PARENT only for mutations, synced to Firestore /families/{familyId}/schedule) ---

    fun addSchedulePeriod(period: SchedulePeriod): Boolean {
        if (!_currentMember.value.isParent) return false
        _schedulePeriods.value = (_schedulePeriods.value + period)
            .sortedWith(compareBy({ it.day.index }, { it.periodIndex }))
        persistSchedulePeriodInFirestore(period, isDelete = false)
        return true
    }

    fun updateSchedulePeriod(period: SchedulePeriod): Boolean {
        if (!_currentMember.value.isParent) return false
        _schedulePeriods.value = _schedulePeriods.value.map {
            if (it.id == period.id) period else it
        }.sortedWith(compareBy({ it.day.index }, { it.periodIndex }))
        persistSchedulePeriodInFirestore(period, isDelete = false)
        return true
    }

    fun deleteSchedulePeriod(periodId: String): Boolean {
        if (!_currentMember.value.isParent) return false
        val target = _schedulePeriods.value.find { it.id == periodId }
        _schedulePeriods.value = _schedulePeriods.value.filterNot { it.id == periodId }
        if (target != null) {
            persistSchedulePeriodInFirestore(target, isDelete = true)
        }
        return true
    }

    private fun persistSchedulePeriodInFirestore(period: SchedulePeriod, isDelete: Boolean) {
        val db = firestoreProvider() ?: return
        val authUser = authProvider()?.currentUser ?: return
        val familyId = _activeFamilyId.value.takeIf { it.isNotBlank() } ?: return
        repoScope.launch {
            runCatching {
                val docRef = db.collection("families").document(familyId)
                    .collection("schedule").document(period.id)
                if (isDelete) {
                    docRef.delete().await()
                } else {
                    val map = mapOf(
                        "id" to period.id,
                        "familyId" to familyId,
                        "childId" to period.childId,
                        "day" to period.day.name,
                        "periodIndex" to period.periodIndex,
                        "startTime" to period.startTime,
                        "endTime" to period.endTime,
                        "subjectName" to period.subjectName,
                        "subjectNameEn" to period.subjectNameEn,
                        "roomNumber" to period.roomNumber,
                        "teacherName" to period.teacherName,
                        "colorHex" to period.colorHex,
                        "iconEmoji" to period.iconEmoji,
                        "reminderMinutesBefore" to period.reminderMinutesBefore,
                        "isAlertEnabled" to period.isAlertEnabled,
                        "equipmentNotes" to period.equipmentNotes,
                        "updatedByUid" to authUser.uid,
                        "updatedAt" to System.currentTimeMillis()
                    )
                    docRef.set(map, SetOptions.merge()).await()
                }
            }
        }
    }

    fun triggerTenMinuteClassAlertPreview(period: SchedulePeriod, childName: String) {
        val child = _members.value.find { it.id == period.childId }
        pushAlert(
            title = "התראת שיעור בעוד ${period.reminderMinutesBefore} דקות! 🔔",
            message = "$childName: השיעור \"${period.subjectName}\" מתחיל ב-${period.startTime} (${period.roomNumber})",
            targetName = childName,
            targetEmoji = child?.avatarEmoji ?: "🎒",
            reason = AlertReason.UPCOMING_CLASS
        )
    }

    // --- Shared Family Alarm Clock Operations (/families/{familyId}/alarms) ---

    fun resyncAlarmsForActiveMember() {
        refreshVisibleFamilyAlarms()
        val ctx = appContext ?: return
        val current = _currentMember.value
        val authUid = authProvider()?.currentUser?.uid.orEmpty()
        val effectiveDeviceMemberId = authUid.ifBlank { current.id }
        val deviceModelLabel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val familyId = _activeFamilyId.value

        val persistedDeleted = com.example.notifications.FamilyAlarmScheduler.getPersistedDeletedAlarmIds(ctx)
        explicitlyDeletedAlarmIds.addAll(persistedDeleted)

        val currentAlarms = allFamilyAlarms.filterNot {
            it.id in explicitlyDeletedAlarmIds || it.isDeleted || it.scheduleStatus == FamilyAlarm.STATUS_DELETED
        }
        val localResults = com.example.notifications.FamilyAlarmScheduler.syncAlarmsForCurrentDevice(
            context = ctx,
            alarms = currentAlarms,
            currentMemberId = current.id,
            currentAuthUid = authUid,
            currentMemberName = current.name,
            isChildDevice = !current.isParent
        )
        val updatedList = currentAlarms.map { alarm ->
            val localRes = localResults[alarm.id]
            if (localRes != null && localRes.isScheduled) {
                val needsAckWrite = alarm.scheduleStatus != localRes.statusCode ||
                    alarm.acknowledgedByMemberId != effectiveDeviceMemberId ||
                    (alarm.isTargetedToAnotherMember() && !alarm.isTargetDeviceAcknowledged())
                val updated = alarm.copy(
                    scheduleStatus = localRes.statusCode,
                    acknowledgedByMemberId = effectiveDeviceMemberId.ifBlank { alarm.acknowledgedByMemberId },
                    acknowledgedByDeviceName = deviceModelLabel,
                    acknowledgedAtMillis = if (alarm.acknowledgedAtMillis > 0L && !needsAckWrite) {
                        alarm.acknowledgedAtMillis
                    } else {
                        System.currentTimeMillis()
                    },
                    nextTriggerAtMillis = localRes.nextTriggerAtMillis,
                    lastScheduleError = localRes.errorMessage
                )
                if (needsAckWrite && familyId.isNotBlank()) {
                    acknowledgeAlarmOnTargetDeviceInFirestore(familyId, updated)
                }
                updated
            } else {
                alarm
            }
        }
        allFamilyAlarms = updatedList
        refreshVisibleFamilyAlarms()
    }

    /**
     * Reconciles alarm IDs that were deleted in Firestore:
     * 1. Records them in [explicitlyDeletedAlarmIds] and persistent `SharedPreferences`.
     * 2. Removes them from [locallyManagedAlarms], [knownRemoteAlarmIds], and [_familyAlarms].
     * 3. Cancels their `AlarmManager` PendingIntent, pending snooze PendingIntent, and stops active ringing if ringing.
     * 4. Confirms cancellation in Firestore on this member's document so a parent device knows the child device cancelled it.
     */
    fun reconcileRemoteDeletedAlarmIds(familyId: String, deletedIds: Collection<String>) {
        val validIds = deletedIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (validIds.isEmpty()) return

        val ctx = appContext
        val newlyCancelledOnDevice = mutableSetOf<String>()

        validIds.forEach { delId ->
            val wasInMemory = allFamilyAlarms.any { it.id == delId } ||
                _familyAlarms.value.any { it.id == delId } ||
                delId in locallyManagedAlarms.keys ||
                delId !in explicitlyDeletedAlarmIds
            val wasScheduledOrPersisted = ctx != null && (
                com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(ctx, delId) ||
                    com.example.notifications.FamilyAlarmScheduler.isSnoozeLocallyScheduled(ctx, delId) ||
                    com.example.notifications.FamilyAlarmScheduler.loadPersistedAlarms(ctx).any { it.id == delId } ||
                    com.example.notifications.FamilyAlarmRingingService.activeRingingAlarm.value?.alarmId == delId
                )

            explicitlyDeletedAlarmIds.add(delId)
            locallyManagedAlarms.remove(delId)
            knownRemoteAlarmIds.remove(delId)
            val cancelledLocally = if (ctx != null) {
                com.example.notifications.FamilyAlarmScheduler.markAlarmDeletedAndCancel(ctx, delId)
                !com.example.notifications.FamilyAlarmScheduler.isAlarmLocallyScheduled(ctx, delId) &&
                    !com.example.notifications.FamilyAlarmScheduler.isSnoozeLocallyScheduled(ctx, delId)
            } else {
                true
            }

            if (cancelledLocally && (wasInMemory || wasScheduledOrPersisted)) {
                newlyCancelledOnDevice.add(delId)
            }
        }

        if (allFamilyAlarms.any { it.id in validIds } || _familyAlarms.value.any { it.id in validIds }) {
            allFamilyAlarms = allFamilyAlarms.filterNot { it.id in validIds }
            refreshVisibleFamilyAlarms()
        }

        if (newlyCancelledOnDevice.isNotEmpty() && familyId.isNotBlank()) {
            confirmDeletedAlarmsCancelledInFirestore(familyId, newlyCancelledOnDevice)
        }
    }

    fun dismissDeletedAlarmNotice(alarmId: String) {
        _deletedAlarmNotices.value = _deletedAlarmNotices.value.filterNot { it.alarmId == alarmId }
    }

    fun addOrUpdateFamilyAlarm(alarm: FamilyAlarm): Boolean {
        val current = _currentMember.value
        val authUid = authProvider()?.currentUser?.uid.orEmpty()
        val effectiveCallerId = authUid.ifBlank { current.id }

        val existing = allFamilyAlarms.find { it.id == alarm.id } ?: _familyAlarms.value.find { it.id == alarm.id }
        if (existing != null && !existing.canMemberModify(current, authUid)) {
            return false
        }

        val resolvedCreatedById = existing?.createdByMemberId?.takeIf { it.isNotBlank() }
            ?: if (!current.isParent) {
                effectiveCallerId
            } else {
                val rawCreator = alarm.createdByMemberId
                if (rawCreator.isBlank() || rawCreator == current.id || rawCreator == authUid) {
                    effectiveCallerId
                } else {
                    rawCreator
                }
            }
        val resolvedCreatedByName = existing?.createdByMemberName?.takeIf { it.isNotBlank() }
            ?: alarm.createdByMemberName.takeIf { it.isNotBlank() }
            ?: current.name
        val resolvedCreatedByRole = existing?.createdByRole?.takeIf { it.isNotBlank() }
            ?: alarm.createdByRole.takeIf { it.isNotBlank() }
            ?: current.role.toFirestoreValue()

        // Child can only create/assign alarms for themselves; parent can assign to self, one child, or multiple recipients
        val rawRequestedIds = alarm.effectiveAssignedMemberIds
        val resolvedTargetIds: List<String> = if (!current.isParent) {
            listOf(effectiveCallerId)
        } else {
            val mapped = rawRequestedIds.map { rawId ->
                if (rawId == current.id || rawId == effectiveCallerId || (rawRequestedIds.size == 1 && alarm.isPersonal)) {
                    effectiveCallerId
                } else {
                    rawId
                }
            }.filter { it.isNotBlank() }.distinct()
            mapped.ifEmpty { listOf(effectiveCallerId) }
        }
        val resolvedTargetNames: List<String> = resolvedTargetIds.mapIndexed { idx, targetId ->
            _members.value.find { it.id == targetId }?.name
                ?: if (targetId == current.id || targetId == effectiveCallerId) current.name else null
                ?: alarm.effectiveAssignedMemberNames.getOrNull(idx)?.takeIf { it.isNotBlank() }
                ?: alarm.assignedMemberName.takeIf { it.isNotBlank() }
                ?: current.name
        }
        val primaryTargetId = resolvedTargetIds.first()
        val combinedTargetNames = resolvedTargetNames.joinToString(", ")

        val deviceModelLabel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val includesSelfTarget = resolvedTargetIds.any { targetId ->
            targetId == current.id || targetId == effectiveCallerId || (authUid.isNotBlank() && targetId == authUid)
        }
        val isTargetedOnlyToOtherMembers = !includesSelfTarget && resolvedTargetIds.any { targetId ->
            targetId.isNotBlank() &&
                targetId != current.id &&
                targetId != effectiveCallerId &&
                (authUid.isBlank() || targetId != authUid)
        }

        val normalizedInput = alarm.copy(
            familyId = _activeFamilyId.value,
            hour = alarm.hour.coerceIn(0, 23),
            minute = alarm.minute.coerceIn(0, 59),
            daysOfWeek = alarm.daysOfWeek.ifEmpty { SchoolDay.allWeekDays },
            assignedMemberId = primaryTargetId,
            assignedToMemberId = primaryTargetId,
            assignedMemberName = combinedTargetNames,
            assignedMemberIds = resolvedTargetIds,
            assignedMemberNames = resolvedTargetNames,
            createdByMemberId = resolvedCreatedById,
            createdByMemberName = resolvedCreatedByName,
            createdByRole = resolvedCreatedByRole,
            isPersonal = resolvedTargetIds.size == 1 && (primaryTargetId == resolvedCreatedById || !current.isParent)
        )

        val ctx = appContext
        val scheduledAlarm = when {
            !normalizedInput.isEnabled -> {
                ctx?.let { com.example.notifications.FamilyAlarmScheduler.cancelLocalAlarm(it, normalizedInput.id) }
                normalizedInput.copy(
                    scheduleStatus = FamilyAlarm.STATUS_DISABLED,
                    nextTriggerAtMillis = 0L
                )
            }
            isTargetedOnlyToOtherMembers -> {
                // Created/updated by parent for a child's device: cancel on parent's phone and wait for child's device confirmation
                ctx?.let { com.example.notifications.FamilyAlarmScheduler.cancelLocalAlarm(it, normalizedInput.id) }
                normalizedInput.copy(
                    scheduleStatus = FamilyAlarm.STATUS_PENDING_TARGET_DEVICE,
                    acknowledgedByMemberId = "",
                    acknowledgedByDeviceName = "",
                    acknowledgedAtMillis = 0L,
                    nextTriggerAtMillis = com.example.notifications.FamilyAlarmScheduler.computeNextTriggerAtMillis(normalizedInput)
                )
            }
            else -> {
                // Personal alarm or alarm assigned to the current device's member: schedule locally immediately
                val localRes = ctx?.let {
                    com.example.notifications.FamilyAlarmScheduler.scheduleLocalAlarm(it, normalizedInput)
                }
                val nextTrigger = localRes?.nextTriggerAtMillis
                    ?: com.example.notifications.FamilyAlarmScheduler.computeNextTriggerAtMillis(normalizedInput)
                val status = localRes?.statusCode ?: FamilyAlarm.STATUS_SCHEDULED_LOCALLY
                normalizedInput.copy(
                    scheduleStatus = status,
                    acknowledgedByMemberId = effectiveCallerId,
                    acknowledgedByDeviceName = deviceModelLabel,
                    acknowledgedAtMillis = System.currentTimeMillis(),
                    nextTriggerAtMillis = nextTrigger,
                    lastScheduleError = localRes?.errorMessage.orEmpty()
                )
            }
        }

        explicitlyDeletedAlarmIds.remove(scheduledAlarm.id)
        ctx?.let { com.example.notifications.FamilyAlarmScheduler.clearDeletedAlarmId(it, scheduledAlarm.id) }
        locallyManagedAlarms[scheduledAlarm.id] = scheduledAlarm
        _deletedAlarmNotices.value = _deletedAlarmNotices.value.filterNot { it.alarmId == scheduledAlarm.id }

        val existingIdx = allFamilyAlarms.indexOfFirst { it.id == scheduledAlarm.id }
        allFamilyAlarms = if (existingIdx >= 0) {
            allFamilyAlarms.map { if (it.id == scheduledAlarm.id) scheduledAlarm else it }
        } else {
            (allFamilyAlarms + scheduledAlarm)
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
        refreshVisibleFamilyAlarms()

        persistFamilyAlarmInFirestore(scheduledAlarm, isDelete = false)
        return true
    }

    fun toggleFamilyAlarm(alarmId: String): Boolean {
        val current = _currentMember.value
        val authUid = authProvider()?.currentUser?.uid.orEmpty()
        val target = allFamilyAlarms.find { it.id == alarmId } ?: _familyAlarms.value.find { it.id == alarmId } ?: return false
        if (!target.canMemberModify(current, authUid)) {
            return false
        }
        return addOrUpdateFamilyAlarm(target.copy(isEnabled = !target.isEnabled))
    }

    fun deleteFamilyAlarm(alarmId: String): Boolean {
        val current = _currentMember.value
        val authUid = authProvider()?.currentUser?.uid.orEmpty()
        val target = allFamilyAlarms.find { it.id == alarmId } ?: _familyAlarms.value.find { it.id == alarmId } ?: return false
        if (!target.canMemberDelete(current, authUid)) {
            return false
        }
        explicitlyDeletedAlarmIds.add(alarmId)
        locallyManagedAlarms.remove(alarmId)
        knownRemoteAlarmIds.remove(alarmId)
        appContext?.let { com.example.notifications.FamilyAlarmScheduler.markAlarmDeletedAndCancel(it, alarmId) }
        allFamilyAlarms = allFamilyAlarms.filterNot { it.id == alarmId }
        refreshVisibleFamilyAlarms()

        val isTargetedAtThisDevice = target.isTargetedAtDeviceMember(
            memberId = current.id,
            authUid = authUid,
            currentMemberName = current.name,
            isChildDevice = !current.isParent
        )
        val isRemoteChildTarget = target.isTargetedToAnotherMember() && !isTargetedAtThisDevice
        val targetMemberId = target.effectiveAssignedMemberId
        val targetMemberName = _members.value.find { it.id == targetMemberId }?.name
            ?: target.assignedMemberName.takeIf { it.isNotBlank() }
            ?: "בן משפחה"
        val alreadyConfirmedByTarget = !isRemoteChildTarget ||
            confirmedCancelledAlarmIdsByMember[targetMemberId]?.contains(alarmId) == true

        val notice = DeletedAlarmSyncNotice(
            alarmId = alarmId,
            title = target.title,
            formattedTime = target.formattedTime24h,
            targetMemberId = targetMemberId,
            targetMemberName = targetMemberName,
            isRemoteTarget = isRemoteChildTarget,
            isDeletedInCloud = true,
            isConfirmedCancelledOnTarget = alreadyConfirmedByTarget,
            deletedAtMillis = System.currentTimeMillis(),
            confirmedAtMillis = if (alreadyConfirmedByTarget) System.currentTimeMillis() else 0L
        )
        _deletedAlarmNotices.value = (listOf(notice) + _deletedAlarmNotices.value.filterNot { it.alarmId == alarmId }).take(5)

        appContext?.let {
            com.example.notifications.FamilyAlarmScheduler.syncAlarmsForCurrentDevice(
                context = it,
                alarms = _familyAlarms.value,
                currentMemberId = current.id,
                currentAuthUid = authUid,
                currentMemberName = current.name,
                isChildDevice = !current.isParent
            )
        }
        persistFamilyAlarmInFirestore(target, isDelete = true)
        return true
    }

    private fun confirmDeletedAlarmsCancelledInFirestore(familyId: String, cancelledIds: Set<String>) {
        val db = firestoreProvider() ?: return
        val authUser = authProvider()?.currentUser ?: return
        if (familyId.isBlank() || cancelledIds.isEmpty()) return
        repoScope.launch {
            runCatching {
                val memberRef = db.collection("families").document(familyId)
                    .collection("members").document(authUser.uid)
                memberRef.update(
                    mapOf(
                        "cancelledAlarmIds" to com.google.firebase.firestore.FieldValue.arrayUnion(*cancelledIds.toTypedArray()),
                        "lastAlarmSyncAtMillis" to System.currentTimeMillis()
                    )
                ).await()
            }
        }
    }

    private fun acknowledgeAlarmOnTargetDeviceInFirestore(familyId: String, alarm: FamilyAlarm) {
        val db = firestoreProvider() ?: return
        authProvider()?.currentUser ?: return
        if (familyId.isBlank() || alarm.id in explicitlyDeletedAlarmIds) return
        repoScope.launch {
            val docRef = db.collection("families").document(familyId)
                .collection("alarms").document(alarm.id)
            // Separate who updated the alarm configuration (updatedByUid) from who acknowledged
            // local scheduling on the target device (acknowledgedByMemberId).
            val ackFields = mapOf(
                "scheduleStatus" to alarm.scheduleStatus,
                "acknowledgedByMemberId" to alarm.acknowledgedByMemberId,
                "acknowledgedByDeviceName" to alarm.acknowledgedByDeviceName,
                "acknowledgedAtMillis" to alarm.acknowledgedAtMillis,
                "nextTriggerAtMillis" to alarm.nextTriggerAtMillis,
                "lastScheduleError" to alarm.lastScheduleError,
                "updatedAt" to System.currentTimeMillis()
            )
            runCatching {
                docRef.update(ackFields).await()
            }.onFailure { err ->
                Log.w(TAG, "acknowledgeAlarmOnTargetDeviceInFirestore update failed for ${alarm.id}: ${err.message}")
            }
        }
    }

    private fun persistFamilyAlarmInFirestore(alarm: FamilyAlarm, isDelete: Boolean) {
        val db = firestoreProvider() ?: return
        val authUser = authProvider()?.currentUser ?: return
        val familyId = _activeFamilyId.value.takeIf { it.isNotBlank() } ?: return
        repoScope.launch {
            runCatching {
                val familyRef = db.collection("families").document(familyId)
                val docRef = familyRef.collection("alarms").document(alarm.id)
                if (isDelete) {
                    // 1. Delete the alarm document from /families/{familyId}/alarms/{alarmId}
                    runCatching { docRef.delete().await() }
                    // 2. Also record tombstone in /families/{familyId}.deletedAlarmIds so offline devices
                    //    reconcile the deletion immediately upon reconnecting
                    runCatching {
                        familyRef.update(
                            mapOf(
                                "deletedAlarmIds" to com.google.firebase.firestore.FieldValue.arrayUnion(alarm.id),
                                "updatedAt" to System.currentTimeMillis()
                            )
                        ).await()
                    }
                } else {
                    val targetId = alarm.effectiveAssignedMemberId
                    val targetIds = alarm.effectiveAssignedMemberIds
                    val targetNames = alarm.effectiveAssignedMemberNames
                    val map = mapOf(
                        "id" to alarm.id,
                        "familyId" to familyId,
                        "title" to alarm.title,
                        "hour" to alarm.hour,
                        "minute" to alarm.minute,
                        "assignedMemberId" to targetId,
                        "assignedToMemberId" to targetId,
                        "assignedMemberName" to alarm.assignedMemberName,
                        "assignedMemberIds" to targetIds,
                        "assignedMemberNames" to targetNames,
                        "daysOfWeek" to alarm.daysOfWeek.map { it.name },
                        "isEnabled" to alarm.isEnabled,
                        "ringtoneEmoji" to alarm.ringtoneEmoji,
                        "createdAtMillis" to alarm.createdAtMillis,
                        "createdByMemberId" to alarm.createdByMemberId,
                        "createdByMemberName" to alarm.createdByMemberName,
                        "createdByRole" to alarm.createdByRole,
                        "isPersonal" to alarm.isPersonal,
                        "soundType" to alarm.soundType,
                        "customSoundUri" to alarm.customSoundUri,
                        "customSoundTitle" to alarm.customSoundTitle,
                        "vibrateEnabled" to alarm.vibrateEnabled,
                        "snoozeDurationMinutes" to alarm.snoozeDurationMinutes,
                        "volumePercent" to alarm.volumePercent,
                        "scheduleStatus" to alarm.scheduleStatus,
                        "acknowledgedByMemberId" to alarm.acknowledgedByMemberId,
                        "acknowledgedByDeviceName" to alarm.acknowledgedByDeviceName,
                        "acknowledgedAtMillis" to alarm.acknowledgedAtMillis,
                        "nextTriggerAtMillis" to alarm.nextTriggerAtMillis,
                        "lastScheduleError" to alarm.lastScheduleError,
                        "isDeleted" to false,
                        "updatedByUid" to authUser.uid,
                        "updatedAt" to System.currentTimeMillis()
                    )
                    docRef.set(map, SetOptions.merge()).await()
                    // Note: locallyManagedAlarms[alarm.id] is retained until alarmsListenerRegistration
                    // observes alarm.id in remoteIds, preventing any race with cached snapshots.
                }
            }
        }
    }

    // --- Shared Family Folders & Files Manager (V1.0.0) ---

    fun selectFolder(folderId: String?) {
        _selectedFolderId.value = folderId
        val famId = _activeFamilyId.value.ifBlank { "default" }
        if (folderId == null) {
            persistedSelectedFolderByFamily.remove("$famId:selected")
            persistedSelectedFolderByFamily["$famId:hasExplicitRoot"] = "true"
        } else {
            persistedSelectedFolderByFamily["$famId:selected"] = folderId
            persistedSelectedFolderByFamily.remove("$famId:hasExplicitRoot")
        }
        runCatching {
            appContext?.getSharedPreferences(FILE_MANAGER_PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString("selected_folder_$famId", folderId ?: "__ROOT__")
                ?.apply()
        }
    }

    fun navigateUpFolder(): Boolean {
        val currentId = _selectedFolderId.value ?: return false
        val currentFolder = _folders.value.find { it.id == currentId }
        val parentId = currentFolder?.parentFolderId?.takeIf { it.isNotBlank() }
        selectFolder(parentId)
        return true
    }

    fun setFileSortOption(option: FileSortOption) {
        if (_fileSortOption.value == option) {
            _fileSortAscending.value = !_fileSortAscending.value
        } else {
            _fileSortOption.value = option
            _fileSortAscending.value = (option == FileSortOption.NAME || option == FileSortOption.TYPE)
        }
    }

    fun setFileSearchQuery(query: String) {
        _fileSearchQuery.value = query
    }

    private fun restorePersistedFileNavigationState(familyId: String) {
        val famId = familyId.ifBlank { "default" }
        val cachedFolders = persistedFoldersByFamily[famId]
            ?.filterNot { it.id in LEGACY_PREDEFINED_FOLDER_IDS }
            .orEmpty()
        _folders.value = cachedFolders

        val cachedDocs = persistedDocumentsByFamily[famId].orEmpty()
        _documents.value = cachedDocs

        persistedDriveConfigByFamily[famId]?.let { cachedDrive ->
            _familyDriveConfig.value = cachedDrive
        }
        val fromPrefs = runCatching {
            appContext?.getSharedPreferences(FILE_MANAGER_PREFS, Context.MODE_PRIVATE)
                ?.getString("selected_folder_$famId", null)
        }.getOrNull()

        val candidateFolderId = when {
            fromPrefs == "__ROOT__" -> null
            !fromPrefs.isNullOrBlank() && fromPrefs !in LEGACY_PREDEFINED_FOLDER_IDS -> fromPrefs
            persistedSelectedFolderByFamily["$famId:hasExplicitRoot"] == "true" -> null
            persistedSelectedFolderByFamily.containsKey("$famId:selected") -> {
                persistedSelectedFolderByFamily["$famId:selected"]?.takeIf { it !in LEGACY_PREDEFINED_FOLDER_IDS }
            }
            else -> null
        }
        _selectedFolderId.value = candidateFolderId?.takeIf { id -> cachedFolders.any { it.id == id } }
    }

    /**
     * Inside the shared `FamilyTasks` Google Drive folder, every active family member
     * (both PARENT and CHILD) has equal file-management permissions.
     */
    fun canCurrentMemberDeleteOrModifyFile(file: FamilyDocument): Boolean {
        return FamilyFileManagerHelper.canMemberModifyOrDeleteFile(
            file = file,
            member = _currentMember.value,
            authenticatedUid = authProvider()?.currentUser?.uid ?: _currentMember.value.id
        )
    }

    /**
     * Inside the shared `FamilyTasks` Google Drive folder, every active family member
     * (both PARENT and CHILD) has equal folder-management permissions.
     */
    fun canCurrentMemberDeleteFolder(): Boolean {
        return FamilyFileManagerHelper.canMemberDeleteFolder(_currentMember.value)
    }

    // --- Google Drive FamilyTasks Root Folder Setup & Sharing ---

    fun setupOrReconnectFamilyGoogleDrive(
        onComplete: ((Result<FamilyDriveConfig>) -> Unit)? = null
    ) {
        repoScope.launch {
            val res = setupOrReconnectFamilyGoogleDriveSuspend(_activeFamilyId.value)
            onComplete?.invoke(res)
        }
    }

    suspend fun connectOrSetupFamilyDriveFolder(
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDriveConfig> = setupOrReconnectFamilyGoogleDriveSuspend(targetFamilyId)

    suspend fun setupOrReconnectFamilyGoogleDriveSuspend(
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDriveConfig> {
        val famId = targetFamilyId.ifBlank { "fam_israeli_8492" }
        val currentMember = _currentMember.value
        val callerUid = currentUid()
        val callerEmail = resolveVerifiedGoogleEmail().ifBlank { "parent@gmail.com" }
        val isParent = currentMember.isParent

        _familyDriveConfig.value = _familyDriveConfig.value.copy(
            familyId = famId,
            connectionStatus = DriveConnectionStatus.CONNECTING,
            errorMessageHe = null,
            errorMessageEn = null
        )

        val db = if (authProvider()?.currentUser != null) firestoreProvider() else null
        var existingRemoteConfig: FamilyDriveConfig? = _familyDriveConfig.value
            .takeIf { it.familyId == famId && it.isConfigured }
            ?: persistedDriveConfigByFamily[famId]?.takeIf { it.isConfigured }
        var existingRootFolderId = existingRemoteConfig?.rootFolderId.orEmpty()

        // First check Firestore `/families/{familyId}` for an authoritative registered `driveRootFolderId`
        // so we NEVER create duplicate `FamilyTasks` root folders across restarts or devices.
        if (existingRootFolderId.isBlank() && db != null) {
            runCatching {
                val snap = db.collection("families").document(famId).get().await()
                if (snap.exists()) {
                    val remoteConfig = GoogleDriveFamilyService.parseDriveConfigFromFamilySnapshot(famId, snap.data)
                    if (remoteConfig.isConfigured) {
                        existingRemoteConfig = remoteConfig
                        existingRootFolderId = remoteConfig.rootFolderId
                        _familyDriveConfig.value = remoteConfig
                        persistedDriveConfigByFamily[famId] = remoteConfig
                    }
                }
            }
        }

        // Also ensure the current signed-in member's verified Google email is synced on their member doc
        if (db != null && callerEmail.contains("@") && callerEmail != "parent@gmail.com") {
            runCatching {
                db.collection("families").document(famId)
                    .collection("members").document(callerUid)
                    .set(
                        mapOf(
                            "googleEmail" to callerEmail,
                            "email" to callerEmail
                        ),
                        SetOptions.merge()
                    )
                    .await()
            }
        }

        val setupRes = GoogleDriveFamilyService.setupOrReconnectFamilyDriveRootFolder(
            context = appContext,
            accountEmail = callerEmail,
            familyId = famId,
            callerUid = callerUid,
            isParent = isParent,
            existingRootFolderId = existingRootFolderId.takeIf { it.isNotBlank() },
            callerName = currentMember.name
        )

        return setupRes.fold(
            onSuccess = { config ->
                if (!isParent) {
                    // Invited CHILD member reconnecting to the existing FamilyTasks root folder:
                    // preserve the parent's ownership & shared member list while marking this session CONNECTED.
                    val baseConfig = existingRemoteConfig ?: config
                    val childStatuses = baseConfig.memberShareStatuses.toMutableMap().apply {
                        put(callerUid, DriveMemberSharingStatus.SHARED_EDITOR)
                    }
                    val childEmails = (baseConfig.sharedWithEmails + callerEmail.trim().lowercase())
                        .filter { it.isNotBlank() && it.contains("@") }
                        .distinct()
                    val childConnectedConfig = baseConfig.copy(
                        isConnected = true,
                        connectionStatus = DriveConnectionStatus.CONNECTED,
                        sharedEmails = childEmails,
                        sharedWithEmails = childEmails,
                        memberShareStatuses = childStatuses,
                        lastSyncedAtMillis = System.currentTimeMillis()
                    )
                    _familyDriveConfig.value = childConnectedConfig
                    persistedDriveConfigByFamily[famId] = childConnectedConfig
                    _driveOperationMessage.value = "תיקיית Google Drive המשפחתית ($GOOGLE_DRIVE_ROOT_FOLDER_NAME) מחוברת ומסונכרנת!"
                    return@fold Result.success(childConnectedConfig)
                }

                // PARENT setup / reconnect: gather all verified member emails from both local state and Firestore
                val memberEmailMap = mutableMapOf<String, String>()
                for (m in _members.value) {
                    val em = m.googleEmail?.trim()?.lowercase().orEmpty()
                    if (em.isNotBlank() && em.contains("@")) {
                        memberEmailMap[m.id] = em
                    }
                }
                if (db != null) {
                    runCatching {
                        val memberDocs = db.collection("families").document(famId)
                            .collection("members").get().await().documents
                        for (doc in memberDocs) {
                            val em = (doc.getString("googleEmail") ?: doc.getString("email"))
                                ?.trim()?.lowercase().orEmpty()
                            if (em.isNotBlank() && em.contains("@")) {
                                memberEmailMap[doc.id] = em
                            }
                        }
                    }
                }

                val mergedSharedEmails = (
                    existingRemoteConfig?.sharedWithEmails.orEmpty() +
                        config.sharedWithEmails +
                        memberEmailMap.values +
                        callerEmail.trim().lowercase()
                    )
                    .filter { it.isNotBlank() && it.contains("@") }
                    .distinct()

                val updatedStatuses = (existingRemoteConfig?.memberShareStatuses ?: config.memberShareStatuses).toMutableMap()
                val resolvedOwnerUid = existingRemoteConfig?.ownerUid?.takeIf { it.isNotBlank() } ?: callerUid
                val resolvedOwnerEmail = existingRemoteConfig?.ownerEmail?.takeIf { it.isNotBlank() } ?: callerEmail
                val resolvedOwnerName = existingRemoteConfig?.ownerName?.takeIf { it.isNotBlank() } ?: currentMember.name
                updatedStatuses[callerUid] = if (callerUid == resolvedOwnerUid) {
                    DriveMemberSharingStatus.OWNER
                } else {
                    DriveMemberSharingStatus.SHARED_EDITOR
                }

                for ((mId, email) in memberEmailMap) {
                    if (mId != callerUid && !email.equals(resolvedOwnerEmail, ignoreCase = true) && config.isConfigured) {
                        runCatching {
                            GoogleDriveFamilyService.shareRootFolderWithFamilyMember(
                                context = appContext,
                                ownerEmail = callerEmail,
                                rootFolderId = config.rootFolderId,
                                targetMemberEmail = email,
                                familyId = famId
                            )
                        }
                        updatedStatuses[mId] = DriveMemberSharingStatus.SHARED_EDITOR
                    } else if (email.equals(resolvedOwnerEmail, ignoreCase = true)) {
                        updatedStatuses[mId] = DriveMemberSharingStatus.OWNER
                    }
                }

                val enrichedConfig = config.copy(
                    ownerUid = resolvedOwnerUid,
                    ownerEmail = resolvedOwnerEmail,
                    ownerName = resolvedOwnerName,
                    sharedEmails = mergedSharedEmails,
                    sharedWithEmails = mergedSharedEmails,
                    memberShareStatuses = updatedStatuses
                )
                _familyDriveConfig.value = enrichedConfig
                persistedDriveConfigByFamily[famId] = enrichedConfig

                if (enrichedConfig.isConfigured && db != null) {
                    runCatching {
                        val payload = GoogleDriveFamilyService.buildDriveConfigFirestoreMap(enrichedConfig)
                        db.collection("families").document(famId)
                            .set(payload, SetOptions.merge())
                            .await()
                    }
                }

                if (enrichedConfig.isConfigured) {
                    _driveOperationMessage.value = "תיקיית Google Drive המשפחתית ($GOOGLE_DRIVE_ROOT_FOLDER_NAME) מחוברת ומסונכרנת!"
                }
                Result.success(enrichedConfig)
            },
            onFailure = { err ->
                when (err) {
                    is GoogleDriveFamilyService.DriveConsentRequiredException -> {
                        _driveConsentPendingIntent.value = err.pendingIntent
                        _driveConsentIntent.value = err.legacyConsentIntent
                        val needsAuthConfig = _familyDriveConfig.value.copy(
                            familyId = famId,
                            driveRootFolderId = existingRootFolderId.ifBlank { _familyDriveConfig.value.driveRootFolderId },
                            connectionStatus = DriveConnectionStatus.NEEDS_OAUTH_AUTHORIZATION,
                            errorMessageHe = "נדרש אישור גישה ל-Google Drive עבור תיקיית FamilyTasks.",
                            errorMessageEn = "Google Drive authorization is required for the FamilyTasks folder."
                        )
                        _familyDriveConfig.value = needsAuthConfig
                        Result.failure(err)
                    }
                    is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException -> {
                        handleSharedFolderPickerException(err)
                        Result.failure(err)
                    }
                    else -> {
                        val errConfig = _familyDriveConfig.value.copy(
                            familyId = famId,
                            driveRootFolderId = existingRootFolderId.ifBlank { _familyDriveConfig.value.driveRootFolderId },
                            connectionStatus = DriveConnectionStatus.ERROR,
                            errorMessageHe = err.localizedMessage ?: "חיבור ל-Google Drive נכשל. נסו שוב.",
                            errorMessageEn = err.localizedMessage ?: "Failed to connect to Google Drive."
                        )
                        _familyDriveConfig.value = errConfig
                        _fileOperationError.value = errConfig.errorMessageHe
                        Result.failure(err)
                    }
                }
            }
        )
    }

    fun updateMemberGoogleEmail(memberId: String, newEmailInput: String): Boolean {
        val cleanEmail = newEmailInput.trim().lowercase()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            _fileOperationError.value = "נא להזין כתובת אימייל של חשבון Google תקינה."
            return false
        }
        val famId = _activeFamilyId.value
        _members.value = _members.value.map { member ->
            if (member.id == memberId) member.copy(googleEmail = cleanEmail) else member
        }
        if (_currentMember.value.id == memberId) {
            _currentMember.value = _currentMember.value.copy(googleEmail = cleanEmail)
        }
        val db = firestoreProvider()
        if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
            repoScope.launch {
                runCatching {
                    db.collection("families").document(famId)
                        .collection("members").document(memberId)
                        .set(
                            mapOf(
                                "googleEmail" to cleanEmail,
                                "email" to cleanEmail
                            ),
                            SetOptions.merge()
                        )
                        .await()
                }
            }
        }
        return true
    }

    fun shareFamilyDriveWithMember(
        memberId: String,
        memberEmailInput: String,
        onComplete: ((Result<FamilyDriveConfig>) -> Unit)? = null
    ) {
        repoScope.launch {
            val res = shareFamilyDriveWithMemberSuspend(memberId, memberEmailInput, _activeFamilyId.value)
            onComplete?.invoke(res)
        }
    }

    suspend fun shareFamilyDriveWithMemberSuspend(
        memberId: String,
        memberEmailInput: String,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDriveConfig> {
        val memberFromState = _members.value.find { it.id == memberId }
        val cleanEmail = memberEmailInput.trim().lowercase().ifBlank {
            memberFromState?.googleEmail?.trim()?.lowercase().orEmpty()
        }
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            val msg = "נא להזין כתובת Google תקינה לשיתוף תיקיית FamilyTasks."
            _fileOperationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        updateMemberGoogleEmail(memberId, cleanEmail)

        var currentConfig = _familyDriveConfig.value
        if (!currentConfig.isConfigured) {
            val setupRes = setupOrReconnectFamilyGoogleDriveSuspend(targetFamilyId)
            currentConfig = setupRes.getOrElse { return Result.failure(it) }
        }
        if (!currentConfig.isConfigured) {
            val msg = "יש לחבר תחילה את תיקיית FamilyTasks ב-Google Drive של ההורה."
            _fileOperationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val ownerEmail = resolveVerifiedGoogleEmail(currentConfig.ownerEmail)
        val shareRes = GoogleDriveFamilyService.shareRootFolderWithFamilyMember(
            context = appContext,
            ownerEmail = ownerEmail,
            rootFolderId = currentConfig.rootFolderId,
            targetMemberEmail = cleanEmail,
            familyId = targetFamilyId
        )
        return shareRes.fold(
            onSuccess = {
                val updatedEmails = (currentConfig.sharedWithEmails + cleanEmail).distinct()
                val updatedStatuses = currentConfig.memberShareStatuses.toMutableMap().apply {
                    put(memberId, DriveMemberSharingStatus.SHARED_EDITOR)
                }
                val updatedConfig = currentConfig.copy(
                    sharedEmails = updatedEmails,
                    sharedWithEmails = updatedEmails,
                    memberShareStatuses = updatedStatuses,
                    lastSyncedAtMillis = System.currentTimeMillis()
                )
                _familyDriveConfig.value = updatedConfig
                persistedDriveConfigByFamily[targetFamilyId] = updatedConfig

                val db = if (authProvider()?.currentUser != null) firestoreProvider() else null
                if (db != null && targetFamilyId.isNotBlank() && _currentMember.value.isParent) {
                    runCatching {
                        val payload = GoogleDriveFamilyService.buildDriveConfigFirestoreMap(updatedConfig)
                        db.collection("families").document(targetFamilyId)
                            .set(payload, SetOptions.merge())
                            .await()
                    }
                }
                val memberName = _members.value.find { it.id == memberId }?.name ?: cleanEmail
                _driveOperationMessage.value = "תיקיית FamilyTasks שותפה בהצלחה עם $memberName ($cleanEmail)"
                Result.success(updatedConfig)
            },
            onFailure = { err ->
                _fileOperationError.value = err.localizedMessage ?: "שיתוף תיקיית FamilyTasks נכשל."
                Result.failure(err)
            }
        )
    }

    fun shareFamilyDriveWithAllMembers(
        onComplete: ((Result<FamilyDriveConfig>) -> Unit)? = null
    ) {
        repoScope.launch {
            val res = shareFamilyDriveWithAllMembersSuspend(_activeFamilyId.value)
            onComplete?.invoke(res)
        }
    }

    suspend fun shareFamilyDriveWithAllMembersSuspend(
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDriveConfig> {
        val famId = targetFamilyId.ifBlank { _activeFamilyId.value }
        var config = _familyDriveConfig.value
        if (!config.isConfigured) {
            val setupRes = setupOrReconnectFamilyGoogleDriveSuspend(famId)
            config = setupRes.getOrElse { return Result.failure(it) }
        }
        val db = if (authProvider()?.currentUser != null) firestoreProvider() else null
        val verifiedEmailsByMemberId = mutableMapOf<String, String>()
        for (member in _members.value) {
            val email = member.googleEmail?.trim()?.lowercase().orEmpty()
            if (email.isNotBlank() && email.contains("@")) {
                verifiedEmailsByMemberId[member.id] = email
            }
        }
        if (db != null && famId.isNotBlank()) {
            runCatching {
                val docs = db.collection("families").document(famId)
                    .collection("members").get().await().documents
                for (doc in docs) {
                    val email = (doc.getString("googleEmail") ?: doc.getString("email"))
                        ?.trim()?.lowercase().orEmpty()
                    if (email.isNotBlank() && email.contains("@")) {
                        verifiedEmailsByMemberId[doc.id] = email
                    }
                }
            }
        }
        var latestConfig = config
        for ((memberId, email) in verifiedEmailsByMemberId) {
            if (email.equals(config.ownerEmail, ignoreCase = true) && memberId == config.ownerUid) {
                continue
            }
            val res = shareFamilyDriveWithMemberSuspend(memberId, email, famId)
            if (res.isSuccess) {
                latestConfig = res.getOrThrow()
            }
        }
        _driveOperationMessage.value = "תיקיית FamilyTasks משותפת עם כל בני המשפחה!"
        return Result.success(latestConfig)
    }

    fun addFolder(folder: DocumentFolder) {
        val famId = _activeFamilyId.value.ifBlank { folder.familyId }
        val callerUid = currentUid()
        val callerName = _currentMember.value.name.ifBlank { "חבר משפחה" }
        val rootDriveId = _familyDriveConfig.value.rootFolderId.trim()
            .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(famId).orEmpty() }
            .ifBlank { "gdrive_root_$famId" }
        val parentDriveId = folder.parentFolderId?.let { pid ->
            _folders.value.find { it.id == pid }?.driveFolderId?.takeIf { it.isNotBlank() && !it.startsWith("f_") }
        } ?: rootDriveId
        val resolvedDriveFolderId = folder.driveFolderId.ifBlank { "drive_folder_${folder.id}" }
        val enriched = folder.copy(
            familyId = famId,
            nameHe = FamilyFileManagerHelper.sanitizeFolderName(folder.nameHe).ifBlank { "תיקייה חדשה" },
            nameEn = FamilyFileManagerHelper.sanitizeFolderName(folder.nameEn).ifBlank { folder.nameHe },
            createdByUid = folder.createdByUid.ifBlank { callerUid },
            createdByName = folder.createdByName.ifBlank { callerName },
            driveFolderId = resolvedDriveFolderId,
            driveParentFolderId = parentDriveId
        )
        _folders.value = (_folders.value.filterNot { it.id == enriched.id } + enriched)
        persistedFoldersByFamily[famId] = _folders.value
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val db = firestoreProvider()
        repoScope.launch {
            val driveRes = GoogleDriveFamilyService.createSubfolderInDrive(
                context = appContext,
                accountEmail = accountEmail,
                parentDriveFolderId = parentDriveId,
                folderName = enriched.nameHe,
                localFolderId = enriched.id,
                familyId = famId,
                driveRootFolderId = rootDriveId
            )
            driveRes.fold(
                onSuccess = { driveMeta ->
                    val finalFolder = enriched.copy(
                        driveFolderId = driveMeta.driveId,
                        driveParentFolderId = parentDriveId
                    )
                    _folders.value = _folders.value.map { if (it.id == finalFolder.id) finalFolder else it }
                    persistedFoldersByFamily[famId] = _folders.value
                    if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
                        runCatching {
                            val payload = FamilyCloudFileTransferEngine.buildFolderFirestoreMap(
                                folder = finalFolder,
                                familyId = famId,
                                callerUid = callerUid,
                                callerName = callerName
                            )
                            db.collection("families").document(famId)
                                .collection("folders").document(finalFolder.id)
                                .set(payload)
                                .await()
                        }
                    }
                },
                onFailure = { err ->
                    // Never disguise a failed/unauthorized folder in Firestore or local state
                    _folders.value = _folders.value.filterNot { it.id == enriched.id }
                    persistedFoldersByFamily[famId] = _folders.value
                    if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                        handleSharedFolderPickerException(err)
                    } else {
                        _fileOperationError.value = err.localizedMessage ?: "יצירת התיקייה ב-Google Drive נכשלה."
                    }
                }
            )
        }
    }

    suspend fun createSharedFolderInCloud(
        nameHe: String,
        nameEn: String = nameHe,
        iconEmoji: String = "📁",
        colorHex: Long = 0xFF1F6E6CL,
        parentFolderId: String? = null,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<DocumentFolder> {
        val cleanHe = FamilyFileManagerHelper.sanitizeFolderName(nameHe)
        if (cleanHe.isBlank()) {
            return Result.failure(IllegalArgumentException("נא להזין שם תיקייה תקין."))
        }
        val cleanEn = FamilyFileManagerHelper.sanitizeFolderName(nameEn).ifBlank { cleanHe }
        val callerUid = currentUid()
        val callerName = _currentMember.value.name.ifBlank { "חבר משפחה" }
        val folderId = "folder_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val rootDriveId = _familyDriveConfig.value.rootFolderId.trim()
            .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(targetFamilyId).orEmpty() }
            .ifBlank { "gdrive_root_$targetFamilyId" }
        val parentDriveId = parentFolderId?.let { pid ->
            _folders.value.find { it.id == pid }?.driveFolderId?.takeIf { it.isNotBlank() && !it.startsWith("f_") }
        } ?: rootDriveId

        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val driveRes = GoogleDriveFamilyService.createSubfolderInDrive(
            context = appContext,
            accountEmail = accountEmail,
            parentDriveFolderId = parentDriveId,
            folderName = cleanHe,
            localFolderId = folderId,
            familyId = targetFamilyId,
            driveRootFolderId = rootDriveId
        )
        val driveMeta = driveRes.getOrElse { err ->
            if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                handleSharedFolderPickerException(err)
            } else {
                _fileOperationError.value = err.localizedMessage ?: "יצירת התיקייה ב-Google Drive נכשלה."
            }
            return Result.failure(err)
        }

        val folder = DocumentFolder(
            id = folderId,
            nameHe = cleanHe,
            nameEn = cleanEn,
            iconEmoji = iconEmoji.ifBlank { "📁" },
            colorHex = colorHex,
            familyId = targetFamilyId,
            parentFolderId = parentFolderId?.takeIf { it.isNotBlank() },
            createdByUid = callerUid,
            createdByName = callerName,
            createdAtMillis = System.currentTimeMillis(),
            driveFolderId = driveMeta.driveId.ifBlank { "drive_folder_$folderId" },
            driveParentFolderId = parentDriveId
        )
        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null && targetFamilyId.isNotBlank()) {
            try {
                val payload = FamilyCloudFileTransferEngine.buildFolderFirestoreMap(
                    folder = folder,
                    familyId = targetFamilyId,
                    callerUid = callerUid,
                    callerName = callerName
                )
                db.collection("families").document(targetFamilyId)
                    .collection("folders").document(folder.id)
                    .set(payload)
                    .await()
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        if (targetFamilyId == _activeFamilyId.value) {
            _folders.value = (_folders.value.filterNot { it.id == folder.id } + folder)
            persistedFoldersByFamily[targetFamilyId] = _folders.value
        }
        return Result.success(folder)
    }

    /**
     * Renames a folder in the shared `FamilyTasks` Google Drive workspace.
     * Available equally to both PARENT and CHILD members.
     */
    fun renameFolder(folderId: String, newNameHe: String, newNameEn: String = newNameHe): Boolean {
        val cleanHe = FamilyFileManagerHelper.sanitizeFolderName(newNameHe)
        if (cleanHe.isBlank()) {
            _fileOperationError.value = "נא להזין שם תיקייה תקין."
            return false
        }
        val cleanEn = FamilyFileManagerHelper.sanitizeFolderName(newNameEn).ifBlank { cleanHe }
        val target = _folders.value.find { it.id == folderId } ?: return false
        val famId = _activeFamilyId.value
        val updated = target.copy(nameHe = cleanHe, nameEn = cleanEn)
        _folders.value = _folders.value.map { if (it.id == folderId) updated else it }
        persistedFoldersByFamily[famId] = _folders.value

        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val db = firestoreProvider()
        repoScope.launch {
            if (target.driveFolderId.isNotBlank()) {
                val renameRes = GoogleDriveFamilyService.renameItemInDrive(
                    context = appContext,
                    accountEmail = accountEmail,
                    driveItemId = target.driveFolderId,
                    newName = cleanHe
                )
                renameRes.onFailure { err ->
                    _folders.value = _folders.value.map { if (it.id == folderId) target else it }
                    persistedFoldersByFamily[famId] = _folders.value
                    if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                        handleSharedFolderPickerException(err)
                    } else {
                        _fileOperationError.value = err.localizedMessage ?: "שינוי שם התיקייה ב-Google Drive נכשל."
                    }
                    return@launch
                }
            }
            if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
                runCatching {
                    val payload = FamilyCloudFileTransferEngine.buildFolderFirestoreMap(
                        folder = updated,
                        familyId = famId,
                        callerUid = currentUid(),
                        callerName = _currentMember.value.name.ifBlank { "חבר משפחה" }
                    )
                    db.collection("families").document(famId)
                        .collection("folders").document(folderId)
                        .set(payload, SetOptions.merge())
                        .await()
                }
            }
        }
        return true
    }

    suspend fun renameSharedFolderInCloud(
        folderId: String,
        newNameHe: String,
        newNameEn: String = newNameHe,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<DocumentFolder> {
        val cleanHe = FamilyFileManagerHelper.sanitizeFolderName(newNameHe)
        if (cleanHe.isBlank()) {
            return Result.failure(IllegalArgumentException("נא להזין שם תיקייה תקין."))
        }
        val cleanEn = FamilyFileManagerHelper.sanitizeFolderName(newNameEn).ifBlank { cleanHe }
        val existing = _folders.value.find { it.id == folderId }
            ?: DocumentFolder(
                id = folderId,
                nameHe = cleanHe,
                nameEn = cleanEn,
                iconEmoji = "📁",
                colorHex = 0xFF1F6E6CL,
                familyId = targetFamilyId
            )
        val updated = existing.copy(nameHe = cleanHe, nameEn = cleanEn)
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        if (updated.driveFolderId.isNotBlank()) {
            val renameRes = GoogleDriveFamilyService.renameItemInDrive(
                context = appContext,
                accountEmail = accountEmail,
                driveItemId = updated.driveFolderId,
                newName = cleanHe
            )
            renameRes.onFailure { err ->
                if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                    handleSharedFolderPickerException(err)
                } else {
                    _fileOperationError.value = err.localizedMessage ?: "שינוי שם התיקייה ב-Google Drive נכשל."
                }
                return Result.failure(err)
            }
        }
        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null && targetFamilyId.isNotBlank()) {
            try {
                db.collection("families").document(targetFamilyId)
                    .collection("folders").document(folderId)
                    .update(
                        mapOf(
                            "nameHe" to cleanHe,
                            "nameEn" to cleanEn,
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                    .await()
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        if (targetFamilyId == _activeFamilyId.value) {
            _folders.value = _folders.value.map { if (it.id == folderId) updated else it }
            persistedFoldersByFamily[targetFamilyId] = _folders.value
        }
        return Result.success(updated)
    }

    /**
     * Deletes a folder in the shared `FamilyTasks` Google Drive workspace.
     * Available equally to both PARENT and CHILD members.
     */
    fun deleteFolder(folderId: String): Boolean {
        val targetFolder = _folders.value.find { it.id == folderId }
        val famId = _activeFamilyId.value
        val previousFolders = _folders.value
        _folders.value = _folders.value.filterNot { it.id == folderId || it.parentFolderId == folderId }
        persistedFoldersByFamily[famId] = _folders.value
        if (_selectedFolderId.value == folderId) {
            selectFolder(targetFolder?.parentFolderId)
        }
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val db = firestoreProvider()
        repoScope.launch {
            if (targetFolder != null && targetFolder.driveFolderId.isNotBlank()) {
                val delRes = GoogleDriveFamilyService.deleteItemInDrive(
                    context = appContext,
                    accountEmail = accountEmail,
                    driveItemId = targetFolder.driveFolderId
                )
                delRes.onFailure { err ->
                    _folders.value = previousFolders
                    persistedFoldersByFamily[famId] = previousFolders
                    if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                        handleSharedFolderPickerException(err)
                    } else {
                        _fileOperationError.value = err.localizedMessage ?: "מחיקת התיקייה ב-Google Drive נכשלה."
                    }
                    return@launch
                }
            }
            if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
                runCatching {
                    db.collection("families").document(famId)
                        .collection("folders").document(folderId)
                        .delete()
                        .await()
                }
            }
        }
        return true
    }

    suspend fun deleteSharedFolderInCloud(
        folderId: String,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<Unit> {
        val targetFolder = _folders.value.find { it.id == folderId }
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        if (targetFolder != null && targetFolder.driveFolderId.isNotBlank()) {
            val delRes = GoogleDriveFamilyService.deleteItemInDrive(
                context = appContext,
                accountEmail = accountEmail,
                driveItemId = targetFolder.driveFolderId
            )
            delRes.onFailure { err ->
                if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                    handleSharedFolderPickerException(err)
                } else {
                    _fileOperationError.value = err.localizedMessage ?: "מחיקת התיקייה ב-Google Drive נכשלה."
                }
                return Result.failure(err)
            }
        }
        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null && targetFamilyId.isNotBlank()) {
            try {
                db.collection("families").document(targetFamilyId)
                    .collection("folders").document(folderId)
                    .delete()
                    .await()
            } catch (e: Exception) {
                _fileOperationError.value = "מחיקת התיקייה נכשלה 🔒"
                return Result.failure(e)
            }
        }
        if (targetFamilyId == _activeFamilyId.value) {
            _folders.value = _folders.value.filterNot { it.id == folderId || it.parentFolderId == folderId }
            persistedFoldersByFamily[targetFamilyId] = _folders.value
            if (_selectedFolderId.value == folderId) {
                selectFolder(targetFolder?.parentFolderId)
            }
        }
        return Result.success(Unit)
    }

    /**
     * Safe, idempotent cleanup of legacy automatically generated default folders (`f_school`, `f_medical`, etc.):
     * - Identifies ONLY folders known with certainty to have been automatically generated by the application.
     * - Never identifies or deletes folders based solely on a folder name.
     * - Never deletes the authoritative FamilyTasks shared root folder.
     * - Never deletes manually created folders, even if they have similar names.
     * - Never deletes any folder containing files or subfolders (in local state, Firestore, or Google Drive).
     * - Confirms each candidate folder is empty in Google Drive before deleting it in Drive.
     * - Removes corresponding Firestore metadata ONLY after successful Drive deletion.
     * - If automatic folder identification or emptiness check is uncertain, leaves the folder untouched.
     */
    suspend fun cleanupEmptyAutoGeneratedDefaultFolders(
        targetFamilyId: String = _activeFamilyId.value
    ): Int {
        val famId = targetFamilyId.trim().ifBlank { _activeFamilyId.value }
        val rootDriveId = _familyDriveConfig.value.driveRootFolderId.trim()
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val currentFolders = _folders.value
        val currentDocs = _documents.value

        var deletedCount = 0
        for (candidate in currentFolders) {
            // 1. Must be positively identified as an application auto-generated default folder (never by name alone)
            val isAutoGenerated = GoogleDriveFamilyService.isKnownAutoGeneratedDefaultFolder(
                folderId = candidate.id,
                driveFolderId = candidate.driveFolderId,
                driveRootFolderId = rootDriveId,
                createdByUid = candidate.createdByUid
            )
            if (!isAutoGenerated) continue

            // 2. Never delete the authoritative FamilyTasks root folder
            if (rootDriveId.isNotBlank() &&
                (candidate.id == rootDriveId || candidate.driveFolderId == rootDriveId)
            ) {
                continue
            }

            // 3. Do not delete if any files or subfolders exist inside it in memory/Firestore
            val hasFiles = currentDocs.any { doc ->
                doc.folderId == candidate.id ||
                    (candidate.driveFolderId.isNotBlank() &&
                        (doc.folderId == candidate.driveFolderId || doc.driveParentFolderId == candidate.driveFolderId))
            }
            if (hasFiles) continue

            val hasSubfolders = currentFolders.any { sub ->
                sub.id != candidate.id && (
                    sub.parentFolderId == candidate.id ||
                        (candidate.driveFolderId.isNotBlank() &&
                            (sub.parentFolderId == candidate.driveFolderId || sub.driveParentFolderId == candidate.driveFolderId))
                    )
            }
            if (hasSubfolders) continue

            // 4. Confirm the folder is empty in Google Drive and delete it from Google Drive BEFORE removing Firestore metadata
            if (candidate.driveFolderId.isNotBlank()) {
                val emptyCheck = GoogleDriveFamilyService.isDriveFolderConfirmedEmpty(
                    context = appContext,
                    accountEmail = accountEmail,
                    familyId = famId,
                    driveRootFolderId = rootDriveId,
                    driveFolderId = candidate.driveFolderId
                )
                if (emptyCheck.isFailure || emptyCheck.getOrDefault(false) != true) {
                    // Uncertain or non-empty in Drive -> leave untouched
                    continue
                }
                val driveDel = GoogleDriveFamilyService.deleteItemInDrive(
                    context = appContext,
                    accountEmail = accountEmail,
                    driveItemId = candidate.driveFolderId
                )
                if (driveDel.isFailure) {
                    // Drive deletion did not succeed -> do not remove Firestore metadata
                    continue
                }
            }

            // 5. Remove Firestore metadata only after Drive deletion succeeded
            val db = firestoreProvider()
            if (db != null && authProvider()?.currentUser != null && famId.isNotBlank()) {
                val firestoreDel = runCatching {
                    db.collection("families").document(famId)
                        .collection("folders").document(candidate.id)
                        .delete()
                        .await()
                }
                if (firestoreDel.isFailure) {
                    continue
                }
            }

            _folders.value = _folders.value.filterNot { it.id == candidate.id }
            persistedFoldersByFamily[famId] = _folders.value
            if (_selectedFolderId.value == candidate.id) {
                _selectedFolderId.value = null
            }
            deletedCount++
        }
        return deletedCount
    }

    /**
     * Injects a folder into repository state for unit/Robolectric testing of safe cleanup behavior.
     */
    internal fun injectFolderForTest(folder: DocumentFolder) {
        val famId = folder.familyId.ifBlank { _activeFamilyId.value }
        _folders.value = (_folders.value.filterNot { it.id == folder.id } + folder)
        persistedFoldersByFamily[famId] = _folders.value
    }

    /**
     * Starts a tracked, asynchronous, cancellable file upload into [folderId].
     * Never marks a file as uploaded or adds it to [documents] until cloud upload
     * and Firestore metadata persistence succeed.
     */
    fun startFileUpload(
        folderId: String?,
        rawFileName: String,
        rawExtension: String,
        rawBytes: ByteArray,
        thumbnailBase64: String? = null,
        onComplete: ((Result<FamilyDocument>) -> Unit)? = null
    ): String {
        val resolvedFolderId = folderId?.trim().orEmpty()
        val sizeBytes = rawBytes.size.toLong().coerceAtLeast(1L)
        val validation = FamilyFileManagerHelper.validateUploadCandidate(
            rawFileName = rawFileName,
            rawExtension = rawExtension,
            sizeBytes = sizeBytes
        )
        val uploadId = "upload_${UUID.randomUUID().toString().replace("-", "").take(10)}"

        if (validation is FamilyFileManagerHelper.FileValidationResult.Invalid) {
            val failedTask = FileUploadTaskState(
                uploadId = uploadId,
                fileName = rawFileName.ifBlank { "קובץ" },
                folderId = resolvedFolderId,
                extension = rawExtension.ifBlank { "FILE" },
                sizeBytes = sizeBytes,
                sizeFormatted = FamilyFileManagerHelper.formatFileSize(sizeBytes),
                progress = 0f,
                status = FileUploadStatus.FAILED,
                errorMessageHe = validation.messageHe,
                errorMessageEn = validation.messageEn,
                rawBytes = rawBytes
            )
            _activeUploads.value = listOf(failedTask) + _activeUploads.value
            _fileOperationError.value = validation.messageHe
            onComplete?.invoke(Result.failure(IllegalArgumentException(validation.messageHe)))
            return uploadId
        }

        val valid = validation as FamilyFileManagerHelper.FileValidationResult.Valid
        val existingNamesInFolder = _documents.value
            .filter { it.folderId == resolvedFolderId }
            .map { it.name }
        val uniqueDisplayName = FamilyFileManagerHelper.resolveUniqueDisplayFileName(
            desiredName = valid.sanitizedName,
            existingNamesInFolder = existingNamesInFolder
        )

        val initialTask = FileUploadTaskState(
            uploadId = uploadId,
            fileName = uniqueDisplayName,
            folderId = resolvedFolderId,
            extension = valid.extension,
            sizeBytes = valid.sizeBytes,
            sizeFormatted = valid.sizeFormatted,
            progress = 0.05f,
            status = FileUploadStatus.UPLOADING,
            mimeType = valid.mimeType,
            rawBytes = rawBytes
        )
        _activeUploads.value = listOf(initialTask) + _activeUploads.value.filterNot { it.uploadId == uploadId }

        val job = repoScope.launch {
            val result = executeFileUploadTask(
                uploadId = uploadId,
                folderId = resolvedFolderId,
                uniqueDisplayName = uniqueDisplayName,
                extension = valid.extension,
                mimeType = valid.mimeType,
                sizeBytes = valid.sizeBytes,
                sizeFormatted = valid.sizeFormatted,
                rawBytes = rawBytes,
                thumbnailBase64 = thumbnailBase64
            )
            onComplete?.invoke(result)
        }
        activeUploadJobs[uploadId] = job
        return uploadId
    }

    private suspend fun executeFileUploadTask(
        uploadId: String,
        folderId: String,
        uniqueDisplayName: String,
        extension: String,
        mimeType: String,
        sizeBytes: Long,
        sizeFormatted: String,
        rawBytes: ByteArray,
        thumbnailBase64: String? = null,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDocument> {
        val callerUid = currentUid()
        val callerMember = _currentMember.value
        val callerName = callerMember.name.ifBlank { "חבר משפחה" }
        val callerRole = callerMember.role.toFirestoreValue()
        val nowMillis = System.currentTimeMillis()
        val todayStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        val fileId = "doc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val storagePath = FamilyFileManagerHelper.buildStoragePath(targetFamilyId, fileId, uniqueDisplayName)

        val resolvedThumb = thumbnailBase64 ?: if (extension in setOf("JPG", "JPEG", "PNG", "WEBP")) {
            FamilyFileManagerHelper.createCompactThumbnailBase64(rawBytes)
        } else {
            null
        }

        val candidateDoc = FamilyDocument(
            id = fileId,
            folderId = folderId,
            name = uniqueDisplayName,
            extension = extension,
            sizeFormatted = sizeFormatted,
            uploadedByMemberId = callerUid,
            uploadedDateFormatted = todayStr,
            isDriveSynced = true,
            uploadedByName = callerName,
            uploadDateFormatted = todayStr,
            familyId = targetFamilyId,
            uploadedByUid = callerUid,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            storagePath = storagePath,
            uploadedAtMillis = nowMillis,
            thumbnailBase64 = resolvedThumb
        )

        return try {
            if (simulateNextFileUploadFailure) {
                simulateNextFileUploadFailure = false
                if (simulatedFileUploadStepDelayMs > 0L) delay(simulatedFileUploadStepDelayMs)
                throw IllegalStateException("העלאת הקובץ לענן נכשלה עקב שגיאת תקשורת. ניתן לנסות שוב.")
            }

            val db = if (authProvider()?.currentUser != null) firestoreProvider() else null
            val memberUids = _members.value.map { it.id }
            val parentUids = _members.value.filter { it.isParent }.map { it.id }
            val callerEmail = _googleUserEmail.value ?: callerMember.googleEmail
            val rootDriveFolderId = _familyDriveConfig.value.rootFolderId.trim()
                .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(targetFamilyId).orEmpty() }
                .ifBlank { "gdrive_root_$targetFamilyId" }
            val targetDriveFolderId = _folders.value.find { it.id == folderId }?.driveFolderId
                ?.takeIf { it.isNotBlank() && !it.startsWith("f_") }
                ?: rootDriveFolderId
            val uploadRes = FamilyCloudFileTransferEngine.uploadBinaryAndPersistMetadata(
                context = appContext,
                db = db,
                familyId = targetFamilyId,
                callerUid = callerUid,
                callerName = callerName,
                callerRole = callerRole,
                callerEmail = callerEmail,
                doc = candidateDoc,
                fileBytes = rawBytes,
                rootDriveFolderId = rootDriveFolderId,
                targetDriveFolderId = targetDriveFolderId,
                authorizedMemberUids = memberUids,
                parentUids = parentUids,
                simulatedStepDelayMs = simulatedFileUploadStepDelayMs,
                onProgress = { prog ->
                    updateUploadTaskState(uploadId) {
                        it.copy(progress = prog.coerceIn(0.05f, 1.0f), status = FileUploadStatus.UPLOADING)
                    }
                }
            )

            val confirmedDoc = uploadRes.getOrThrow()
            // Confirmed in Google Drive + metadata persisted: now mark as uploaded and add to documents list
            if (targetFamilyId == _activeFamilyId.value) {
                _documents.value = listOf(confirmedDoc) + _documents.value.filterNot { it.id == confirmedDoc.id }
                persistedDocumentsByFamily[targetFamilyId] = _documents.value
            }
            updateUploadTaskState(uploadId) {
                it.copy(
                    fileName = confirmedDoc.name,
                    progress = 1.0f,
                    status = FileUploadStatus.SUCCESS,
                    errorMessageHe = null,
                    errorMessageEn = null
                )
            }
            pushAlert(
                title = "קובץ חדש הועלה לתיקיית FamilyTasks ב-Google Drive ☁️",
                message = "${confirmedDoc.name} הועלה ע\"י $callerName",
                targetName = callerName,
                targetEmoji = callerMember.avatarEmoji.ifBlank { "📁" },
                reason = AlertReason.DRIVE_SYNC
            )
            activeUploadJobs.remove(uploadId)
            Result.success(confirmedDoc)
        } catch (ce: CancellationException) {
            activeUploadJobs.remove(uploadId)
            updateUploadTaskState(uploadId) {
                it.copy(
                    status = FileUploadStatus.CANCELLED,
                    errorMessageHe = "העלאת הקובץ בוטלה.",
                    errorMessageEn = "File upload was cancelled."
                )
            }
            Result.failure(ce)
        } catch (e: Exception) {
            activeUploadJobs.remove(uploadId)
            if (e is GoogleDriveFamilyService.DriveConsentRequiredException) {
                _driveConsentPendingIntent.value = e.pendingIntent
                _driveConsentIntent.value = e.legacyConsentIntent
            }
            if (e is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                handleSharedFolderPickerException(e)
            }
            val hebrewErr = when {
                e is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException ->
                    e.messageHe
                e is GoogleDriveFamilyService.DriveConsentRequiredException ->
                    "נדרש אישור גישה ל-Google Drive להעלאת הקובץ."
                e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                    "אין הרשאה להעלות קובץ למשפחה זו 🔒"
                !e.localizedMessage.isNullOrBlank() && e.localizedMessage!!.any { it in '\u0590'..'\u05FF' } ->
                    e.localizedMessage!!
                else ->
                    "העלאת הקובץ ל-Google Drive נכשלה. בדקו את החיבור לאינטרנט ונסו שוב."
            }
            updateUploadTaskState(uploadId) {
                it.copy(
                    status = FileUploadStatus.FAILED,
                    errorMessageHe = hebrewErr,
                    errorMessageEn = e.localizedMessage ?: "File upload failed."
                )
            }
            _fileOperationError.value = hebrewErr
            Result.failure(e)
        }
    }

    /**
     * Suspendable direct cloud upload API for coroutines and integration tests.
     */
    suspend fun uploadFamilyFileInCloud(
        folderId: String,
        rawFileName: String,
        rawExtension: String,
        rawBytes: ByteArray,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDocument> {
        val sizeBytes = rawBytes.size.toLong()
        val validation = FamilyFileManagerHelper.validateUploadCandidate(
            rawFileName = rawFileName,
            rawExtension = rawExtension,
            sizeBytes = sizeBytes
        )
        if (validation is FamilyFileManagerHelper.FileValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(validation.messageHe))
        }
        val valid = validation as FamilyFileManagerHelper.FileValidationResult.Valid
        val existingNamesInFolder = _documents.value
            .filter { it.folderId == folderId && (it.familyId.isBlank() || it.familyId == targetFamilyId) }
            .map { it.name }
        val uniqueDisplayName = FamilyFileManagerHelper.resolveUniqueDisplayFileName(
            desiredName = valid.sanitizedName,
            existingNamesInFolder = existingNamesInFolder
        )
        val uploadId = "upload_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val initialTask = FileUploadTaskState(
            uploadId = uploadId,
            fileName = uniqueDisplayName,
            folderId = folderId,
            extension = valid.extension,
            sizeBytes = valid.sizeBytes,
            sizeFormatted = valid.sizeFormatted,
            progress = 0.05f,
            status = FileUploadStatus.UPLOADING,
            mimeType = valid.mimeType,
            rawBytes = rawBytes
        )
        _activeUploads.value = listOf(initialTask) + _activeUploads.value
        return executeFileUploadTask(
            uploadId = uploadId,
            folderId = folderId,
            uniqueDisplayName = uniqueDisplayName,
            extension = valid.extension,
            mimeType = valid.mimeType,
            sizeBytes = valid.sizeBytes,
            sizeFormatted = valid.sizeFormatted,
            rawBytes = rawBytes,
            targetFamilyId = targetFamilyId
        )
    }

    fun cancelFileUpload(uploadId: String) {
        val job = activeUploadJobs.remove(uploadId)
        job?.cancel(CancellationException("בוטל על ידי המשתמש"))
        updateUploadTaskState(uploadId) {
            it.copy(
                status = FileUploadStatus.CANCELLED,
                errorMessageHe = "העלאת הקובץ בוטלה.",
                errorMessageEn = "File upload was cancelled."
            )
        }
    }

    fun retryFileUpload(uploadId: String, onComplete: ((Result<FamilyDocument>) -> Unit)? = null) {
        val task = _activeUploads.value.find { it.uploadId == uploadId } ?: return
        val bytes = task.rawBytes ?: FamilyFileManagerHelper.generateDocumentPdfBytes(
            title = task.fileName,
            uploaderName = _currentMember.value.name,
            dateFormatted = "היום",
            bodyLines = listOf("מסמך משותף: ${task.fileName}")
        )
        updateUploadTaskState(uploadId) {
            it.copy(
                progress = 0.05f,
                status = FileUploadStatus.UPLOADING,
                errorMessageHe = null,
                errorMessageEn = null
            )
        }
        val job = repoScope.launch {
            val res = executeFileUploadTask(
                uploadId = uploadId,
                folderId = task.folderId,
                uniqueDisplayName = task.fileName,
                extension = task.extension,
                mimeType = task.mimeType,
                sizeBytes = bytes.size.toLong().coerceAtLeast(1L),
                sizeFormatted = FamilyFileManagerHelper.formatFileSize(bytes.size.toLong().coerceAtLeast(1L)),
                rawBytes = bytes
            )
            onComplete?.invoke(res)
        }
        activeUploadJobs[uploadId] = job
    }

    fun dismissUploadTaskState(uploadId: String) {
        activeUploadJobs.remove(uploadId)?.cancel()
        _activeUploads.value = _activeUploads.value.filterNot { it.uploadId == uploadId }
    }

    private fun updateUploadTaskState(
        uploadId: String,
        transform: (FileUploadTaskState) -> FileUploadTaskState
    ) {
        _activeUploads.value = _activeUploads.value.map {
            if (it.uploadId == uploadId) transform(it) else it
        }
    }

    fun addDocument(doc: FamilyDocument) {
        val famId = _activeFamilyId.value.ifBlank { doc.familyId }
        val callerUid = currentUid()
        val callerName = _currentMember.value.name.ifBlank { doc.uploadedByName.ifBlank { "חבר משפחה" } }
        val existingNames = _documents.value
            .filter { it.folderId == doc.folderId && it.id != doc.id }
            .map { it.name }
        val sanitized = FamilyFileManagerHelper.sanitizeFileName(doc.name, doc.extension.lowercase())
        val deduplicatedName = FamilyFileManagerHelper.resolveUniqueDisplayFileName(sanitized, existingNames)
        val storagePath = FamilyFileManagerHelper.buildStoragePath(famId, doc.id, deduplicatedName)
        val rootDriveId = _familyDriveConfig.value.rootFolderId.ifBlank { "drive_root_$famId" }
        val targetDriveFolderId = _folders.value.find { it.id == doc.folderId }?.driveFolderId
            ?.takeIf { it.isNotBlank() } ?: rootDriveId
        val resolvedDriveFileId = doc.driveFileId.ifBlank { "drive_file_${doc.id}" }
        val enriched = doc.copy(
            name = deduplicatedName,
            familyId = famId,
            uploadedByUid = doc.uploadedByUid.ifBlank { callerUid },
            uploadedByMemberId = doc.uploadedByMemberId.ifBlank { callerUid },
            uploadedByName = callerName,
            storagePath = storagePath,
            driveFileId = resolvedDriveFileId,
            driveParentFolderId = targetDriveFolderId,
            webViewLink = doc.webViewLink.ifBlank { "https://drive.google.com/file/d/$resolvedDriveFileId/view" }
        )
        _documents.value = listOf(enriched) + _documents.value.filterNot { it.id == enriched.id }
        persistedDocumentsByFamily[famId] = _documents.value
        val uploader = _members.value.find { it.id == enriched.uploadedByMemberId } ?: _currentMember.value
        pushAlert(
            title = "קובץ חדש סונכרן לתיקייה המשפחתית ☁️",
            message = "${enriched.name} הועלה ע\"י ${uploader.name.ifBlank { "חבר משפחה" }}",
            targetName = uploader.name.ifBlank { "משפחה" },
            targetEmoji = uploader.avatarEmoji.ifBlank { "📁" },
            reason = AlertReason.DRIVE_SYNC
        )
        val db = firestoreProvider()
        if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
            repoScope.launch {
                runCatching {
                    val payload = FamilyCloudFileTransferEngine.buildFileFirestoreMap(
                        doc = enriched,
                        familyId = famId,
                        callerUid = callerUid,
                        callerName = callerName,
                        callerRole = _currentMember.value.role.toFirestoreValue()
                    )
                    db.collection("families").document(famId)
                        .collection("files").document(enriched.id)
                        .set(payload)
                        .await()
                }
            }
        }
    }

    /**
     * Renames a file in the shared `FamilyTasks` Google Drive workspace.
     * Available equally to both PARENT and CHILD members.
     */
    fun renameDocument(documentId: String, newName: String): Boolean {
        val target = _documents.value.find { it.id == documentId } ?: return false
        val clean = FamilyFileManagerHelper.sanitizeFileName(newName.trim(), target.extension.lowercase())
        if (clean.isEmpty()) return false
        val famId = _activeFamilyId.value
        _documents.value = _documents.value.map { doc ->
            if (doc.id == documentId) doc.copy(name = clean) else doc
        }
        persistedDocumentsByFamily[famId] = _documents.value
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val db = firestoreProvider()
        repoScope.launch {
            if (target.driveFileId.isNotBlank()) {
                val renameRes = GoogleDriveFamilyService.renameItemInDrive(
                    context = appContext,
                    accountEmail = accountEmail,
                    driveItemId = target.driveFileId,
                    newName = clean
                )
                renameRes.onFailure { err ->
                    _documents.value = _documents.value.map { if (it.id == documentId) target else it }
                    persistedDocumentsByFamily[famId] = _documents.value
                    if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                        handleSharedFolderPickerException(err)
                    } else {
                        _fileOperationError.value = err.localizedMessage ?: "שינוי שם הקובץ ב-Google Drive נכשל."
                    }
                    return@launch
                }
            }
            if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
                runCatching {
                    db.collection("families").document(famId)
                        .collection("files").document(documentId)
                        .update(
                            mapOf(
                                "name" to clean,
                                "updatedAt" to System.currentTimeMillis()
                            )
                        )
                        .await()
                }
            }
        }
        return true
    }

    /**
     * Moves a file to another folder inside the shared `FamilyTasks` Google Drive workspace.
     * Available equally to both PARENT and CHILD members.
     */
    fun moveDocumentToFolder(documentId: String, targetFolderId: String): Boolean {
        val targetDoc = _documents.value.find { it.id == documentId } ?: return false
        if (targetDoc.folderId == targetFolderId) return true
        val famId = _activeFamilyId.value
        val rootDriveId = _familyDriveConfig.value.rootFolderId.trim()
            .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(famId).orEmpty() }
            .ifBlank { "gdrive_root_$famId" }
        val newParentDriveId = _folders.value.find { it.id == targetFolderId }?.driveFolderId
            ?.takeIf { it.isNotBlank() && !it.startsWith("f_") } ?: rootDriveId
        val oldParentDriveId = targetDoc.driveParentFolderId.takeIf { it.isNotBlank() }

        val updatedDoc = targetDoc.copy(
            folderId = targetFolderId,
            driveParentFolderId = newParentDriveId
        )
        _documents.value = _documents.value.map { doc ->
            if (doc.id == documentId) updatedDoc else doc
        }
        persistedDocumentsByFamily[famId] = _documents.value

        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val db = firestoreProvider()
        repoScope.launch {
            if (targetDoc.driveFileId.isNotBlank()) {
                val moveRes = GoogleDriveFamilyService.moveItemInDrive(
                    context = appContext,
                    accountEmail = accountEmail,
                    driveItemId = targetDoc.driveFileId,
                    newParentDriveFolderId = newParentDriveId,
                    oldParentDriveFolderId = oldParentDriveId
                )
                moveRes.onFailure { err ->
                    _documents.value = _documents.value.map { if (it.id == documentId) targetDoc else it }
                    persistedDocumentsByFamily[famId] = _documents.value
                    if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                        handleSharedFolderPickerException(err)
                    } else {
                        _fileOperationError.value = err.localizedMessage ?: "העברת הקובץ ב-Google Drive נכשלה."
                    }
                    return@launch
                }
            }
            if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
                runCatching {
                    db.collection("families").document(famId)
                        .collection("files").document(documentId)
                        .update(
                            mapOf(
                                "folderId" to targetFolderId,
                                "driveParentFolderId" to newParentDriveId,
                                "updatedAt" to System.currentTimeMillis()
                            )
                        )
                        .await()
                }
            }
        }
        return true
    }

    suspend fun moveFamilyFileInCloud(
        documentId: String,
        targetFolderId: String,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<FamilyDocument> {
        val existing = _documents.value.find { it.id == documentId }
            ?: return Result.failure(IllegalArgumentException("קובץ לא נמצא"))
        val rootDriveId = _familyDriveConfig.value.rootFolderId.trim()
            .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(targetFamilyId).orEmpty() }
            .ifBlank { "gdrive_root_$targetFamilyId" }
        val newParentDriveId = _folders.value.find { it.id == targetFolderId }?.driveFolderId
            ?.takeIf { it.isNotBlank() && !it.startsWith("f_") } ?: rootDriveId
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        if (existing.driveFileId.isNotBlank()) {
            val moveRes = GoogleDriveFamilyService.moveItemInDrive(
                context = appContext,
                accountEmail = accountEmail,
                driveItemId = existing.driveFileId,
                newParentDriveFolderId = newParentDriveId,
                oldParentDriveFolderId = existing.driveParentFolderId.takeIf { it.isNotBlank() }
            )
            moveRes.onFailure { err ->
                if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                    handleSharedFolderPickerException(err)
                } else {
                    _fileOperationError.value = err.localizedMessage ?: "העברת הקובץ ב-Google Drive נכשלה."
                }
                return Result.failure(err)
            }
        }
        val updated = existing.copy(
            folderId = targetFolderId,
            driveParentFolderId = newParentDriveId
        )
        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null && targetFamilyId.isNotBlank()) {
            try {
                db.collection("families").document(targetFamilyId)
                    .collection("files").document(documentId)
                    .update(
                        mapOf(
                            "folderId" to targetFolderId,
                            "driveParentFolderId" to newParentDriveId,
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                    .await()
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        if (targetFamilyId == _activeFamilyId.value) {
            _documents.value = _documents.value.map { if (it.id == documentId) updated else it }
            persistedDocumentsByFamily[targetFamilyId] = _documents.value
        }
        return Result.success(updated)
    }

    /**
     * Moves a folder to another parent folder inside the shared `FamilyTasks` Google Drive workspace.
     * Available equally to both PARENT and CHILD members.
     */
    fun moveFolderToParent(folderId: String, newParentFolderId: String?): Boolean {
        if (folderId == newParentFolderId) return false
        val targetFolder = _folders.value.find { it.id == folderId } ?: return false
        val famId = _activeFamilyId.value
        val rootDriveId = _familyDriveConfig.value.rootFolderId.trim()
            .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(famId).orEmpty() }
            .ifBlank { "gdrive_root_$famId" }
        val newParentDriveId = newParentFolderId?.let { pid ->
            _folders.value.find { it.id == pid }?.driveFolderId?.takeIf { it.isNotBlank() && !it.startsWith("f_") }
        } ?: rootDriveId
        val updatedFolder = targetFolder.copy(
            parentFolderId = newParentFolderId,
            driveParentFolderId = newParentDriveId
        )
        _folders.value = _folders.value.map { if (it.id == folderId) updatedFolder else it }
        persistedFoldersByFamily[famId] = _folders.value

        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val db = firestoreProvider()
        repoScope.launch {
            if (targetFolder.driveFolderId.isNotBlank()) {
                val moveRes = GoogleDriveFamilyService.moveItemInDrive(
                    context = appContext,
                    accountEmail = accountEmail,
                    driveItemId = targetFolder.driveFolderId,
                    newParentDriveFolderId = newParentDriveId,
                    oldParentDriveFolderId = targetFolder.driveParentFolderId.takeIf { it.isNotBlank() }
                )
                moveRes.onFailure { err ->
                    _folders.value = _folders.value.map { if (it.id == folderId) targetFolder else it }
                    persistedFoldersByFamily[famId] = _folders.value
                    if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                        handleSharedFolderPickerException(err)
                    } else {
                        _fileOperationError.value = err.localizedMessage ?: "העברת התיקייה ב-Google Drive נכשלה."
                    }
                    return@launch
                }
            }
            if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
                runCatching {
                    db.collection("families").document(famId)
                        .collection("folders").document(folderId)
                        .update(
                            mapOf(
                                "parentFolderId" to (newParentFolderId ?: ""),
                                "driveParentFolderId" to newParentDriveId,
                                "updatedAt" to System.currentTimeMillis()
                            )
                        )
                        .await()
                }
            }
        }
        return true
    }

    /**
     * Deletes a file in the shared `FamilyTasks` Google Drive workspace.
     * Available equally to both PARENT and CHILD members.
     */
    fun deleteDocument(documentId: String): Boolean {
        val target = _documents.value.find { it.id == documentId } ?: return false
        val famId = _activeFamilyId.value
        val previousDocs = _documents.value
        _documents.value = _documents.value.filterNot { it.id == documentId }
        persistedDocumentsByFamily[famId] = _documents.value
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val db = firestoreProvider()
        repoScope.launch {
            if (target.driveFileId.isNotBlank()) {
                val delRes = GoogleDriveFamilyService.deleteItemInDrive(
                    driveItemId = target.driveFileId,
                    context = appContext,
                    accountEmail = accountEmail
                )
                delRes.onFailure { err ->
                    _documents.value = previousDocs
                    persistedDocumentsByFamily[famId] = previousDocs
                    if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                        handleSharedFolderPickerException(err)
                    } else {
                        _fileOperationError.value = err.localizedMessage ?: "מחיקת הקובץ מ-Google Drive נכשלה."
                    }
                    return@launch
                }
            }
            runCatching {
                if (db != null && famId.isNotBlank() && authProvider()?.currentUser != null) {
                    db.collection("families").document(famId)
                        .collection("files").document(documentId)
                        .delete()
                        .await()
                }
            }
        }
        return true
    }

    suspend fun deleteFamilyFileInCloud(
        fileId: String,
        targetFamilyId: String = _activeFamilyId.value
    ): Result<Unit> {
        val localTarget = _documents.value.find { it.id == fileId }
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        if (localTarget != null && localTarget.driveFileId.isNotBlank()) {
            val delRes = GoogleDriveFamilyService.deleteItemInDrive(
                driveItemId = localTarget.driveFileId,
                context = appContext,
                accountEmail = accountEmail
            )
            delRes.onFailure { err ->
                if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                    handleSharedFolderPickerException(err)
                } else {
                    _fileOperationError.value = err.localizedMessage ?: "מחיקת הקובץ מ-Google Drive נכשלה."
                }
                return Result.failure(err)
            }
        }
        val db = firestoreProvider()
        if (db != null && authProvider()?.currentUser != null && targetFamilyId.isNotBlank()) {
            try {
                db.collection("families").document(targetFamilyId)
                    .collection("files").document(fileId)
                    .delete()
                    .await()
            } catch (e: Exception) {
                _fileOperationError.value = "מחיקת הקובץ נכשלה 🔒"
                return Result.failure(e)
            }
        }
        if (targetFamilyId == _activeFamilyId.value) {
            _documents.value = _documents.value.filterNot { it.id == fileId }
            persistedDocumentsByFamily[targetFamilyId] = _documents.value
        }
        return Result.success(Unit)
    }

    suspend fun fetchDocumentBytes(doc: FamilyDocument): ByteArray? {
        val accountEmail = _googleUserEmail.value ?: _currentMember.value.googleEmail
        val driveFileId = doc.driveFileId.ifBlank { doc.id }
        val driveDownloadRes = GoogleDriveFamilyService.downloadFileBytesFromDrive(driveFileId)
        driveDownloadRes.exceptionOrNull()?.let { err ->
            if (err is GoogleDriveFamilyService.SharedFolderPickerAuthorizationRequiredException) {
                handleSharedFolderPickerException(err)
                return null
            }
        }
        return FamilyCloudFileTransferEngine.fetchDocumentBytes(
            context = appContext,
            db = firestoreProvider(),
            familyId = doc.familyId.ifBlank { _activeFamilyId.value },
            doc = doc,
            callerEmail = accountEmail
        )
    }

    // --- Notification Banner & History Management ---

    private fun observeIncomingPushEvents() {
        repoScope.launch {
            FcmTokenManager.incomingPushEvents.collect { payload ->
                val canonicalKey = FcmTokenManager.canonicalizeEventKey(payload.eventKey)
                val alreadyInHistory = _notifications.value.any { existing ->
                    val existingKey = existing.eventKey.orEmpty()
                    existingKey.isNotBlank() && (
                        existingKey == payload.eventKey ||
                            FcmTokenManager.canonicalizeEventKey(existingKey) == canonicalKey
                        )
                }
                val isOpenTaskEvent = payload.eventType == FcmTokenManager.EVENT_OPEN_TASK_CREATED
                val reason = when (payload.eventType) {
                    FcmTokenManager.EVENT_TASK_APPROVED -> AlertReason.TASK_COMPLETED
                    else -> AlertReason.TASK_REMINDER
                }
                if (!alreadyInHistory) {
                    pushAlert(
                        title = payload.title,
                        message = payload.body,
                        targetName = _currentMember.value.name.ifBlank { "בן משפחה" },
                        targetEmoji = if (isOpenTaskEvent) "🙋" else _currentMember.value.displayAvatarEmoji,
                        reason = reason,
                        showBanner = FcmTokenManager.isAppInForeground,
                        taskId = payload.taskId.takeIf { it.isNotBlank() },
                        eventKey = payload.eventKey,
                        isOpenTaskAlert = isOpenTaskEvent
                    )
                } else if (FcmTokenManager.isAppInForeground) {
                    val matchingAlert = _notifications.value.firstOrNull { existing ->
                        val existingKey = existing.eventKey.orEmpty()
                        existingKey == payload.eventKey ||
                            FcmTokenManager.canonicalizeEventKey(existingKey) == canonicalKey
                    }
                    if (matchingAlert != null) {
                        _activeBannerAlert.value = matchingAlert
                    }
                }
                if (FcmTokenManager.isAppInForeground) {
                    playForegroundEventSoundOnce(payload.eventKey, payload.eventType)
                }
            }
        }
    }

    private fun dispatchTaskNotificationOrForegroundBanner(
        title: String,
        message: String,
        targetName: String,
        targetEmoji: String,
        reason: AlertReason,
        shouldNotifyThisUser: Boolean,
        shouldPostOsNotificationWhenBackground: Boolean = shouldNotifyThisUser,
        eventKey: String,
        taskId: String,
        eventType: String,
        isOpenTaskAlert: Boolean = false
    ) {
        val inForeground = FcmTokenManager.isAppInForeground
        pushAlert(
            title = title,
            message = message,
            targetName = targetName,
            targetEmoji = targetEmoji,
            reason = reason,
            showBanner = shouldNotifyThisUser && inForeground,
            taskId = taskId,
            eventKey = eventKey,
            isOpenTaskAlert = isOpenTaskAlert
        )
        if (shouldNotifyThisUser && inForeground) {
            playForegroundEventSoundOnce(eventKey, eventType)
        } else if (shouldPostOsNotificationWhenBackground && !inForeground) {
            appContext?.let { ctx ->
                NotificationHelper.showIncomingFcmNotification(
                    context = ctx,
                    title = title,
                    message = message,
                    eventKey = eventKey,
                    taskId = taskId,
                    eventType = eventType
                )
            }
        }
    }

    private fun playForegroundEventSoundOnce(eventKey: String, eventType: String) {
        if (!FcmTokenManager.markSoundPlayedIfNew(eventKey)) return
        val usageType = NotificationHelper.resolveSoundUsageForEvent(eventType)
        val ctx = appContext
        if (ctx != null) {
            val uri = com.example.notifications.FamilySoundManager.resolvePlayableUriOrFallback(ctx, usageType)
            val cfg = com.example.notifications.FamilySoundManager.getSoundConfigForUsage(ctx, usageType)
            FcmTokenManager.recordForegroundSoundPlayed(uri.toString())
            com.example.notifications.FamilySoundManager.playNotificationSoundOneShot(
                context = ctx,
                uri = uri,
                volumePercent = cfg.volumePercent
            )
        } else {
            FcmTokenManager.recordForegroundSoundPlayed("default:${usageType.name}")
        }
    }

    /**
     * Automatically dismisses `_activeBannerAlert` if it references a task that was deleted,
     * completed, or an open task that was already claimed/assigned or cancelled.
     */
    fun reconcileActiveBannerAgainstTasks(currentTasks: List<FamilyTask> = _tasks.value) {
        val banner = _activeBannerAlert.value ?: return
        val bannerTaskId = banner.taskId?.takeIf { it.isNotBlank() } ?: return
        val matchingTask = currentTasks.find { it.id == bannerTaskId }
        if (matchingTask == null) {
            _activeBannerAlert.value = null
            return
        }
        if (banner.isOpenTaskAlert && !matchingTask.isUnassignedOpenTask) {
            _activeBannerAlert.value = null
        }
    }

    fun pushAlert(
        title: String,
        message: String,
        targetName: String,
        targetEmoji: String,
        reason: AlertReason,
        showBanner: Boolean = false,
        taskId: String? = null,
        eventKey: String? = null,
        isOpenTaskAlert: Boolean = false
    ) {
        val nowStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val alert = FamilyNotificationAlert(
            title = title,
            message = message,
            targetMemberName = targetName,
            targetMemberEmoji = targetEmoji,
            reason = reason,
            timestampFormatted = nowStr,
            taskId = taskId,
            eventKey = eventKey,
            isOpenTaskAlert = isOpenTaskAlert
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

    init {
        context?.let { loadUiPreferences(it.applicationContext ?: it) }
        restorePersistedPointsState(_activeFamilyId.value)
    }

    private fun loadUiPreferences(ctx: Context) {
        runCatching {
            val prefs = ctx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_THEME_MODE, null)?.let { raw ->
                runCatching { AppThemeMode.valueOf(raw) }.getOrNull()?.let { _themeMode.value = it }
            }
            prefs.getString(KEY_TEXT_SCALE, null)?.let { raw ->
                runCatching { AppTextScale.valueOf(raw) }.getOrNull()?.let { _appTextScale.value = it }
            }
            prefs.getString(KEY_APP_LANGUAGE, null)?.let { raw ->
                runCatching { AppLanguage.valueOf(raw) }.getOrNull()?.let { _appLanguage.value = it }
            }
            prefs.getString(KEY_FIRST_DAY_OF_WEEK, null)?.let { raw ->
                runCatching { FirstDayOfWeek.valueOf(raw) }.getOrNull()?.let { _firstDayOfWeek.value = it }
            }
            prefs.getString(KEY_TASKS_VIEW_MODE, null)?.let { raw ->
                _tasksViewMode.value = TasksViewMode.fromRaw(raw)
            }
        }
        restorePersistedPointsState(_activeFamilyId.value)
    }

    private fun saveUiPreference(key: String, value: String) {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key, value)
                .commit()
        }
    }

    fun setLanguage(lang: AppLanguage) {
        _appLanguage.value = lang
        saveUiPreference(KEY_APP_LANGUAGE, lang.name)
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        saveUiPreference(KEY_THEME_MODE, mode.name)
    }

    fun setAppTextScale(scale: AppTextScale) {
        _appTextScale.value = scale
        saveUiPreference(KEY_TEXT_SCALE, scale.name)
    }

    fun setFirstDayOfWeek(day: FirstDayOfWeek) {
        _firstDayOfWeek.value = day
        saveUiPreference(KEY_FIRST_DAY_OF_WEEK, day.name)
    }

    fun setTimetableViewMode(mode: TimetableViewMode) {
        _timetableViewMode.value = mode
    }

    fun setFilesViewMode(mode: FilesViewMode) {
        _filesViewMode.value = mode
    }

    fun setTasksViewMode(mode: TasksViewMode) {
        _tasksViewMode.value = mode
        saveUiPreference(KEY_TASKS_VIEW_MODE, mode.name)
    }

    /**
     * Toggles the tasks display mode ([TasksViewMode.STANDARD] <-> [TasksViewMode.COMPACT])
     * without changing [currentMember], user role, or parent permissions, and persists the choice.
     */
    fun toggleTasksViewMode(): TasksViewMode {
        val next = _tasksViewMode.value.toggled()
        setTasksViewMode(next)
        return next
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
        val effectiveHasDueTime = task.hasDueTime || task.reminderOffset != TaskReminderOffset.NONE
        val effectiveReminderOffset = if (effectiveHasDueTime) task.reminderOffset else TaskReminderOffset.NONE
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
                "assignedMemberId" to task.assignedToMemberId,
                "isOpenForVolunteering" to task.isOpenForVolunteering,
                "is_open_for_volunteering" to task.isOpenForVolunteering,
                "dateEpochDay" to task.dateEpochDay,
                "dueDate" to (task.dateEpochDay * 86_400_000L),
                "dueTimeFormatted" to task.dueTimeFormatted,
                "due_time" to task.dueTimeFormatted,
                "hasDueTime" to effectiveHasDueTime,
                "has_due_time" to effectiveHasDueTime,
                "reminderOffset" to effectiveReminderOffset.name,
                "reminder_offset" to effectiveReminderOffset.name,
                "reminderEnabled" to (effectiveHasDueTime && effectiveReminderOffset != TaskReminderOffset.NONE),
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
                val isOpen = doc.getBoolean("isOpenForVolunteering") ?: doc.getBoolean("is_open_for_volunteering") ?: false
                val epochDay = doc.getLong("dateEpochDay") ?: ((doc.getLong("dueDate") ?: 0L) / 86_400_000L)
                val pts = (doc.getLong("points") ?: doc.getLong("pointsValue") ?: 10L).toInt()
                val prioStr = doc.getString("priority") ?: TaskPriority.MEDIUM.name
                val catStr = doc.getString("category") ?: TaskCategory.OTHER.name
                val recStr = doc.getString("recurrence") ?: TaskRecurrence.ONCE.name
                val dueTime = doc.getString("dueTimeFormatted") ?: doc.getString("due_time") ?: "16:00"
                val offsetRaw = doc.getString("reminderOffset") ?: doc.getString("reminder_offset")
                val parsedOffset = TaskReminderOffset.fromRaw(offsetRaw)
                val hasDueTime = (doc.getBoolean("hasDueTime") ?: doc.getBoolean("has_due_time") ?: false) ||
                    parsedOffset != TaskReminderOffset.NONE
                FamilyTask(
                    id = doc.id,
                    familyId = doc.getString("familyId") ?: familyId,
                    title = title,
                    description = desc,
                    category = runCatching { TaskCategory.valueOf(catStr) }.getOrDefault(TaskCategory.OTHER),
                    assignedToMemberId = assigned,
                    isOpenForVolunteering = isOpen && assigned.isBlank(),
                    dateEpochDay = epochDay,
                    dueTimeFormatted = dueTime,
                    hasDueTime = hasDueTime,
                    reminderOffset = parsedOffset,
                    reminderEnabled = hasDueTime && parsedOffset != TaskReminderOffset.NONE,
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
        val effectiveHasDueTime = task.hasDueTime || task.reminderOffset != TaskReminderOffset.NONE
        val effectiveReminderOffset = if (effectiveHasDueTime) task.reminderOffset else TaskReminderOffset.NONE
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
                            "isOpenForVolunteering" to task.isOpenForVolunteering,
                            "is_open_for_volunteering" to task.isOpenForVolunteering,
                            "dateEpochDay" to task.dateEpochDay,
                            "dueDate" to (task.dateEpochDay * 86_400_000L),
                            "dueTimeFormatted" to task.dueTimeFormatted,
                            "due_time" to task.dueTimeFormatted,
                            "hasDueTime" to effectiveHasDueTime,
                            "has_due_time" to effectiveHasDueTime,
                            "reminderOffset" to effectiveReminderOffset.name,
                            "reminder_offset" to effectiveReminderOffset.name,
                            "reminderEnabled" to (effectiveHasDueTime && effectiveReminderOffset != TaskReminderOffset.NONE),
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
                        "dueTimeFormatted" to task.dueTimeFormatted,
                        "due_time" to task.dueTimeFormatted,
                        "hasDueTime" to effectiveHasDueTime,
                        "has_due_time" to effectiveHasDueTime,
                        "reminderOffset" to effectiveReminderOffset.name,
                        "reminder_offset" to effectiveReminderOffset.name,
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
            val rawAvatarId = snap.getString("avatarId")?.trim().orEmpty()
            val avatarOption = FamilyAvatarCatalog.findById(rawAvatarId)
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
                    avatarEmoji = avatarOption?.emoji ?: rawEmoji ?: identity.defaultEmoji,
                    avatarId = avatarOption?.id ?: rawAvatarId,
                    colorHex = avatarOption?.primaryColorHex
                        ?: snap.getLong("colorHex")
                        ?: if (role == UserRole.PARENT) 0xFF1A73E8L else 0xFF34A853L,
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

    // =========================================================================
    // Family Points Management System (Parent-Only Atomic Ledger & History)
    // =========================================================================

    fun clearPointsOperationError() {
        _pointsOperationError.value = null
    }

    fun getPointsHistoryForMember(
        memberId: String? = null,
        actionFilter: PointsActionType? = null
    ): List<PointsHistoryEntry> {
        return _pointsHistory.value
            .asSequence()
            .filter { memberId.isNullOrBlank() || it.memberId == memberId }
            .filter { actionFilter == null || it.actionType == actionFilter }
            .sortedByDescending { it.timestampMillis }
            .toList()
    }

    /**
     * Atomically adds points to [memberId]'s balance and creates an immutable [PointsHistoryEntry]
     * in `/families/{familyId}/points_history/{operationId}`.
     * Restricted strictly to PARENT role.
     */
    suspend fun addMemberPointsAsync(
        memberId: String,
        amount: Int,
        reason: String,
        operationId: String = "pts_add_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Result<PointsHistoryEntry> {
        return adjustMemberPointsAsync(
            memberId = memberId,
            actionType = PointsActionType.ADD,
            amount = amount,
            reason = reason.trim().ifBlank { "בונוס נקודות מהורה" },
            operationId = operationId,
            allowNegativeBalance = false
        )
    }

    /**
     * Atomically deducts points from [memberId]'s balance and creates an immutable [PointsHistoryEntry]
     * in `/families/{familyId}/points_history/{operationId}`.
     * Prevents negative balance by default (`allowNegativeBalance = false`).
     * Restricted strictly to PARENT role.
     */
    suspend fun deductMemberPointsAsync(
        memberId: String,
        amount: Int,
        reason: String,
        allowNegativeBalance: Boolean = false,
        operationId: String = "pts_ded_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Result<PointsHistoryEntry> {
        return adjustMemberPointsAsync(
            memberId = memberId,
            actionType = PointsActionType.DEDUCT,
            amount = amount,
            reason = reason.trim().ifBlank { "הפחתת נקודות" },
            operationId = operationId,
            allowNegativeBalance = allowNegativeBalance
        )
    }

    /**
     * Atomically resets ONLY [memberId]'s points balance to 0 and creates an immutable [PointsHistoryEntry]
     * in `/families/{familyId}/points_history/{operationId}` recording the previous balance and acting parent.
     * Never deletes task history or points history, and never modifies other family members.
     * Restricted strictly to PARENT role.
     */
    suspend fun resetMemberPointsAsync(
        memberId: String,
        reason: String = "איפוס יתרת נקודות ע\"י הורה",
        operationId: String = "pts_rst_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Result<PointsHistoryEntry> {
        return adjustMemberPointsAsync(
            memberId = memberId,
            actionType = PointsActionType.RESET,
            amount = 0,
            reason = reason.trim().ifBlank { "איפוס יתרת נקודות ע\"י הורה" },
            operationId = operationId,
            allowNegativeBalance = false
        )
    }

    fun addMemberPoints(
        memberId: String,
        amount: Int,
        reason: String,
        operationId: String = "pts_add_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי להוסיף נקודות לבני המשפחה 🔒"
            return false
        }
        if (amount <= 0) {
            _pointsOperationError.value = "יש להזין מספר נקודות חיובי וגדול מ-0"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                addMemberPointsAsync(memberId, amount, reason, operationId).isSuccess
            }
        }
        repoScope.launch {
            addMemberPointsAsync(memberId, amount, reason, operationId)
        }
        return true
    }

    fun deductMemberPoints(
        memberId: String,
        amount: Int,
        reason: String,
        allowNegativeBalance: Boolean = false,
        operationId: String = "pts_ded_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי להפחית נקודות לבני המשפחה 🔒"
            return false
        }
        if (amount <= 0) {
            _pointsOperationError.value = "יש להזין מספר נקודות חיובי להפחתה"
            return false
        }
        val target = _members.value.find { it.id == memberId }
        if (target != null && !allowNegativeBalance && target.earnedPoints - amount < 0) {
            _pointsOperationError.value = "לא ניתן להפחית $amount נקודות — היתרה הנוכחית של ${target.name} היא ${target.earnedPoints} נקודות בלבד"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                deductMemberPointsAsync(memberId, amount, reason, allowNegativeBalance, operationId).isSuccess
            }
        }
        repoScope.launch {
            deductMemberPointsAsync(memberId, amount, reason, allowNegativeBalance, operationId)
        }
        return true
    }

    fun resetMemberPoints(
        memberId: String,
        reason: String = "איפוס יתרת נקודות ע\"י הורה",
        operationId: String = "pts_rst_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי לאפס נקודות לבני המשפחה 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                resetMemberPointsAsync(memberId, reason, operationId).isSuccess
            }
        }
        repoScope.launch {
            resetMemberPointsAsync(memberId, reason, operationId)
        }
        return true
    }

    /**
     * Core serialized, idempotent, and atomic points ledger operation.
     * Uses [pointsMutex] to serialize concurrent calls and Firestore `runTransaction` when connected
     * so that updating `/families/{familyId}/members/{memberId}.earnedPoints` and creating
     * `/families/{familyId}/points_history/{operationId}` succeed or fail together.
     */
    suspend fun adjustMemberPointsAsync(
        memberId: String,
        actionType: PointsActionType,
        amount: Int,
        reason: String,
        operationId: String = "pts_${UUID.randomUUID().toString().replace("-", "").take(12)}",
        allowNegativeBalance: Boolean = false,
        taskId: String? = null
    ): Result<PointsHistoryEntry> = pointsMutex.withLock {
        val cleanOpId = operationId.trim().ifBlank {
            "pts_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        }

        // Idempotency guard: if this exact operationId already succeeded, return existing entry
        val existingEntry = _pointsHistory.value.find { it.operationId == cleanOpId || it.id == cleanOpId }
        if (existingEntry != null || !processedPointsOperationIds.add(cleanOpId)) {
            if (existingEntry != null) {
                return@withLock Result.success(existingEntry)
            }
        }

        val caller = _currentMember.value
        if (!caller.isParent) {
            processedPointsOperationIds.remove(cleanOpId)
            val errMsg = "רק הורה רשאי לבצע שינויי נקודות במשפחה 🔒"
            _pointsOperationError.value = errMsg
            return@withLock Result.failure(SecurityException(errMsg))
        }

        val cleanReason = reason.trim()
        if (cleanReason.isBlank()) {
            processedPointsOperationIds.remove(cleanOpId)
            val errMsg = "נא להזין סיבה לשינוי הנקודות"
            _pointsOperationError.value = errMsg
            return@withLock Result.failure(IllegalArgumentException(errMsg))
        }

        if (actionType != PointsActionType.RESET && amount <= 0) {
            processedPointsOperationIds.remove(cleanOpId)
            val errMsg = "מספר הנקודות חייב להיות שלם וחיובי (גדול מ-0)"
            _pointsOperationError.value = errMsg
            return@withLock Result.failure(IllegalArgumentException(errMsg))
        }

        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
        val localTarget = _members.value.find { it.id == memberId }
        if (localTarget == null) {
            processedPointsOperationIds.remove(cleanOpId)
            val errMsg = "חבר המשפחה המבוקש לא נמצא"
            _pointsOperationError.value = errMsg
            return@withLock Result.failure(IllegalArgumentException(errMsg))
        }

        val isDeductingAction = actionType == PointsActionType.DEDUCT ||
            actionType == PointsActionType.TRANSFER_OUT ||
            actionType == PointsActionType.REWARD_REDEEMED
        val localPrevBalance = localTarget.earnedPoints.coerceAtLeast(0)
        if (isDeductingAction && !allowNegativeBalance && localPrevBalance - amount < 0) {
            processedPointsOperationIds.remove(cleanOpId)
            val errMsg = "לא ניתן להפחית $amount נקודות — היתרה הנוכחית של ${localTarget.name} היא $localPrevBalance נקודות בלבד"
            _pointsOperationError.value = errMsg
            return@withLock Result.failure(IllegalArgumentException(errMsg))
        }

        _isProcessingPointsOperation.value = true
        _pointsOperationError.value = null

        val nowMillis = System.currentTimeMillis()
        val authUser = authProvider()?.currentUser
        val actorUid = authUser?.uid?.takeIf { it.isNotBlank() } ?: caller.id
        val actorName = caller.name.ifBlank { "הורה" }

        try {
            if (simulateOfflineNetworkFailure) {
                throw FirebaseFirestoreException(
                    "Simulated offline network failure during points transaction",
                    FirebaseFirestoreException.Code.UNAVAILABLE
                )
            }

            val db = firestoreProvider()
            val committedEntry: PointsHistoryEntry = if (db != null && authUser != null) {
                val familyRef = db.collection("families").document(familyId)
                val callerMemberRef = familyRef.collection("members").document(actorUid)
                val targetMemberRef = familyRef.collection("members").document(memberId)
                val historyDocRef = familyRef.collection("points_history").document(cleanOpId)

                db.runTransaction { tx ->
                    val existingHistSnap = tx.get(historyDocRef)
                    val callerSnap = tx.get(callerMemberRef)
                    val targetSnap = tx.get(targetMemberRef)

                    if (existingHistSnap.exists()) {
                        val existingData = existingHistSnap.data.orEmpty()
                        return@runTransaction parsePointsHistoryDocument(cleanOpId, existingData, familyId)
                            ?: PointsHistoryEntry(
                                id = cleanOpId,
                                operationId = cleanOpId,
                                familyId = familyId,
                                memberId = memberId,
                                memberName = localTarget.name,
                                actionType = actionType,
                                pointsDelta = 0,
                                previousBalance = localPrevBalance,
                                newBalance = localPrevBalance,
                                reason = cleanReason,
                                performedByUid = actorUid,
                                performedByName = actorName,
                                timestampMillis = nowMillis,
                                taskId = taskId
                            )
                    }

                    // Server-side role verification inside transaction
                    val callerRoleStr = callerSnap.getString("role").orEmpty()
                    if (callerSnap.exists() && !UserRole.fromFirestoreValue(callerRoleStr).isParent) {
                        throw SecurityException("Caller is not authorized as PARENT in family $familyId")
                    }

                    val previousBalance = if (targetSnap.exists()) {
                        (targetSnap.getLong("earnedPoints") ?: localPrevBalance.toLong()).toInt().coerceAtLeast(0)
                    } else {
                        localPrevBalance
                    }
                    val resolvedMemberName = targetSnap.getString("name")?.takeIf { it.isNotBlank() } ?: localTarget.name

                    val pointsDelta: Int
                    val newBalance: Int
                    when (actionType) {
                        PointsActionType.ADD,
                        PointsActionType.TASK_EARNED,
                        PointsActionType.TASK_SPLIT_EARNED,
                        PointsActionType.TRANSFER_IN,
                        PointsActionType.HELPING_BONUS -> {
                            pointsDelta = amount
                            newBalance = previousBalance + amount
                        }
                        PointsActionType.DEDUCT,
                        PointsActionType.TRANSFER_OUT,
                        PointsActionType.REWARD_REDEEMED -> {
                            if (!allowNegativeBalance && previousBalance - amount < 0) {
                                throw IllegalArgumentException("לא ניתן להפחית $amount נקודות — היתרה בענן היא $previousBalance נקודות בלבד")
                            }
                            pointsDelta = -amount
                            newBalance = if (allowNegativeBalance) previousBalance - amount else (previousBalance - amount).coerceAtLeast(0)
                        }
                        PointsActionType.RESET -> {
                            pointsDelta = -previousBalance
                            newBalance = 0
                        }
                    }

                    if (targetSnap.exists()) {
                        tx.update(
                            targetMemberRef,
                            mapOf(
                                "earnedPoints" to newBalance,
                                "lastPointsOperationId" to cleanOpId,
                                "updatedAt" to nowMillis
                            )
                        )
                    } else {
                        tx.set(
                            targetMemberRef,
                            mapOf(
                                "id" to localTarget.id,
                                "uid" to localTarget.id,
                                "familyId" to familyId,
                                "name" to localTarget.name,
                                "nameEn" to localTarget.nameEn,
                                "role" to localTarget.role.toFirestoreValue(),
                                "familyIdentity" to localTarget.familyIdentity.toFirestoreValue(),
                                "avatarEmoji" to localTarget.avatarEmoji,
                                "colorHex" to localTarget.colorHex,
                                "earnedPoints" to newBalance,
                                "spentPoints" to localTarget.spentPoints,
                                "lastPointsOperationId" to cleanOpId,
                                "invitationAccepted" to true,
                                "invitationPending" to false,
                                "updatedAt" to nowMillis
                            ),
                            SetOptions.merge()
                        )
                    }

                    val historyMap = mutableMapOf<String, Any?>(
                        "id" to cleanOpId,
                        "operationId" to cleanOpId,
                        "familyId" to familyId,
                        "memberId" to memberId,
                        "memberName" to resolvedMemberName,
                        "actionType" to actionType.firestoreValue,
                        "pointsDelta" to pointsDelta,
                        "previousBalance" to previousBalance,
                        "newBalance" to newBalance,
                        "reason" to cleanReason,
                        "performedByUid" to actorUid,
                        "performedByName" to actorName,
                        "timestampMillis" to nowMillis,
                        "serverTimestamp" to FieldValue.serverTimestamp()
                    )
                    if (!taskId.isNullOrBlank()) {
                        historyMap["taskId"] = taskId
                    }
                    tx.set(historyDocRef, historyMap)

                    PointsHistoryEntry(
                        id = cleanOpId,
                        operationId = cleanOpId,
                        familyId = familyId,
                        memberId = memberId,
                        memberName = resolvedMemberName,
                        actionType = actionType,
                        pointsDelta = pointsDelta,
                        previousBalance = previousBalance,
                        newBalance = newBalance,
                        reason = cleanReason,
                        performedByUid = actorUid,
                        performedByName = actorName,
                        timestampMillis = nowMillis,
                        taskId = taskId
                    )
                }.await()
            } else {
                // Local / offline / Robolectric execution path (atomic under pointsMutex)
                val currentMemberNow = _members.value.find { it.id == memberId } ?: localTarget
                val previousBalance = currentMemberNow.earnedPoints.coerceAtLeast(0)
                val pointsDelta: Int
                val newBalance: Int
                when (actionType) {
                    PointsActionType.ADD,
                    PointsActionType.TASK_EARNED,
                    PointsActionType.TASK_SPLIT_EARNED,
                    PointsActionType.TRANSFER_IN,
                    PointsActionType.HELPING_BONUS -> {
                        pointsDelta = amount
                        newBalance = previousBalance + amount
                    }
                    PointsActionType.DEDUCT,
                    PointsActionType.TRANSFER_OUT,
                    PointsActionType.REWARD_REDEEMED -> {
                        if (!allowNegativeBalance && previousBalance - amount < 0) {
                            throw IllegalArgumentException("לא ניתן להפחית $amount נקודות — היתרה הנוכחית היא $previousBalance נקודות בלבד")
                        }
                        pointsDelta = -amount
                        newBalance = if (allowNegativeBalance) previousBalance - amount else (previousBalance - amount).coerceAtLeast(0)
                    }
                    PointsActionType.RESET -> {
                        pointsDelta = -previousBalance
                        newBalance = 0
                    }
                }
                PointsHistoryEntry(
                    id = cleanOpId,
                    operationId = cleanOpId,
                    familyId = familyId,
                    memberId = memberId,
                    memberName = currentMemberNow.name,
                    actionType = actionType,
                    pointsDelta = pointsDelta,
                    previousBalance = previousBalance,
                    newBalance = newBalance,
                    reason = cleanReason,
                    performedByUid = actorUid,
                    performedByName = actorName,
                    timestampMillis = nowMillis,
                    taskId = taskId
                )
            }

            // Apply committed balance & history entry to in-memory state and local SharedPreferences
            _members.value = _members.value.map { m ->
                if (m.id == memberId) {
                    val updated = m.copy(earnedPoints = committedEntry.newBalance)
                    if (_currentMember.value.id == m.id) {
                        _currentMember.value = updated
                    }
                    updated
                } else {
                    m
                }
            }
            persistMemberPointsBalanceLocally(familyId, memberId, committedEntry.newBalance)
            appendPointsHistoryEntryLocally(familyId, committedEntry)

            _isProcessingPointsOperation.value = false
            return@withLock Result.success(committedEntry)
        } catch (e: Exception) {
            processedPointsOperationIds.remove(cleanOpId)
            _isProcessingPointsOperation.value = false
            val friendlyMsg = e.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "שגיאה בעדכון הנקודות. הפעולה בוטלה כדי לשמור על תקינות הנתונים."
            _pointsOperationError.value = friendlyMsg
            return@withLock Result.failure(e)
        }
    }

    private fun parsePointsHistoryDocument(
        docId: String,
        data: Map<String, Any?>,
        fallbackFamilyId: String
    ): PointsHistoryEntry? {
        val memberId = (data["memberId"] as? String)?.trim().orEmpty()
        if (memberId.isBlank()) return null
        val actionRaw = (data["actionType"] as? String) ?: "ADD"
        val actionType = PointsActionType.fromFirestoreValue(actionRaw)
        val delta = (data["pointsDelta"] as? Number)?.toInt() ?: 0
        val prevBal = (data["previousBalance"] as? Number)?.toInt() ?: 0
        val newBal = (data["newBalance"] as? Number)?.toInt() ?: 0
        val reason = (data["reason"] as? String)?.takeIf { it.isNotBlank() } ?: actionType.labelHe
        val performedByUid = (data["performedByUid"] as? String).orEmpty()
        val performedByName = (data["performedByName"] as? String)?.takeIf { it.isNotBlank() } ?: "הורה"
        val memberName = (data["memberName"] as? String)?.takeIf { it.isNotBlank() }
            ?: _members.value.find { it.id == memberId }?.name.orEmpty()
        val tsMillis = (data["timestampMillis"] as? Number)?.toLong()
            ?: (data["serverTimestamp"] as? com.google.firebase.Timestamp)?.toDate()?.time
            ?: System.currentTimeMillis()
        val opId = (data["operationId"] as? String)?.takeIf { it.isNotBlank() } ?: docId
        val taskId = (data["taskId"] as? String)?.takeIf { it.isNotBlank() }
        val performedTaskByMemberId = (data["performedTaskByMemberId"] as? String)?.takeIf { it.isNotBlank() }
        val performedTaskByMemberName = (data["performedTaskByMemberName"] as? String)?.takeIf { it.isNotBlank() }
        val relatedMemberId = (data["relatedMemberId"] as? String)?.takeIf { it.isNotBlank() }
        val relatedMemberName = (data["relatedMemberName"] as? String)?.takeIf { it.isNotBlank() }
        val rewardId = (data["rewardId"] as? String)?.takeIf { it.isNotBlank() }
        val redemptionId = (data["redemptionId"] as? String)?.takeIf { it.isNotBlank() }
        val proposalId = (data["proposalId"] as? String)?.takeIf { it.isNotBlank() }
        return PointsHistoryEntry(
            id = docId,
            operationId = opId,
            familyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackFamilyId,
            memberId = memberId,
            memberName = memberName,
            actionType = actionType,
            pointsDelta = delta,
            previousBalance = prevBal,
            newBalance = newBal,
            reason = reason,
            performedByUid = performedByUid,
            performedByName = performedByName,
            timestampMillis = tsMillis,
            taskId = taskId,
            performedTaskByMemberId = performedTaskByMemberId,
            performedTaskByMemberName = performedTaskByMemberName,
            relatedMemberId = relatedMemberId,
            relatedMemberName = relatedMemberName,
            rewardId = rewardId,
            redemptionId = redemptionId,
            proposalId = proposalId
        )
    }

    private fun mergeRemotePointsHistory(familyId: String, remoteEntries: List<PointsHistoryEntry>) {
        val localEntries = _pointsHistory.value.filter { it.familyId.isBlank() || it.familyId == familyId }
        val mergedById = LinkedHashMap<String, PointsHistoryEntry>()
        (remoteEntries + localEntries).forEach { entry ->
            val key = entry.operationId.ifBlank { entry.id }
            if (!mergedById.containsKey(key)) {
                mergedById[key] = entry
            }
            processedPointsOperationIds.add(key)
        }
        val sorted = mergedById.values.sortedByDescending { it.timestampMillis }
        _pointsHistory.value = sorted
        savePointsHistoryToPrefs(familyId, sorted)
    }

    private fun appendPointsHistoryEntryLocally(familyId: String, entry: PointsHistoryEntry) {
        val key = entry.operationId.ifBlank { entry.id }
        processedPointsOperationIds.add(key)
        val updated = (listOf(entry) + _pointsHistory.value.filterNot {
            it.id == entry.id || it.operationId == key
        }).sortedByDescending { it.timestampMillis }
        _pointsHistory.value = updated
        savePointsHistoryToPrefs(familyId, updated)
    }

    private fun persistMemberPointsBalanceLocally(familyId: String, memberId: String, newBalance: Int) {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            val key = "pts_bal_${familyId}_$memberId"
            prefs.edit().putInt(key, newBalance).commit()
        }
    }

    private fun persistMemberTargetRewardLocally(familyId: String, memberId: String, rewardId: String?) {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            val key = "target_reward_${familyId}_$memberId"
            val editor = prefs.edit()
            if (rewardId.isNullOrBlank()) {
                editor.remove(key)
            } else {
                editor.putString(key, rewardId)
            }
            editor.commit()
        }
    }

    private fun persistMemberAvatarLocally(familyId: String, memberId: String, avatarId: String?) {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            val key = "avatar_id_${familyId}_$memberId"
            val editor = prefs.edit()
            if (avatarId.isNullOrBlank()) {
                editor.remove(key)
            } else {
                editor.putString(key, avatarId.trim())
            }
            editor.commit()
        }
    }

    private fun savePointsHistoryToPrefs(familyId: String, entries: List<PointsHistoryEntry>) {
        val ctx = appContext ?: return
        runCatching {
            val arr = org.json.JSONArray()
            entries.take(300).forEach { e ->
                val obj = org.json.JSONObject().apply {
                    put("id", e.id)
                    put("operationId", e.operationId)
                    put("familyId", e.familyId.ifBlank { familyId })
                    put("memberId", e.memberId)
                    put("memberName", e.memberName)
                    put("actionType", e.actionType.firestoreValue)
                    put("pointsDelta", e.pointsDelta)
                    put("previousBalance", e.previousBalance)
                    put("newBalance", e.newBalance)
                    put("reason", e.reason)
                    put("performedByUid", e.performedByUid)
                    put("performedByName", e.performedByName)
                    put("timestampMillis", e.timestampMillis)
                    if (!e.taskId.isNullOrBlank()) put("taskId", e.taskId)
                    if (!e.performedTaskByMemberId.isNullOrBlank()) put("performedTaskByMemberId", e.performedTaskByMemberId)
                    if (!e.performedTaskByMemberName.isNullOrBlank()) put("performedTaskByMemberName", e.performedTaskByMemberName)
                    if (!e.relatedMemberId.isNullOrBlank()) put("relatedMemberId", e.relatedMemberId)
                    if (!e.relatedMemberName.isNullOrBlank()) put("relatedMemberName", e.relatedMemberName)
                    if (!e.rewardId.isNullOrBlank()) put("rewardId", e.rewardId)
                    if (!e.redemptionId.isNullOrBlank()) put("redemptionId", e.redemptionId)
                    if (!e.proposalId.isNullOrBlank()) put("proposalId", e.proposalId)
                }
                arr.put(obj)
            }
            ctx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("pts_hist_$familyId", arr.toString())
                .commit()
        }
    }

    private fun restorePersistedPointsState(
        familyId: String,
        allowOverwritingMemberBalances: Boolean = true
    ) {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            // 1. Restore member point balances, personal reward goals & avatars for this family ONLY if live server state has not already synced
            if (allowOverwritingMemberBalances && !syncedServerMemberBalancesForFamilies.contains(familyId)) {
                var currentUpdated: FamilyMember? = null
                val restoredMembers = _members.value.map { member ->
                    val balKey = "pts_bal_${familyId}_${member.id}"
                    val goalKey = "target_reward_${familyId}_${member.id}"
                    val avatarKey = "avatar_id_${familyId}_${member.id}"
                    val savedBal = if (prefs.contains(balKey)) {
                        prefs.getInt(balKey, member.earnedPoints).coerceAtLeast(0)
                    } else {
                        member.earnedPoints
                    }
                    val savedGoal = if (prefs.contains(goalKey)) {
                        prefs.getString(goalKey, null)?.takeIf { it.isNotBlank() }
                    } else {
                        member.targetRewardId
                    }
                    val savedAvatarId = if (prefs.contains(avatarKey)) {
                        prefs.getString(avatarKey, null)?.trim().orEmpty()
                    } else {
                        member.avatarId
                    }
                    val avatarOption = FamilyAvatarCatalog.findById(savedAvatarId)
                    val resolvedEmoji = avatarOption?.emoji ?: member.avatarEmoji
                    val resolvedColor = avatarOption?.primaryColorHex ?: member.colorHex
                    if (savedBal != member.earnedPoints || savedGoal != member.targetRewardId || savedAvatarId != member.avatarId) {
                        val updated = member.copy(
                            earnedPoints = savedBal,
                            targetRewardId = savedGoal,
                            avatarId = avatarOption?.id ?: savedAvatarId,
                            avatarEmoji = resolvedEmoji,
                            colorHex = resolvedColor
                        )
                        if (_currentMember.value.id == member.id) {
                            currentUpdated = updated
                        }
                        updated
                    } else {
                        member
                    }
                }
                _members.value = restoredMembers
                if (currentUpdated != null) {
                    _currentMember.value = currentUpdated!!
                }
            }

            // 2. Restore points history for this family
            val rawHist = prefs.getString("pts_hist_$familyId", null)
            if (!rawHist.isNullOrBlank()) {
                val arr = org.json.JSONArray(rawHist)
                val restoredList = mutableListOf<PointsHistoryEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val entry = PointsHistoryEntry(
                        id = obj.optString("id"),
                        operationId = obj.optString("operationId", obj.optString("id")),
                        familyId = obj.optString("familyId", familyId),
                        memberId = obj.optString("memberId"),
                        memberName = obj.optString("memberName"),
                        actionType = PointsActionType.fromFirestoreValue(obj.optString("actionType", "ADD")),
                        pointsDelta = obj.optInt("pointsDelta", 0),
                        previousBalance = obj.optInt("previousBalance", 0),
                        newBalance = obj.optInt("newBalance", 0),
                        reason = obj.optString("reason"),
                        performedByUid = obj.optString("performedByUid"),
                        performedByName = obj.optString("performedByName", "הורה"),
                        timestampMillis = obj.optLong("timestampMillis", 0L),
                        taskId = obj.optString("taskId").takeIf { it.isNotBlank() },
                        performedTaskByMemberId = obj.optString("performedTaskByMemberId").takeIf { it.isNotBlank() },
                        performedTaskByMemberName = obj.optString("performedTaskByMemberName").takeIf { it.isNotBlank() },
                        relatedMemberId = obj.optString("relatedMemberId").takeIf { it.isNotBlank() },
                        relatedMemberName = obj.optString("relatedMemberName").takeIf { it.isNotBlank() },
                        rewardId = obj.optString("rewardId").takeIf { it.isNotBlank() },
                        redemptionId = obj.optString("redemptionId").takeIf { it.isNotBlank() },
                        proposalId = obj.optString("proposalId").takeIf { it.isNotBlank() }
                    )
                    if (entry.memberId.isNotBlank()) {
                        restoredList.add(entry)
                        processedPointsOperationIds.add(entry.operationId.ifBlank { entry.id })
                    }
                }
                if (restoredList.isNotEmpty()) {
                    val existingById = _pointsHistory.value.associateBy { it.operationId.ifBlank { it.id } }.toMutableMap()
                    restoredList.forEach { r ->
                        existingById.putIfAbsent(r.operationId.ifBlank { r.id }, r)
                    }
                    _pointsHistory.value = existingById.values.sortedByDescending { it.timestampMillis }
                }
            }
        }
    }

    // =========================================================================
    //  TASK COLLABORATION (PART A & B): VOLUNTEER FOR TASK & SWAP TASKS
    // =========================================================================

    private val collaborationMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Optional hook for unit/Robolectric tests to simulate concurrent remote task state changes
     * (e.g., another parent on a second device approving a different volunteer right before commit).
     */
    internal var remoteTaskConflictStateProvider: ((taskId: String) -> FamilyTask?)? = null

    fun clearCollaborationError() {
        _collaborationError.value = null
    }

    private fun parseTaskProposalDocument(
        docId: String,
        data: Map<String, Any?>,
        fallbackFamilyId: String
    ): TaskCollaborationProposal? {
        val requesterChildId = (data["requesterChildId"] as? String)?.trim().orEmpty()
        val targetChildId = (data["targetChildId"] as? String)?.trim().orEmpty()
        val primaryTaskId = (data["primaryTaskId"] as? String)?.trim().orEmpty()
        val isOpenTaskVolunteer = (data["isOpenTaskVolunteer"] as? Boolean)
            ?: (data["is_open_task_volunteer"] as? Boolean)
            ?: false
        if (requesterChildId.isBlank() || primaryTaskId.isBlank()) return null
        if (!isOpenTaskVolunteer && targetChildId.isBlank()) return null

        val typeRaw = (data["type"] as? String) ?: TaskProposalType.VOLUNTEER_FOR_TASK.firestoreValue
        val defaultStatus = if (isOpenTaskVolunteer) {
            TaskProposalStatus.PENDING_PARENT_APPROVAL.firestoreValue
        } else {
            TaskProposalStatus.PENDING_CHILD_APPROVAL.firestoreValue
        }
        val statusRaw = (data["status"] as? String) ?: defaultStatus
        val createdAt = (data["createdAtMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val updatedAt = (data["updatedAtMillis"] as? Number)?.toLong() ?: createdAt

        return TaskCollaborationProposal(
            id = docId,
            operationId = (data["operationId"] as? String)?.takeIf { it.isNotBlank() } ?: docId,
            familyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackFamilyId,
            type = TaskProposalType.fromFirestoreValue(typeRaw),
            requesterChildId = requesterChildId,
            requesterChildName = (data["requesterChildName"] as? String).orEmpty(),
            targetChildId = targetChildId,
            targetChildName = (data["targetChildName"] as? String).orEmpty(),
            primaryTaskId = primaryTaskId,
            primaryTaskTitle = (data["primaryTaskTitle"] as? String).orEmpty(),
            primaryTaskPoints = (data["primaryTaskPoints"] as? Number)?.toInt() ?: 0,
            isOpenTaskVolunteer = isOpenTaskVolunteer,
            offeredSwapTaskId = (data["offeredSwapTaskId"] as? String)?.takeIf { it.isNotBlank() },
            offeredSwapTaskTitle = (data["offeredSwapTaskTitle"] as? String)?.takeIf { it.isNotBlank() },
            offeredSwapTaskPoints = (data["offeredSwapTaskPoints"] as? Number)?.toInt(),
            status = TaskProposalStatus.fromFirestoreValue(statusRaw),
            note = (data["note"] as? String).orEmpty(),
            childRespondedAtMillis = (data["childRespondedAtMillis"] as? Number)?.toLong(),
            parentDecidedByUid = (data["parentDecidedByUid"] as? String)?.takeIf { it.isNotBlank() },
            parentDecidedByName = (data["parentDecidedByName"] as? String)?.takeIf { it.isNotBlank() },
            parentDecidedAtMillis = (data["parentDecidedAtMillis"] as? Number)?.toLong(),
            createdAtMillis = createdAt,
            updatedAtMillis = updatedAt
        )
    }

    private fun buildTaskProposalFirestoreMap(proposal: TaskCollaborationProposal): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "id" to proposal.id,
            "operationId" to proposal.operationId,
            "familyId" to proposal.familyId,
            "type" to proposal.type.firestoreValue,
            "requesterChildId" to proposal.requesterChildId,
            "requesterChildName" to proposal.requesterChildName,
            "targetChildId" to proposal.targetChildId,
            "targetChildName" to proposal.targetChildName,
            "primaryTaskId" to proposal.primaryTaskId,
            "primaryTaskTitle" to proposal.primaryTaskTitle,
            "primaryTaskPoints" to proposal.primaryTaskPoints,
            "isOpenTaskVolunteer" to proposal.isOpenTaskVolunteer,
            "status" to proposal.status.firestoreValue,
            "note" to proposal.note,
            "createdAtMillis" to proposal.createdAtMillis,
            "updatedAtMillis" to proposal.updatedAtMillis
        )
        if (!proposal.offeredSwapTaskId.isNullOrBlank()) map["offeredSwapTaskId"] = proposal.offeredSwapTaskId
        if (!proposal.offeredSwapTaskTitle.isNullOrBlank()) map["offeredSwapTaskTitle"] = proposal.offeredSwapTaskTitle
        if (proposal.offeredSwapTaskPoints != null) map["offeredSwapTaskPoints"] = proposal.offeredSwapTaskPoints
        if (proposal.childRespondedAtMillis != null) map["childRespondedAtMillis"] = proposal.childRespondedAtMillis
        if (!proposal.parentDecidedByUid.isNullOrBlank()) map["parentDecidedByUid"] = proposal.parentDecidedByUid
        if (!proposal.parentDecidedByName.isNullOrBlank()) map["parentDecidedByName"] = proposal.parentDecidedByName
        if (proposal.parentDecidedAtMillis != null) map["parentDecidedAtMillis"] = proposal.parentDecidedAtMillis
        return map
    }

    /**
     * Part C (Open Tasks for Volunteering):
     * Allows a CHILD in the family to volunteer for an unassigned open task (`isOpenForVolunteering == true`).
     * - Creates a volunteer request in `task_proposals` with deterministic ID `vol_open_{taskId}_{childId}`
     *   in [TaskProposalStatus.PENDING_PARENT_APPROVAL].
     * - Multiple different children can volunteer for the same open task.
     * - Prevents the same child from creating duplicate pending volunteer requests for the same task.
     * - Does NOT assign the task before parent approval.
     */
    suspend fun volunteerForOpenTaskAsync(
        taskId: String,
        note: String = ""
    ): Result<TaskCollaborationProposal> {
        val requester = _currentMember.value
        if (requester.isParent) {
            val msg = "רק ילדי המשפחה יכולים להתנדב למשימה פתוחה"
            _collaborationError.value = msg
            return Result.failure(SecurityException(msg))
        }
        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
        val targetTask = _tasks.value.find { it.id == taskId }
        if (targetTask == null) {
            _collaborationError.value = "המשימה הפתוחה לא נמצאה"
            return Result.failure(IllegalArgumentException("המשימה הפתוחה לא נמצאה"))
        }
        if (targetTask.isCompleted || targetTask.approvedByParent) {
            val msg = "לא ניתן להתנדב למשימה שכבר הושלמה"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        if (!targetTask.isOpenForVolunteering || targetTask.assignedToMemberId.isNotBlank()) {
            val msg = "משימה זו כבר שויכה או אינה פתוחה להתנדבות"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val proposalId = "vol_open_${targetTask.id}_${requester.id}"
        val existingForSameChild = _taskProposals.value.find {
            (it.id == proposalId || (it.primaryTaskId == targetTask.id && it.requesterChildId == requester.id && it.isOpenTaskVolunteer)) &&
                it.status.isPending
        }
        if (existingForSameChild != null) {
            val msg = "כבר שלחת בקשת התנדבות פעילה למשימה זו"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val nowMillis = System.currentTimeMillis()
        val proposal = TaskCollaborationProposal(
            id = proposalId,
            operationId = proposalId,
            familyId = familyId,
            type = TaskProposalType.VOLUNTEER_FOR_TASK,
            requesterChildId = requester.id,
            requesterChildName = requester.name,
            targetChildId = "",
            targetChildName = "משימה פתוחה",
            primaryTaskId = targetTask.id,
            primaryTaskTitle = targetTask.title,
            primaryTaskPoints = targetTask.points,
            isOpenTaskVolunteer = true,
            status = TaskProposalStatus.PENDING_PARENT_APPROVAL,
            note = note.trim(),
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val proposalRef = db.collection("families").document(familyId)
                    .collection("task_proposals").document(proposalId)
                proposalRef.set(buildTaskProposalFirestoreMap(proposal)).await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בשליחת בקשת ההתנדבות למשימה הפתוחה"
                _collaborationError.value = msg
                return Result.failure(e)
            }
        }

        _collaborationError.value = null
        _taskProposals.value = listOf(proposal) + _taskProposals.value.filterNot { it.id == proposalId }

        pushAlert(
            title = "🙋 התנדבות למשימה פתוחה",
            message = "${requester.name} מתנדב/ת לבצע את המשימה הפתוחה \"${targetTask.title}\" (+${targetTask.points} נק'). ממתין לאישור הורה.",
            targetName = "הורים",
            targetEmoji = requester.avatarEmoji.ifBlank { "🙋" },
            reason = AlertReason.TASK_REMINDER
        )
        return Result.success(proposal)
    }

    fun volunteerForOpenTask(taskId: String, note: String = ""): Boolean {
        if (_currentMember.value.isParent) {
            _collaborationError.value = "רק ילדי המשפחה יכולים להתנדב למשימה פתוחה"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                volunteerForOpenTaskAsync(taskId, note).isSuccess
            }
        }
        repoScope.launch {
            volunteerForOpenTaskAsync(taskId, note)
        }
        return true
    }

    suspend fun selectVolunteerForOpenTaskAsync(proposalId: String): Result<TaskCollaborationProposal> {
        return decideTaskProposalAsParentAsync(proposalId = proposalId, approve = true)
    }

    fun selectVolunteerForOpenTask(proposalId: String): Boolean {
        return decideTaskProposalAsParent(proposalId = proposalId, approve = true)
    }

    /**
     * Part A: Child proposes to volunteer to perform another child's active task.
     * Stage 1 -> Creates proposal in [TaskProposalStatus.PENDING_CHILD_APPROVAL].
     * Task ownership is NOT changed until both target child and parent approve.
     */
    suspend fun proposeVolunteerForTaskAsync(
        taskId: String,
        note: String = ""
    ): Result<TaskCollaborationProposal> {
        val requester = _currentMember.value
        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
        val targetTask = _tasks.value.find { it.id == taskId }
        if (targetTask == null) {
            _collaborationError.value = "המשימה המבוקשת לא נמצאה"
            return Result.failure(IllegalArgumentException("המשימה המבוקשת לא נמצאה"))
        }

        if (targetTask.isCompleted || targetTask.approvedByParent) {
            val msg = "לא ניתן להציע לבצע משימה שכבר הושלמה"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }
        if (targetTask.assignedToMemberId.isBlank() || targetTask.assignedToMemberId == requester.id) {
            val msg = "לא ניתן להתנדב למשימה שכבר משויכת אליך"
            _collaborationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        val hasPendingProposal = !targetTask.pendingProposalId.isNullOrBlank() ||
            _taskProposals.value.any {
                it.status.isPending && (it.primaryTaskId == taskId || it.offeredSwapTaskId == taskId)
            }
        if (hasPendingProposal) {
            val msg = "קיימת כבר הצעה פעילה עבור משימה זו"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val targetChild = _members.value.find { it.id == targetTask.assignedToMemberId }
        val targetChildName = targetChild?.name?.ifBlank { "ילד/ה" } ?: "ילד/ה"
        val nowMillis = System.currentTimeMillis()
        val proposalId = "prop_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val proposal = TaskCollaborationProposal(
            id = proposalId,
            operationId = proposalId,
            familyId = familyId,
            type = TaskProposalType.VOLUNTEER_FOR_TASK,
            requesterChildId = requester.id,
            requesterChildName = requester.name,
            targetChildId = targetTask.assignedToMemberId,
            targetChildName = targetChildName,
            primaryTaskId = targetTask.id,
            primaryTaskTitle = targetTask.title,
            primaryTaskPoints = targetTask.points,
            status = TaskProposalStatus.PENDING_CHILD_APPROVAL,
            note = note.trim(),
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val familyRef = db.collection("families").document(familyId)
                val proposalRef = familyRef.collection("task_proposals").document(proposalId)
                val taskRef = familyRef.collection("tasks").document(targetTask.id)
                db.runBatch { batch ->
                    batch.set(proposalRef, buildTaskProposalFirestoreMap(proposal))
                    batch.update(taskRef, mapOf("pendingProposalId" to proposalId, "updatedAt" to nowMillis))
                }.await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בשליחת ההצעה לביצוע המשימה"
                _collaborationError.value = msg
                return Result.failure(e)
            }
        }

        _collaborationError.value = null
        _taskProposals.value = listOf(proposal) + _taskProposals.value.filterNot { it.id == proposalId }
        _tasks.value = _tasks.value.map { t ->
            if (t.id == targetTask.id) t.copy(pendingProposalId = proposalId, updatedAtMillis = nowMillis) else t
        }

        pushAlert(
            title = "🙋 הצעה חדשה לביצוע משימה",
            message = "${requester.name} מציע/ה לבצע את המשימה \"${targetTask.title}\" במקום $targetChildName. ממתין לאישור של $targetChildName.",
            targetName = targetChildName,
            targetEmoji = targetChild?.avatarEmoji ?: "🙋",
            reason = AlertReason.TASK_REMINDER
        )
        return Result.success(proposal)
    }

    fun proposeVolunteerForTask(taskId: String, note: String = ""): Boolean {
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                proposeVolunteerForTaskAsync(taskId, note).isSuccess
            }
        }
        repoScope.launch {
            proposeVolunteerForTaskAsync(taskId, note)
        }
        return true
    }

    /**
     * Part B: Child proposes to swap one of their active tasks ([offeredTaskId])
     * with another child's active task ([targetTaskId]).
     * Stage 1 -> Creates proposal in [TaskProposalStatus.PENDING_CHILD_APPROVAL].
     * Neither task changes owner until both target child and parent approve.
     */
    suspend fun proposeTaskSwapAsync(
        offeredTaskId: String = "",
        targetTaskId: String,
        offeredSwapTaskId: String = offeredTaskId,
        note: String = ""
    ): Result<TaskCollaborationProposal> {
        val effectiveOfferedId = offeredSwapTaskId.ifBlank { offeredTaskId }
        val requester = _currentMember.value
        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }

        if (effectiveOfferedId.isBlank() || targetTaskId.isBlank() || effectiveOfferedId == targetTaskId) {
            val msg = "יש לבחור שתי משימות שונות להחלפה"
            _collaborationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        val offeredTask = _tasks.value.find { it.id == effectiveOfferedId }
        if (offeredTask == null) {
            _collaborationError.value = "המשימה שלך להחלפה לא נמצאה"
            return Result.failure(IllegalArgumentException("המשימה שלך להחלפה לא נמצאה"))
        }
        val targetTask = _tasks.value.find { it.id == targetTaskId }
        if (targetTask == null) {
            _collaborationError.value = "המשימה המבוקשת להחלפה לא נמצאה"
            return Result.failure(IllegalArgumentException("המשימה המבוקשת להחלפה לא נמצאה"))
        }

        if (offeredTask.assignedToMemberId != requester.id) {
            val msg = "ניתן להציע להחלפה רק משימה שמשויכת אליך"
            _collaborationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        if (targetTask.assignedToMemberId.isBlank() || targetTask.assignedToMemberId == requester.id) {
            val msg = "יש לבחור משימה של ילד/ה אחר/ת להחלפה"
            _collaborationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        if (offeredTask.isCompleted || offeredTask.approvedByParent || targetTask.isCompleted || targetTask.approvedByParent) {
            val msg = "לא ניתן להחליף משימות שכבר הושלמו"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val anyPending = !offeredTask.pendingProposalId.isNullOrBlank() ||
            !targetTask.pendingProposalId.isNullOrBlank() ||
            _taskProposals.value.any {
                it.status.isPending && (
                    it.primaryTaskId == effectiveOfferedId ||
                        it.primaryTaskId == targetTaskId ||
                        it.offeredSwapTaskId == effectiveOfferedId ||
                        it.offeredSwapTaskId == targetTaskId
                    )
            }
        if (anyPending) {
            val msg = "אחת המשימות כבר נמצאת בבקשת שיתוף או החלפה פעילה"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val targetChild = _members.value.find { it.id == targetTask.assignedToMemberId }
        val targetChildName = targetChild?.name?.ifBlank { "ילד/ה" } ?: "ילד/ה"
        val nowMillis = System.currentTimeMillis()
        val proposalId = "prop_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val proposal = TaskCollaborationProposal(
            id = proposalId,
            operationId = proposalId,
            familyId = familyId,
            type = TaskProposalType.SWAP_TASKS,
            requesterChildId = requester.id,
            requesterChildName = requester.name,
            targetChildId = targetTask.assignedToMemberId,
            targetChildName = targetChildName,
            primaryTaskId = targetTask.id,
            primaryTaskTitle = targetTask.title,
            primaryTaskPoints = targetTask.points,
            offeredSwapTaskId = offeredTask.id,
            offeredSwapTaskTitle = offeredTask.title,
            offeredSwapTaskPoints = offeredTask.points,
            status = TaskProposalStatus.PENDING_CHILD_APPROVAL,
            note = note.trim(),
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val familyRef = db.collection("families").document(familyId)
                val proposalRef = familyRef.collection("task_proposals").document(proposalId)
                val targetTaskRef = familyRef.collection("tasks").document(targetTask.id)
                val offeredTaskRef = familyRef.collection("tasks").document(offeredTask.id)
                db.runBatch { batch ->
                    batch.set(proposalRef, buildTaskProposalFirestoreMap(proposal))
                    batch.update(targetTaskRef, mapOf("pendingProposalId" to proposalId, "updatedAt" to nowMillis))
                    batch.update(offeredTaskRef, mapOf("pendingProposalId" to proposalId, "updatedAt" to nowMillis))
                }.await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בשליחת הצעת החלפת המשימות"
                _collaborationError.value = msg
                return Result.failure(e)
            }
        }

        _collaborationError.value = null
        _taskProposals.value = listOf(proposal) + _taskProposals.value.filterNot { it.id == proposalId }
        _tasks.value = _tasks.value.map { t ->
            if (t.id == targetTask.id || t.id == offeredTask.id) {
                t.copy(pendingProposalId = proposalId, updatedAtMillis = nowMillis)
            } else {
                t
            }
        }

        pushAlert(
            title = "🔁 הצעה להחלפת משימות",
            message = "${requester.name} מציע/ה להחליף את \"${offeredTask.title}\" תמורת \"${targetTask.title}\" של $targetChildName.",
            targetName = targetChildName,
            targetEmoji = targetChild?.avatarEmoji ?: "🔁",
            reason = AlertReason.TASK_REMINDER
        )
        return Result.success(proposal)
    }

    fun proposeTaskSwap(offeredTaskId: String, targetTaskId: String, note: String = ""): Boolean {
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                proposeTaskSwapAsync(offeredTaskId, targetTaskId, note).isSuccess
            }
        }
        repoScope.launch {
            proposeTaskSwapAsync(offeredTaskId, targetTaskId, note)
        }
        return true
    }

    /**
     * Stage 2 of Task Collaboration: The target child (owner of the requested task)
     * approves or rejects the proposal.
     * - If approved: status transitions to [TaskProposalStatus.PENDING_PARENT_APPROVAL] (tasks are NOT reassigned yet!).
     * - If rejected: status transitions to [TaskProposalStatus.REJECTED_BY_CHILD] and `pendingProposalId` is cleared.
     */
    suspend fun respondToTaskProposalAsChildAsync(
        proposalId: String,
        approve: Boolean
    ): Result<TaskCollaborationProposal> {
        val current = _currentMember.value
        val authUid = currentUid()
        val proposal = _taskProposals.value.find { it.id == proposalId }
        if (proposal == null) {
            _collaborationError.value = "ההצעה לא נמצאה"
            return Result.failure(IllegalArgumentException("ההצעה לא נמצאה"))
        }

        if (proposal.status != TaskProposalStatus.PENDING_CHILD_APPROVAL) {
            val msg = "הצעה זו אינה ממתינה לאישור הילד/ה"
            _collaborationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val isTargetChild = current.id == proposal.targetChildId || (authUid.isNotBlank() && authUid == proposal.targetChildId)
        if (!isTargetChild) {
            val msg = "רק בעל/ת המשימה (${proposal.targetChildName}) יכול/ה לאשר או לדחות בשלב זה"
            _collaborationError.value = msg
            return Result.failure(SecurityException(msg))
        }

        val nowMillis = System.currentTimeMillis()
        val familyId = proposal.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
        val newStatus = if (approve) {
            TaskProposalStatus.PENDING_PARENT_APPROVAL
        } else {
            TaskProposalStatus.REJECTED_BY_CHILD
        }
        val updatedProposal = proposal.copy(
            status = newStatus,
            childRespondedAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val familyRef = db.collection("families").document(familyId)
                val proposalRef = familyRef.collection("task_proposals").document(proposalId)
                db.runBatch { batch ->
                    batch.update(
                        proposalRef,
                        mapOf(
                            "status" to newStatus.firestoreValue,
                            "childRespondedAtMillis" to nowMillis,
                            "updatedAtMillis" to nowMillis
                        )
                    )
                    if (!approve) {
                        val primaryRef = familyRef.collection("tasks").document(proposal.primaryTaskId)
                        batch.update(primaryRef, mapOf("pendingProposalId" to null, "updatedAt" to nowMillis))
                        if (!proposal.offeredSwapTaskId.isNullOrBlank()) {
                            val swapRef = familyRef.collection("tasks").document(proposal.offeredSwapTaskId)
                            batch.update(swapRef, mapOf("pendingProposalId" to null, "updatedAt" to nowMillis))
                        }
                    }
                }.await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בעדכון תגובת הילד/ה להצעה"
                _collaborationError.value = msg
                return Result.failure(e)
            }
        }

        _collaborationError.value = null
        _taskProposals.value = _taskProposals.value.map { if (it.id == proposalId) updatedProposal else it }
        if (!approve) {
            _tasks.value = _tasks.value.map { t ->
                if (t.id == proposal.primaryTaskId || t.id == proposal.offeredSwapTaskId) {
                    t.copy(pendingProposalId = null, updatedAtMillis = nowMillis)
                } else {
                    t
                }
            }
            pushAlert(
                title = "ההצעה נדחתה ע״י ${proposal.targetChildName}",
                message = "${proposal.targetChildName} דחה/תה את ההצעה עבור המשימה \"${proposal.primaryTaskTitle}\".",
                targetName = proposal.requesterChildName,
                targetEmoji = "ℹ️",
                reason = AlertReason.TASK_REMINDER
            )
        } else {
            pushAlert(
                title = "ממתין לאישור הורה 🛡️",
                message = "${proposal.targetChildName} אישר/ה את ההצעה של ${proposal.requesterChildName} עבור \"${proposal.primaryTaskTitle}\". כעת ממתין לאישור הורה.",
                targetName = "הורים",
                targetEmoji = "🛡️",
                reason = AlertReason.TASK_REMINDER
            )
        }
        return Result.success(updatedProposal)
    }

    fun respondToTaskProposalAsChild(proposalId: String, approve: Boolean): Boolean {
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                respondToTaskProposalAsChildAsync(proposalId, approve).isSuccess
            }
        }
        repoScope.launch {
            respondToTaskProposalAsChildAsync(proposalId, approve)
        }
        return true
    }

    /**
     * Stage 3 of Task Collaboration (PARENT ONLY):
     * Parent approves or rejects a proposal that has already been approved by the target child
     * ([TaskProposalStatus.PENDING_PARENT_APPROVAL]).
     * - Only when `approve == true` does task ownership actually change!
     * - For [TaskProposalType.VOLUNTEER_FOR_TASK]: `primaryTask.assignedToMemberId` becomes `requesterChildId`,
     *   preserving `originalAssignedToMemberId` and `originalAssignedMemberName`.
     * - For [TaskProposalType.SWAP_TASKS]: `primaryTask` and `offeredSwapTask` swap their `assignedToMemberId`
     *   atomically, preserving `originalAssignedToMemberId` and `originalAssignedMemberName` on both tasks.
     */
    suspend fun decideTaskProposalAsParentAsync(
        proposalId: String,
        approve: Boolean
    ): Result<TaskCollaborationProposal> = collaborationMutex.withLock {
        val caller = _currentMember.value
        if (!caller.isParent) {
            val msg = "רק הורה רשאי לאשר או לדחות סופית שינוי שיוך או החלפת משימות 🔒"
            _collaborationError.value = msg
            return@withLock Result.failure(SecurityException(msg))
        }

        val proposal = _taskProposals.value.find { it.id == proposalId }
        if (proposal == null) {
            _collaborationError.value = "ההצעה לא נמצאה"
            return@withLock Result.failure(IllegalArgumentException("ההצעה לא נמצאה"))
        }

        if (proposal.status != TaskProposalStatus.PENDING_PARENT_APPROVAL) {
            val msg = if (proposal.status == TaskProposalStatus.CLOSED_NOT_SELECTED) {
                "המשימה הפתוחה כבר שויכה למתנדב אחר"
            } else {
                "ניתן לאשר/לדחות כהורה רק הצעה שממתינה לאישור הורה"
            }
            _collaborationError.value = msg
            return@withLock Result.failure(IllegalStateException(msg))
        }

        val localPrimaryTask = _tasks.value.find { it.id == proposal.primaryTaskId }
        if (localPrimaryTask == null) {
            _collaborationError.value = "המשימה הראשית בהצעה לא נמצאה"
            return@withLock Result.failure(IllegalStateException("המשימה הראשית בהצעה לא נמצאה"))
        }
        val simulatedRemoteTask = remoteTaskConflictStateProvider?.invoke(proposal.primaryTaskId)
        val primaryTask = simulatedRemoteTask ?: localPrimaryTask

        if (primaryTask.isCompleted || primaryTask.approvedByParent) {
            val msg = "לא ניתן לאשר העברה של משימה שכבר הושלמה"
            _collaborationError.value = msg
            return@withLock Result.failure(IllegalStateException(msg))
        }

        if (proposal.isOpenTaskVolunteer && approve) {
            val alreadyAssignedToAnother = !primaryTask.isOpenForVolunteering ||
                primaryTask.assignedToMemberId.isNotBlank() ||
                (!primaryTask.transferProposalId.isNullOrBlank() && primaryTask.transferProposalId != proposalId)
            if (alreadyAssignedToAnother) {
                val conflictNow = System.currentTimeMillis()
                // Reconcile local state so this competing proposal reflects CLOSED_NOT_SELECTED
                _tasks.value = _tasks.value.map { if (it.id == primaryTask.id) primaryTask else it }
                _taskProposals.value = _taskProposals.value.map { existing ->
                    if (existing.id == proposalId && existing.status.isPending) {
                        existing.copy(
                            status = TaskProposalStatus.CLOSED_NOT_SELECTED,
                            updatedAtMillis = conflictNow
                        )
                    } else {
                        existing
                    }
                }
                val msg = "המשימה הפתוחה כבר שויכה למתנדב אחר על ידי הורה נוסף"
                _collaborationError.value = msg
                return@withLock Result.failure(IllegalStateException(msg))
            }
        }

        val offeredSwapTask = if (proposal.type == TaskProposalType.SWAP_TASKS) {
            val swapId = proposal.offeredSwapTaskId.orEmpty()
            val found = _tasks.value.find { it.id == swapId }
            if (found == null) {
                _collaborationError.value = "המשימה המוצעת להחלפה לא נמצאה"
                return@withLock Result.failure(IllegalStateException("המשימה המוצעת להחלפה לא נמצאה"))
            }
            if (found.isCompleted || found.approvedByParent) {
                val msg = "לא ניתן להחליף משימה שכבר הושלמה"
                _collaborationError.value = msg
                return@withLock Result.failure(IllegalStateException(msg))
            }
            found
        } else {
            null
        }

        val nowMillis = System.currentTimeMillis()
        val familyId = proposal.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
        val actorUid = currentUid().ifBlank { caller.id }
        val actorName = caller.name.ifBlank { "הורה" }
        val newStatus = if (approve) {
            TaskProposalStatus.APPROVED_BY_PARENT
        } else {
            TaskProposalStatus.REJECTED_BY_PARENT
        }

        val updatedProposal = proposal.copy(
            status = newStatus,
            parentDecidedByUid = actorUid,
            parentDecidedByName = actorName,
            parentDecidedAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )

        // When approving an open-task volunteer, close all competing pending volunteer requests for the same task
        val competingOpenProposalsToClose = if (approve && proposal.isOpenTaskVolunteer) {
            _taskProposals.value.filter {
                it.primaryTaskId == primaryTask.id &&
                    it.id != proposal.id &&
                    it.status.isPending
            }
        } else {
            emptyList()
        }
        val closedCompetingById = competingOpenProposalsToClose.associate { comp ->
            comp.id to comp.copy(
                status = TaskProposalStatus.CLOSED_NOT_SELECTED,
                parentDecidedByUid = actorUid,
                parentDecidedByName = actorName,
                parentDecidedAtMillis = nowMillis,
                updatedAtMillis = nowMillis
            )
        }

        val origPrimaryOwnerId = if (proposal.isOpenTaskVolunteer) {
            null
        } else {
            primaryTask.originalAssignedToMemberId?.takeIf { it.isNotBlank() }
                ?: primaryTask.assignedToMemberId.takeIf { it.isNotBlank() }
        }
        val origPrimaryOwnerName = if (proposal.isOpenTaskVolunteer) {
            null
        } else {
            primaryTask.originalAssignedMemberName?.takeIf { it.isNotBlank() }
                ?: origPrimaryOwnerId?.let { id -> _members.value.find { it.id == id }?.name }
                ?: proposal.targetChildName.takeIf { it.isNotBlank() }
        }

        val updatedPrimaryTask = if (approve) {
            primaryTask.copy(
                assignedToMemberId = proposal.requesterChildId,
                isOpenForVolunteering = false,
                originalAssignedToMemberId = origPrimaryOwnerId,
                originalAssignedMemberName = origPrimaryOwnerName,
                transferProposalId = proposal.id,
                pendingProposalId = null,
                updatedAtMillis = nowMillis
            )
        } else {
            primaryTask.copy(
                pendingProposalId = if (primaryTask.pendingProposalId == proposal.id) null else primaryTask.pendingProposalId,
                updatedAtMillis = nowMillis
            )
        }

        val updatedSwapTask = if (offeredSwapTask != null) {
            if (approve) {
                val origSwapOwnerId = offeredSwapTask.originalAssignedToMemberId?.takeIf { it.isNotBlank() }
                    ?: offeredSwapTask.assignedToMemberId
                val origSwapOwnerName = offeredSwapTask.originalAssignedMemberName?.takeIf { it.isNotBlank() }
                    ?: _members.value.find { it.id == origSwapOwnerId }?.name
                    ?: proposal.requesterChildName
                offeredSwapTask.copy(
                    assignedToMemberId = proposal.targetChildId,
                    originalAssignedToMemberId = origSwapOwnerId,
                    originalAssignedMemberName = origSwapOwnerName,
                    transferProposalId = proposal.id,
                    pendingProposalId = null,
                    updatedAtMillis = nowMillis
                )
            } else {
                offeredSwapTask.copy(
                    pendingProposalId = null,
                    updatedAtMillis = nowMillis
                )
            }
        } else {
            null
        }

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val familyRef = db.collection("families").document(familyId)
                val proposalRef = familyRef.collection("task_proposals").document(proposalId)
                val primaryRef = familyRef.collection("tasks").document(updatedPrimaryTask.id)
                val swapRef = updatedSwapTask?.let { familyRef.collection("tasks").document(it.id) }

                // Candidate competing volunteer proposal IDs (both known local proposals and deterministic child volunteer IDs)
                val deterministicCompetingIds = if (approve && proposal.isOpenTaskVolunteer) {
                    _members.value
                        .filter { !it.isParent && it.id != proposal.requesterChildId }
                        .map { "vol_open_${primaryTask.id}_${it.id}" }
                } else {
                    emptyList()
                }
                val allCandidateCompetingIds = (closedCompetingById.keys + deterministicCompetingIds)
                    .distinct()
                    .filter { it.isNotBlank() && it != proposalId }
                val candidateCompetingRefs = allCandidateCompetingIds.map { compId ->
                    compId to familyRef.collection("task_proposals").document(compId)
                }

                db.runTransaction { tx ->
                    // 1. All reads MUST happen before any writes in a Firestore Transaction
                    val primarySnap = tx.get(primaryRef)
                    val proposalSnap = tx.get(proposalRef)
                    val swapSnap = swapRef?.let { tx.get(it) }
                    val competingSnaps = candidateCompetingRefs.map { (compId, compRef) ->
                        Triple(compId, compRef, tx.get(compRef))
                    }

                    // 2. Validate proposal status at approval time
                    if (proposalSnap.exists()) {
                        val remoteStatus = TaskProposalStatus.fromFirestoreValue(proposalSnap.getString("status"))
                        if (remoteStatus != TaskProposalStatus.PENDING_PARENT_APPROVAL) {
                            throw IllegalStateException("המשימה הפתוחה כבר שויכה למתנדב אחר על ידי הורה נוסף")
                        }
                    }

                    // 3. Validate primary task state at approval time (prevents race condition between two parents)
                    if (primarySnap.exists()) {
                        val remoteCompleted = (primarySnap.getBoolean("isCompleted") ?: primarySnap.getBoolean("is_completed")) ?: false
                        val remoteApproved = (primarySnap.getBoolean("approvedByParent") ?: primarySnap.getBoolean("approved_by_parent")) ?: false
                        if (remoteCompleted || remoteApproved) {
                            throw IllegalStateException("לא ניתן לאשר העברה של משימה שכבר הושלמה")
                        }

                        if (proposal.isOpenTaskVolunteer && approve) {
                            val remoteOpen = (primarySnap.getBoolean("isOpenForVolunteering")
                                ?: primarySnap.getBoolean("is_open_for_volunteering")) ?: false
                            val remoteAssignee = (primarySnap.getString("assignedToMemberId")
                                ?: primarySnap.getString("assignedUid")
                                ?: primarySnap.getString("assignedMemberId")).orEmpty().trim()
                            val remoteTransferProp = (primarySnap.getString("transferProposalId")
                                ?: primarySnap.getString("activeCollaborationProposalId")).orEmpty().trim()

                            if (!remoteOpen || remoteAssignee.isNotBlank() || (remoteTransferProp.isNotBlank() && remoteTransferProp != proposalId)) {
                                throw IllegalStateException("המשימה הפתוחה כבר שויכה למתנדב אחר על ידי הורה נוסף")
                            }
                        } else if (!proposal.isOpenTaskVolunteer && approve) {
                            val remoteAssignee = (primarySnap.getString("assignedToMemberId")
                                ?: primarySnap.getString("assignedUid")).orEmpty().trim()
                            if (remoteAssignee.isNotBlank() && remoteAssignee != proposal.targetChildId) {
                                throw IllegalStateException("שיוך המשימה כבר שונה במקביל על ידי הורה אחר")
                            }
                        }
                    }

                    if (swapSnap != null && swapSnap.exists() && approve) {
                        val swapCompleted = (swapSnap.getBoolean("isCompleted") ?: false) ||
                            (swapSnap.getBoolean("approvedByParent") ?: false)
                        val swapAssignee = (swapSnap.getString("assignedToMemberId")
                            ?: swapSnap.getString("assignedUid")).orEmpty().trim()
                        if (swapCompleted || (swapAssignee.isNotBlank() && swapAssignee != proposal.requesterChildId)) {
                            throw IllegalStateException("המשימה המוצעת להחלפה כבר שונתה או הושלמה במקביל")
                        }
                    }

                    // 4. Atomic writes inside the transaction
                    if (proposalSnap.exists()) {
                        tx.update(
                            proposalRef,
                            mapOf(
                                "status" to newStatus.firestoreValue,
                                "parentDecidedByUid" to actorUid,
                                "parentDecidedByName" to actorName,
                                "parentDecidedAtMillis" to nowMillis,
                                "updatedAtMillis" to nowMillis
                            )
                        )
                    } else {
                        tx.set(proposalRef, buildTaskProposalFirestoreMap(updatedProposal), SetOptions.merge())
                    }

                    competingSnaps.forEach { (compId, compRef, compSnap) ->
                        if (compSnap.exists()) {
                            val compRemoteStatus = TaskProposalStatus.fromFirestoreValue(compSnap.getString("status"))
                            if (compRemoteStatus.isPending) {
                                tx.update(
                                    compRef,
                                    mapOf(
                                        "status" to TaskProposalStatus.CLOSED_NOT_SELECTED.firestoreValue,
                                        "parentDecidedByUid" to actorUid,
                                        "parentDecidedByName" to actorName,
                                        "parentDecidedAtMillis" to nowMillis,
                                        "updatedAtMillis" to nowMillis
                                    )
                                )
                            }
                        } else if (closedCompetingById.containsKey(compId)) {
                            tx.set(
                                compRef,
                                buildTaskProposalFirestoreMap(closedCompetingById.getValue(compId)),
                                SetOptions.merge()
                            )
                        }
                    }

                    if (approve || !proposal.isOpenTaskVolunteer) {
                        tx.set(
                            primaryRef,
                            buildFullTaskFirestoreMap(updatedPrimaryTask, familyId, nowMillis),
                            SetOptions.merge()
                        )
                    }
                    if (updatedSwapTask != null && swapRef != null) {
                        tx.set(
                            swapRef,
                            buildFullTaskFirestoreMap(updatedSwapTask, familyId, nowMillis),
                            SetOptions.merge()
                        )
                    }
                }.await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בעדכון החלטת ההורה על ההצעה"
                _collaborationError.value = msg
                return@withLock Result.failure(e)
            }
        }

        _collaborationError.value = null
        _taskProposals.value = _taskProposals.value.map { existing ->
            when {
                existing.id == proposalId -> updatedProposal
                closedCompetingById.containsKey(existing.id) -> closedCompetingById.getValue(existing.id)
                else -> existing
            }
        }
        _tasks.value = _tasks.value.map { t ->
            when (t.id) {
                updatedPrimaryTask.id -> updatedPrimaryTask
                updatedSwapTask?.id -> updatedSwapTask
                else -> t
            }
        }
        reconcileActiveBannerAgainstTasks(_tasks.value)
        resyncTaskRemindersForActiveMember()
        repoScope.launch {
            runCatching {
                effectiveTaskDao?.upsertTask(updatedPrimaryTask.toCoreTask(familyId))
            }
        }

        if (approve) {
            val alertMessage = when {
                proposal.isOpenTaskVolunteer ->
                    "$actorName בחר/ה ב-${proposal.requesterChildName} לביצוע המשימה הפתוחה \"${updatedPrimaryTask.title}\" (+${updatedPrimaryTask.points} נק')!"
                proposal.type == TaskProposalType.SWAP_TASKS && updatedSwapTask != null ->
                    "$actorName אישר/ה את החלפת המשימות בין ${proposal.requesterChildName} לבין ${proposal.targetChildName}!"
                else ->
                    "$actorName אישר/ה ש-${proposal.requesterChildName} יבצע/תבצע את המשימה \"${updatedPrimaryTask.title}\" במקום ${proposal.targetChildName}!"
            }
            pushAlert(
                title = if (proposal.isOpenTaskVolunteer) "המשימה שויכה אליך! 🎯" else "אושר ע״י הורה! ✅",
                message = alertMessage,
                targetName = proposal.requesterChildName,
                targetEmoji = "✅",
                reason = AlertReason.TASK_REMINDER
            )
        } else {
            pushAlert(
                title = "ההצעה נדחתה ע״י הורה",
                message = "$actorName דחה/תה את הבקשה לשינוי שיוך המשימה \"${updatedPrimaryTask.title}\".",
                targetName = proposal.requesterChildName,
                targetEmoji = "ℹ️",
                reason = AlertReason.TASK_REMINDER
            )
        }
        return@withLock Result.success(updatedProposal)
    }

    fun decideTaskProposalAsParent(proposalId: String, approve: Boolean): Boolean {
        if (!_currentMember.value.isParent) {
            _collaborationError.value = "רק הורה רשאי לאשר או לדחות סופית שינוי שיוך או החלפת משימות 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                decideTaskProposalAsParentAsync(proposalId, approve).isSuccess
            }
        }
        repoScope.launch {
            decideTaskProposalAsParentAsync(proposalId, approve)
        }
        return true
    }

    suspend fun cancelTaskProposalAsync(proposalId: String): Result<TaskCollaborationProposal> {
        val current = _currentMember.value
        val proposal = _taskProposals.value.find { it.id == proposalId }
            ?: return Result.failure(IllegalArgumentException("ההצעה לא נמצאה"))
        if (! proposal.status.isPending) {
            return Result.failure(IllegalStateException("לא ניתן לבטל הצעה שכבר הסתיימה"))
        }
        if (!current.isParent && current.id != proposal.requesterChildId) {
            return Result.failure(SecurityException("רק יוזם/ת ההצעה או הורה רשאים לבטל אותה"))
        }

        val nowMillis = System.currentTimeMillis()
        val familyId = proposal.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
        val cancelled = proposal.copy(
            status = TaskProposalStatus.CANCELLED,
            updatedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val familyRef = db.collection("families").document(familyId)
                val proposalRef = familyRef.collection("task_proposals").document(proposalId)
                db.runBatch { batch ->
                    batch.update(proposalRef, mapOf("status" to TaskProposalStatus.CANCELLED.firestoreValue, "updatedAtMillis" to nowMillis))
                    val primaryRef = familyRef.collection("tasks").document(proposal.primaryTaskId)
                    batch.update(primaryRef, mapOf("pendingProposalId" to null, "updatedAt" to nowMillis))
                    if (!proposal.offeredSwapTaskId.isNullOrBlank()) {
                        val swapRef = familyRef.collection("tasks").document(proposal.offeredSwapTaskId)
                        batch.update(swapRef, mapOf("pendingProposalId" to null, "updatedAt" to nowMillis))
                    }
                }.await()
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }

        _taskProposals.value = _taskProposals.value.map { if (it.id == proposalId) cancelled else it }
        _tasks.value = _tasks.value.map { t ->
            if (t.id == proposal.primaryTaskId || t.id == proposal.offeredSwapTaskId) {
                t.copy(pendingProposalId = null, updatedAtMillis = nowMillis)
            } else {
                t
            }
        }
        return Result.success(cancelled)
    }

    fun cancelTaskProposal(proposalId: String): Boolean {
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                cancelTaskProposalAsync(proposalId).isSuccess
            }
        }
        repoScope.launch {
            cancelTaskProposalAsync(proposalId)
        }
        return true
    }

    // =========================================================================
    //  PART C: FLEXIBLE POINTS SPLITTING ON PARENT TASK APPROVAL
    // =========================================================================

    /**
     * Approves a task completion as a PARENT and distributes its points flexibly between:
     * - the performing child (`targetTask.assignedToMemberId`): [performerPoints]
     * - the original task owner (`targetTask.originalAssignedToMemberId`, if transferred/swapped): [originalOwnerPoints]
     * - optional extra parent helping bonus (`bonusPointsForPerformer`) that does not deduct from the original owner.
     *
     * Enforces:
     * 1. Caller is PARENT.
     * 2. `performerPoints >= 0`, `originalOwnerPoints >= 0`, `bonusPointsForPerformer >= 0`.
     * 3. `performerPoints + originalOwnerPoints <= targetTask.points` (never exceeds base task points).
     * 4. Strict idempotency (`pts_task_$taskId`) so a task can never award base points more than once.
     */
    suspend fun approveTaskCompletionWithSplitAsync(
        taskId: String,
        performerPoints: Int,
        originalOwnerPoints: Int = 0,
        bonusPointsForPerformer: Int = 0,
        parentNote: String = ""
    ): Result<FamilyTask> {
        val caller = _currentMember.value
        if (!caller.isParent) {
            val errMsg = "רק הורה רשאי לאשר השלמת משימה ולחלק נקודות 🔒"
            _pointsOperationError.value = errMsg
            _taskOperationError.value = errMsg
            return Result.failure(SecurityException(errMsg))
        }

        val targetTask = resolveTaskFromStateOrFirestore(taskId)
        if (targetTask == null) {
            _pointsOperationError.value = "המשימה לא נמצאה"
            return Result.failure(IllegalArgumentException("המשימה לא נמצאה"))
        }

        if (performerPoints < 0 || originalOwnerPoints < 0 || bonusPointsForPerformer < 0) {
            val errMsg = "חלוקת הנקודות חייבת להכיל ערכים אי-שליליים בלבד"
            _pointsOperationError.value = errMsg
            return Result.failure(IllegalArgumentException(errMsg))
        }

        if (performerPoints + originalOwnerPoints > targetTask.points) {
            val errMsg = "סך הנקודות לחלוקה (${performerPoints + originalOwnerPoints}) עולה על נקודות המשימה (${targetTask.points})"
            _pointsOperationError.value = errMsg
            return Result.failure(IllegalArgumentException(errMsg))
        }

        val origOwnerId = targetTask.originalAssignedToMemberId?.takeIf { it.isNotBlank() }
        if (originalOwnerPoints > 0 && origOwnerId.isNullOrBlank()) {
            val errMsg = "לא ניתן להעניק נקודות לבעלים מקורי במשימה שלא הועברה"
            _pointsOperationError.value = errMsg
            return Result.failure(IllegalArgumentException(errMsg))
        }

        // If 100% goes to the performer and no originalOwnerPoints / bonusPoints are requested,
        // and performerPoints == targetTask.points, we can still execute the unified split/approval transaction below
        return pointsMutex.withLock {
            val resolvedFamilyId = targetTask.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
            val nowMillis = System.currentTimeMillis()
            val actorUid = currentUid().ifBlank { caller.id }
            val actorName = caller.name.ifBlank { "הורה" }
            val taskOpId = "pts_task_$taskId"
            val origOpId = "pts_task_${taskId}_orig"
            val bonusOpId = "pts_task_${taskId}_bonus"

            val wasAlreadyApprovedLocally = (targetTask.isCompleted && targetTask.approvedByParent) ||
                processedPointsOperationIds.contains(taskOpId) ||
                _pointsHistory.value.any {
                    it.operationId == taskOpId ||
                        it.id == taskOpId ||
                        ((it.actionType == PointsActionType.TASK_EARNED || it.actionType == PointsActionType.TASK_SPLIT_EARNED) && it.taskId == taskId)
                }

            val completedBy = targetTask.completedByMemberId ?: targetTask.assignedToMemberId.ifBlank { actorUid }
            val approvedTask = targetTask.copy(
                familyId = resolvedFamilyId,
                isCompleted = true,
                approvedByParent = true,
                completedAtMillis = targetTask.completedAtMillis ?: nowMillis,
                completedByMemberId = completedBy,
                updatedAtMillis = nowMillis
            )

            if (wasAlreadyApprovedLocally) {
                // Reject duplicate split-approval without awarding points a second time
                _tasks.value = _tasks.value.map { if (it.id == taskId) approvedTask else it }
                _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVED_APPROVED)
                val dupMsg = "המשימה כבר אושרה והנקודות עבורה כבר חולקו"
                _pointsOperationError.value = dupMsg
                return@withLock Result.failure(IllegalStateException(dupMsg))
            }

            val performerId = approvedTask.assignedToMemberId
            val performerMember = _members.value.find { it.id == performerId }
            val performerName = performerMember?.name.orEmpty()
            val origMember = origOwnerId?.let { id -> _members.value.find { it.id == id } }
            val origName = approvedTask.originalAssignedMemberName?.takeIf { it.isNotBlank() }
                ?: origMember?.name.orEmpty()

            val isSplit = originalOwnerPoints > 0 || performerPoints < approvedTask.points
            val actionForTask = if (isSplit) PointsActionType.TASK_SPLIT_EARNED else PointsActionType.TASK_EARNED

            val createdEntries = mutableListOf<PointsHistoryEntry>()
            val updatedBalances = mutableMapOf<String, Int>()

            if (performerId.isNotBlank() && performerPoints > 0) {
                val prevBal = (performerMember?.earnedPoints ?: 0).coerceAtLeast(0)
                val newBal = prevBal + performerPoints
                updatedBalances[performerId] = newBal
                val reason = if (!origName.isNullOrBlank() && origOwnerId != performerId) {
                    if (isSplit) {
                        "חלוקת נקודות ממשימה: ${approvedTask.title} ($performerPoints/${approvedTask.points} נק׳ למבצע/ת במקום $origName)"
                    } else {
                        "השלמת משימה: ${approvedTask.title} (בוצע במקום $origName)"
                    }
                } else {
                    "השלמת משימה: ${approvedTask.title}"
                }
                createdEntries.add(
                    PointsHistoryEntry(
                        id = taskOpId,
                        operationId = taskOpId,
                        familyId = resolvedFamilyId,
                        memberId = performerId,
                        memberName = performerName,
                        actionType = actionForTask,
                        pointsDelta = performerPoints,
                        previousBalance = prevBal,
                        newBalance = newBal,
                        reason = reason,
                        performedByUid = actorUid,
                        performedByName = actorName,
                        timestampMillis = nowMillis,
                        taskId = approvedTask.id,
                        performedTaskByMemberId = performerId,
                        performedTaskByMemberName = performerName,
                        relatedMemberId = origOwnerId,
                        relatedMemberName = origName.ifBlank { null },
                        proposalId = approvedTask.transferProposalId
                    )
                )
            } else {
                // Record the base taskOpId even if performerPoints == 0 so the task can never be rewarded again
                processedPointsOperationIds.add(taskOpId)
            }

            if (!origOwnerId.isNullOrBlank() && originalOwnerPoints > 0) {
                val baseBal = updatedBalances[origOwnerId] ?: (origMember?.earnedPoints ?: 0).coerceAtLeast(0)
                val newBal = baseBal + originalOwnerPoints
                updatedBalances[origOwnerId] = newBal
                val reason = "חלוקת נקודות ממשימה: ${approvedTask.title} ($originalOwnerPoints/${approvedTask.points} נק׳ לבעלים המקורי, בוצע ע״י $performerName)"
                createdEntries.add(
                    PointsHistoryEntry(
                        id = origOpId,
                        operationId = origOpId,
                        familyId = resolvedFamilyId,
                        memberId = origOwnerId,
                        memberName = origName,
                        actionType = PointsActionType.TASK_SPLIT_EARNED,
                        pointsDelta = originalOwnerPoints,
                        previousBalance = baseBal,
                        newBalance = newBal,
                        reason = reason,
                        performedByUid = actorUid,
                        performedByName = actorName,
                        timestampMillis = nowMillis + 1,
                        taskId = approvedTask.id,
                        performedTaskByMemberId = performerId,
                        performedTaskByMemberName = performerName,
                        relatedMemberId = performerId,
                        relatedMemberName = performerName.ifBlank { null },
                        proposalId = approvedTask.transferProposalId
                    )
                )
            }

            if (performerId.isNotBlank() && bonusPointsForPerformer > 0) {
                val baseBal = updatedBalances[performerId] ?: (performerMember?.earnedPoints ?: 0).coerceAtLeast(0)
                val newBal = baseBal + bonusPointsForPerformer
                updatedBalances[performerId] = newBal
                val reason = "בונוס מיוחד מהורה על עזרה במשימה: ${approvedTask.title}"
                createdEntries.add(
                    PointsHistoryEntry(
                        id = bonusOpId,
                        operationId = bonusOpId,
                        familyId = resolvedFamilyId,
                        memberId = performerId,
                        memberName = performerName,
                        actionType = PointsActionType.HELPING_BONUS,
                        pointsDelta = bonusPointsForPerformer,
                        previousBalance = baseBal,
                        newBalance = newBal,
                        reason = reason,
                        performedByUid = actorUid,
                        performedByName = actorName,
                        timestampMillis = nowMillis + 2,
                        taskId = approvedTask.id,
                        performedTaskByMemberId = performerId,
                        performedTaskByMemberName = performerName,
                        relatedMemberId = origOwnerId,
                        relatedMemberName = origName.ifBlank { null },
                        proposalId = approvedTask.transferProposalId
                    )
                )
            }

            val db = firestoreProvider()
            val authUser = authProvider()?.currentUser
            if (db != null && authUser != null) {
                try {
                    val familyRef = db.collection("families").document(resolvedFamilyId)
                    val taskDocRef = familyRef.collection("tasks").document(approvedTask.id)
                    db.runBatch { batch ->
                        val fullMap = buildFullTaskFirestoreMap(approvedTask, resolvedFamilyId, nowMillis).toMutableMap()
                        fullMap["rewardedOperationId"] = taskOpId
                        batch.set(taskDocRef, fullMap, SetOptions.merge())

                        val lastOpIdByMember = createdEntries.associate { it.memberId to it.id }
                        updatedBalances.forEach { (mId, bal) ->
                            val mRef = familyRef.collection("members").document(mId)
                            val memberOpId = lastOpIdByMember[mId] ?: taskOpId
                            batch.set(
                                mRef,
                                mapOf(
                                    "earnedPoints" to bal,
                                    "lastPointsOperationId" to memberOpId,
                                    "updatedAt" to nowMillis
                                ),
                                SetOptions.merge()
                            )
                        }

                        createdEntries.forEach { entry ->
                            val hRef = familyRef.collection("points_history").document(entry.id)
                            val hMap = mutableMapOf<String, Any?>(
                                "id" to entry.id,
                                "operationId" to entry.operationId,
                                "familyId" to entry.familyId,
                                "memberId" to entry.memberId,
                                "memberName" to entry.memberName,
                                "actionType" to entry.actionType.firestoreValue,
                                "pointsDelta" to entry.pointsDelta,
                                "previousBalance" to entry.previousBalance,
                                "newBalance" to entry.newBalance,
                                "reason" to entry.reason,
                                "performedByUid" to entry.performedByUid,
                                "performedByName" to entry.performedByName,
                                "timestampMillis" to entry.timestampMillis,
                                "taskId" to entry.taskId,
                                "performedTaskByMemberId" to entry.performedTaskByMemberId,
                                "performedTaskByMemberName" to entry.performedTaskByMemberName,
                                "serverTimestamp" to FieldValue.serverTimestamp()
                            )
                            if (!entry.relatedMemberId.isNullOrBlank()) hMap["relatedMemberId"] = entry.relatedMemberId
                            if (!entry.relatedMemberName.isNullOrBlank()) hMap["relatedMemberName"] = entry.relatedMemberName
                            if (!entry.proposalId.isNullOrBlank()) hMap["proposalId"] = entry.proposalId
                            batch.set(hRef, hMap)
                        }
                    }.await()
                } catch (e: Exception) {
                    val msg = e.localizedMessage ?: "שגיאה באישור המשימה וחלוקת הנקודות"
                    _pointsOperationError.value = msg
                    return@withLock Result.failure(e)
                }
            }

            processedPointsOperationIds.add(taskOpId)
            _taskSyncStates.value = _taskSyncStates.value + (taskId to TaskCompletionSyncState.SAVED_APPROVED)
            _tasks.value = _tasks.value.map { if (it.id == taskId) approvedTask else it }
            resyncTaskRemindersForActiveMember()

            if (updatedBalances.isNotEmpty()) {
                _members.value = _members.value.map { m ->
                    val newBal = updatedBalances[m.id]
                    if (newBal != null) {
                        val updated = m.copy(earnedPoints = newBal)
                        if (_currentMember.value.id == m.id) {
                            _currentMember.value = updated
                        }
                        persistMemberPointsBalanceLocally(resolvedFamilyId, m.id, newBal)
                        updated
                    } else {
                        m
                    }
                }
            }

            createdEntries.forEach { entry ->
                appendPointsHistoryEntryLocally(resolvedFamilyId, entry)
            }

            spawnNextRecurringOccurrenceIfNeeded(approvedTask)
            emitTaskCompletionNotificationIfNeeded(approvedTask, isParentApprovalEvent = true)
            Result.success(approvedTask)
        }
    }

    fun approveTaskCompletionWithSplit(
        taskId: String,
        performerPoints: Int,
        originalOwnerPoints: Int = 0,
        bonusPointsForPerformer: Int = 0
    ): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי לאשר השלמת משימה ולחלק נקודות 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                approveTaskCompletionWithSplitAsync(
                    taskId = taskId,
                    performerPoints = performerPoints,
                    originalOwnerPoints = originalOwnerPoints,
                    bonusPointsForPerformer = bonusPointsForPerformer
                ).isSuccess
            }
        }
        repoScope.launch {
            approveTaskCompletionWithSplitAsync(
                taskId = taskId,
                performerPoints = performerPoints,
                originalOwnerPoints = originalOwnerPoints,
                bonusPointsForPerformer = bonusPointsForPerformer
            )
        }
        return true
    }

    // =========================================================================
    //  PART D: PARENT POINTS TRANSFER BETWEEN CHILDREN & HELPING BONUS
    // =========================================================================

    /**
     * Atomically transfers [amount] points from [fromMemberId] to [toMemberId] (PARENT ONLY).
     * Validates:
     * 1. Caller is PARENT.
     * 2. `fromMemberId != toMemberId` (blocks self-transfer).
     * 3. `amount > 0` (blocks zero or negative transfer).
     * 4. `reason.isNotBlank()`.
     * 5. `fromMember.earnedPoints >= amount` (blocks transfer exceeding sender's balance).
     */
    suspend fun transferPointsBetweenMembersAsync(
        fromMemberId: String,
        toMemberId: String,
        amount: Int,
        reason: String,
        operationId: String = "pts_tx_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Result<Pair<PointsHistoryEntry, PointsHistoryEntry>> = pointsMutex.withLock {
        val caller = _currentMember.value
        if (!caller.isParent) {
            val msg = "רק הורה רשאי להעביר נקודות בין ילדים 🔒"
            _pointsOperationError.value = msg
            return@withLock Result.failure(SecurityException(msg))
        }
        if (fromMemberId.isBlank() || toMemberId.isBlank() || fromMemberId == toMemberId) {
            val msg = "יש לבחור שני בני משפחה שונים להעברת נקודות"
            _pointsOperationError.value = msg
            return@withLock Result.failure(IllegalArgumentException(msg))
        }
        if (amount <= 0) {
            val msg = "כמות הנקודות להעברה חייבת להיות חיובית (גדולה מ-0)"
            _pointsOperationError.value = msg
            return@withLock Result.failure(IllegalArgumentException(msg))
        }
        val cleanReason = reason.trim()
        if (cleanReason.isBlank()) {
            val msg = "נא להזין סיבה להעברת הנקודות"
            _pointsOperationError.value = msg
            return@withLock Result.failure(IllegalArgumentException(msg))
        }

        val sender = _members.value.find { it.id == fromMemberId }
        if (sender == null) {
            _pointsOperationError.value = "הילד/ה המעביר/ה לא נמצא/ה"
            return@withLock Result.failure(IllegalArgumentException("הילד/ה המעביר/ה לא נמצא/ה"))
        }
        val receiver = _members.value.find { it.id == toMemberId }
        if (receiver == null) {
            _pointsOperationError.value = "הילד/ה המקבל/ת לא נמצא/ה"
            return@withLock Result.failure(IllegalArgumentException("הילד/ה המקבל/ת לא נמצא/ה"))
        }

        val senderPrev = sender.earnedPoints.coerceAtLeast(0)
        if (senderPrev < amount) {
            val msg = "לא ניתן להעביר $amount נקודות מ-${sender.name} — היתרה הקיימת היא $senderPrev נקודות בלבד"
            _pointsOperationError.value = msg
            return@withLock Result.failure(IllegalArgumentException(msg))
        }

        val receiverPrev = receiver.earnedPoints.coerceAtLeast(0)
        val senderNew = senderPrev - amount
        val receiverNew = receiverPrev + amount
        val nowMillis = System.currentTimeMillis()
        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
        val actorUid = currentUid().ifBlank { caller.id }
        val actorName = caller.name.ifBlank { "הורה" }

        val outOpId = "${operationId}_out"
        val inOpId = "${operationId}_in"

        val outEntry = PointsHistoryEntry(
            id = outOpId,
            operationId = outOpId,
            familyId = familyId,
            memberId = sender.id,
            memberName = sender.name,
            actionType = PointsActionType.TRANSFER_OUT,
            pointsDelta = -amount,
            previousBalance = senderPrev,
            newBalance = senderNew,
            reason = "העברה אל ${receiver.name}: $cleanReason",
            performedByUid = actorUid,
            performedByName = actorName,
            timestampMillis = nowMillis,
            relatedMemberId = receiver.id,
            relatedMemberName = receiver.name
        )

        val inEntry = PointsHistoryEntry(
            id = inOpId,
            operationId = inOpId,
            familyId = familyId,
            memberId = receiver.id,
            memberName = receiver.name,
            actionType = PointsActionType.TRANSFER_IN,
            pointsDelta = amount,
            previousBalance = receiverPrev,
            newBalance = receiverNew,
            reason = "קבלה מ-${sender.name}: $cleanReason",
            performedByUid = actorUid,
            performedByName = actorName,
            timestampMillis = nowMillis + 1,
            relatedMemberId = sender.id,
            relatedMemberName = sender.name
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val familyRef = db.collection("families").document(familyId)
                val senderRef = familyRef.collection("members").document(sender.id)
                val receiverRef = familyRef.collection("members").document(receiver.id)
                val outHistRef = familyRef.collection("points_history").document(outOpId)
                val inHistRef = familyRef.collection("points_history").document(inOpId)

                db.runTransaction { tx ->
                    val senderSnap = tx.get(senderRef)
                    val receiverSnap = tx.get(receiverRef)
                    val cloudSenderPrev = if (senderSnap.exists()) {
                        (senderSnap.getLong("earnedPoints") ?: senderPrev.toLong()).toInt().coerceAtLeast(0)
                    } else {
                        senderPrev
                    }
                    if (cloudSenderPrev < amount) {
                        throw IllegalArgumentException("לא ניתן להעביר $amount נקודות מ-${sender.name} — היתרה בענן היא $cloudSenderPrev נקודות בלבד")
                    }
                    val cloudReceiverPrev = if (receiverSnap.exists()) {
                        (receiverSnap.getLong("earnedPoints") ?: receiverPrev.toLong()).toInt().coerceAtLeast(0)
                    } else {
                        receiverPrev
                    }
                    val cloudSenderNew = cloudSenderPrev - amount
                    val cloudReceiverNew = cloudReceiverPrev + amount

                    tx.set(
                        senderRef,
                        mapOf(
                            "earnedPoints" to cloudSenderNew,
                            "lastPointsOperationId" to outOpId,
                            "updatedAt" to nowMillis
                        ),
                        SetOptions.merge()
                    )
                    tx.set(
                        receiverRef,
                        mapOf(
                            "earnedPoints" to cloudReceiverNew,
                            "lastPointsOperationId" to inOpId,
                            "updatedAt" to nowMillis
                        ),
                        SetOptions.merge()
                    )
                    tx.set(
                        outHistRef,
                        mapOf(
                            "id" to outEntry.id,
                            "operationId" to outEntry.operationId,
                            "familyId" to familyId,
                            "memberId" to sender.id,
                            "memberName" to sender.name,
                            "actionType" to PointsActionType.TRANSFER_OUT.firestoreValue,
                            "pointsDelta" to -amount,
                            "previousBalance" to cloudSenderPrev,
                            "newBalance" to cloudSenderNew,
                            "reason" to outEntry.reason,
                            "performedByUid" to actorUid,
                            "performedByName" to actorName,
                            "timestampMillis" to outEntry.timestampMillis,
                            "relatedMemberId" to receiver.id,
                            "relatedMemberName" to receiver.name,
                            "serverTimestamp" to FieldValue.serverTimestamp()
                        )
                    )
                    tx.set(
                        inHistRef,
                        mapOf(
                            "id" to inEntry.id,
                            "operationId" to inEntry.operationId,
                            "familyId" to familyId,
                            "memberId" to receiver.id,
                            "memberName" to receiver.name,
                            "actionType" to PointsActionType.TRANSFER_IN.firestoreValue,
                            "pointsDelta" to amount,
                            "previousBalance" to cloudReceiverPrev,
                            "newBalance" to cloudReceiverNew,
                            "reason" to inEntry.reason,
                            "performedByUid" to actorUid,
                            "performedByName" to actorName,
                            "timestampMillis" to inEntry.timestampMillis,
                            "relatedMemberId" to sender.id,
                            "relatedMemberName" to sender.name,
                            "serverTimestamp" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בהעברת הנקודות בין הילדים"
                _pointsOperationError.value = msg
                return@withLock Result.failure(e)
            }
        }

        _pointsOperationError.value = null
        _members.value = _members.value.map { m ->
            when (m.id) {
                sender.id -> {
                    val updated = m.copy(earnedPoints = senderNew)
                    if (_currentMember.value.id == m.id) _currentMember.value = updated
                    updated
                }
                receiver.id -> {
                    val updated = m.copy(earnedPoints = receiverNew)
                    if (_currentMember.value.id == m.id) _currentMember.value = updated
                    updated
                }
                else -> m
            }
        }
        persistMemberPointsBalanceLocally(familyId, sender.id, senderNew)
        persistMemberPointsBalanceLocally(familyId, receiver.id, receiverNew)
        appendPointsHistoryEntryLocally(familyId, outEntry)
        appendPointsHistoryEntryLocally(familyId, inEntry)

        pushAlert(
            title = "🔄 העברת נקודות בוצעה",
            message = "$actorName העביר/ה $amount נקודות מ-${sender.name} אל ${receiver.name} ($cleanReason)",
            targetName = receiver.name,
            targetEmoji = receiver.avatarEmoji,
            reason = AlertReason.TASK_COMPLETED
        )
        return@withLock Result.success(outEntry to inEntry)
    }

    fun transferPointsBetweenMembers(
        fromMemberId: String,
        toMemberId: String,
        amount: Int,
        reason: String
    ): Boolean {
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                transferPointsBetweenMembersAsync(fromMemberId, toMemberId, amount, reason).isSuccess
            }
        }
        repoScope.launch {
            transferPointsBetweenMembersAsync(fromMemberId, toMemberId, amount, reason)
        }
        return true
    }

    /**
     * Grants a special parent helping bonus ([PointsActionType.HELPING_BONUS]) to [memberId]
     * without deducting points from any other child.
     */
    suspend fun grantHelpingBonusPointsAsync(
        memberId: String,
        amount: Int,
        reason: String,
        relatedMemberId: String? = null,
        taskId: String? = null
    ): Result<PointsHistoryEntry> {
        val cleanReason = reason.trim().ifBlank { "בונוס מיוחד על עזרה ושיתוף פעולה" }
        val result = adjustMemberPointsAsync(
            memberId = memberId,
            actionType = PointsActionType.HELPING_BONUS,
            amount = amount,
            reason = cleanReason,
            taskId = taskId
        )
        if (result.isSuccess && !relatedMemberId.isNullOrBlank()) {
            val entry = result.getOrNull()!!
            val relName = _members.value.find { it.id == relatedMemberId }?.name
            val enriched = entry.copy(
                relatedMemberId = relatedMemberId,
                relatedMemberName = relName
            )
            val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
            appendPointsHistoryEntryLocally(familyId, enriched)
            return Result.success(enriched)
        }
        return result
    }

    fun grantHelpingBonusPoints(
        memberId: String,
        amount: Int,
        reason: String,
        relatedMemberId: String? = null,
        taskId: String? = null
    ): Boolean {
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                grantHelpingBonusPointsAsync(memberId, amount, reason, relatedMemberId, taskId).isSuccess
            }
        }
        repoScope.launch {
            grantHelpingBonusPointsAsync(memberId, amount, reason, relatedMemberId, taskId)
        }
        return true
    }

    // =========================================================================
    //  PART E: FULL REWARD CATALOG MANAGEMENT & PERSONAL REWARD GOAL
    // =========================================================================

    /**
     * Part A (Personal Reward Goal Progress Meter):
     * Sets or clears a child's personal savings goal reward (`targetRewardId`) in `/families/{familyId}/members/{targetChildId}`.
     * - Only the child themselves (`caller.id == targetChildId`) or an authorized PARENT (`caller.isParent`)
     *   can change that child's personal reward goal.
     * - Selecting or changing a reward goal NEVER deducts points.
     */
    suspend fun setPersonalRewardGoalAsync(
        rewardId: String?,
        targetChildId: String = _currentMember.value.id
    ): Result<FamilyMember> {
        val caller = _currentMember.value
        val authUid = currentUid()
        val isSelfChild = caller.id == targetChildId || (authUid.isNotBlank() && authUid == targetChildId)
        if (!caller.isParent && !isSelfChild) {
            val msg = "רק הילד/ה עצמו/ה או הורה מורשה רשאים לשנות את יעד הפרס האישי 🔒"
            _pointsOperationError.value = msg
            return Result.failure(SecurityException(msg))
        }

        val targetMember = _members.value.find { it.id == targetChildId }
        if (targetMember == null) {
            _pointsOperationError.value = "פרופיל הילד/ה לא נמצא"
            return Result.failure(IllegalArgumentException("פרופיל הילד/ה לא נמצא"))
        }
        if (targetMember.isParent) {
            val msg = "יעד חיסכון לפרס זמין עבור ילדי המשפחה בלבד"
            _pointsOperationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        val cleanRewardId = rewardId?.trim()?.takeIf { it.isNotBlank() }
        if (cleanRewardId != null) {
            val reward = _rewards.value.find { it.id == cleanRewardId && !it.isDeleted && it.isActive }
            if (reward == null) {
                val msg = "הפרס שנבחר אינו זמין כעת לבחירה כיעד אישי"
                _pointsOperationError.value = msg
                return Result.failure(IllegalArgumentException(msg))
            }
        }

        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
        val nowMillis = System.currentTimeMillis()
        val updatedMember = targetMember.copy(targetRewardId = cleanRewardId)

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                db.collection("families")
                    .document(familyId)
                    .collection("members")
                    .document(targetChildId)
                    .set(
                        mapOf(
                            "targetRewardId" to (cleanRewardId ?: ""),
                            "updatedAt" to nowMillis
                        ),
                        SetOptions.merge()
                    )
                    .await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בשמירת יעד הפרס האישי"
                _pointsOperationError.value = msg
                return Result.failure(e)
            }
        }

        _pointsOperationError.value = null
        _members.value = _members.value.map { if (it.id == targetChildId) updatedMember else it }
        if (_currentMember.value.id == targetChildId) {
            _currentMember.value = updatedMember
        }
        persistMemberTargetRewardLocally(familyId, targetChildId, cleanRewardId)
        return Result.success(updatedMember)
    }

    fun setPersonalRewardGoal(
        rewardId: String?,
        targetChildId: String = _currentMember.value.id
    ): Boolean {
        val caller = _currentMember.value
        val authUid = currentUid()
        val isSelfChild = caller.id == targetChildId || (authUid.isNotBlank() && authUid == targetChildId)
        if (!caller.isParent && !isSelfChild) {
            _pointsOperationError.value = "רק הילד/ה עצמו/ה או הורה מורשה רשאים לשנות את יעד הפרס האישי 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                setPersonalRewardGoalAsync(rewardId, targetChildId).isSuccess
            }
        }
        repoScope.launch {
            setPersonalRewardGoalAsync(rewardId, targetChildId)
        }
        return true
    }

    fun getRewardGoalProgress(memberId: String = _currentMember.value.id): RewardGoalProgressUiModel? {
        val member = _members.value.find { it.id == memberId }
            ?: _currentMember.value.takeIf { it.id == memberId }
            ?: return null
        return RewardGoalProgressUiModel.compute(member, _rewards.value)
    }

    private fun buildRewardFirestoreMap(reward: RewardItem, familyId: String): Map<String, Any?> {
        return mapOf(
            "id" to reward.id,
            "familyId" to familyId,
            "title" to reward.title,
            "pointsCost" to reward.pointsCost,
            "iconEmoji" to reward.iconEmoji,
            "description" to reward.description,
            "isActive" to reward.isActive,
            "isDeleted" to reward.isDeleted,
            "createdAtMillis" to reward.createdAtMillis,
            "updatedAtMillis" to reward.updatedAtMillis
        )
    }

    suspend fun createRewardAsync(
        title: String,
        pointsCost: Int,
        iconEmoji: String = "🎁",
        description: String = "",
        isActive: Boolean = true
    ): Result<RewardItem> {
        val caller = _currentMember.value
        if (!caller.isParent) {
            val msg = "רק הורה רשאי להוסיף פרסים לקטלוג 🔒"
            _pointsOperationError.value = msg
            return Result.failure(SecurityException(msg))
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            val msg = "נא להזין שם לפרס"
            _pointsOperationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        if (pointsCost <= 0) {
            val msg = "עלות הפרס בנקודות חייבת להיות גדולה מ-0"
            _pointsOperationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        val nowMillis = System.currentTimeMillis()
        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
        val rewardId = "rew_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val reward = RewardItem(
            id = rewardId,
            familyId = familyId,
            title = cleanTitle,
            pointsCost = pointsCost,
            iconEmoji = iconEmoji.trim().ifBlank { "🎁" },
            description = description.trim(),
            isActive = isActive,
            isDeleted = false,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                db.collection("families")
                    .document(familyId)
                    .collection("rewards")
                    .document(rewardId)
                    .set(buildRewardFirestoreMap(reward, familyId))
                    .await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בשמירת הפרס החדש"
                _pointsOperationError.value = msg
                return Result.failure(e)
            }
        }

        _pointsOperationError.value = null
        _rewards.value = (_rewards.value.filterNot { it.id == rewardId } + reward)
            .sortedBy { it.pointsCost }
        return Result.success(reward)
    }

    fun createReward(
        title: String,
        pointsCost: Int,
        iconEmoji: String = "🎁",
        description: String = "",
        isActive: Boolean = true
    ): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי להוסיף פרסים לקטלוג 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                createRewardAsync(title, pointsCost, iconEmoji, description, isActive).isSuccess
            }
        }
        repoScope.launch {
            createRewardAsync(title, pointsCost, iconEmoji, description, isActive)
        }
        return true
    }

    suspend fun updateRewardAsync(
        rewardId: String,
        title: String,
        pointsCost: Int,
        iconEmoji: String,
        description: String,
        isActive: Boolean = true
    ): Result<RewardItem> {
        val caller = _currentMember.value
        if (!caller.isParent) {
            val msg = "רק הורה רשאי לערוך פרסים בקטלוג 🔒"
            _pointsOperationError.value = msg
            return Result.failure(SecurityException(msg))
        }
        val existing = _rewards.value.find { it.id == rewardId && !it.isDeleted }
        if (existing == null) {
            _pointsOperationError.value = "הפרס המבוקש לא נמצא בקטלוג"
            return Result.failure(IllegalArgumentException("הפרס המבוקש לא נמצא בקטלוג"))
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            val msg = "נא להזין שם לפרס"
            _pointsOperationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }
        if (pointsCost <= 0) {
            val msg = "עלות הפרס בנקודות חייבת להיות גדולה מ-0"
            _pointsOperationError.value = msg
            return Result.failure(IllegalArgumentException(msg))
        }

        val nowMillis = System.currentTimeMillis()
        val familyId = existing.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
        val updated = existing.copy(
            familyId = familyId,
            title = cleanTitle,
            pointsCost = pointsCost,
            iconEmoji = iconEmoji.trim().ifBlank { "🎁" },
            description = description.trim(),
            isActive = isActive,
            updatedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                db.collection("families")
                    .document(familyId)
                    .collection("rewards")
                    .document(rewardId)
                    .set(buildRewardFirestoreMap(updated, familyId), SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בעדכון הפרס"
                _pointsOperationError.value = msg
                return Result.failure(e)
            }
        }

        _pointsOperationError.value = null
        _rewards.value = _rewards.value.map { if (it.id == rewardId) updated else it }
            .sortedBy { it.pointsCost }
        return Result.success(updated)
    }

    fun updateReward(
        rewardId: String,
        title: String,
        pointsCost: Int,
        iconEmoji: String,
        description: String,
        isActive: Boolean = true
    ): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי לערוך פרסים בקטלוג 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                updateRewardAsync(rewardId, title, pointsCost, iconEmoji, description, isActive).isSuccess
            }
        }
        repoScope.launch {
            updateRewardAsync(rewardId, title, pointsCost, iconEmoji, description, isActive)
        }
        return true
    }

    suspend fun setRewardActiveStateAsync(rewardId: String, isActive: Boolean): Result<RewardItem> {
        val existing = _rewards.value.find { it.id == rewardId && !it.isDeleted }
            ?: return Result.failure(IllegalArgumentException("הפרס לא נמצא"))
        return updateRewardAsync(
            rewardId = existing.id,
            title = existing.title,
            pointsCost = existing.pointsCost,
            iconEmoji = existing.iconEmoji,
            description = existing.description,
            isActive = isActive
        )
    }

    fun setRewardActiveState(rewardId: String, isActive: Boolean): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי להפעיל או להשבית פרסים בקטלוג 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                setRewardActiveStateAsync(rewardId, isActive).isSuccess
            }
        }
        repoScope.launch {
            setRewardActiveStateAsync(rewardId, isActive)
        }
        return true
    }

    /**
     * Soft-deletes a reward from the active catalog without corrupting or deleting historical
     * [RewardRedemptionRequest] or [PointsHistoryEntry] records.
     */
    suspend fun deleteRewardFromCatalogAsync(rewardId: String): Result<Boolean> {
        val caller = _currentMember.value
        if (!caller.isParent) {
            val msg = "רק הורה רשאי למחוק פרסים מהקטלוג 🔒"
            _pointsOperationError.value = msg
            return Result.failure(SecurityException(msg))
        }
        val existing = _rewards.value.find { it.id == rewardId }
            ?: return Result.failure(IllegalArgumentException("הפרס לא נמצא בקטלוג"))

        val nowMillis = System.currentTimeMillis()
        val familyId = existing.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
        val softDeleted = existing.copy(isActive = false, isDeleted = true, updatedAtMillis = nowMillis)

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                db.collection("families")
                    .document(familyId)
                    .collection("rewards")
                    .document(rewardId)
                    .set(buildRewardFirestoreMap(softDeleted, familyId), SetOptions.merge())
                    .await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה במחיקת הפרס מהקטלוג"
                _pointsOperationError.value = msg
                return Result.failure(e)
            }
        }

        _pointsOperationError.value = null
        _rewards.value = _rewards.value.map { if (it.id == rewardId) softDeleted else it }
        return Result.success(true)
    }

    fun deleteRewardFromCatalog(rewardId: String): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי למחוק פרסים מהקטלוג 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                deleteRewardFromCatalogAsync(rewardId).isSuccess
            }
        }
        repoScope.launch {
            deleteRewardFromCatalogAsync(rewardId)
        }
        return true
    }

    // =========================================================================
    //  PART F: REWARD REDEMPTION REQUESTS & PARENT APPROVAL
    // =========================================================================

    private fun parseRewardRedemptionDocument(
        docId: String,
        data: Map<String, Any?>,
        fallbackFamilyId: String
    ): RewardRedemptionRequest? {
        val rewardId = (data["rewardId"] as? String)?.trim().orEmpty()
        val childUid = (data["childUid"] as? String)?.trim().orEmpty()
        if (rewardId.isBlank() || childUid.isBlank()) return null

        val statusRaw = (data["status"] as? String) ?: RedemptionStatus.PENDING_PARENT_APPROVAL.firestoreValue
        val pointsCost = (data["pointsCost"] as? Number)?.toInt() ?: 0
        val requestedAt = (data["requestedAtMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()

        return RewardRedemptionRequest(
            id = docId,
            operationId = (data["operationId"] as? String)?.takeIf { it.isNotBlank() } ?: docId,
            familyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() } ?: fallbackFamilyId,
            rewardId = rewardId,
            rewardTitle = (data["rewardTitle"] as? String).orEmpty(),
            rewardIconEmoji = (data["rewardIconEmoji"] as? String)?.takeIf { it.isNotBlank() } ?: "🎁",
            pointsCost = pointsCost,
            childUid = childUid,
            childName = (data["childName"] as? String).orEmpty(),
            status = RedemptionStatus.fromFirestoreValue(statusRaw),
            requestedAtMillis = requestedAt,
            decidedAtMillis = (data["decidedAtMillis"] as? Number)?.toLong(),
            decidedByParentUid = (data["decidedByParentUid"] as? String)?.takeIf { it.isNotBlank() },
            decidedByParentName = (data["decidedByParentName"] as? String)?.takeIf { it.isNotBlank() },
            rejectionReason = (data["rejectionReason"] as? String)?.takeIf { it.isNotBlank() }
        )
    }

    private fun buildRewardRedemptionFirestoreMap(req: RewardRedemptionRequest): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "id" to req.id,
            "operationId" to req.operationId,
            "familyId" to req.familyId,
            "rewardId" to req.rewardId,
            "rewardTitle" to req.rewardTitle,
            "rewardIconEmoji" to req.rewardIconEmoji,
            "pointsCost" to req.pointsCost,
            "childUid" to req.childUid,
            "childName" to req.childName,
            "status" to req.status.firestoreValue,
            "requestedAtMillis" to req.requestedAtMillis
        )
        if (req.decidedAtMillis != null) map["decidedAtMillis"] = req.decidedAtMillis
        if (!req.decidedByParentUid.isNullOrBlank()) map["decidedByParentUid"] = req.decidedByParentUid
        if (!req.decidedByParentName.isNullOrBlank()) map["decidedByParentName"] = req.decidedByParentName
        if (!req.rejectionReason.isNullOrBlank()) map["rejectionReason"] = req.rejectionReason
        return map
    }

    /**
     * Child requests to redeem an active reward from the catalog.
     * - Validates that the reward is active (`isActive && !isDeleted`) and `child.earnedPoints >= reward.pointsCost`.
     * - Creates a [RewardRedemptionRequest] in [RedemptionStatus.PENDING_PARENT_APPROVAL].
     * - Does NOT deduct points before parent approval!
     */
    suspend fun requestRewardRedemptionAsync(
        reward: RewardItem,
        childMemberId: String = _currentMember.value.id
    ): Result<RewardRedemptionRequest> {
        if (_rewards.value.none { it.id == reward.id }) {
            _rewards.value = _rewards.value + reward
        }
        return requestRewardRedemptionAsync(reward.id, childMemberId)
    }

    suspend fun requestRewardRedemptionAsync(
        rewardId: String,
        childMemberId: String = _currentMember.value.id
    ): Result<RewardRedemptionRequest> {
        val reward = _rewards.value.find { it.id == rewardId && !it.isDeleted && it.isActive }
        if (reward == null) {
            _pointsOperationError.value = "הפרס אינו זמין למימוש"
            return Result.failure(IllegalArgumentException("הפרס אינו זמין למימוש"))
        }

        val child = _members.value.find { it.id == childMemberId } ?: _currentMember.value
        if (child.earnedPoints < reward.pointsCost) {
            val msg = "אין מספיק נקודות למימוש הפרס \"${reward.title}\" (נדרשות ${reward.pointsCost} נק׳, ברשותך ${child.earnedPoints} נק׳)"
            _pointsOperationError.value = msg
            return Result.failure(IllegalStateException(msg))
        }

        val nowMillis = System.currentTimeMillis()
        val familyId = _activeFamilyId.value.ifBlank { "fam_israeli_8492" }
        val redemptionId = "red_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val request = RewardRedemptionRequest(
            id = redemptionId,
            operationId = redemptionId,
            familyId = familyId,
            rewardId = reward.id,
            rewardTitle = reward.title,
            rewardIconEmoji = reward.iconEmoji,
            pointsCost = reward.pointsCost,
            childUid = child.id,
            childName = child.name,
            status = RedemptionStatus.PENDING_PARENT_APPROVAL,
            requestedAtMillis = nowMillis
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                db.collection("families")
                    .document(familyId)
                    .collection("redemptions")
                    .document(redemptionId)
                    .set(buildRewardRedemptionFirestoreMap(request))
                    .await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה בשליחת בקשת מימוש הפרס"
                _pointsOperationError.value = msg
                return Result.failure(e)
            }
        }

        _pointsOperationError.value = null
        _rewardRedemptions.value = listOf(request) + _rewardRedemptions.value.filterNot { it.id == redemptionId }

        pushAlert(
            title = "🎁 בקשה חדשה למימוש פרס",
            message = "${child.name} מבקש/ת לממש את הפרס \"${reward.title}\" (${reward.pointsCost} נק׳). ממתין לאישור הורה.",
            targetName = "הורים",
            targetEmoji = reward.iconEmoji,
            reason = AlertReason.TASK_COMPLETED
        )
        return Result.success(request)
    }

    fun requestRewardRedemption(
        rewardId: String,
        childMemberId: String = _currentMember.value.id
    ): Boolean {
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                requestRewardRedemptionAsync(rewardId, childMemberId).isSuccess
            }
        }
        repoScope.launch {
            requestRewardRedemptionAsync(rewardId, childMemberId)
        }
        return true
    }

    /**
     * Parent approves or rejects a pending [RewardRedemptionRequest].
     * - Under `pointsMutex.withLock`:
     *   - If `approve == true`: verifies the child STILL has `earnedPoints >= request.pointsCost`
     *     at the moment of approval (preventing two parallel requests from pushing the child into a negative balance),
     *     deducts `pointsCost` from `earnedPoints`, increments `spentPoints`, records a [PointsHistoryEntry]
     *     ([PointsActionType.REWARD_REDEEMED]), and marks the request [RedemptionStatus.APPROVED].
     *   - If `approve == false`: marks the request [RedemptionStatus.REJECTED] without deducting any points.
     */
    suspend fun decideRewardRedemptionAsParentAsync(
        redemptionId: String,
        approve: Boolean,
        rejectionReason: String = ""
    ): Result<RewardRedemptionRequest> = pointsMutex.withLock {
        val caller = _currentMember.value
        if (!caller.isParent) {
            val msg = "רק הורה רשאי לאשר או לדחות מימוש פרס 🔒"
            _pointsOperationError.value = msg
            return@withLock Result.failure(SecurityException(msg))
        }

        val request = _rewardRedemptions.value.find { it.id == redemptionId }
        if (request == null) {
            _pointsOperationError.value = "בקשת המימוש לא נמצאה"
            return@withLock Result.failure(IllegalArgumentException("בקשת המימוש לא נמצאה"))
        }

        if (request.status != RedemptionStatus.PENDING_PARENT_APPROVAL) {
            val msg = "בקשת מימוש זו כבר טופלה"
            _pointsOperationError.value = msg
            return@withLock Result.failure(IllegalStateException(msg))
        }

        val nowMillis = System.currentTimeMillis()
        val familyId = request.familyId.ifBlank { _activeFamilyId.value.ifBlank { "fam_israeli_8492" } }
        val actorUid = currentUid().ifBlank { caller.id }
        val actorName = caller.name.ifBlank { "הורה" }

        if (!approve) {
            val rejected = request.copy(
                status = RedemptionStatus.REJECTED,
                decidedAtMillis = nowMillis,
                decidedByParentUid = actorUid,
                decidedByParentName = actorName,
                rejectionReason = rejectionReason.trim().ifBlank { null }
            )
            val db = firestoreProvider()
            val authUser = authProvider()?.currentUser
            if (db != null && authUser != null) {
                try {
                    db.collection("families")
                        .document(familyId)
                        .collection("redemptions")
                        .document(redemptionId)
                        .set(buildRewardRedemptionFirestoreMap(rejected), SetOptions.merge())
                        .await()
                } catch (e: Exception) {
                    val msg = e.localizedMessage ?: "שגיאה בדחיית בקשת המימוש"
                    _pointsOperationError.value = msg
                    return@withLock Result.failure(e)
                }
            }
            _pointsOperationError.value = null
            _rewardRedemptions.value = _rewardRedemptions.value.map { if (it.id == redemptionId) rejected else it }
            pushAlert(
                title = "בקשת מימוש הפרס נדחתה",
                message = "$actorName דחה/תה את הבקשה למימוש הפרס \"${request.rewardTitle}\". הנקודות לא הופחתו.",
                targetName = request.childName,
                targetEmoji = "ℹ️",
                reason = AlertReason.TASK_REMINDER
            )
            return@withLock Result.success(rejected)
        }

        // Parent approval path: re-verify child balance atomically so parallel redemptions can never go negative
        val child = _members.value.find { it.id == request.childUid }
        if (child == null) {
            _pointsOperationError.value = "חשבון הילד/ה לא נמצא"
            return@withLock Result.failure(IllegalStateException("חשבון הילד/ה לא נמצא"))
        }

        val prevBal = child.earnedPoints.coerceAtLeast(0)
        if (prevBal < request.pointsCost) {
            val msg = "לא ניתן לאשר את מימוש הפרס \"${request.rewardTitle}\" (${request.pointsCost} נק׳) — היתרה הנוכחית של ${child.name} היא $prevBal נק׳ בלבד"
            _pointsOperationError.value = msg
            return@withLock Result.failure(IllegalStateException(msg))
        }

        val newBal = prevBal - request.pointsCost
        val newSpent = child.spentPoints + request.pointsCost
        val histOpId = "pts_red_$redemptionId"
        val approvedRequest = request.copy(
            status = RedemptionStatus.APPROVED,
            decidedAtMillis = nowMillis,
            decidedByParentUid = actorUid,
            decidedByParentName = actorName
        )
        val historyEntry = PointsHistoryEntry(
            id = histOpId,
            operationId = histOpId,
            familyId = familyId,
            memberId = child.id,
            memberName = child.name,
            actionType = PointsActionType.REWARD_REDEEMED,
            pointsDelta = -request.pointsCost,
            previousBalance = prevBal,
            newBalance = newBal,
            reason = "מימוש פרס באישור הורה: ${request.rewardTitle}",
            performedByUid = actorUid,
            performedByName = actorName,
            timestampMillis = nowMillis,
            rewardId = request.rewardId,
            redemptionId = request.id
        )

        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if (db != null && authUser != null) {
            try {
                val familyRef = db.collection("families").document(familyId)
                val childRef = familyRef.collection("members").document(child.id)
                val redRef = familyRef.collection("redemptions").document(redemptionId)
                val histRef = familyRef.collection("points_history").document(histOpId)

                db.runTransaction { tx ->
                    val childSnap = tx.get(childRef)
                    val redSnap = tx.get(redRef)
                    if (redSnap.exists()) {
                        val cloudStatus = RedemptionStatus.fromFirestoreValue(redSnap.getString("status"))
                        if (cloudStatus != RedemptionStatus.PENDING_PARENT_APPROVAL) {
                            throw IllegalStateException("בקשת מימוש זו כבר טופלה")
                        }
                    }
                    val cloudPrev = if (childSnap.exists()) {
                        (childSnap.getLong("earnedPoints") ?: prevBal.toLong()).toInt().coerceAtLeast(0)
                    } else {
                        prevBal
                    }
                    if (cloudPrev < request.pointsCost) {
                        throw IllegalStateException("לא ניתן לאשר מימוש — היתרה של ${child.name} בענן היא $cloudPrev נק׳ בלבד")
                    }
                    val cloudNew = cloudPrev - request.pointsCost
                    val cloudSpent = ((childSnap.getLong("spentPoints") ?: child.spentPoints.toLong()).toInt() + request.pointsCost)

                    tx.set(
                        childRef,
                        mapOf(
                            "earnedPoints" to cloudNew,
                            "spentPoints" to cloudSpent,
                            "lastPointsOperationId" to histOpId,
                            "updatedAt" to nowMillis
                        ),
                        SetOptions.merge()
                    )
                    tx.set(redRef, buildRewardRedemptionFirestoreMap(approvedRequest), SetOptions.merge())
                    tx.set(
                        histRef,
                        mapOf(
                            "id" to historyEntry.id,
                            "operationId" to historyEntry.operationId,
                            "familyId" to familyId,
                            "memberId" to child.id,
                            "memberName" to child.name,
                            "actionType" to PointsActionType.REWARD_REDEEMED.firestoreValue,
                            "pointsDelta" to -request.pointsCost,
                            "previousBalance" to cloudPrev,
                            "newBalance" to cloudNew,
                            "reason" to historyEntry.reason,
                            "performedByUid" to actorUid,
                            "performedByName" to actorName,
                            "timestampMillis" to nowMillis,
                            "rewardId" to request.rewardId,
                            "redemptionId" to request.id,
                            "serverTimestamp" to FieldValue.serverTimestamp()
                        )
                    )
                }.await()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "שגיאה באישור מימוש הפרס"
                _pointsOperationError.value = msg
                return@withLock Result.failure(e)
            }
        }

        _pointsOperationError.value = null
        _rewardRedemptions.value = _rewardRedemptions.value.map { if (it.id == redemptionId) approvedRequest else it }
        _members.value = _members.value.map { m ->
            if (m.id == child.id) {
                val updated = m.copy(earnedPoints = newBal, spentPoints = newSpent)
                if (_currentMember.value.id == m.id) {
                    _currentMember.value = updated
                }
                updated
            } else {
                m
            }
        }
        persistMemberPointsBalanceLocally(familyId, child.id, newBal)
        appendPointsHistoryEntryLocally(familyId, historyEntry)

        pushAlert(
            title = "🎉 מימוש הפרס אושר!",
            message = "$actorName אישר/ה ל-${child.name} את הפרס \"${request.rewardTitle}\" (-${request.pointsCost} נק׳).",
            targetName = child.name,
            targetEmoji = request.rewardIconEmoji,
            reason = AlertReason.TASK_COMPLETED
        )
        return@withLock Result.success(approvedRequest)
    }

    fun decideRewardRedemptionAsParent(
        redemptionId: String,
        approve: Boolean,
        rejectionReason: String = ""
    ): Boolean {
        if (!_currentMember.value.isParent) {
            _pointsOperationError.value = "רק הורה רשאי לאשר או לדחות מימוש פרס 🔒"
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                decideRewardRedemptionAsParentAsync(redemptionId, approve, rejectionReason).isSuccess
            }
        }
        repoScope.launch {
            decideRewardRedemptionAsParentAsync(redemptionId, approve, rejectionReason)
        }
        return true
    }

    // --- Personal Avatar Selection Operations ---

    private val avatarOperationMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun updateMemberAvatarAsync(
        targetMemberId: String = _currentMember.value.id,
        avatarId: String
    ): Result<FamilyMember> = avatarOperationMutex.withLock {
        val current = _currentMember.value
        val callerUid = currentUid().ifBlank { current.id }
        val cleanTargetId = targetMemberId.trim().ifBlank { current.id }
        val targetMember = _members.value.find { it.id == cleanTargetId }
            ?: return@withLock Result.failure(IllegalArgumentException("בן המשפחה לא נמצא"))

        val isSelf = cleanTargetId == current.id || cleanTargetId == callerUid
        if (!isSelf && !current.isParent) {
            return@withLock Result.failure(SecurityException("ילד יכול לשנות רק את האווטאר של עצמו 🔒"))
        }

        val avatarOption = FamilyAvatarCatalog.findById(avatarId)
            ?: return@withLock Result.failure(IllegalArgumentException("האווטאר שנבחר אינו קיים בגלריה"))

        val familyId = _activeFamilyId.value
        val nowMillis = System.currentTimeMillis()
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser

        if (db != null && authUser != null) {
            try {
                val memberRef = db.collection("families").document(familyId)
                    .collection("members").document(targetMember.id)
                memberRef.set(
                    mapOf(
                        "avatarId" to avatarOption.id,
                        "avatarEmoji" to avatarOption.emoji,
                        "colorHex" to avatarOption.primaryColorHex,
                        "updatedAt" to nowMillis
                    ),
                    SetOptions.merge()
                ).await()

                if (targetMember.id == authUser.uid) {
                    runCatching {
                        db.collection("users").document(authUser.uid)
                            .set(
                                mapOf(
                                    "avatarId" to avatarOption.id,
                                    "avatarEmoji" to avatarOption.emoji,
                                    "updatedAt" to nowMillis
                                ),
                                SetOptions.merge()
                            ).await()
                    }
                }
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }
        }

        val updatedMember = targetMember.copy(
            avatarId = avatarOption.id,
            avatarEmoji = avatarOption.emoji,
            colorHex = avatarOption.primaryColorHex
        )

        _members.value = _members.value.map { m ->
            if (m.id == targetMember.id) updatedMember else m
        }
        if (_currentMember.value.id == targetMember.id) {
            _currentMember.value = updatedMember
        }
        persistMemberAvatarLocally(familyId, targetMember.id, avatarOption.id)
        return@withLock Result.success(updatedMember)
    }

    fun updateMemberAvatar(
        targetMemberId: String = _currentMember.value.id,
        avatarId: String
    ): Boolean {
        val current = _currentMember.value
        val callerUid = currentUid().ifBlank { current.id }
        val cleanTargetId = targetMemberId.trim().ifBlank { current.id }
        val isSelf = cleanTargetId == current.id || cleanTargetId == callerUid
        if (!isSelf && !current.isParent) {
            return false
        }
        if (FamilyAvatarCatalog.findById(avatarId) == null) {
            return false
        }
        if (_members.value.none { it.id == cleanTargetId }) {
            return false
        }
        val db = firestoreProvider()
        val authUser = authProvider()?.currentUser
        if ((db == null || authUser == null) && !simulateOfflineNetworkFailure) {
            return kotlinx.coroutines.runBlocking {
                updateMemberAvatarAsync(cleanTargetId, avatarId).isSuccess
            }
        }
        repoScope.launch {
            updateMemberAvatarAsync(cleanTargetId, avatarId)
        }
        return true
    }
}

