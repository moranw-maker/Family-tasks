package com.example.util

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

/**
 * Explicit startup stages for tracing `restoreExistingGoogleSession()`,
 * `checkAuthenticatedUserMembership()`, and every Firestore read during startup.
 */
enum class StartupDiagnosticStage(val code: String, val description: String) {
    RESTORE_AUTH_SESSION("STAGE_1_RESTORE_AUTH_SESSION", "Restoring existing FirebaseAuth session"),
    READ_USER_PROFILE("STAGE_2_READ_USER_PROFILE", "Reading /users/{uid} profile document"),
    READ_FAMILY_MEMBER_DOC("STAGE_3_READ_FAMILY_MEMBER_DOC", "Reading /families/{familyId}/members/{uid} document"),
    READ_FAMILY_DOC("STAGE_4_READ_FAMILY_DOC", "Reading /families/{familyId} document"),
    DISCOVER_LEGACY_FAMILY("STAGE_5_DISCOVER_LEGACY_FAMILY", "Querying /families for existing V1.5/V1.6 membership"),
    MIGRATE_LEGACY_DATA("STAGE_6_MIGRATE_LEGACY_DATA", "Migrating legacy V1.5 fields to V1.6 canonical schema"),
    ATTACH_REALTIME_LISTENERS("STAGE_7_ATTACH_REALTIME_LISTENERS", "Attaching real-time family listeners")
}

/**
 * Distinct diagnostic categories for startup outcomes and failures (V1.6 Requirement 5).
 */
enum class FirestoreFailureCategory {
    PERMISSION_DENIED,
    UNAVAILABLE,
    NOT_FOUND,
    MISSING_MEMBERSHIP,
    LEGACY_PROFILE,
    UNEXPECTED_EXCEPTION
}

/**
 * Privacy-safe diagnostic record for startup and Firestore operations.
 * Never contains authentication tokens, invitation codes, email addresses, or private user data.
 */
data class FirestoreDiagnosticInfo(
    val stage: StartupDiagnosticStage,
    val category: FirestoreFailureCategory,
    val firestoreErrorCode: String,
    val databaseId: String,
    val sanitizedPath: String,
    val sanitizedMessage: String,
    val causeSummary: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Redacts JWTs, OAuth tokens, email addresses, UIDs in query strings, and invitation codes
 * so diagnostic logs never leak sensitive or private user data.
 */
fun sanitizeFirestoreDiagnosticMessage(rawMessage: String?): String {
    if (rawMessage.isNullOrBlank()) return "No message"
    return rawMessage
        .replace(Regex("eyJ[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*"), "[REDACTED_TOKEN]")
        .replace(Regex("ya29\\.[A-Za-z0-9_.-]+"), "[REDACTED_TOKEN]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[REDACTED_EMAIL]")
        .replace(
            Regex("(?i)(id_token|access_token|refresh_token|token|password|secret|authorization|bearer|inviteCode|code)\\s*[:=]\\s*([^&\\s,\"]+)"),
            "$1=[REDACTED]"
        )
}

/**
 * Sanitizes a Firestore document or collection path so collection structure is preserved
 * while user UIDs and invitation codes are masked (e.g., `users/{uid}`, `families/{familyId}/members/{uid}`, `family_invites/{code}`).
 */
fun sanitizeFirestorePath(path: String?): String {
    if (path.isNullOrBlank()) return "unknown_path"
    val segments = path.trim('/').split('/')
    val sanitized = segments.mapIndexed { index, segment ->
        val prev = segments.getOrNull(index - 1)
        when (prev) {
            "users", "members" -> "{uid}"
            "family_invites" -> "{inviteCode}"
            else -> segment
        }
    }
    return sanitized.joinToString("/")
}

/**
 * Classifies a Throwable into a distinct [FirestoreFailureCategory] and extracts the
 * [FirebaseFirestoreException.Code] if present in the causal chain.
 */
fun classifyFirestoreException(throwable: Throwable): Pair<FirestoreFailureCategory, String> {
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < 10) {
        if (current is FirebaseFirestoreException) {
            val code = current.code
            val category = when (code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> FirestoreFailureCategory.PERMISSION_DENIED
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                FirebaseFirestoreException.Code.ABORTED -> FirestoreFailureCategory.UNAVAILABLE
                FirebaseFirestoreException.Code.NOT_FOUND -> FirestoreFailureCategory.NOT_FOUND
                else -> FirestoreFailureCategory.UNEXPECTED_EXCEPTION
            }
            return category to code.name
        }
        if (current is FirebaseNetworkException) {
            return FirestoreFailureCategory.UNAVAILABLE to "FIREBASE_NETWORK_EXCEPTION"
        }
        current = current.cause
        depth++
    }

    val msg = throwable.message.orEmpty()
    return when {
        msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
            msg.contains("Missing or insufficient permissions", ignoreCase = true) ->
            FirestoreFailureCategory.PERMISSION_DENIED to "PERMISSION_DENIED"
        msg.contains("UNAVAILABLE", ignoreCase = true) ||
            msg.contains("offline", ignoreCase = true) ||
            msg.contains("network", ignoreCase = true) ->
            FirestoreFailureCategory.UNAVAILABLE to "UNAVAILABLE"
        msg.contains("NOT_FOUND", ignoreCase = true) ->
            FirestoreFailureCategory.NOT_FOUND to "NOT_FOUND"
        else ->
            FirestoreFailureCategory.UNEXPECTED_EXCEPTION to throwable.javaClass.simpleName
    }
}

