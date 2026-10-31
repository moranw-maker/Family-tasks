package com.example.model

import java.time.LocalDate
import java.util.UUID

enum class AppScreen {
    HOME,
    CALENDAR,
    TIMETABLE,
    ALARMS,
    FILES,
    AI_ASSISTANT,
    SETTINGS,
    ADMIN_DASHBOARD,
    POINTS_MANAGEMENT
}

enum class AppLanguage(val code: String, val displayName: String, val isRtl: Boolean) {
    HEBREW("he", "עברית (RTL)", true),
    ENGLISH("en", "English (LTR)", false)
}

enum class AppThemeMode(
    val labelHe: String,
    val labelEn: String,
    val editionSubtitleHe: String,
    val editionSubtitleEn: String
) {
    SYSTEM("לפי הגדרות המכשיר", "System", "אוטומטי", "Auto"),
    LIGHT("בהיר", "Light", "Velvet Light", "Velvet Light"),
    DARK("כהה", "Dark", "Velvet Dark", "Velvet Dark");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
    fun getEditionSubtitle(isHebrew: Boolean): String = if (isHebrew) editionSubtitleHe else editionSubtitleEn
}

enum class AppTextScale(
    val scaleFactor: Float,
    val labelHe: String,
    val labelEn: String
) {
    SMALL(0.90f, "קטן", "Small"),
    NORMAL(1.00f, "רגיל", "Normal"),
    LARGE(1.12f, "גדול", "Large"),
    EXTRA_LARGE(1.24f, "גדול מאוד", "Extra Large"),
    HUGE(1.36f, "ענק", "Huge");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
}

enum class AppThemeColor(
    val primaryHex: Long,
    val containerHex: Long,
    val labelHe: String,
    val labelEn: String
) {
    GOOGLE_BLUE(0xFF1A73E8, 0xFFE8F0FE, "כחול Google", "Google Blue"),
    EMERALD_GREEN(0xFF1E8E3E, 0xFFE6F4EA, "ירוק אזמרגד", "Emerald Green"),
    ROYAL_PURPLE(0xFF9334E6, 0xFFF3E8FD, "סגול מלכותי", "Royal Purple"),
    SUNSET_ORANGE(0xFFE8710A, 0xFFFEF7E0, "כתום שקיעה", "Sunset Orange")
}

enum class AlertSound(val labelHe: String, val labelEn: String) {
    GOOGLE_CHIME("פעמון Google", "Google Chime"),
    BRIGHT_BELL("פעמון בהיר", "Bright Bell"),
    POP_BUBBLE("בועה קופצנית", "Pop Bubble"),
    COSMIC_TONE("צליל קוסמי", "Cosmic Tone"),
    GENTLE_HARP("נבל עדין", "Gentle Harp");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
}

data class AppSettings(
    val familyTitleHe: String = "משפחת וקסלר",
    val familyTitleEn: String = "Wexler Family",
    val fallbackPin: String = "1234",
    val biometricEnabled: Boolean = true,
    val requireBiometricOnStart: Boolean = false
) {
    fun getFamilyTitle(isHebrew: Boolean): String = if (isHebrew) familyTitleHe else familyTitleEn
}

enum class FirstDayOfWeek(val labelHe: String, val labelEn: String) {
    SUNDAY("יום ראשון (ישראל)", "Sunday"),
    MONDAY("יום שני (אירופה/ארה״ב)", "Monday");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
}

enum class TimetableViewMode {
    DAILY_LIST,
    WEEKLY_TABLE
}

enum class FilesViewMode {
    FOLDERS,
    LIST,
    GRID
}

enum class TasksViewMode(val labelHe: String, val labelEn: String) {
    STANDARD("תצוגה מפורטת", "Detailed View"),
    COMPACT("תצוגה קומפקטית", "Compact View");

    fun next(): TasksViewMode = if (this == STANDARD) COMPACT else STANDARD
    fun toggled(): TasksViewMode = next()
    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    companion object {
        fun fromRaw(raw: String?): TasksViewMode {
            return entries.find { it.name.equals(raw?.trim(), ignoreCase = true) } ?: STANDARD
        }
    }
}

/**
 * V1.6 Two-Role Family Permission Model:
 * Strictly two roles exist in the family system:
 * - [PARENT]: Full family management permissions (create/edit/assign/delete tasks, invite parents or children,
 *   manage members, change roles with last-parent check, manage family settings).
 * - [CHILD]: Can view permitted family tasks, view their assigned tasks, mark their assigned tasks as completed,
 *   and view progress/rewards. Cannot create/edit/assign/delete tasks, invite members, or manage roles.
 */
enum class UserRole(val labelHe: String, val labelEn: String, val isAdmin: Boolean) {
    PARENT("הורה (מנהל משפחה)", "Parent (Admin)", true),
    CHILD("ילד/ה", "Child", false);

    val isParent: Boolean get() = this == PARENT
    val isParentRole: Boolean get() = this == PARENT
    val isChild: Boolean get() = this == CHILD

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    fun toFirestoreValue(): String = if (this == PARENT) "PARENT" else "CHILD"

    companion object {
        val PRIMARY_ADMIN: UserRole get() = PARENT
        val SECONDARY_ADMIN: UserRole get() = PARENT
        val ADMIN_PARENT: UserRole get() = PARENT
        val MEMBER_KID: UserRole get() = CHILD
        val GUEST: UserRole get() = CHILD

        /**
         * Parses any stored Firestore role string into the canonical two-role model ([PARENT] or [CHILD]).
         * Unknown or missing role strings always default safely to [CHILD] (least privilege).
         */
        fun fromFirestoreValue(raw: String?): UserRole {
            return when (raw?.trim()?.uppercase()) {
                "PARENT", "PRIMARY_ADMIN", "SECONDARY_ADMIN", "ADMIN_PARENT", "ADMIN" -> PARENT
                "CHILD", "MEMBER_KID", "KID", "MEMBER", "GUEST" -> CHILD
                else -> CHILD
            }
        }
    }
}

/**
 * Alias for [UserRole] to match Firestore schema terminology.
 */
typealias FamilyRole = UserRole

/**
 * Represents the user's personal identity within the family (e.g., [FATHER] or [MOTHER]),
 * distinct from their authorization [UserRole] ([UserRole.PARENT] or [UserRole.CHILD]).
 * Both [FATHER] and [MOTHER] hold the [UserRole.PARENT] authorization role.
 */
enum class FamilyIdentity(
    val firestoreValue: String,
    val labelHe: String,
    val labelEn: String,
    val defaultEmoji: String,
    val authorizationRole: UserRole
) {
    FATHER("FATHER", "אבא", "Father", "👨", UserRole.PARENT),
    MOTHER("MOTHER", "אמא", "Mother", "👩", UserRole.PARENT),
    SON("SON", "בן", "Son", "👦", UserRole.CHILD),
    DAUGHTER("DAUGHTER", "בת", "Daughter", "👧", UserRole.CHILD),
    CHILD("CHILD", "ילד/ה", "Child", "🧒", UserRole.CHILD);

    val isParentIdentity: Boolean get() = authorizationRole == UserRole.PARENT

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    fun toFirestoreValue(): String = firestoreValue

    companion object {
        /**
         * Resolves [FamilyIdentity] from stored Firestore values without EVER defaulting
         * an existing [FATHER] or unconfigured [UserRole.PARENT] to [MOTHER].
         */
        fun fromFirestoreValue(
            raw: String?,
            role: UserRole = UserRole.CHILD,
            avatarEmoji: String? = null,
            nameHint: String? = null,
            memberId: String? = null,
            fallbackParentIdentity: FamilyIdentity = FATHER
        ): FamilyIdentity {
            val normalized = raw?.trim()?.uppercase()
            when (normalized) {
                "FATHER", "DAD", "ABA", "אבא" -> return if (role == UserRole.PARENT) FATHER else CHILD
                "MOTHER", "MOM", "IMA", "אמא" -> return if (role == UserRole.PARENT) MOTHER else CHILD
                "SON", "BOY", "בן" -> return if (role == UserRole.PARENT) FATHER else SON
                "DAUGHTER", "GIRL", "בת" -> return if (role == UserRole.PARENT) MOTHER else DAUGHTER
                "CHILD", "KID", "ילד", "ילדה", "ילד/ה" -> if (role == UserRole.CHILD) return CHILD
            }

            val emoji = avatarEmoji?.trim().orEmpty()
            val lowerName = nameHint?.trim()?.lowercase().orEmpty()

            return if (role == UserRole.PARENT) {
                when {
                    memberId == "m_dad" ||
                        emoji == "👨" ||
                        lowerName.contains("אבא") ||
                        lowerName.contains("father") ||
                        lowerName.contains("dad") -> FATHER
                    memberId == "m_mom" ||
                        emoji == "👩" ||
                        lowerName.contains("אמא") ||
                        lowerName.contains("mother") ||
                        lowerName.contains("mom") -> MOTHER
                    else -> fallbackParentIdentity
                }
            } else {
                when {
                    emoji == "👦" || lowerName.contains("בן") || lowerName.contains("son") -> SON
                    emoji == "👧" || lowerName.contains("בת") || lowerName.contains("daughter") -> DAUGHTER
                    else -> CHILD
                }
            }
        }
    }
}

/**
 * Explicit startup state machine preventing premature navigation to onboarding
 * or destructive profile/family creation before Firestore data has finished loading.
 */
sealed class StartupState {
    /** Step 1: Restoring FirebaseAuth session on application launch. */
    data object LoadingAuthentication : StartupState()

    /** Step 2: Authenticated user session exists; loading `/users/{uid}` profile from Firestore. */
    data class LoadingUserProfile(val uid: String) : StartupState()

    /** Step 3: Loading `/families/{familyId}` and `/families/{familyId}/members/{uid}` from Firestore. */
    data class LoadingFamilyMembership(val uid: String, val familyId: String) : StartupState()

    /** Step 4a: User is unauthenticated OR Firestore confirmed no existing family membership exists. */
    data class OnboardingRequired(
        val uid: String? = null,
        val email: String? = null,
        val displayName: String? = null
    ) : StartupState()

    /** Step 4b: Authenticated user's profile and family membership are loaded and verified. */
    data class ReadyToEnterDashboard(
        val uid: String,
        val familyId: String,
        val familyName: String,
        val role: UserRole,
        val familyIdentity: FamilyIdentity
    ) : StartupState()

    /** Step 4c: Transient/offline or Firestore error while loading existing user/family; allows safe retry without resetting data. */
    data class RecoverableError(
        val uid: String? = null,
        val messageHe: String,
        val messageEn: String = messageHe,
        val errorCode: String = "UNAVAILABLE",
        val stage: String = "",
        val databaseId: String = ""
    ) : StartupState()
}

