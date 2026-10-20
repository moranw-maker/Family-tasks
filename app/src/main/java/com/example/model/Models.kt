package com.example.model

import java.time.LocalDate
import java.util.UUID

enum class AppScreen {
    HOME,
    CALENDAR,
    TIMETABLE,
    FILES,
    AI_ASSISTANT,
    SETTINGS,
    ADMIN_DASHBOARD
}

enum class AppLanguage(val code: String, val displayName: String, val isRtl: Boolean) {
    HEBREW("he", "עברית (RTL)", true),
    ENGLISH("en", "English (LTR)", false)
}

enum class AppThemeMode(val labelHe: String, val labelEn: String) {
    SYSTEM("מערכת", "System"),
    LIGHT("בהיר", "Light"),
    DARK("כהה", "Dark");

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
    val invitedByMemberId: String? = ""
) {
    val isAdmin: Boolean get() = role == UserRole.PARENT
    val isParent: Boolean get() = role == UserRole.PARENT
    val isChild: Boolean get() = role == UserRole.CHILD
    val isFather: Boolean get() = role == UserRole.PARENT && familyIdentity == FamilyIdentity.FATHER
    val isMother: Boolean get() = role == UserRole.PARENT && familyIdentity == FamilyIdentity.MOTHER

    /** Available points balance after subtracting redeemed rewards. */
    val availablePoints: Int get() = (earnedPoints - spentPoints).coerceAtLeast(0)

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) name else nameEn.ifBlank { name }

    fun getRoleWithIdentityLabel(isHebrew: Boolean): String {
        return if (role == UserRole.PARENT) {
            if (familyIdentity == FamilyIdentity.FATHER) {
                if (isHebrew) "👨 אבא — הורה (מנהל משפחה)" else "👨 Father — Parent (Admin)"
            } else {
                if (isHebrew) "👩 אמא — הורה (מנהל משפחה)" else "👩 Mother — Parent (Admin)"
            }
        } else {
            if (isHebrew) "$avatarEmoji ילד/ה — חבר משפחה" else "$avatarEmoji Child — Family Member"
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
    val description: String = ""
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

data class FamilyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val category: TaskCategory = TaskCategory.OTHER,
    val assignedToMemberId: String,
    val dateEpochDay: Long,
    val dueTimeFormatted: String = "16:00",
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
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
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
    FRIDAY(5, "יום שישי", "ו'", "Friday", "Fri");

    fun getDisplayName(isHebrew: Boolean): String = if (isHebrew) nameHe else nameEn
    fun getShortName(isHebrew: Boolean): String = if (isHebrew) shortHe else shortEn
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
    val pointsGained: Int? = null
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
    val assignedMemberName: String = "",
    val daysOfWeek: List<SchoolDay> = listOf(
        SchoolDay.SUNDAY,
        SchoolDay.MONDAY,
        SchoolDay.TUESDAY,
        SchoolDay.WEDNESDAY,
        SchoolDay.THURSDAY,
        SchoolDay.FRIDAY
    ),
    val isEnabled: Boolean = true,
    val ringtoneEmoji: String = "⏰",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val createdByMemberId: String = "",
    val createdByMemberName: String = "",
    val isPersonal: Boolean = false,
    val soundType: String = "SYSTEM_ALARM", // SYSTEM_ALARM, SYSTEM_NOTIFICATION, SYSTEM_RINGTONE, CUSTOM_URI
    val customSoundUri: String = "",
    val customSoundTitle: String = "",
    val vibrateEnabled: Boolean = true,
    val snoozeDurationMinutes: Int = 5,
    val volumePercent: Int = 85,
    val scheduleStatus: String = "SCHEDULED_LOCALLY", // SCHEDULED_LOCALLY, PENDING_TARGET_DEVICE, EXACT_PERMISSION_NEEDED, DISABLED
    val acknowledgedByMemberId: String = "",
    val acknowledgedByDeviceName: String = "",
    val acknowledgedAtMillis: Long = 0L,
    val nextTriggerAtMillis: Long = 0L
) {
    val timeFormatted: String
        get() = String.format(java.util.Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    val formattedTime24h: String
        get() = timeFormatted

    val isConfirmedOnTargetDevice: Boolean
        get() = isEnabled && (
            scheduleStatus == "SCHEDULED_LOCALLY" &&
                (assignedMemberId.isBlank() || acknowledgedByMemberId == assignedMemberId || isPersonal)
        )
}