/**
 * Structured helper object for startup stage tracing, error classification, and V1.5 -> V1.6
 * migration logging without exposing authentication tokens, invitation codes, or private user data.
 */
object FirestoreErrorHandler {
    const val NAMED_DATABASE_ID = "ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca"
    private const val TAG = "FirestoreDiagnostic"

    enum class StartupStage(val stageName: String) {
        RESTORE_AUTH_SESSION("RESTORE_AUTH_SESSION"),
        LOAD_USER_PROFILE("LOAD_USER_PROFILE"),
        LOAD_FAMILY_DOCUMENT("LOAD_FAMILY_DOCUMENT"),
        LOAD_FAMILY_MEMBER("LOAD_FAMILY_MEMBER"),
        VERIFY_MEMBERSHIP("VERIFY_MEMBERSHIP"),
        MIGRATE_LEGACY_V15_DATA("MIGRATE_LEGACY_V15_DATA"),
        ATTACH_LISTENERS("ATTACH_LISTENERS")
    }

    enum class StartupFailureCategory {
        PERMISSION_DENIED,
        UNAVAILABLE,
        NOT_FOUND,
        MISSING_MEMBERSHIP,
        LEGACY_PROFILE,
        UNEXPECTED_EXCEPTION
    }

    data class ClassifiedStartupFailure(
        val category: StartupFailureCategory,
        val errorCode: String,
        val stage: StartupStage,
        val databaseId: String,
        val collectionPath: String,
        val documentPath: String,
        val sanitizedMessage: String,
        val causeSummary: String,
        val messageHe: String,
        val messageEn: String
    )