/**
 * Represents the authenticated user's verified family membership state in Firestore.
 * Used after Google Sign-In to prevent automatic family creation or automatic PARENT promotion.
 */
sealed class FamilyMembershipState {
    /** Initial state while checking Firestore or before authentication. */
    data object Checking : FamilyMembershipState()

    /** User is not signed in with Google. */
    data object Unauthenticated : FamilyMembershipState()

    /**
     * Authenticated Google user has NO family membership in Firestore.
     * The app MUST display the mandatory onboarding screen ("Create a family" or "Join a family")
     * and MUST NOT open the family dashboard or auto-assign PARENT.
     */
    data object NoMembership : FamilyMembershipState() {
        operator fun invoke(
            uid: String = "",
            email: String = "",
            displayName: String = "",
            photoUrl: String? = null
        ): NoMembership = NoMembership
    }

    /**
     * Authenticated user has a verified membership in a Firestore family with an explicit role ([UserRole.PARENT] or [UserRole.CHILD])
     * and preserved [familyIdentity] ([FamilyIdentity.FATHER] or [FamilyIdentity.MOTHER] for parents).
     */
    data class ActiveMember(
        val familyId: String,
        val familyName: String,
        val role: UserRole,
        val familyIdentity: FamilyIdentity = if (role == UserRole.PARENT) FamilyIdentity.FATHER else FamilyIdentity.CHILD,
        val member: FamilyMember = FamilyMember(
            name = "",
            role = role,
            familyIdentity = familyIdentity,
            avatarEmoji = familyIdentity.defaultEmoji
        )
    ) : FamilyMembershipState() {
        val memberId: String get() = member.id
    }

    /**
     * Transient or offline error while loading membership from Firestore.
     * Never treated as [NoMembership] so existing families and profiles are never overwritten.
     */
    data class RecoverableError(
        val messageHe: String,
        val messageEn: String = messageHe,
        val errorCode: String = "UNAVAILABLE",
        val stage: String = "",
        val databaseId: String = ""
    ) : FamilyMembershipState()
}

/**
 * Status of a role-specific family invitation in Firestore.
 */
enum class InvitationStatus {
    PENDING,
    REDEEMED,
    EXPIRED,
    REVOKED;

    companion object {
        fun fromFirestoreValue(raw: String?): InvitationStatus {
            return when (raw?.trim()?.uppercase()) {
                "REDEEMED", "ACCEPTED", "USED" -> REDEEMED
                "EXPIRED" -> EXPIRED
                "REVOKED" -> REVOKED
                else -> PENDING
            }
        }
    }
}

/**
 * Role-specific invitation stored in Firestore (`/family_invites/{inviteCode}`).
 * The [invitedRole] is immutable after creation so the recipient cannot choose or elevate their role.
 */
data class FamilyInvitation(
    val code: String = "",
    val familyId: String = "",
    val familyName: String = "",
    val invitedRole: UserRole = UserRole.CHILD,
    val invitingParentUid: String = "",
    val invitingParentName: String = "",
    val invitedNickname: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_EXPIRATION_MS,
    val status: InvitationStatus = InvitationStatus.PENDING,
    val redeemedByUid: String? = null,
    val redeemedByName: String? = null,
    val redeemedAt: Long? = null
) {
    val inviteCode: String get() = code
    val inviterUid: String get() = invitingParentUid
    val inviterName: String get() = invitingParentName
    val inviteeNameHint: String get() = invitedNickname
    val createdAtMillis: Long get() = createdAt
    val expiresAtMillis: Long get() = expiresAt
    val redeemedAtMillis: Long? get() = redeemedAt

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis > expiresAt || status == InvitationStatus.EXPIRED

    fun isRedeemable(nowMillis: Long = System.currentTimeMillis()): Boolean =
        status == InvitationStatus.PENDING && !isExpired(nowMillis) && redeemedByUid.isNullOrBlank()

    companion object {
        const val DEFAULT_EXPIRATION_MS: Long = 7L * 24 * 60 * 60 * 1000L
    }
}

/**
 * Result of validating an invitation code entered by a user before confirming joining.
 */
sealed class InvitationValidationResult {
    data class Valid(
        val invitation: FamilyInvitation
    ) : InvitationValidationResult() {
        val familyId: String get() = invitation.familyId
        val familyName: String get() = invitation.familyName
        val invitedRole: UserRole get() = invitation.invitedRole
        val inviterName: String get() = invitation.invitingParentName
    }

    data class Invalid(
        val errorCode: String = "INVALID_INVITE",
        val messageHe: String,
        val messageEn: String = messageHe
    ) : InvitationValidationResult() {
        val messageHebrew: String get() = messageHe
        val messageEnglish: String get() = messageEn
    }
}

data class FamilyMember(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val nameEn: String = name,
    val email: String = "",
    val googleEmail: String? = email,
    val schoolGrade: String? = "",
    val role: UserRole = UserRole.CHILD,
    val familyIdentity: FamilyIdentity = FamilyIdentity.fromFirestoreValue(
        raw = null,
        role = role,
        avatarEmoji = null,
        nameHint = name,
        memberId = id
    ),
    val avatarEmoji: String = familyIdentity.defaultEmoji,
    val avatarId: String = "",
    val photoUrl: String? = null,
    val colorHex: Long = 0xFF1A73E8,
    val earnedPoints: Int = 0,
    val spentPoints: Int = 0,
    val completedTasksCount: Int = 0,
    val streakDays: Int = 1,
    val isOnline: Boolean = true,
    val invitationAccepted: Boolean = true,
    val invitationPending: Boolean = false,
    val inviteToken: String? = "",
    val invitedByMemberId: String? = "",
    val targetRewardId: String? = null
) {
    val isAdmin: Boolean get() = role == UserRole.PARENT
    val isParent: Boolean get() = role == UserRole.PARENT
    val isChild: Boolean get() = role == UserRole.CHILD
    val isFather: Boolean get() = role == UserRole.PARENT && familyIdentity == FamilyIdentity.FATHER
    val isMother: Boolean get() = role == UserRole.PARENT && familyIdentity == FamilyIdentity.MOTHER

    /** Resolved built-in catalog avatar if [avatarId] matches a known catalog entry. */
    val resolvedAvatarOption: FamilyAvatarOption?
        get() = FamilyAvatarCatalog.findById(avatarId)

    /** Safe display emoji that prefers [resolvedAvatarOption] and falls back to [avatarEmoji] or role default. */
    val displayAvatarEmoji: String
        get() = resolvedAvatarOption?.emoji
            ?: avatarEmoji.takeIf { it.isNotBlank() }
            ?: familyIdentity.defaultEmoji

    /** Effective accent color hex that prefers [resolvedAvatarOption] when an avatarId is selected. */
    val effectiveColorHex: Long
        get() = resolvedAvatarOption?.primaryColorHex ?: colorHex

    /** Available points balance after subtracting redeemed rewards. */
    val availablePoints: Int get() = (earnedPoints - spentPoints).coerceAtLeast(0)

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) name else nameEn.ifBlank { name }

    fun getRoleWithIdentityLabel(isHebrew: Boolean): String {
        val emoji = displayAvatarEmoji
        return if (role == UserRole.PARENT) {
            if (familyIdentity == FamilyIdentity.FATHER) {
                if (isHebrew) "$emoji אבא — הורה (מנהל משפחה)" else "$emoji Father — Parent (Admin)"
            } else {
                if (isHebrew) "$emoji אמא — הורה (מנהל משפחה)" else "$emoji Mother — Parent (Admin)"
            }
        } else {
            if (isHebrew) "$emoji ילד/ה — חבר משפחה" else "$emoji Child — Family Member"
        }
    }
}

enum class TaskPriority(val labelHe: String, val labelEn: String, val defaultPoints: Int, val colorHex: Long) {
    HIGH("דחוף", "High", 25, 0xFFD93025),
    MEDIUM("רגיל", "Medium", 15, 0xFF1A73E8),
    LOW("נמוך", "Low", 10, 0xFF1E8E3E);

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
}

enum class TaskRecurrence(val labelHe: String, val labelEn: String) {
    ONCE("חד-פעמי", "One-time"),
    DAILY("יומי", "Daily"),
    WEEKLY("שבועי", "Weekly"),
    BIWEEKLY("דו-שבועי", "Bi-weekly"),
    MONTHLY("חודשי", "Monthly"),
    SCHOOL_DAYS("ימי לימודים (א׳-ו׳)", "School Days");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    val isRecurring: Boolean
        get() = this != ONCE

    /**
     * Computes the next scheduled epoch day for this recurrence rule after [currentEpochDay].
     * Returns `null` for [ONCE].
     */
    fun nextOccurrenceEpochDay(currentEpochDay: Long): Long? {
        val currentDate = LocalDate.ofEpochDay(currentEpochDay)
        val nextDate = when (this) {
            ONCE -> null
            DAILY -> currentDate.plusDays(1)
            WEEKLY -> currentDate.plusWeeks(1)
            BIWEEKLY -> currentDate.plusWeeks(2)
            MONTHLY -> currentDate.plusMonths(1)
            SCHOOL_DAYS -> {
                var candidate = currentDate.plusDays(1)
                while (candidate.dayOfWeek == java.time.DayOfWeek.SATURDAY) {
                    candidate = candidate.plusDays(1)
                }
                candidate
            }
        }
        return nextDate?.toEpochDay()
    }
}

enum class TaskCategory(val labelHe: String, val labelEn: String, val emoji: String) {
    BEDROOM("סידור חדר", "Bedroom", "🛏️"),
    KITCHEN("מטבח ושולחן", "Kitchen", "🍽️"),
    DISHES("שטיפת כלים ופינוי מדיח", "Dishes", "🫧"),
    PETS("טיפול בחיות מחמד", "Pets", "🐶"),
    HOMEWORK("שיעורי בית ולימודים", "Homework", "📚"),
    LAUNDRY("כביסה וקיפול", "Laundry", "👕"),
    TRASH("הוצאת אשפה", "Take Out Trash", "🗑️"),
    TABLE("עריכת שולחן", "Set Table", "🥗"),
    CLEANING("ניקיון הבית", "House Cleaning", "🧹"),
    GROCERIES("קניות ומצרכים", "Groceries", "🛒"),
    OTHER("אחר", "Other", "✨");

    val defaultEmoji: String get() = emoji

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
}

/**
 * Reward catalog item redeemable with earned task points.
 */
