package com.example.model

enum class UserRole {
    PRIMARY_ADMIN,   // מנהל ראשי
    SECONDARY_ADMIN, // מנהל משני
    MEMBER_KID;      // חבר בית / ילד

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        PRIMARY_ADMIN -> if (isHebrew) "מנהל ראשי" else "Primary Admin"
        SECONDARY_ADMIN -> if (isHebrew) "מנהל משני" else "Secondary Admin"
        MEMBER_KID -> if (isHebrew) "בן משפחה / ילד" else "Family Member / Kid"
    }

    val isAdmin: Boolean get() = this == PRIMARY_ADMIN || this == SECONDARY_ADMIN
}

data class FamilyMember(
    val id: String,
    val name: String,
    val email: String,
    val role: UserRole,
    val avatarEmoji: String,
    val colorHex: Long,
    val earnedPoints: Int = 0,
    val completedTasksCount: Int = 0,
    val schoolGrade: String? = null,
    val isCurrentUser: Boolean = false
) {
    val isAdmin: Boolean get() = role.isAdmin
    val isPrimaryAdmin: Boolean get() = role == UserRole.PRIMARY_ADMIN
}

enum class TaskCategory(val defaultEmoji: String, val colorHex: Long) {
    CLEANING("🧹", 0xFF1A73E8),
    HOMEWORK("📚", 0xFF9334E8),
    DISHES("🍽️", 0xFF00ACC1),
    BEDROOM("🛏️", 0xFF34A853),
    PETS("🐾", 0xFFF29900),
    TRASH("🗑️", 0xFF757575),
    GROCERIES("🛒", 0xFFE91E63),
    OTHER("⭐", 0xFFFBBC04);

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        CLEANING -> if (isHebrew) "ניקיון" else "Cleaning"
        HOMEWORK -> if (isHebrew) "שיעורי בית" else "Homework"
        DISHES -> if (isHebrew) "שטיפת כלים" else "Dishes"
        BEDROOM -> if (isHebrew) "סידור חדר" else "Bedroom"
        PETS -> if (isHebrew) "חיות מחמד" else "Pets"
        TRASH -> if (isHebrew) "הורדת זבל" else "Trash"
        GROCERIES -> if (isHebrew) "קניות" else "Groceries"
        OTHER -> if (isHebrew) "משימה כללית" else "General"
    }
}

enum class TaskPriority {
    LOW, MEDIUM, HIGH;

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        LOW -> if (isHebrew) "רגילה" else "Low"
        MEDIUM -> if (isHebrew) "בינונית" else "Medium"
        HIGH -> if (isHebrew) "דחופה!" else "Urgent!"
    }
}

enum class TaskRecurrence {
    ONCE, DAILY, WEEKLY, SCHOOL_DAYS;

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        ONCE -> if (isHebrew) "חד פעמי" else "Once"
        DAILY -> if (isHebrew) "יומי" else "Daily"
        WEEKLY -> if (isHebrew) "שבועי" else "Weekly"
        SCHOOL_DAYS -> if (isHebrew) "ימי לימודים" else "School days"
    }
}

data class FamilyTask(
    val id: String,
    val title: String,
    val description: String = "",
    val category: TaskCategory = TaskCategory.OTHER,
    val assignedToMemberId: String,
    val dateEpochDay: Long, // LocalDate.toEpochDay()
    val dueTimeFormatted: String = "18:00",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val points: Int = 10,
    val recurrence: TaskRecurrence = TaskRecurrence.DAILY,
    val isCompleted: Boolean = false,
    val completedAtMillis: Long? = null,
    val completedByMemberId: String? = null,
    val familyId: String = "",
    val approvedByParent: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class FamilyGroup(
    val id: String,
    val name: String,
    val inviteCode: String,
    val createdByUserId: String,
    val memberIds: List<String> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class RewardItem(
    val id: String,
    val familyId: String,
    val title: String,
    val description: String = "",
    val pointsCost: Int = 50,
    val iconEmoji: String = "🎁",
    val isClaimed: Boolean = false,
    val claimedByMemberId: String? = null,
    val approvedByParent: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

enum class SchoolDay(val dayNumber: Int) {
    SUNDAY(1),
    MONDAY(2),
    TUESDAY(3),
    WEDNESDAY(4),
    THURSDAY(5),
    FRIDAY(6);

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        SUNDAY -> if (isHebrew) "ראשון" else "Sunday"
        MONDAY -> if (isHebrew) "שני" else "Monday"
        TUESDAY -> if (isHebrew) "שלישי" else "Tuesday"
        WEDNESDAY -> if (isHebrew) "רביעי" else "Wednesday"
        THURSDAY -> if (isHebrew) "חמישי" else "Thursday"
        FRIDAY -> if (isHebrew) "שישי" else "Friday"
    }
}

data class SchedulePeriod(
    val id: String,
    val childId: String,
    val day: SchoolDay,
    val periodIndex: Int, // 1, 2, 3...
    val startTime: String, // "08:00"
    val endTime: String,   // "08:45"
    val subjectName: String,
    val roomNumber: String = "",
    val teacherName: String = "",
    val colorHex: Long = 0xFF1A73E8,
    val isAlertEnabled: Boolean = true
)

data class DocumentFolder(
    val id: String,
    val nameHebrew: String,
    val nameEnglish: String,
    val colorHex: Long,
    val iconEmoji: String
) {
    fun getName(isHebrew: Boolean): String = if (isHebrew) nameHebrew else nameEnglish
}

data class FamilyDocument(
    val id: String,
    val folderId: String,
    val name: String,
    val extension: String, // PDF, DOCX, JPG, PNG
    val sizeFormatted: String,
    val uploadedByName: String,
    val uploadDateFormatted: String,
    val isDriveSynced: Boolean = true,
    val driveFileUrl: String = "https://drive.google.com/family-hub"
)

enum class NotificationType {
    TASK_ASSIGNED,
    TASK_COMPLETED,
    NUDGE_REMINDER,
    SCHOOL_START,
    FILE_SHARED,
    MEMBER_INVITED
}

enum class AppThemeColor(
    val primaryHex: Long,
    val containerHex: Long,
    val nameHebrew: String,
    val nameEnglish: String
) {
    GOOGLE_BLUE(0xFF1A73E8, 0xFFE8F0FE, "כחול Google", "Google Blue"),
    ROYAL_PURPLE(0xFF7C4DFF, 0xFFEDE7F6, "סגול מלכותי", "Royal Purple"),
    FOREST_GREEN(0xFF2E7D32, 0xFFE8F5E9, "ירוק יער", "Forest Green"),
    CORAL_SUNSET(0xFFE64A19, 0xFFFBE9E7, "שקיעה קורל", "Coral Sunset"),
    OCEAN_CYAN(0xFF0097A7, 0xFFE0F7FA, "טורקיז אוקיינוס", "Ocean Cyan"),
    GOLDEN_AMBER(0xFFF57F17, 0xFFFFF8E1, "זהב אמבר", "Golden Amber");

    fun getName(isHebrew: Boolean): String = if (isHebrew) nameHebrew else nameEnglish
}

enum class FilesViewMode {
    FOLDERS, // תיקיות
    LIST,    // רשימה מפורטת
    GRID;    // גריד כרטיסיות

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        FOLDERS -> if (isHebrew) "תיקיות" else "Folders"
        LIST -> if (isHebrew) "רשימה" else "List"
        GRID -> if (isHebrew) "גריד" else "Grid"
    }
}

enum class TimetableViewMode {
    DAILY_LIST,   // תצוגת רשימה יומית
    WEEKLY_TABLE; // תצוגת טבלה שבועית מלאה

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        DAILY_LIST -> if (isHebrew) "יומי" else "Daily"
        WEEKLY_TABLE -> if (isHebrew) "טבלה שבועית" else "Weekly Table"
    }
}