    fun classifyStartupFailure(
        error: Throwable,
        stage: StartupStage,
        collectionPath: String,
        documentId: String? = null,
        databaseId: String = NAMED_DATABASE_ID
    ): ClassifiedStartupFailure {
        val (rawCat, code) = classifyFirestoreException(error)
        val category = when (rawCat) {
            FirestoreFailureCategory.PERMISSION_DENIED -> StartupFailureCategory.PERMISSION_DENIED
            FirestoreFailureCategory.UNAVAILABLE -> StartupFailureCategory.UNAVAILABLE
            FirestoreFailureCategory.NOT_FOUND -> StartupFailureCategory.NOT_FOUND
            FirestoreFailureCategory.MISSING_MEMBERSHIP -> StartupFailureCategory.MISSING_MEMBERSHIP
            FirestoreFailureCategory.LEGACY_PROFILE -> StartupFailureCategory.LEGACY_PROFILE
            FirestoreFailureCategory.UNEXPECTED_EXCEPTION -> StartupFailureCategory.UNEXPECTED_EXCEPTION
        }

        val cleanCollection = collectionPath.trim('/')
        val docPath = if (!documentId.isNullOrBlank()) {
            "$cleanCollection/{redacted}"
        } else {
            cleanCollection
        }
        val safeMsg = sanitizeFirestoreDiagnosticMessage(error.message)
        val safeCause = error.cause?.let {
            "${it.javaClass.simpleName}: ${sanitizeFirestoreDiagnosticMessage(it.message)}"
        } ?: "none"

        val (messageHe, messageEn) = when (category) {
            StartupFailureCategory.PERMISSION_DENIED -> Pair(
                "הגישה לנתוני הפרופיל בענן נדחתה (PERMISSION_DENIED בשלב ${stage.stageName}). ודאו שחוקי האבטחה (Firestore Rules) פורסמו למסד הנתונים.",
                "Cloud profile access was denied (PERMISSION_DENIED at ${stage.stageName}). Verify Firestore Security Rules deployment."
            )
            StartupFailureCategory.UNAVAILABLE -> Pair(
                "שרתי הענן אינם זמינים כרגע (UNAVAILABLE). בדקו את החיבור לאינטרנט ונסו שוב.",
                "Cloud servers are temporarily unavailable (UNAVAILABLE). Check your internet connection and retry."
            )
            StartupFailureCategory.NOT_FOUND -> Pair(
                "מסמך הפרופיל או המשפחה לא נמצא במסד הנתונים (NOT_FOUND בשלב ${stage.stageName}).",
                "Profile or family document was not found in the database (NOT_FOUND at ${stage.stageName})."
            )
            StartupFailureCategory.MISSING_MEMBERSHIP -> Pair(
                "לא נמצאה חברות פעילה במשפחה עבור חשבון זה.",
                "No active family membership was found for this account."
            )
            StartupFailureCategory.LEGACY_PROFILE -> Pair(
                "פרופיל מדור קודם (V1.5) דורש סנכרון מחדש.",
                "Legacy V1.5 profile requires migration."
            )
            StartupFailureCategory.UNEXPECTED_EXCEPTION -> Pair(
                "אירעה שגיאה בטעינת הפרופיל מהענן ($code בשלב ${stage.stageName}). נסו שוב.",
                "An unexpected error occurred while loading your cloud profile ($code at ${stage.stageName}). Please retry."
            )
        }

        val payload = JSONObject().apply {
            put("event", "STARTUP_FIRESTORE_FAILURE")
            put("stage", stage.stageName)
            put("category", category.name)
            put("errorCode", code)
            put("databaseId", databaseId)
            put("collectionPath", cleanCollection)
            put("documentPath", docPath)
            put("message", safeMsg)
            put("cause", safeCause)
        }
        Log.e(TAG, payload.toString())

        return ClassifiedStartupFailure(
            category = category,
            errorCode = code,
            stage = stage,
            databaseId = databaseId,
            collectionPath = cleanCollection,
            documentPath = docPath,
            sanitizedMessage = safeMsg,
            causeSummary = safeCause,
            messageHe = messageHe,
            messageEn = messageEn
        )
    }

    fun logStartupMilestone(
        stage: StartupStage,
        collectionPath: String,
        databaseId: String = NAMED_DATABASE_ID,
        note: String = ""
    ) {
        val payload = JSONObject().apply {
            put("event", "STARTUP_STAGE")
            put("stage", stage.stageName)
            put("databaseId", databaseId)
            put("collectionPath", collectionPath)
            if (note.isNotBlank()) {
                put("note", sanitizeFirestoreDiagnosticMessage(note))
            }
        }
        Log.i(TAG, payload.toString())
    }

    fun logLegacyMigration(
        databaseId: String,
        legacyRole: String?,
        migratedRole: String,
        preservedIdentity: String,
        hadMemberDoc: Boolean,
        hadUserDoc: Boolean
    ) {
        val payload = JSONObject().apply {
            put("event", "V15_TO_V16_MIGRATION")
            put("stage", StartupStage.MIGRATE_LEGACY_V15_DATA.stageName)
            put("category", StartupFailureCategory.LEGACY_PROFILE.name)
            put("databaseId", databaseId)
            put("legacyRole", legacyRole ?: "NONE")
            put("migratedRole", migratedRole)
            put("preservedIdentity", preservedIdentity)
            put("hadMemberDoc", hadMemberDoc)
            put("hadUserDoc", hadUserDoc)
        }
        Log.i(TAG, payload.toString())
    }