data class RewardItem(
    val id: String = UUID.randomUUID().toString(),
    val familyId: String = "",
    val title: String = "",
    val pointsCost: Int = 50,
    val iconEmoji: String = "🎁",
    val description: String = "",
    val isActive: Boolean = true,
    val isDeleted: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Explicit UI & cloud persistence state for task completion and parent approval (V1.6).
 */
enum class TaskCompletionSyncState {
    /** Task is incomplete and idle. */
    IDLE,
    /** Task completion or approval write is in flight to Firestore. */
    SAVING,
    /** Child completed the task and the write is persisted in Firestore, awaiting parent approval. */
    PENDING_APPROVAL,
    /** Task completion is persisted in Firestore and approved by a parent. */
    SAVED_APPROVED,
    /** Last completion/approval write failed; previous state was restored. */
    FAILED
}

enum class TaskReminderOffset(
    val labelHe: String,
    val labelEn: String,
    val offsetMinutes: Long?
) {
    NONE("ללא תזכורת", "No reminder", null),
    AT_TIME("בזמן שנקבע", "At due time", 0L),
    MINUTES_10_BEFORE("10 דקות לפני", "10 min before", 10L),
    MINUTES_30_BEFORE("30 דקות לפני", "30 min before", 30L),
    HOUR_1_BEFORE("שעה לפני", "1 hour before", 60L);

    val isEnabled: Boolean
        get() = this != NONE

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    fun getShortLabel(isHebrew: Boolean): String = getDisplayName(isHebrew)

    companion object {
        fun fromRaw(raw: String?): TaskReminderOffset {
            if (raw.isNullOrBlank()) return NONE
            return entries.find { it.name.equals(raw.trim(), ignoreCase = true) } ?: NONE
        }
    }
}

data class FamilyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val category: TaskCategory = TaskCategory.OTHER,
    val assignedToMemberId: String,
    val dateEpochDay: Long,
    val dueTimeFormatted: String = "16:00",
    val hasDueTime: Boolean = false,
    val reminderOffset: TaskReminderOffset = TaskReminderOffset.NONE,
    val isCompleted: Boolean = false,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val recurrence: TaskRecurrence = TaskRecurrence.ONCE,
    val points: Int = 10,
    val reminderEnabled: Boolean = true,
    val syncedToGoogleCalendar: Boolean = true,
    val completedAtMillis: Long? = null,
    val completedByMemberId: String? = null,
    val familyId: String = "",
    val approvedByParent: Boolean = false,
    val originalAssignedToMemberId: String? = null,
    val originalAssignedMemberName: String? = null,
    val transferProposalId: String? = null,
    val pendingProposalId: String? = null,
    val isOpenForVolunteering: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    /**
     * True when this task is an open volunteering task that has not yet been assigned to a child
     * and is not completed or approved.
     */
    val isUnassignedOpenTask: Boolean
        get() = isOpenForVolunteering && assignedToMemberId.isBlank() && !isCompleted && !approvedByParent

    /**
     * True when the task has been marked completed by a child and is awaiting parent approval.
     */
    val isPendingParentApproval: Boolean
        get() = isCompleted && !approvedByParent

    /**
     * True when the task is both completed and approved by a parent.
     */
    val isFullyApprovedCompletion: Boolean
        get() = isCompleted && approvedByParent

    /**
     * True when this task was originally assigned to another child and reassigned via an approved
     * volunteer offer or child task swap, enabling flexible parent point splitting upon completion.
     */
    val wasTransferredOrSwapped: Boolean
        get() = !originalAssignedToMemberId.isNullOrBlank() && originalAssignedToMemberId != assignedToMemberId

    /**
     * True when this task is currently locked in a pending volunteer or swap proposal.
     */
    val hasActiveProposal: Boolean
        get() = !pendingProposalId.isNullOrBlank()

    /**
     * True when this task is active (not completed, not approved) and not currently in another active proposal.
     */
    fun isEligibleForChildProposal(): Boolean =
        !isCompleted && !approvedByParent && !isOpenForVolunteering && assignedToMemberId.isNotBlank() && pendingProposalId.isNullOrBlank()

    /**
     * True when this task has an explicit execution time, a non-NONE reminder offset, and is not completed.
     */
    val hasActiveReminder: Boolean
        get() = hasDueTime && reminderOffset != TaskReminderOffset.NONE && !isCompleted

    /**
     * Versioned schedule signature used to distinguish an already-fired reminder
     * from a newly updated time/date/offset for the same [id].
     */
    fun reminderScheduleSignature(): String {
        val normalizedTime = if (hasDueTime) dueTimeFormatted.trim() else ""
        return "$id|$dateEpochDay|$normalizedTime|${reminderOffset.name}"
    }

    /**
     * Computes the exact epoch milliseconds of the task's due date + due time in [zoneId],
     * or returns `null` if [hasDueTime] is false or [dueTimeFormatted] cannot be parsed as `HH:mm`.
     */
    fun computeDueEpochMillis(zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        if (!hasDueTime) return null
        val parts = dueTimeFormatted.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val date = LocalDate.ofEpochDay(dateEpochDay)
        val dateTime = date.atTime(hour, minute)
        return dateTime.atZone(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Computes the epoch milliseconds when the task reminder should trigger in [zoneId],
     * or returns `null` if [hasDueTime] is false, [reminderOffset] is [TaskReminderOffset.NONE],
     * or the task is already completed.
     */
    fun computeReminderTriggerEpochMillis(zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        if (!hasDueTime || isCompleted) return null
        val offsetMins = reminderOffset.offsetMinutes ?: return null
        val dueMillis = computeDueEpochMillis(zoneId) ?: return null
        return dueMillis - (offsetMins * 60_000L)
    }

    /**
     * Converts UI FamilyTask to Room/Firestore core Task entity.
     */
    fun toCoreTask(familyId: String = this.familyId.ifBlank { "default_family" }): Task {
        return Task.fromFamilyTask(
            familyTask = this.copy(familyId = familyId),
            assignedMemberName = assignedToMemberId,
            userId = assignedToMemberId
        )
    }
}

enum class SchoolDay(
    val index: Int,
    val nameHe: String,
    val shortHe: String,
    val nameEn: String,
    val shortEn: String
) {
    SUNDAY(0, "יום ראשון", "א'", "Sunday", "Sun"),
    MONDAY(1, "יום שני", "ב'", "Monday", "Mon"),
    TUESDAY(2, "יום שלישי", "ג'", "Tuesday", "Tue"),
    WEDNESDAY(3, "יום רביעי", "ד'", "Wednesday", "Wed"),
    THURSDAY(4, "יום חמישי", "ה'", "Thursday", "Thu"),
    FRIDAY(5, "יום שישי", "ו'", "Friday", "Fri"),
    SATURDAY(6, "יום שבת", "ש'", "Saturday", "Sat");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) nameHe else nameEn
    fun getShortName(isHebrew: Boolean): String = if (isHebrew) shortHe else shortEn

    companion object {
        val schoolDays: List<SchoolDay> = listOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        val allWeekDays: List<SchoolDay> = listOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY)

        fun fromJavaDayOfWeek(dayOfWeek: java.time.DayOfWeek): SchoolDay? {
            return when (dayOfWeek) {
                java.time.DayOfWeek.SUNDAY -> SUNDAY
                java.time.DayOfWeek.MONDAY -> MONDAY
                java.time.DayOfWeek.TUESDAY -> TUESDAY
                java.time.DayOfWeek.WEDNESDAY -> WEDNESDAY
                java.time.DayOfWeek.THURSDAY -> THURSDAY
                java.time.DayOfWeek.FRIDAY -> FRIDAY
                java.time.DayOfWeek.SATURDAY -> null
            }
        }
    }
}

data class SchedulePeriod(
    val id: String = UUID.randomUUID().toString(),
    val childId: String,
    val day: SchoolDay,
    val periodIndex: Int, // 1..7
    val subjectName: String,
    val roomNumber: String,
    val teacherName: String = "",
    val startTime: String,
    val endTime: String,
    val colorHex: Long = 0xFF1A73E8,
    val tenMinuteAlertEnabled: Boolean = true,
    val isAlertEnabled: Boolean = tenMinuteAlertEnabled,
    val subjectNameEn: String = subjectName,
    val iconEmoji: String = "📚",
    val reminderMinutesBefore: Int = 10,
    val equipmentNotes: String = ""
)

enum class FileSortOption(val labelHe: String, val labelEn: String) {
    DATE("תאריך", "Date"),
    NAME("שם", "Name"),
    SIZE("גודל", "Size"),
    TYPE("סוג", "Type");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
}