data class AppNotification(
    val id: String,
    val title: String,
    val message: String,
    val timestampMillis: Long,
    val type: NotificationType,
    val isRead: Boolean = false,
    val targetMemberName: String? = null,
    val completedByMemberName: String? = null,
    val completedByAvatar: String? = null,
    val taskTitle: String? = null,
    val pointsGained: Int? = null
)

enum class AppLanguage {
    HEBREW, ENGLISH
}

enum class AlertSound(val soundTitle: String) {
    GOOGLE_CHIME("Google Chime"),
    BRIGHT_BELL("Bright Bell"),
    POP_BUBBLE("Pop Bubble"),
    COSMIC_TONE("Cosmic Tone"),
    GENTLE_HARP("Gentle Harp")
}

enum class FirstDayOfWeek {
    SUNDAY, MONDAY;

    fun getDisplayName(isHebrew: Boolean): String = when (this) {
        SUNDAY -> if (isHebrew) "ראשון (ברירת מחדל)" else "Sunday (Default)"
        MONDAY -> if (isHebrew) "שני" else "Monday"
    }
}

data class AppSettings(
    val familyName: String = "וקסלר",
    val hasCompletedGoogleSignIn: Boolean = false,
    val googleAccountEmail: String = "Moranw@gmail.com",
    val language: AppLanguage = AppLanguage.HEBREW,
    val biometricEnabled: Boolean = true,
    val alertSound: AlertSound = AlertSound.GOOGLE_CHIME,
    val customSoundName: String? = null,
    val soundVolume: Float = 0.85f,
    val firstDayOfWeek: FirstDayOfWeek = FirstDayOfWeek.SUNDAY,
    val isDriveSyncActive: Boolean = true,
    val lastSyncFormatted: String = "לפני דקה",
    val themeColor: AppThemeColor = AppThemeColor.GOOGLE_BLUE,
    val filesViewMode: FilesViewMode = FilesViewMode.FOLDERS,
    val timetableViewMode: TimetableViewMode = TimetableViewMode.DAILY_LIST,
    val appVersion: Double = 1.0,
    val joinedFamilyCode: String? = null,
    val customSoundUri: String? = null,
    val requireBiometricOnStart: Boolean = false,
    val fallbackPin: String = "1234"
) {
    val formattedVersion: String
        get() = String.format(java.util.Locale.US, "%.1f", appVersion)

    fun getFamilyTitle(isHebrew: Boolean): String {
        val trimmed = familyName.trim()
        return if (isHebrew) {
            if (trimmed.startsWith("משפחת")) trimmed else "משפחת $trimmed"
        } else {
            if (trimmed.endsWith("Family", ignoreCase = true) || trimmed.startsWith("Family", ignoreCase = true)) {
                trimmed
            } else {
                "$trimmed Family"
            }
        }
    }

    fun getDynamicInviteCode(): String {
        joinedFamilyCode?.let { if (it.isNotBlank()) return it }
        val cleanName = familyName
            .replace("משפחת", "")
            .replace("Family", "", ignoreCase = true)
            .trim()
            .replace(Regex("[^A-Za-z0-9א-ת]"), "")
            .uppercase()
        val tag = if (cleanName.isNotBlank()) cleanName else "HOUSE"
        return "FAM-$tag-7749"
    }

    fun getDynamicInviteLink(): String {
        return "https://familytasks.app/join/${getDynamicInviteCode()}"
    }
}