    /**
     * Explicit invitation lifecycle stages for creation, validation, redemption, and revocation.
     */
    enum class InvitationStage(val stageName: String) {
        VERIFY_PARENT_AUTHORIZATION("VERIFY_PARENT_AUTHORIZATION"),
        CREATE_INVITATION_WRITE("CREATE_INVITATION_WRITE"),
        CONFIRM_INVITATION_COMMIT("CONFIRM_INVITATION_COMMIT"),
        VALIDATE_INVITATION_READ("VALIDATE_INVITATION_READ"),
        REDEEM_INVITATION_TRANSACTION("REDEEM_INVITATION_TRANSACTION"),
        REVOKE_INVITATION_WRITE("REVOKE_INVITATION_WRITE")
    }

    /**
     * Distinct error categories for invitation creation, validation, and redemption.
     */
    enum class InvitationFailureCategory {
        PERMISSION_DENIED,
        UNAVAILABLE,
        NOT_FOUND,
        UNAUTHORIZED_ROLE,
        INVALID_FORMAT,
        EXPIRED,
        ALREADY_REDEEMED,
        REVOKED,
        ALREADY_MEMBER,
        UNEXPECTED_EXCEPTION
    }

    data class ClassifiedInvitationFailure(
        val category: InvitationFailureCategory,
        val errorCode: String,
        val stage: InvitationStage,
        val databaseId: String,
        val sanitizedPath: String,
        val sanitizedMessage: String,
        val causeSummary: String,
        val messageHe: String,
        val messageEn: String
    )

    class InvitationFirestoreException(
        val classified: ClassifiedInvitationFailure,
        cause: Throwable? = null
    ) : Exception(classified.messageHe, cause)

    /**
     * Redacts sensitive tokens, emails, and the specific invitation code from diagnostic messages
     * so full invitation codes and credentials are never written to logs.
     */
    fun sanitizeInvitationDiagnosticMessage(
        rawMessage: String?,
        rawInviteCode: String? = null
    ): String {
        var sanitized = sanitizeFirestoreDiagnosticMessage(rawMessage)
        val cleanCode = rawInviteCode?.trim()?.uppercase()
        if (!cleanCode.isNullOrBlank() && cleanCode.length >= 4) {
            sanitized = sanitized.replace(cleanCode, "[REDACTED_INVITE_CODE]", ignoreCase = true)
        }
        return sanitized
    }

    fun logInvitationMilestone(
        stage: InvitationStage,
        path: String,
        databaseId: String = NAMED_DATABASE_ID,
        rawInviteCode: String? = null,
        category: String = "OK",
        note: String = ""
    ) {
        val safePath = sanitizeFirestorePath(path)
        val safeNote = sanitizeInvitationDiagnosticMessage(note, rawInviteCode)
        val payload = JSONObject().apply {
            put("event", "INVITATION_DIAGNOSTIC")
            put("stage", stage.stageName)
            put("category", category)
            put("databaseId", databaseId)
            put("documentPath", safePath)
            if (note.isNotBlank()) {
                put("note", safeNote)
            }
        }
        Log.i(TAG, payload.toString())
    }