enum class FileUploadStatus {
    UPLOADING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class FileUploadTaskState(
    val uploadId: String = UUID.randomUUID().toString(),
    val fileName: String,
    val folderId: String,
    val extension: String,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val progress: Float = 0f,
    val status: FileUploadStatus = FileUploadStatus.UPLOADING,
    val errorMessageHe: String? = null,
    val errorMessageEn: String? = null,
    val mimeType: String = "application/octet-stream",
    val rawBytes: ByteArray? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun getErrorMessage(isHebrew: Boolean): String? =
        if (isHebrew) errorMessageHe else (errorMessageEn ?: errorMessageHe)
}

const val GOOGLE_DRIVE_ROOT_FOLDER_NAME = "FamilyTasks"

enum class DriveConnectionStatus {
    NOT_SETUP,
    WAITING_FOR_PARENT_SETUP,
    NEEDS_OAUTH_AUTHORIZATION,
    NEEDS_SHARED_FOLDER_PICKER,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Represents a request for the user to explicitly authorize access to the family's single
 * shared `FamilyTasks` folder (or a shared item inside it) via the Google Picker API
 * under the least-privilege `drive.file` scope.
 */
data class DrivePickerAuthorizationRequest(
    val familyId: String,
    val expectedDriveRootFolderId: String,
    val expectedItemDriveId: String = expectedDriveRootFolderId,
    val expectedItemName: String = GOOGLE_DRIVE_ROOT_FOLDER_NAME,
    val isRootFolderSelection: Boolean = true,
    val ownerEmail: String = "",
    val reasonMessageHe: String = "כדי לגשת לתיקיית המשפחה המשותפת (FamilyTasks), יש לבחור ולאשר את התיקייה המשותפת בבורר הקבצים של Google Drive.",
    val reasonMessageEn: String = "To access the shared FamilyTasks folder, please select and authorize the shared folder in Google Picker."
)

enum class DriveMemberSharingStatus(val labelHe: String, val labelEn: String) {
    OWNER("בעלים (Owner)", "Owner"),
    EDITOR_SHARED("עורך משותף (Editor)", "Shared Editor"),
    SHARED_EDITOR("עורך משותף (Editor)", "Shared Editor"),
    INVITED_PENDING("שותף - ממתין לחיבור Drive", "Shared - Pending Drive Connect"),
    PENDING_SHARE("טרם שותף", "Not Shared Yet"),
    NOT_SHARED("טרם שותף", "Not Shared Yet"),
    ERROR("שגיאת שיתוף", "Share Error");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
    fun getLabel(isHebrew: Boolean): String = getDisplayName(isHebrew)
}

data class FamilyDriveConfig(
    val familyId: String = "",
    val driveRootFolderId: String = "",
    val driveRootFolderName: String = GOOGLE_DRIVE_ROOT_FOLDER_NAME,
    val ownerUid: String = "",
    val ownerEmail: String = "",
    val ownerName: String = "",
    val isConnected: Boolean = false,
    val webViewLink: String = "",
    val sharedEmails: List<String> = emptyList(),
    val sharedWithEmails: List<String> = sharedEmails,
    val memberStatuses: Map<String, String> = emptyMap(),
    val memberShareStatuses: Map<String, DriveMemberSharingStatus> = emptyMap(),
    val connectionStatus: DriveConnectionStatus = if (isConnected || driveRootFolderId.isNotBlank()) DriveConnectionStatus.CONNECTED else DriveConnectionStatus.NOT_SETUP,
    val errorMessageHe: String? = null,
    val errorMessageEn: String? = null,
    val connectedAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val lastSyncedAtMillis: Long = updatedAtMillis
) {
    val isConfigured: Boolean
        get() = isConnected || driveRootFolderId.isNotBlank()

    val rootFolderId: String
        get() = driveRootFolderId

    val rootFolderName: String
        get() = driveRootFolderName

    val sharedMemberEmails: List<String>
        get() = (sharedEmails + sharedWithEmails).distinct()

    val hasValidRootFolder: Boolean
        get() = driveRootFolderId.isNotBlank() && driveRootFolderName == GOOGLE_DRIVE_ROOT_FOLDER_NAME
}

data class DocumentFolder(
    val id: String = UUID.randomUUID().toString(),
    val nameHe: String,
    val nameEn: String,
    val iconEmoji: String,
    val colorHex: Long,
    val familyId: String = "",
    val parentFolderId: String? = null,
    val createdByUid: String = "",
    val createdByName: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val driveFolderId: String = id,
    val driveParentFolderId: String = parentFolderId ?: "",
    val webViewLink: String = ""
) {
    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) nameHe else nameEn.ifBlank { nameHe }
    fun getName(isHebrew: Boolean): String = getDisplayName(isHebrew)
}

data class FamilyDocument(
    val id: String = UUID.randomUUID().toString(),
    val folderId: String,
    val name: String,
    val extension: String, // PDF, JPG, PNG, DOCX, XLSX, PPTX, TXT, ZIP
    val sizeFormatted: String,
    val uploadedByMemberId: String = "",
    val uploadedDateFormatted: String = "Today",
    val isDriveSynced: Boolean = true,
    val localUriString: String? = null,
    val driveUrl: String = "",
    val contentPreviewHe: String = "",
    val contentPreviewEn: String = "",
    val uploadedByName: String = uploadedByMemberId,
    val uploadDateFormatted: String = uploadedDateFormatted,
    val driveFileUrl: String = driveUrl,
    val familyId: String = "",
    val uploadedByUid: String = uploadedByMemberId,
    val sizeBytes: Long = parseFormattedSizeToBytes(sizeFormatted),
    val mimeType: String = defaultMimeTypeForExtension(extension),
    val storagePath: String = "",
    val uploadedAtMillis: Long = System.currentTimeMillis(),
    val thumbnailBase64: String? = null,
    val chunkCount: Int = 0,
    val driveFileId: String = id,
    val driveParentFolderId: String = folderId,
    val webViewLink: String = driveUrl
) {
    val isImageFile: Boolean
        get() = extension.uppercase() in setOf("JPG", "JPEG", "PNG", "WEBP")

    val isPdfFile: Boolean
        get() = extension.uppercase() == "PDF"

    val isTextFile: Boolean
        get() = extension.uppercase() == "TXT"

    val isOfficeOrArchiveFile: Boolean
        get() = extension.uppercase() in setOf("DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX", "TXT", "ZIP")

    companion object {
        fun parseFormattedSizeToBytes(formatted: String): Long {
            val clean = formatted.trim().uppercase()
            val numberPart = clean.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: return 1024L
            return when {
                clean.contains("MB") -> (numberPart * 1024.0 * 1024.0).toLong().coerceAtLeast(1L)
                clean.contains("KB") -> (numberPart * 1024.0).toLong().coerceAtLeast(1L)
                clean.contains("B") -> numberPart.toLong().coerceAtLeast(1L)
                else -> (numberPart * 1024.0).toLong().coerceAtLeast(1L)
            }
        }

        fun defaultMimeTypeForExtension(ext: String): String {
            return when (ext.trim().uppercase()) {
                "PDF" -> "application/pdf"
                "JPG", "JPEG" -> "image/jpeg"
                "PNG" -> "image/png"
                "WEBP" -> "image/webp"
                "DOC" -> "application/msword"
                "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "XLS" -> "application/vnd.ms-excel"
                "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "PPT" -> "application/vnd.ms-powerpoint"
                "PPTX" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "TXT" -> "text/plain"
                "ZIP" -> "application/zip"
                else -> "application/octet-stream"
            }
        }
    }
}

enum class NotificationType {
    TASK_COMPLETED,
    TASK_ASSIGNED,
    NUDGE_REMINDER,
    SCHOOL_START,
    FILE_SHARED,
    MEMBER_INVITED
}

enum class AlertReason(val notificationType: NotificationType) {
    TASK_COMPLETED(NotificationType.TASK_COMPLETED),
    TASK_REMINDER(NotificationType.TASK_ASSIGNED),
    PARENT_NUDGE(NotificationType.NUDGE_REMINDER),
    UPCOMING_CLASS(NotificationType.SCHOOL_START),
    DRIVE_SYNC(NotificationType.FILE_SHARED),
    MEMBER_INVITED(NotificationType.MEMBER_INVITED)
}

data class FamilyNotificationAlert(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val targetMemberName: String = "",
    val targetMemberEmoji: String = "🔔",
    val reason: AlertReason = AlertReason.TASK_COMPLETED,
    val timestampFormatted: String = "",
    val isRead: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis(),
    val taskTitle: String? = null,
    val pointsGained: Int? = null,
    val taskId: String? = null,
    val eventKey: String? = null,
    val isOpenTaskAlert: Boolean = false
) {
    val type: NotificationType get() = reason.notificationType
    val completedByAvatar: String? get() = targetMemberEmoji
    val completedByMemberName: String? get() = targetMemberName.ifBlank { null }
}

typealias FamilyNotification = FamilyNotificationAlert
typealias AppNotification = FamilyNotificationAlert

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderName: String,
    val isFromAi: Boolean,
    val text: String,
    val timestamp: String
)

data class CloudFamilyGroup(
    val familyId: String = "FAM-WEXLER-2025",
    val familyName: String = "משפחת ישראלי",
    val inviteCode: String = "WEX-8842",
    val primaryAdminEmail: String = "yael.wexler@gmail.com",
    val ownerEmail: String = primaryAdminEmail,
    val ownerName: String = "אבא (מנהל ראשי)",
    val sharedCalendarId: String = "family_calendar_wexler@group.calendar.google.com",
    val sharedDriveFolderId: String = "drive_folder_wexler_shared_docs",
    val isRealTimeConnected: Boolean = true,
    val lastSyncedTimestamp: String = "היום, 14:30",
    val lastSyncedEpochMillis: Long = System.currentTimeMillis(),
    val syncedMembersCount: Int = 4
)

/**
 * Shared family wake-up / school / reminder alarm clock entry.
 * Supports personal alarms and parent-created alarms for children with real local Android
 * AlarmManager scheduling, device acknowledgement, custom audio, vibration, snooze, and volume.
 */
data class FamilyAlarm(
    val id: String = "alarm_${UUID.randomUUID().toString().take(8)}",
    val familyId: String = "",
    val title: String,
    val hour: Int,
    val minute: Int,
    val assignedMemberId: String = "",
    val assignedToMemberId: String = assignedMemberId,
    val assignedMemberName: String = "",
    val assignedMemberIds: List<String> = emptyList(),
    val assignedMemberNames: List<String> = emptyList(),
    val daysOfWeek: List<SchoolDay> = SchoolDay.allWeekDays,
    val isEnabled: Boolean = true,
    val ringtoneEmoji: String = "⏰",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val createdByMemberId: String = "",
    val createdByMemberName: String = "",
    val createdByRole: String = "", // "PARENT" or "CHILD"
    val isPersonal: Boolean = false,
    val soundType: String = "SYSTEM_ALARM", // SYSTEM_ALARM, SYSTEM_NOTIFICATION, SYSTEM_RINGTONE, MELODY_CLASSIC, MELODY_GENTLE, MELODY_ENERGETIC, CUSTOM_URI
    val customSoundUri: String = "",
    val customSoundTitle: String = "",
    val vibrateEnabled: Boolean = true,
    val snoozeDurationMinutes: Int = 5,
    val volumePercent: Int = 85,
    val scheduleStatus: String = STATUS_SCHEDULED_LOCALLY, // SCHEDULED_LOCALLY, PENDING_TARGET_DEVICE, EXACT_PERMISSION_NEEDED, SCHEDULE_ERROR, DISABLED
    val acknowledgedByMemberId: String = "",
    val acknowledgedByMemberIds: List<String> = emptyList(),
    val acknowledgedByDeviceName: String = "",
    val acknowledgedAtMillis: Long = 0L,
    val nextTriggerAtMillis: Long = 0L,
    val lastScheduleError: String = "",
    val isDeleted: Boolean = false,
    val deletedAtMillis: Long = 0L
) {
    companion object {
        const val STATUS_SCHEDULED_LOCALLY = "SCHEDULED_LOCALLY"
        const val STATUS_PENDING_TARGET_DEVICE = "PENDING_TARGET_DEVICE"
        const val STATUS_EXACT_PERMISSION_NEEDED = "EXACT_PERMISSION_NEEDED"
        const val STATUS_SCHEDULE_ERROR = "SCHEDULE_ERROR"
        const val STATUS_DISABLED = "DISABLED"
        const val STATUS_DELETED = "DELETED"
    }

    val effectiveAssignedMemberId: String
        get() = assignedToMemberId.ifBlank { assignedMemberId }.ifBlank { assignedMemberIds.firstOrNull().orEmpty() }

    val effectiveAssignedMemberIds: List<String>
        get() {
            val fromList = assignedMemberIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (fromList.isNotEmpty()) return fromList
            val single = assignedToMemberId.ifBlank { assignedMemberId }.trim()
            return if (single.isNotEmpty()) listOf(single) else emptyList()
        }

    val effectiveAssignedMemberNames: List<String>
        get() {
            val fromList = assignedMemberNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (fromList.isNotEmpty()) return fromList
            val single = assignedMemberName.trim()
            return if (single.isNotEmpty()) listOf(single) else emptyList()
        }

    val effectiveAcknowledgedMemberIds: List<String>
        get() = buildList {
            acknowledgedByMemberIds.forEach { id ->
                val trimmed = id.trim()
                if (trimmed.isNotEmpty() && trimmed !in this) add(trimmed)
            }
            val legacy = acknowledgedByMemberId.trim()
            if (legacy.isNotEmpty() && legacy !in this) add(legacy)
        }

    fun isAcknowledgedByMember(memberIdOrUid: String): Boolean {
        val clean = memberIdOrUid.trim()
        if (clean.isEmpty()) return false
        if (scheduleStatus == STATUS_PENDING_TARGET_DEVICE) return false
        return clean in effectiveAcknowledgedMemberIds
    }

    val timeFormatted: String
        get() = String.format(java.util.Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    val formattedTime24h: String
        get() = timeFormatted

    val formattedTime24: String
        get() = timeFormatted

    val isConfirmedOnTargetDevice: Boolean
        get() = isEnabled && (
            (scheduleStatus == STATUS_SCHEDULED_LOCALLY || scheduleStatus == STATUS_EXACT_PERMISSION_NEEDED) &&
                (isPersonal ||
                    effectiveAssignedMemberId.isBlank() ||
                    effectiveAssignedMemberIds.all { it == createdByMemberId } ||
                    effectiveAcknowledgedMemberIds.isNotEmpty())
        )

    fun isTargetedToAnotherMember(): Boolean {
        val targetIds = effectiveAssignedMemberIds
        if (targetIds.isEmpty()) return false
        if (isPersonal && targetIds.size == 1 && (createdByMemberId.isBlank() || targetIds.first() == createdByMemberId)) {
            return false
        }
        return targetIds.any { it.isNotBlank() && it != createdByMemberId }
    }

    fun isTargetDeviceAcknowledged(): Boolean {
        if (!isTargetedToAnotherMember()) return true
        val remoteTargetIds = effectiveAssignedMemberIds.filter { it.isNotBlank() && it != createdByMemberId }
        val ackIds = effectiveAcknowledgedMemberIds.filter { it != createdByMemberId }
        return isConfirmedOnTargetDevice &&
            scheduleStatus != STATUS_PENDING_TARGET_DEVICE &&
            ackIds.isNotEmpty() &&
            (remoteTargetIds.size <= 1 || remoteTargetIds.all { it in effectiveAcknowledgedMemberIds })
    }

    /**
     * Returns true if this alarm is a parent's personal alarm (created by a parent ONLY for themselves,
     * with no child recipients).
     * Parent personal alarms are strictly private to parents and must NEVER be visible to children.
     */
    fun isParentPersonalAlarm(members: List<FamilyMember> = emptyList()): Boolean {
        val targetIds = effectiveAssignedMemberIds
        val creatorIsParent = createdByRole.equals("PARENT", ignoreCase = true) ||
            (createdByMemberId.isNotBlank() && members.find { it.id == createdByMemberId }?.isParent == true)

        // If any recipient is a known child, it is NOT a parent-only personal alarm
        if (targetIds.any { id -> members.find { it.id == id }?.isChild == true }) {
            return false
        }
        if (targetIds.isNotEmpty() && targetIds.all { id -> members.find { it.id == id }?.isParent == true }) {
            return true
        }
        if (creatorIsParent) {
            if (isPersonal && (targetIds.isEmpty() || targetIds.all { it == createdByMemberId })) return true
            if (targetIds.isNotEmpty() && createdByMemberId.isNotBlank() && targetIds.all { it == createdByMemberId }) {
                return true
            }
        }
        return false
    }

    /**
     * Determines whether [currentMember] is allowed to see this alarm in the Alarm Clock UI:
     * - Parents can see their own personal alarms and all child/family alarms.
     * - Children can NEVER see a parent's personal alarm; children only see alarms assigned to
     *   them (or created by them for themselves, or family-wide non-personal alarms).
     */
    fun isVisibleToMember(
        currentMember: FamilyMember,
        authUid: String = "",
        members: List<FamilyMember> = emptyList()
    ): Boolean {
        if (isDeleted || scheduleStatus == STATUS_DELETED) return false
        if (currentMember.isParent) {
            return true
        }
        // Child view: NEVER show a parent's personal alarm
        if (isParentPersonalAlarm(members)) {
            return false
        }
        val candidateIds = buildSet {
            if (currentMember.id.isNotBlank()) add(currentMember.id)
            if (authUid.isNotBlank()) add(authUid)
        }
        val targetIds = effectiveAssignedMemberIds
        if (createdByMemberId.isNotBlank() && createdByMemberId in candidateIds) {
            return true
        }
        if (targetIds.any { it in candidateIds }) {
            return true
        }
        if (targetIds.isNotEmpty()) {
            val matchedAnyByName = targetIds.any { tId ->
                val matchedTargetMember = members.find { it.id == tId }
                matchedTargetMember != null &&
                    currentMember.name.isNotBlank() &&
                    matchedTargetMember.name.trim().equals(currentMember.name.trim(), ignoreCase = true)
            }
            if (matchedAnyByName) return true
            // If all targetIds belong to other known members, do not show to this child
            val allKnownOtherMembers = targetIds.all { tId ->
                val m = members.find { it.id == tId }
                m != null && m.id !in candidateIds
            }
            if (allKnownOtherMembers) return false
        }
        if (effectiveAssignedMemberNames.any { n ->
                currentMember.name.isNotBlank() && n.trim().equals(currentMember.name.trim(), ignoreCase = true)
            }
        ) {
            return true
        }
        if (targetIds.isEmpty() && !isPersonal) {
            return true
        }
        return isTargetedAtDeviceMember(
            memberId = currentMember.id,
            authUid = authUid,
            currentMemberName = currentMember.name,
            isChildDevice = true
        )
    }

    /**
     * Checks whether [member] (or [authUid]) has permission to edit, enable/disable, or delete this alarm:
     * - PARENT can manage all alarms in the family (own, parent-created child alarms, and child-created alarms).
     * - CHILD can ONLY edit/enable/disable/delete alarms that the child created for themselves.
     * - Legacy alarms with blank/unknown `createdByMemberId` can only be modified by a PARENT (never guessed for a child).
     */
    fun canMemberModify(member: FamilyMember, authUid: String = ""): Boolean {
        if (member.isParent) return true
        if (createdByMemberId.isBlank()) return false
        if (createdByRole.equals("PARENT", ignoreCase = true)) return false

        val candidateIds = buildSet {
            if (member.id.isNotBlank()) add(member.id)
            if (authUid.isNotBlank()) add(authUid)
        }
        if (candidateIds.isEmpty()) return false

        val isCreatedByThisChild = createdByMemberId in candidateIds
        val targetIds = effectiveAssignedMemberIds
        val isAssignedToThisChild = targetIds.isEmpty() ||
            targetIds.any { it in candidateIds } ||
            effectiveAssignedMemberNames.any { it.trim().equals(member.name.trim(), ignoreCase = true) }
        return isCreatedByThisChild && isAssignedToThisChild
    }

    fun canMemberDelete(member: FamilyMember, authUid: String = ""): Boolean =
        canMemberModify(member, authUid)

    /**
     * Determines if this alarm should ring on the device belonging to [memberId] / [authUid]:
     * - Parent-created alarm for child -> rings on that child's device.
     * - Multi-recipient alarm -> rings on every device whose member is in [effectiveAssignedMemberIds].
     * - Child-created alarm for self -> rings ONLY on that child's device.
     * - Parent-created alarm for self -> rings ONLY on that parent's device.
     */
    fun isTargetedAtDeviceMember(
        memberId: String,
        authUid: String = "",
        currentMemberName: String = "",
        isChildDevice: Boolean = false
    ): Boolean {
        val candidateIds = buildSet {
            if (memberId.isNotBlank()) add(memberId)
            if (authUid.isNotBlank()) add(authUid)
        }
        if (candidateIds.isEmpty()) return false
        val targetIds = effectiveAssignedMemberIds
        val targetId = effectiveAssignedMemberId

        if (isChildDevice && isParentPersonalAlarm()) {
            return false
        }

        // 1. Direct ID or Auth UID match on any target in effectiveAssignedMemberIds
        if (targetIds.any { it.isNotBlank() && it in candidateIds }) {
            return true
        }

        // 2. Personal alarm created by this device's user for themselves
        if (createdByMemberId.isNotBlank() && createdByMemberId in candidateIds) {
            if (isPersonal || targetIds.isEmpty() || targetId == createdByMemberId) {
                return true
            }
        }

        // 3. Child device matching a parent-created alarm assigned to this child
        //    (handles cases where parent selected a child profile before UID migration or matched by name)
        if (isChildDevice) {
            val isParentPersonal = createdByRole.equals("PARENT", ignoreCase = true) &&
                (isPersonal || (targetId.isNotBlank() && targetIds.size == 1 && targetId == createdByMemberId))
            if (!isParentPersonal) {
                val targetNames = effectiveAssignedMemberNames
                val matchesByName = currentMemberName.isNotBlank() &&
                    targetNames.any { it.trim().equals(currentMemberName.trim(), ignoreCase = true) }
                // Only use name fallback if targetIds is empty or contains pre-migration local IDs (`m_kid*`)
                if (matchesByName && (targetIds.isEmpty() || targetIds.any { it.startsWith("m_kid") })) {
                    return true
                }
                // Pre-UID-migration fallback: only when device member ID is a migrated UID (not a different `m_kid*` profile)
                // and target name is either unspecified or matches this child
                val deviceUsesLocalPlaceholder = candidateIds.any { it.startsWith("m_kid") }
                if (
                    !deviceUsesLocalPlaceholder &&
                    targetIds.size == 1 &&
                    targetId.startsWith("m_kid") &&
                    createdByRole.equals("PARENT", ignoreCase = true) &&
                    (currentMemberName.isBlank() || targetNames.isEmpty() || matchesByName)
                ) {
                    return true
                }
            }
        }

        return false
    }
}

/**
 * Tracks a recently deleted alarm so the UI can distinguish "Deleted in cloud (awaiting target device)"
 * from "Confirmed cancelled on target device" when a parent deletes a child-assigned alarm.
 */
data class DeletedAlarmSyncNotice(
    val alarmId: String,
    val title: String,
    val formattedTime: String,
    val targetMemberId: String,
    val targetMemberName: String,
    val isRemoteTarget: Boolean,
    val isDeletedInCloud: Boolean = true,
    val isConfirmedCancelledOnTarget: Boolean = false,
    val deletedAtMillis: Long = System.currentTimeMillis(),
    val confirmedAtMillis: Long = 0L
)

/**
 * Type of family points ledger operation recorded in `/families/{familyId}/points_history/{operationId}`.
 */
enum class PointsActionType(
    val firestoreValue: String,
    val labelHe: String,
    val labelEn: String
) {
    ADD("ADD", "הוספה", "Addition"),
    DEDUCT("DEDUCT", "הפחתה", "Deduction"),
    RESET("RESET", "איפוס", "Reset"),
    TASK_EARNED("TASK_EARNED", "צבירה ממשימה", "Task Earned"),
    TASK_SPLIT_EARNED("TASK_SPLIT_EARNED", "חלוקת נקודות ממשימה", "Split Task Points"),
    TRANSFER_OUT("TRANSFER_OUT", "העברה לבן משפחה", "Transfer Out"),
    TRANSFER_IN("TRANSFER_IN", "קבלה מבן משפחה", "Transfer In"),
    HELPING_BONUS("HELPING_BONUS", "בונוס על עזרה", "Helping Bonus"),
    REWARD_REDEEMED("REWARD_REDEEMED", "מימוש פרס", "Reward Redeemed");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    companion object {
        fun fromFirestoreValue(raw: String?): PointsActionType {
            return entries.find { it.firestoreValue.equals(raw?.trim(), ignoreCase = true) }
                ?: ADD
        }
    }
}

/**
 * Immutable audit record for a family member's points balance modification.
 * Stored in `/families/{familyId}/points_history/{operationId}` and persisted locally.
 */
data class PointsHistoryEntry(
    val id: String = "pts_${UUID.randomUUID().toString().replace("-", "").take(12)}",
    val operationId: String = id,
    val familyId: String = "",
    val memberId: String,
    val memberName: String = "",
    val actionType: PointsActionType,
    val pointsDelta: Int,
    val previousBalance: Int,
    val newBalance: Int,
    val reason: String,
    val performedByUid: String,
    val performedByName: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val taskId: String? = null,
    val performedTaskByMemberId: String? = null,
    val performedTaskByMemberName: String? = null,
    val relatedMemberId: String? = null,
    val relatedMemberName: String? = null,
    val rewardId: String? = null,
    val redemptionId: String? = null,
    val proposalId: String? = null
)

/**
 * Type of child-initiated task collaboration request stored in `/families/{familyId}/task_proposals/{proposalId}`.
 */
enum class TaskProposalType(
    val firestoreValue: String,
    val labelHe: String,
    val labelEn: String
) {
    VOLUNTEER_FOR_TASK("VOLUNTEER_FOR_TASK", "הצעה לביצוע משימה", "Volunteer for Task"),
    SWAP_TASKS("SWAP_TASKS", "החלפת משימות", "Task Swap");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    companion object {
        fun fromFirestoreValue(raw: String?): TaskProposalType {
            return entries.find { it.firestoreValue.equals(raw?.trim(), ignoreCase = true) }
                ?: VOLUNTEER_FOR_TASK
        }
    }
}

/**
 * Two-stage approval workflow status for child task volunteering and task swaps:
 * 1. Child requester proposes -> [PENDING_CHILD_APPROVAL]
 * 2. Target child approves -> [PENDING_PARENT_APPROVAL] (or rejects -> [REJECTED_BY_CHILD])
 * 3. Parent approves -> [APPROVED_BY_PARENT] (or rejects -> [REJECTED_BY_PARENT])
 */
enum class TaskProposalStatus(
    val firestoreValue: String,
    val labelHe: String,
    val labelEn: String
) {
    PENDING_CHILD_APPROVAL("PENDING_CHILD_APPROVAL", "ממתין לאישור הילד/ה", "Pending Child Approval"),
    REJECTED_BY_CHILD("REJECTED_BY_CHILD", "נדחה ע\"י הילד/ה", "Rejected by Child"),
    PENDING_PARENT_APPROVAL("PENDING_PARENT_APPROVAL", "ממתין לאישור הורה", "Pending Parent Approval"),
    APPROVED_BY_PARENT("APPROVED_BY_PARENT", "אושר ע\"י הורה", "Approved by Parent"),
    REJECTED_BY_PARENT("REJECTED_BY_PARENT", "נדחה ע\"י הורה", "Rejected by Parent"),
    CLOSED_NOT_SELECTED("CLOSED_NOT_SELECTED", "לא נבחר/ה (נבחר מתנדב אחר)", "Not Selected"),
    CANCELLED("CANCELLED", "בוטל", "Cancelled");

    val isPending: Boolean
        get() = this == PENDING_CHILD_APPROVAL || this == PENDING_PARENT_APPROVAL

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    companion object {
        fun fromFirestoreValue(raw: String?): TaskProposalStatus {
            return entries.find { it.firestoreValue.equals(raw?.trim(), ignoreCase = true) }
                ?: PENDING_CHILD_APPROVAL
        }
    }
}

/**
 * Represents a child's offer to perform another child's task ([TaskProposalType.VOLUNTEER_FOR_TASK]),
 * a proposal to swap tasks between two children ([TaskProposalType.SWAP_TASKS]),
 * or a child volunteering for an unassigned open task ([isOpenTaskVolunteer] = true).
 * Stored in `/families/{familyId}/task_proposals/{proposalId}`.
 */
data class TaskCollaborationProposal(
    val id: String = "prop_${UUID.randomUUID().toString().replace("-", "").take(12)}",
    val operationId: String = id,
    val familyId: String = "",
    val type: TaskProposalType = TaskProposalType.VOLUNTEER_FOR_TASK,
    val requesterChildId: String,
    val requesterChildName: String = "",
    val targetChildId: String = "",
    val targetChildName: String = "",
    val primaryTaskId: String,
    val primaryTaskTitle: String = "",
    val primaryTaskPoints: Int = 0,
    val offeredSwapTaskId: String? = null,
    val offeredSwapTaskTitle: String? = null,
    val offeredSwapTaskPoints: Int? = null,
    val isOpenTaskVolunteer: Boolean = false,
    val status: TaskProposalStatus = TaskProposalStatus.PENDING_CHILD_APPROVAL,
    val note: String = "",
    val childRespondedAtMillis: Long? = null,
    val parentDecidedByUid: String? = null,
    val parentDecidedByName: String? = null,
    val parentDecidedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val proposerMemberId: String
        get() = requesterChildId

    val targetOwnerMemberId: String
        get() = targetChildId

    /**
     * Role-based visibility:
     * - Parents see all proposals in the family.
     * - Children see proposals where they are the requester or the target child.
     */
    fun isVisibleToMember(member: FamilyMember, authUid: String = ""): Boolean {
        if (member.isParent) return true
        val candidateIds = buildSet {
            if (member.id.isNotBlank()) add(member.id)
            if (authUid.isNotBlank()) add(authUid)
        }
        return requesterChildId in candidateIds || targetChildId in candidateIds
    }

    /**
     * True when this proposal requires an immediate decision from [member]:
     * - For a target CHILD: when status is [TaskProposalStatus.PENDING_CHILD_APPROVAL].
     * - For a PARENT: when status is [TaskProposalStatus.PENDING_PARENT_APPROVAL].
     */
    fun isAwaitingActionFromMember(member: FamilyMember, authUid: String = ""): Boolean {
        if (member.isParent) {
            return status == TaskProposalStatus.PENDING_PARENT_APPROVAL
        }
        val candidateIds = buildSet {
            if (member.id.isNotBlank()) add(member.id)
            if (authUid.isNotBlank()) add(authUid)
        }
        return status == TaskProposalStatus.PENDING_CHILD_APPROVAL && targetChildId in candidateIds
    }
}

/**
 * Status of a child's reward redemption request stored in `/families/{familyId}/redemptions/{redemptionId}`.
 * Points are deducted ONLY when a parent transitions the request to [APPROVED].
 */
enum class RedemptionStatus(
    val firestoreValue: String,
    val labelHe: String,
    val labelEn: String
) {
    PENDING_PARENT_APPROVAL("PENDING_PARENT_APPROVAL", "ממתין לאישור הורה", "Pending Parent Approval"),
    APPROVED("APPROVED", "אושר ומומש", "Approved"),
    REJECTED("REJECTED", "נדחה", "Rejected"),
    CANCELLED("CANCELLED", "בוטל", "Cancelled");

    val isPending: Boolean
        get() = this == PENDING_PARENT_APPROVAL

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn

    companion object {
        fun fromFirestoreValue(raw: String?): RedemptionStatus {
            return entries.find { it.firestoreValue.equals(raw?.trim(), ignoreCase = true) }
                ?: PENDING_PARENT_APPROVAL
        }
    }
}

/**
 * Immutable/audited reward redemption request and history record.
 * Captures a snapshot of [rewardTitle] and [pointsCost] at request time so later catalog edits or deletions
 * never corrupt historical redemptions.
 * Stored in `/families/{familyId}/redemptions/{redemptionId}`.
 */
data class RewardRedemptionRequest(
    val id: String = "red_${UUID.randomUUID().toString().replace("-", "").take(12)}",
    val operationId: String = id,
    val familyId: String = "",
    val rewardId: String,
    val rewardTitle: String,
    val rewardIconEmoji: String = "🎁",
    val pointsCost: Int,
    val childUid: String,
    val childName: String = "",
    val status: RedemptionStatus = RedemptionStatus.PENDING_PARENT_APPROVAL,
    val requestedAtMillis: Long = System.currentTimeMillis(),
    val decidedAtMillis: Long? = null,
    val decidedByParentUid: String? = null,
    val decidedByParentName: String? = null,
    val rejectionReason: String? = null
) {
    val requestedByMemberId: String
        get() = childUid

    val requestedByMemberName: String
        get() = childName

    fun isVisibleToMember(member: FamilyMember, authUid: String = ""): Boolean {
        if (member.isParent) return true
        val candidateIds = buildSet {
            if (member.id.isNotBlank()) add(member.id)
            if (authUid.isNotBlank()) add(authUid)
        }
        return childUid in candidateIds
    }

    fun isAwaitingActionFromMember(member: FamilyMember): Boolean {
        return member.isParent && status == RedemptionStatus.PENDING_PARENT_APPROVAL
    }
}

/**
 * Pure, deterministic progress calculation model for a child's personal reward savings goal.
 * Ensures progress percentage and remaining points are never negative, clamps progress bar
 * between 0% and 100% even when the child's points exceed the reward cost, and detects when a
 * previously selected target reward was disabled or deleted from the catalog.
 */
data class RewardGoalProgressUiModel(
    val childId: String,
    val childName: String,
    val targetRewardId: String?,
    val targetReward: RewardItem?,
    val currentPoints: Int,
    val requiredPoints: Int,
    val remainingPoints: Int,
    val progressFraction: Float,
    val progressPercent: Int,
    val isGoalReached: Boolean,
    val isRewardUnavailable: Boolean,
    val hasGoalSelected: Boolean
) {
    val reward: RewardItem?
        get() = targetReward

    val rewardIconEmoji: String
        get() = targetReward?.iconEmoji ?: "🎯"

    val rewardTitle: String
        get() = targetReward?.title ?: ""

    val pointsCost: Int
        get() = requiredPoints

    val progressPercentage: Int
        get() = progressPercent

    fun getStatusMessageHe(): String {
        return when {
            !hasGoalSelected -> "בחר/י פרס מהקטלוג כיעד אישי כדי לעקוב אחרי החיסכון שלך"
            isRewardUnavailable -> "הפרס שנבחר כיעד אינו זמין כעת בקטלוג. אפשר לבחור יעד חדש!"
            isGoalReached -> "🎉 הגעת ליעד! אפשר לבקש לממש את הפרס!"
            else -> "עוד $remainingPoints נקודות לפרס שלך!"
        }
    }

    fun getStatusMessageEn(): String {
        return when {
            !hasGoalSelected -> "Select a reward from the catalog to track your personal goal"
            isRewardUnavailable -> "The selected target reward is no longer available. Choose a new goal!"
            isGoalReached -> "🎉 Goal reached! You can request to redeem this reward!"
            else -> "$remainingPoints more points to reach your reward!"
        }
    }

    companion object {
        fun compute(
            member: FamilyMember,
            rewards: List<RewardItem>
        ): RewardGoalProgressUiModel {
            val cleanGoalId = member.targetRewardId?.trim()?.takeIf { it.isNotBlank() }
            val currentPts = member.earnedPoints.coerceAtLeast(0)
            if (cleanGoalId == null) {
                return RewardGoalProgressUiModel(
                    childId = member.id,
                    childName = member.name,
                    targetRewardId = null,
                    targetReward = null,
                    currentPoints = currentPts,
                    requiredPoints = 0,
                    remainingPoints = 0,
                    progressFraction = 0f,
                    progressPercent = 0,
                    isGoalReached = false,
                    isRewardUnavailable = false,
                    hasGoalSelected = false
                )
            }

            val matchedReward = rewards.find { it.id == cleanGoalId }
            val unavailable = matchedReward == null || matchedReward.isDeleted || !matchedReward.isActive
            if (unavailable || matchedReward == null) {
                return RewardGoalProgressUiModel(
                    childId = member.id,
                    childName = member.name,
                    targetRewardId = cleanGoalId,
                    targetReward = matchedReward?.takeIf { !it.isDeleted },
                    currentPoints = currentPts,
                    requiredPoints = matchedReward?.pointsCost?.coerceAtLeast(0) ?: 0,
                    remainingPoints = 0,
                    progressFraction = 0f,
                    progressPercent = 0,
                    isGoalReached = false,
                    isRewardUnavailable = true,
                    hasGoalSelected = true
                )
            }

            val reqPts = matchedReward.pointsCost.coerceAtLeast(1)
            val remPts = (reqPts - currentPts).coerceAtLeast(0)
            val rawFraction = (currentPts.toFloat() / reqPts.toFloat()).coerceIn(0f, 1f)
            val pct = ((currentPts.toDouble() * 100.0) / reqPts.toDouble()).toInt().coerceIn(0, 100)
            val reached = currentPts >= reqPts

            return RewardGoalProgressUiModel(
                childId = member.id,
                childName = member.name,
                targetRewardId = cleanGoalId,
                targetReward = matchedReward,
                currentPoints = currentPts,
                requiredPoints = reqPts,
                remainingPoints = remPts,
                progressFraction = rawFraction,
                progressPercent = pct,
                isGoalReached = reached,
                isRewardUnavailable = false,
                hasGoalSelected = true
            )
        }
    }
}

/**
 * Categories of built-in personal avatars in FamilyTasks — Velvet Edition.
 */
enum class AvatarCategory(
    val titleHe: String,
    val titleEn: String,
    val iconEmoji: String
) {
    BOYS("בנים", "Boys", "👦"),
    GIRLS("בנות", "Girls", "👧"),
    KIDS_GENERAL("ילדים ודמויות", "Kids & People", "🧑"),
    DOGS("כלבים", "Dogs", "🐶"),
    CATS("חתולים", "Cats", "🐱"),
    FOXES("שועלים", "Foxes", "🦊"),
    PANDAS("פנדות ודובים", "Pandas & Bears", "🐼"),
    ROBOTS("רובוטים", "Robots", "🤖"),
    SPACE("חלל ואסטרונאוטים", "Space", "🚀"),
    FANTASY("חדי־קרן ופנטזיה", "Fantasy", "🦄"),
    SUPERHEROES("גיבורי־על", "Superheroes", "🦸"),
    COLORFUL("צבעוניים ושמחים", "Colorful & Fun", "🌈");

    val labelHe: String get() = titleHe
    val labelEn: String get() = titleEn
    val emoji: String get() = iconEmoji

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) titleHe else titleEn
}