    fun classifyInvitationFailure(
        error: Throwable,
        stage: InvitationStage,
        path: String = "family_invites/{inviteCode}",
        rawInviteCode: String? = null,
        databaseId: String = NAMED_DATABASE_ID,
        overrideCategory: InvitationFailureCategory? = null
    ): ClassifiedInvitationFailure {
        if (error is InvitationFirestoreException) {
            return error.classified
        }

        val (rawCat, code) = classifyFirestoreException(error)
        val category = overrideCategory ?: when {
            error is SecurityException -> InvitationFailureCategory.PERMISSION_DENIED
            rawCat == FirestoreFailureCategory.PERMISSION_DENIED -> InvitationFailureCategory.PERMISSION_DENIED
            rawCat == FirestoreFailureCategory.UNAVAILABLE -> InvitationFailureCategory.UNAVAILABLE
            rawCat == FirestoreFailureCategory.NOT_FOUND -> InvitationFailureCategory.NOT_FOUND
            else -> InvitationFailureCategory.UNEXPECTED_EXCEPTION
        }

        val safePath = sanitizeFirestorePath(path)
        val safeMsg = sanitizeInvitationDiagnosticMessage(error.message, rawInviteCode)
        val safeCause = error.cause?.let {
            "${it.javaClass.simpleName}: ${sanitizeInvitationDiagnosticMessage(it.message, rawInviteCode)}"
        } ?: "none"

        val (messageHe, messageEn) = when (category) {
            InvitationFailureCategory.UNAUTHORIZED_ROLE -> Pair(
                "רק הורה (מנהל משפחה) רשאי ליצור קודי הזמנה למשפחה.",
                "Only a PARENT is authorized to create family invitations."
            )
            InvitationFailureCategory.PERMISSION_DENIED -> Pair(
                when (stage) {
                    InvitationStage.VERIFY_PARENT_AUTHORIZATION,
                    InvitationStage.CREATE_INVITATION_WRITE,
                    InvitationStage.CONFIRM_INVITATION_COMMIT ->
                        "יצירת קוד ההזמנה נדחתה בענן (PERMISSION_DENIED). ודאו שאתם מחוברים כהורה במשפחה ונסו שוב."
                    InvitationStage.VALIDATE_INVITATION_READ,
                    InvitationStage.REDEEM_INVITATION_TRANSACTION ->
                        "קוד ההזמנה אינו זמין למימוש (ייתכן שכבר נוצל או בוטל)."
                    InvitationStage.REVOKE_INVITATION_WRITE ->
                        "ביטול קוד ההזמנה נדחה (PERMISSION_DENIED)."
                },
                "Invitation operation was denied by Firestore Security Rules (PERMISSION_DENIED at ${stage.stageName})."
            )
            InvitationFailureCategory.UNAVAILABLE -> Pair(
                when (stage) {
                    InvitationStage.VERIFY_PARENT_AUTHORIZATION,
                    InvitationStage.CREATE_INVITATION_WRITE,
                    InvitationStage.CONFIRM_INVITATION_COMMIT ->
                        "שרתי הענן אינם זמינים כרגע (UNAVAILABLE). קוד ההזמנה לא נשמר בענן — בדקו את החיבור לאינטרנט ונסו שוב."
                    else ->
                        "שרתי הענן אינם זמינים כרגע (UNAVAILABLE). בדקו את החיבור לאינטרנט ונסו שוב."
                },
                "Cloud servers are temporarily unavailable (UNAVAILABLE at ${stage.stageName}). Check your internet connection and retry."
            )
            InvitationFailureCategory.NOT_FOUND -> Pair(
                when (stage) {
                    InvitationStage.CONFIRM_INVITATION_COMMIT ->
                        "שמירת קוד ההזמנה בענן לא אומתה (NOT_FOUND). הקוד לא נשמר — נסו שוב."
                    else ->
                        "קוד ההזמנה לא נמצא במסד הנתונים בענן. ודאו שהקוד הוקלד נכון."
                },
                "Invitation document was not found in Firestore (NOT_FOUND at ${stage.stageName})."
            )
            InvitationFailureCategory.INVALID_FORMAT -> Pair(
                "נא להזין קוד הזמנה חוקי (6 תווים).",
                "Please enter a valid 6-character invitation code."
            )
            InvitationFailureCategory.EXPIRED -> Pair(
                "פג תוקפו של קוד ההזמנה. בקשו מההורה ליצור קוד הזמנה חדש.",
                "This invitation code has expired."
            )
            InvitationFailureCategory.ALREADY_REDEEMED -> Pair(
                "קוד הזמנה זה כבר נוצל ולא ניתן להשתמש בו שוב.",
                "This invitation code has already been redeemed."
            )
            InvitationFailureCategory.REVOKED -> Pair(
                "קוד הזמנה זה בוטל על ידי הורה במשפחה.",
                "This invitation code was revoked by a parent."
            )
            InvitationFailureCategory.ALREADY_MEMBER -> Pair(
                "אתם כבר רשומים כחברים במשפחה זו!",
                "You are already an active member of this family."
            )
            InvitationFailureCategory.UNEXPECTED_EXCEPTION -> Pair(
                "שגיאה בשמירה או אימות קוד ההזמנה בענן ($code). נסו שוב.",
                "An unexpected error occurred during invitation processing ($code at ${stage.stageName})."
            )
        }

        val payload = JSONObject().apply {
            put("event", "INVITATION_FIRESTORE_FAILURE")
            put("stage", stage.stageName)
            put("category", category.name)
            put("errorCode", code)
            put("databaseId", databaseId)
            put("documentPath", safePath)
            put("message", safeMsg)
            put("cause", safeCause)
        }
        Log.e(TAG, payload.toString())

        return ClassifiedInvitationFailure(
            category = category,
            errorCode = code,
            stage = stage,
            databaseId = databaseId,
            sanitizedPath = safePath,
            sanitizedMessage = safeMsg,
            causeSummary = safeCause,
            messageHe = messageHe,
            messageEn = messageEn
        )
    }
}