typealias FamilyAvatarCategory = AvatarCategory

/**
 * Built-in personal avatar definition with a stable [id] persisted in Firestore (`avatarId`).
 */
data class FamilyAvatarOption(
    val id: String,
    val emoji: String,
    val labelHe: String,
    val labelEn: String,
    val category: AvatarCategory,
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val badgeEmoji: String = ""
) {
    val titleHe: String get() = labelHe
    val titleEn: String get() = labelEn

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) labelHe else labelEn
}

/**
 * Built-in gallery of 38 cheerful, family-friendly personal avatars across 12 categories.
 * Provides safe lookup by [avatarId] with graceful fallback when [avatarId] is empty or unknown.
 */
object FamilyAvatarCatalog {

    val allAvatars: List<FamilyAvatarOption> = listOf(
        // 1. בנים (Boys)
        FamilyAvatarOption(
            id = "avatar_boy_01",
            emoji = "👦",
            labelHe = "ילד חיוך",
            labelEn = "Smiling Boy",
            category = AvatarCategory.BOYS,
            primaryColorHex = 0xFF3B82F6,
            secondaryColorHex = 0xFF1D4ED8
        ),
        FamilyAvatarOption(
            id = "avatar_boy_02",
            emoji = "👦🏻",
            labelHe = "ילד אלוף",
            labelEn = "Champion Boy",
            category = AvatarCategory.BOYS,
            primaryColorHex = 0xFF0EA5E9,
            secondaryColorHex = 0xFF0369A1,
            badgeEmoji = "⭐"
        ),
        FamilyAvatarOption(
            id = "avatar_boy_03",
            emoji = "👦🏽",
            labelHe = "ילד ספורטיבי",
            labelEn = "Sporty Boy",
            category = AvatarCategory.BOYS,
            primaryColorHex = 0xFF2563EB,
            secondaryColorHex = 0xFF1E40AF,
            badgeEmoji = "⚽"
        ),

        // 2. בנות (Girls)
        FamilyAvatarOption(
            id = "avatar_girl_01",
            emoji = "👧",
            labelHe = "ילדה חיוך",
            labelEn = "Smiling Girl",
            category = AvatarCategory.GIRLS,
            primaryColorHex = 0xFFEC4899,
            secondaryColorHex = 0xFFBE185D
        ),
        FamilyAvatarOption(
            id = "avatar_girl_02",
            emoji = "👧🏻",
            labelHe = "ילדה כוכבת",
            labelEn = "Star Girl",
            category = AvatarCategory.GIRLS,
            primaryColorHex = 0xFFF43F5E,
            secondaryColorHex = 0xFFE11D48,
            badgeEmoji = "🌟"
        ),
        FamilyAvatarOption(
            id = "avatar_girl_03",
            emoji = "👧🏽",
            labelHe = "ילדה יצירתית",
            labelEn = "Creative Girl",
            category = AvatarCategory.GIRLS,
            primaryColorHex = 0xFFA855F7,
            secondaryColorHex = 0xFF7E22CE,
            badgeEmoji = "🎨"
        ),

        // 3. ילדים ודמויות כלליות (Kids & General)
        FamilyAvatarOption(
            id = "avatar_kid_01",
            emoji = "🧒",
            labelHe = "ילד/ה הרפתקן/ית",
            labelEn = "Adventurer Kid",
            category = AvatarCategory.KIDS_GENERAL,
            primaryColorHex = 0xFF14B8A6,
            secondaryColorHex = 0xFF0F766E
        ),
        FamilyAvatarOption(
            id = "avatar_kid_02",
            emoji = "🤓",
            labelHe = "גאון המשפחה",
            labelEn = "Smart Scholar",
            category = AvatarCategory.KIDS_GENERAL,
            primaryColorHex = 0xFF6366F1,
            secondaryColorHex = 0xFF4338CA
        ),
        FamilyAvatarOption(
            id = "avatar_kid_03",
            emoji = "😎",
            labelHe = "כוכב מגניב",
            labelEn = "Cool Star",
            category = AvatarCategory.KIDS_GENERAL,
            primaryColorHex = 0xFF3B82F6,
            secondaryColorHex = 0xFF1D4ED8
        ),
        FamilyAvatarOption(
            id = "avatar_parent_father",
            emoji = "👨",
            labelHe = "אבא",
            labelEn = "Father",
            category = AvatarCategory.KIDS_GENERAL,
            primaryColorHex = 0xFF1A73E8,
            secondaryColorHex = 0xFF1557B0
        ),
        FamilyAvatarOption(
            id = "avatar_parent_mother",
            emoji = "👩",
            labelHe = "אמא",
            labelEn = "Mother",
            category = AvatarCategory.KIDS_GENERAL,
            primaryColorHex = 0xFF9334E6,
            secondaryColorHex = 0xFF6B21A8
        ),

        // 4. כלבים (Dogs)
        FamilyAvatarOption(
            id = "avatar_dog_01",
            emoji = "🐶",
            labelHe = "גור כלבים מתוק",
            labelEn = "Cute Puppy",
            category = AvatarCategory.DOGS,
            primaryColorHex = 0xFFF59E0B,
            secondaryColorHex = 0xFFD97706
        ),
        FamilyAvatarOption(
            id = "avatar_dog_02",
            emoji = "🐕",
            labelHe = "כלב נאמן",
            labelEn = "Loyal Dog",
            category = AvatarCategory.DOGS,
            primaryColorHex = 0xFFD97706,
            secondaryColorHex = 0xFFB45309
        ),
        FamilyAvatarOption(
            id = "avatar_dog_03",
            emoji = "🐩",
            labelHe = "פודל מלכותי",
            labelEn = "Royal Poodle",
            category = AvatarCategory.DOGS,
            primaryColorHex = 0xFFEC4899,
            secondaryColorHex = 0xFFDB2777
        ),

        // 5. חתולים (Cats)
        FamilyAvatarOption(
            id = "avatar_cat_01",
            emoji = "🐱",
            labelHe = "חתלתול חמוד",
            labelEn = "Cute Kitten",
            category = AvatarCategory.CATS,
            primaryColorHex = 0xFFF97316,
            secondaryColorHex = 0xFFEA580C
        ),
        FamilyAvatarOption(
            id = "avatar_cat_02",
            emoji = "😺",
            labelHe = "חתול מחייך",
            labelEn = "Smiling Cat",
            category = AvatarCategory.CATS,
            primaryColorHex = 0xFFEAB308,
            secondaryColorHex = 0xFFCA8A04
        ),
        FamilyAvatarOption(
            id = "avatar_cat_03",
            emoji = "🐾",
            labelHe = "כפות קטיפה",
            labelEn = "Velvet Paws",
            category = AvatarCategory.CATS,
            primaryColorHex = 0xFFA855F7,
            secondaryColorHex = 0xFF7E22CE
        ),

        // 6. שועלים (Foxes)
        FamilyAvatarOption(
            id = "avatar_fox_01",
            emoji = "🦊",
            labelHe = "שועל כתום",
            labelEn = "Clever Fox",
            category = AvatarCategory.FOXES,
            primaryColorHex = 0xFFEA580C,
            secondaryColorHex = 0xFFC2410C
        ),
        FamilyAvatarOption(
            id = "avatar_fox_02",
            emoji = "🦊",
            labelHe = "שועל היער",
            labelEn = "Forest Fox",
            category = AvatarCategory.FOXES,
            primaryColorHex = 0xFF16A34A,
            secondaryColorHex = 0xFF15803D,
            badgeEmoji = "🌲"
        ),
        FamilyAvatarOption(
            id = "avatar_fox_03",
            emoji = "🦊",
            labelHe = "שועל הכוכבים",
            labelEn = "Starlight Fox",
            category = AvatarCategory.FOXES,
            primaryColorHex = 0xFF6366F1,
            secondaryColorHex = 0xFF4338CA,
            badgeEmoji = "✨"
        ),

        // 7. פנדות ודובים (Pandas & Bears)
        FamilyAvatarOption(
            id = "avatar_panda_01",
            emoji = "🐼",
            labelHe = "פנדה חמודה",
            labelEn = "Cute Panda",
            category = AvatarCategory.PANDAS,
            primaryColorHex = 0xFF10B981,
            secondaryColorHex = 0xFF059669
        ),
        FamilyAvatarOption(
            id = "avatar_panda_02",
            emoji = "🐻",
            labelHe = "דובון חם",
            labelEn = "Cozy Bear",
            category = AvatarCategory.PANDAS,
            primaryColorHex = 0xFFB45309,
            secondaryColorHex = 0xFF92400E
        ),
        FamilyAvatarOption(
            id = "avatar_panda_03",
            emoji = "🐨",
            labelHe = "קואלה שמחה",
            labelEn = "Happy Koala",
            category = AvatarCategory.PANDAS,
            primaryColorHex = 0xFF0EA5E9,
            secondaryColorHex = 0xFF0284C7
        ),

        // 8. רובוטים (Robots)
        FamilyAvatarOption(
            id = "avatar_robot_blue_01",
            emoji = "🤖",
            labelHe = "רובוט כחול",
            labelEn = "Blue Robot",
            category = AvatarCategory.ROBOTS,
            primaryColorHex = 0xFF2563EB,
            secondaryColorHex = 0xFF1D4ED8
        ),
        FamilyAvatarOption(
            id = "avatar_robot_cyber_02",
            emoji = "🤖",
            labelHe = "רובוט סגול",
            labelEn = "Cyber Robot",
            category = AvatarCategory.ROBOTS,
            primaryColorHex = 0xFF7C3AED,
            secondaryColorHex = 0xFF5B21B6,
            badgeEmoji = "⚡"
        ),
        FamilyAvatarOption(
            id = "avatar_robot_emerald_03",
            emoji = "🤖",
            labelHe = "רובוט טורקיז",
            labelEn = "Emerald Bot",
            category = AvatarCategory.ROBOTS,
            primaryColorHex = 0xFF0D9488,
            secondaryColorHex = 0xFF0F766E,
            badgeEmoji = "🔷"
        ),

        // 9. חלל ואסטרונאוטים (Space & Astronauts)
        FamilyAvatarOption(
            id = "avatar_space_01",
            emoji = "🧑🏼‍🚀",
            labelHe = "אסטרונאוט",
            labelEn = "Astronaut",
            category = AvatarCategory.SPACE,
            primaryColorHex = 0xFF1E3A8A,
            secondaryColorHex = 0xFF172554
        ),
        FamilyAvatarOption(
            id = "avatar_space_02",
            emoji = "🚀",
            labelHe = "חללית מהירה",
            labelEn = "Space Rocket",
            category = AvatarCategory.SPACE,
            primaryColorHex = 0xFF3B82F6,
            secondaryColorHex = 0xFF1D4ED8
        ),
        FamilyAvatarOption(
            id = "avatar_space_03",
            emoji = "🪐",
            labelHe = "כוכב שבתאי",
            labelEn = "Ringed Planet",
            category = AvatarCategory.SPACE,
            primaryColorHex = 0xFF8B5CF6,
            secondaryColorHex = 0xFF5B21B6
        ),

        // 10. חדי־קרן ויצורי פנטזיה (Unicorns & Fantasy)
        FamilyAvatarOption(
            id = "avatar_fantasy_01",
            emoji = "🦄",
            labelHe = "חד־קרן קסום",
            labelEn = "Magic Unicorn",
            category = AvatarCategory.FANTASY,
            primaryColorHex = 0xFFEC4899,
            secondaryColorHex = 0xFF9333EA
        ),
        FamilyAvatarOption(
            id = "avatar_fantasy_02",
            emoji = "🐉",
            labelHe = "דרקון ידידותי",
            labelEn = "Friendly Dragon",
            category = AvatarCategory.FANTASY,
            primaryColorHex = 0xFF10B981,
            secondaryColorHex = 0xFF047857
        ),
        FamilyAvatarOption(
            id = "avatar_fantasy_03",
            emoji = "🧚",
            labelHe = "פיית האור",
            labelEn = "Light Fairy",
            category = AvatarCategory.FANTASY,
            primaryColorHex = 0xFFD946EF,
            secondaryColorHex = 0xFFA21CAF
        ),

        // 11. גיבורי־על מקוריים (Original Superheroes)
        FamilyAvatarOption(
            id = "avatar_hero_01",
            emoji = "🦸",
            labelHe = "גיבור־על כחול",
            labelEn = "Blue Superhero",
            category = AvatarCategory.SUPERHEROES,
            primaryColorHex = 0xFF2563EB,
            secondaryColorHex = 0xFF1E40AF
        ),
        FamilyAvatarOption(
            id = "avatar_hero_02",
            emoji = "🦸‍♀️",
            labelHe = "גיבורת־על",
            labelEn = "Super Heroine",
            category = AvatarCategory.SUPERHEROES,
            primaryColorHex = 0xFFE11D48,
            secondaryColorHex = 0xFF9F1239
        ),
        FamilyAvatarOption(
            id = "avatar_hero_03",
            emoji = "⚡",
            labelHe = "מגן הברק",
            labelEn = "Lightning Hero",
            category = AvatarCategory.SUPERHEROES,
            primaryColorHex = 0xFFF59E0B,
            secondaryColorHex = 0xFFB45309
        ),

        // 12. אווטארים צבעוניים ושמחים (Colorful & Happy)
        FamilyAvatarOption(
            id = "avatar_colorful_01",
            emoji = "🌈",
            labelHe = "קשת בענן",
            labelEn = "Rainbow",
            category = AvatarCategory.COLORFUL,
            primaryColorHex = 0xFF06B6D4,
            secondaryColorHex = 0xFF3B82F6
        ),
        FamilyAvatarOption(
            id = "avatar_colorful_02",
            emoji = "🌟",
            labelHe = "כוכב זוהר",
            labelEn = "Shining Star",
            category = AvatarCategory.COLORFUL,
            primaryColorHex = 0xFFEAB308,
            secondaryColorHex = 0xFFCA8A04
        ),
        FamilyAvatarOption(
            id = "avatar_colorful_03",
            emoji = "👑",
            labelHe = "כתר האלופים",
            labelEn = "Champion Crown",
            category = AvatarCategory.COLORFUL,
            primaryColorHex = 0xFFF59E0B,
            secondaryColorHex = 0xFFD97706
        )
    )

    private val byIdMap: Map<String, FamilyAvatarOption> = allAvatars.associateBy { it.id }

    private val semanticAliasMap: Map<String, String> = mapOf(
        "boy_curious" to "avatar_boy_01",
        "boy_champion" to "avatar_boy_02",
        "boy_sporty" to "avatar_boy_03",
        "girl_smile" to "avatar_girl_01",
        "girl_star" to "avatar_girl_02",
        "girl_creative" to "avatar_girl_03",
        "space_astronaut" to "avatar_space_01",
        "space_rocket" to "avatar_space_02",
        "space_planet" to "avatar_space_03",
        "robot_friendly" to "avatar_robot_blue_01",
        "robot_cyber" to "avatar_robot_cyber_02",
        "robot_emerald" to "avatar_robot_emerald_03",
        "fantasy_unicorn" to "avatar_fantasy_01",
        "fantasy_dragon" to "avatar_fantasy_02",
        "fantasy_fairy" to "avatar_fantasy_03",
        "hero_blue" to "avatar_hero_01",
        "hero_girl" to "avatar_hero_02",
        "hero_lightning" to "avatar_hero_03"
    )

    fun findById(avatarId: String?): FamilyAvatarOption? {
        val clean = avatarId?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return byIdMap[clean] ?: semanticAliasMap[clean]?.let { byIdMap[it] }
    }

    fun resolveEmoji(avatarId: String?, fallbackEmoji: String): String {
        return findById(avatarId)?.emoji ?: fallbackEmoji.ifBlank { "🧒" }
    }

    fun getByCategory(category: AvatarCategory?): List<FamilyAvatarOption> {
        if (category == null) return allAvatars
        return allAvatars.filter { it.category == category }
    }

    fun byCategory(category: AvatarCategory?): List<FamilyAvatarOption> = getByCategory(category)

    fun avatarsByCategory(category: AvatarCategory?): List<FamilyAvatarOption> = getByCategory(category)
}