fun logStartupFirestoreDiagnostic(
    stage: StartupDiagnosticStage,
    databaseId: String,
    path: String?,
    exception: Throwable? = null,
    overrideCategory: FirestoreFailureCategory? = null,
    additionalNote: String? = null
): FirestoreDiagnosticInfo {
    val (inferredCategory, errorCode) = if (exception != null) {
        classifyFirestoreException(exception)
    } else {
        (overrideCategory ?: FirestoreFailureCategory.LEGACY_PROFILE) to (overrideCategory?.name ?: "OK")
    }
    val finalCategory = overrideCategory ?: inferredCategory
    val safePath = sanitizeFirestorePath(path)
    val safeMsg = buildString {
        if (!additionalNote.isNullOrBlank()) {
            append(sanitizeFirestoreDiagnosticMessage(additionalNote))
        }
        if (exception != null) {
            if (isNotEmpty()) append(" | ")
            append("${exception.javaClass.simpleName}: ${sanitizeFirestoreDiagnosticMessage(exception.message)}")
        }
        if (isEmpty()) {
            append(finalCategory.name)
        }
    }
    val causeSummary = exception?.cause?.let { cause ->
        "${cause.javaClass.simpleName}(${ (cause as? FirebaseFirestoreException)?.code?.name ?: "CAUSE" }): " +
            sanitizeFirestoreDiagnosticMessage(cause.message)
    } ?: "none"

    val info = FirestoreDiagnosticInfo(
        stage = stage,
        category = finalCategory,
        firestoreErrorCode = errorCode,
        databaseId = databaseId,
        sanitizedPath = safePath,
        sanitizedMessage = safeMsg,
        causeSummary = causeSummary
    )

    val logLine = "[STARTUP_DIAGNOSTIC] stage=${info.stage.code} | category=${info.category} | " +
        "code=${info.firestoreErrorCode} | db=${info.databaseId} | path=${info.sanitizedPath} | " +
        "msg=${info.sanitizedMessage} | cause=${info.causeSummary}"

    if (exception != null || finalCategory == FirestoreFailureCategory.PERMISSION_DENIED || finalCategory == FirestoreFailureCategory.UNEXPECTED_EXCEPTION) {
        Log.e("FirestoreDiagnostic", logLine)
    } else {
        Log.i("FirestoreDiagnostic", logLine)
    }

    return info
}

fun handleFirestoreError(
    exception: Exception,
    operationType: OperationType,
    path: String?,
    databaseId: String = FirestoreErrorHandler.NAMED_DATABASE_ID,
    startupStage: StartupDiagnosticStage? = null
): String {
    val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
    val currentUser = auth?.currentUser
    val (category, codeName) = classifyFirestoreException(exception)
    val safePath = sanitizeFirestorePath(path)
    val safeErrorMsg = sanitizeFirestoreDiagnosticMessage(exception.message ?: exception.toString())
    val safeCause = exception.cause?.let {
        "${it.javaClass.simpleName}: ${sanitizeFirestoreDiagnosticMessage(it.message)}"
    } ?: "none"

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("authenticated", currentUser != null)
        put("emailVerified", currentUser?.isEmailVerified ?: false)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("category", category.name)
        put("errorCode", codeName)
        put("error", safeErrorMsg)
        put("cause", safeCause)
        put("databaseId", databaseId)
        put("operationType", operationType.value)
        put("path", safePath)
        if (startupStage != null) {
            put("startupStage", startupStage.code)
        }
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}
