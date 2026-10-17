package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task as GmsTask
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Categorized failure reasons for Google Sign-In and Firebase Authentication.
 */
enum class AuthFailureReason {
    CANCELLED,
    NO_CREDENTIAL,
    CONFIGURATION_ERROR,
    CREDENTIAL_MANAGER_ERROR,
    FIREBASE_AUTH_ERROR,
    NETWORK_ERROR,
    UNKNOWN_ERROR
}

/**
 * Structured, user-safe exception carrying localized Hebrew and English diagnostic messages
 * without exposing raw tokens or sensitive credentials.
 */
class GoogleSignInAccountException(
    val reason: AuthFailureReason,
    val userMessageHebrew: String,
    val userMessageEnglish: String,
    val diagnosticCode: String? = null,
    cause: Throwable? = null
) : Exception(userMessageEnglish, cause) {

    fun getLocalizedUserMessage(isHebrew: Boolean): String {
        val base = if (isHebrew) userMessageHebrew else userMessageEnglish
        return if (!diagnosticCode.isNullOrBlank() && !base.contains(diagnosticCode)) {
            "$base [$diagnosticCode]"
        } else {
            base
        }
    }
}

/**
 * Sealed hierarchy representing the authentication state of the user.
 */
sealed class AuthState {
    /**
     * No user is currently authenticated with Firebase.
     */
    data object Unauthenticated : AuthState()

    /**
     * An authentication operation is in progress (e.g. Google Sign-In prompt or credential verification).
     */
    data object Loading : AuthState()

    /**
     * A user is successfully signed into Firebase.
     *
     * @property user The active [FirebaseUser] session.
     */
    data class Authenticated(val user: FirebaseUser) : AuthState() {
        val uid: String get() = user.uid
        val email: String? get() = user.email
        val displayName: String? get() = user.displayName
        val photoUrl: String? get() = user.photoUrl?.toString()
    }

    /**
     * An authentication attempt failed.
     *
     * @property message Descriptive error message.
     * @property cause The underlying exception if available.
     * @property reason Categorized failure reason for UI diagnostics.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val reason: AuthFailureReason = AuthFailureReason.UNKNOWN_ERROR
    ) : AuthState()
}

/**
 * Unwraps [ContextWrapper] layers to locate the hosting [Activity], which is required by
 * Jetpack [CredentialManager] to display the Google account chooser bottom sheet on physical devices.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}

/**
 * Redacts JWTs, ID tokens, and OAuth access tokens from technical error strings before logging to Logcat.
 */
internal fun sanitizeLogMessage(message: String?): String {
    if (message.isNullOrBlank()) return "No message"
    return message
        .replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[REDACTED_JWT]")
        .replace(Regex("ya29\\.[A-Za-z0-9_-]+"), "[REDACTED_ACCESS_TOKEN]")
        .replace(Regex("(?i)(id_token|access_token|token|secret)=([^&\\s]+)"), "$1=[REDACTED]")
}

/**
 * Firebase Authentication manager that handles Google sign-in using Jetpack Credential Manager
 * and exposes reactive state objects to track the logged-in user across the application.
 */
class FirebaseAuthManager(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val tag = "FirebaseAuthManager"

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    /**
     * StateFlow exposing the currently logged-in [FirebaseUser], or null if unauthenticated.
     */
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(
        auth.currentUser?.let { AuthState.Authenticated(it) } ?: AuthState.Unauthenticated
    )
    /**
     * StateFlow exposing the current high-level [AuthState] (Unauthenticated, Loading, Authenticated, Error).
     */
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Listener keeping [_currentUser] and [_authState] synchronized with Firebase session changes in real-time.
     */
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _currentUser.value = user
        _authState.value = if (user != null) {
            AuthState.Authenticated(user)
        } else {
            AuthState.Unauthenticated
        }
    }

    init {
        auth.addAuthStateListener(authStateListener)
    }

    /**
     * Whether a user is currently authenticated.
     */
    val isAuthenticated: Boolean
        get() = auth.currentUser != null

    /**
     * Returns the UID of the currently signed-in user, or null if unauthenticated.
     */
    val currentUserId: String?
        get() = auth.currentUser?.uid

    /**
     * Resolves and validates the OAuth 2.0 Web Client ID (`client_type: 3` from `google-services.json`).
     * Never falls back to a fake placeholder so configuration issues are caught immediately.
     */
    fun resolveWebClientId(context: Context, overrideClientId: String? = null): Result<String> {
        val candidate = overrideClientId?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { context.getString(com.example.R.string.default_web_client_id).trim() }.getOrNull()

        if (candidate.isNullOrBlank() || candidate == "mock_client_id" || !candidate.endsWith(".apps.googleusercontent.com")) {
            val ex = GoogleSignInAccountException(
                reason = AuthFailureReason.CONFIGURATION_ERROR,
                userMessageHebrew = "הגדרת Google Sign-In חסרה או לא תקינה (default_web_client_id). ודא שקובץ google-services.json תקין.",
                userMessageEnglish = "Google Sign-In configuration missing or invalid (default_web_client_id). Verify google-services.json.",
                diagnosticCode = "INVALID_WEB_CLIENT_ID"
            )
            Log.e(tag, "Web OAuth Client ID validation failed. Ensure default_web_client_id from google-services.json (client_type 3) is present.")
            return Result.failure(ex)
        }
        return Result.success(candidate)
    }

    /**
     * Builds the explicit interactive [GetSignInWithGoogleOption] for the "Sign in with Google" button.
     */
    fun buildSignInWithGoogleOption(serverClientId: String): GetSignInWithGoogleOption {
        return GetSignInWithGoogleOption.Builder(serverClientId = serverClientId).build()
    }

    /**
     * Builds [GetGoogleIdOption] with the verified Web OAuth Client ID.
     */
    fun buildGoogleIdOption(
        serverClientId: String,
        filterByAuthorizedAccounts: Boolean = false,
        autoSelectEnabled: Boolean = false
    ): GetGoogleIdOption {
        return GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(autoSelectEnabled)
            .build()
    }

    /**
     * Initiates interactive Google Sign-In via Jetpack Credential Manager and authenticates
     * with Firebase Authentication using the retrieved Google ID token.
     *
     * Uses [GetSignInWithGoogleOption] as the primary interactive account chooser flow, with a
     * single-option fallback to [GetGoogleIdOption] (`filterByAuthorizedAccounts = false`) if
     * needed on older Play Services versions.
     *
     * @param context Context (preferably Activity) used to invoke CredentialManager UI.
     * @param serverClientId Optional OAuth web client ID override. If null, resolves R.string.default_web_client_id.
     * @return Result containing the authenticated [FirebaseUser] on success, or a [GoogleSignInAccountException] on failure.
     */
    suspend fun signInWithGoogle(
        context: Context,
        serverClientId: String? = null
    ): Result<FirebaseUser> {
        _authState.value = AuthState.Loading
        return try {
            val resolvedClientId = resolveWebClientId(context, serverClientId).getOrElse { configErr ->
                val mapped = classifyAuthException(configErr)
                _authState.value = AuthState.Error(mapped.userMessageEnglish, mapped, mapped.reason)
                return Result.failure(mapped)
            }

            val activity = context.findActivity()
            if (activity == null) {
                Log.w(
                    tag,
                    "signInWithGoogle invoked with non-Activity context (${context.javaClass.simpleName}). " +
                        "CredentialManager requires an Activity context on physical devices to display the account picker."
                )
            }
            val uiContext: Context = activity ?: context

            val credentialManager = CredentialManager.create(uiContext)
            val credential = requestGoogleCredential(
                credentialManager = credentialManager,
                uiContext = uiContext,
                serverClientId = resolvedClientId
            )

            val idToken = extractGoogleIdToken(credential)
            val authCredential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult: AuthResult = auth.signInWithCredential(authCredential).await()
            val signedInUser = authResult.user
                ?: throw GoogleSignInAccountException(
                    reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                    userMessageHebrew = "ההתחברות ל-Firebase הושלמה אך לא התקבל פרופיל משתמש.",
                    userMessageEnglish = "Firebase sign-in completed without a user profile.",
                    diagnosticCode = "NULL_FIREBASE_USER"
                )

            Log.i(tag, "Google Sign-In and Firebase Authentication succeeded for uid=${signedInUser.uid}")
            _currentUser.value = signedInUser
            _authState.value = AuthState.Authenticated(signedInUser)
            Result.success(signedInUser)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val classified = classifyAuthException(e)
            if (classified.reason == AuthFailureReason.CANCELLED) {
                val priorUser = auth.currentUser
                _authState.value = if (priorUser != null) AuthState.Authenticated(priorUser) else AuthState.Unauthenticated
            } else {
                _authState.value = AuthState.Error(classified.userMessageEnglish, classified, classified.reason)
            }
            Result.failure(classified)
        }
    }

    /**
     * Requests a Google credential from [CredentialManager] using a single option per request:
     * 1. First attempts [GetSignInWithGoogleOption] (explicit interactive button account picker).
     * 2. If [NoCredentialException] or [GetCredentialUnsupportedException] is thrown, falls back to
     *    a separate single-option [GetGoogleIdOption] (`filterByAuthorizedAccounts = false`).
     */
    private suspend fun requestGoogleCredential(
        credentialManager: CredentialManager,
        uiContext: Context,
        serverClientId: String
    ): Credential {
        val interactiveSignInRequest = GetCredentialRequest.Builder()
            .addCredentialOption(buildSignInWithGoogleOption(serverClientId))
            .build()

        return try {
            Log.d(tag, "Launching CredentialManager with GetSignInWithGoogleOption")
            credentialManager.getCredential(context = uiContext, request = interactiveSignInRequest).credential
        } catch (firstErr: NoCredentialException) {
            Log.w(
                tag,
                "GetSignInWithGoogleOption returned NoCredentialException (${sanitizeLogMessage(firstErr.message)}); " +
                    "retrying with GetGoogleIdOption(filterByAuthorizedAccounts=false)"
            )
            val googleIdRequest = GetCredentialRequest.Builder()
                .addCredentialOption(
                    buildGoogleIdOption(
                        serverClientId = serverClientId,
                        filterByAuthorizedAccounts = false,
                        autoSelectEnabled = false
                    )
                )
                .build()
            credentialManager.getCredential(context = uiContext, request = googleIdRequest).credential
        } catch (unsupportedErr: GetCredentialUnsupportedException) {
            Log.w(
                tag,
                "GetSignInWithGoogleOption unsupported (${sanitizeLogMessage(unsupportedErr.message)}); " +
                    "retrying with GetGoogleIdOption(filterByAuthorizedAccounts=false)"
            )
            val googleIdRequest = GetCredentialRequest.Builder()
                .addCredentialOption(
                    buildGoogleIdOption(
                        serverClientId = serverClientId,
                        filterByAuthorizedAccounts = false,
                        autoSelectEnabled = false
                    )
                )
                .build()
            credentialManager.getCredential(context = uiContext, request = googleIdRequest).credential
        }
    }

    /**
     * Extracts and validates the Google ID token from a [Credential] returned by Credential Manager.
     */
    internal fun extractGoogleIdToken(credential: Credential): String {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                if (idToken.isBlank()) {
                    throw GoogleSignInAccountException(
                        reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                        userMessageHebrew = "אסימון הזיהוי (ID Token) שהתקבל מחשבון Google ריק.",
                        userMessageEnglish = "Received an empty Google ID token.",
                        diagnosticCode = "EMPTY_ID_TOKEN"
                    )
                }
                return idToken
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(tag, "Failed to parse GoogleIdTokenCredential: ${sanitizeLogMessage(e.message)}", e)
                throw GoogleSignInAccountException(
                    reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                    userMessageHebrew = "שגיאה בפענוח פרטי ההתחברות מ-Google. נסה שוב.",
                    userMessageEnglish = "Failed to parse Google ID token credential. Please try again.",
                    diagnosticCode = "INVALID_GOOGLE_ID_TOKEN",
                    cause = e
                )
            }
        } else {
            val safeType = sanitizeLogMessage(credential.type)
            Log.e(tag, "Unsupported credential type returned from CredentialManager: $safeType")
            throw GoogleSignInAccountException(
                reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                userMessageHebrew = "סוג פרטי ההתחברות אינו נתמך ($safeType).",
                userMessageEnglish = "Unsupported credential type ($safeType).",
                diagnosticCode = "UNSUPPORTED_CREDENTIAL_TYPE"
            )
        }
    }

    /**
     * Maps raw Credential Manager, Google Play Services, and Firebase exceptions into
     * distinct [GoogleSignInAccountException] instances with safe Logcat logging.
     */
    fun classifyAuthException(e: Throwable): GoogleSignInAccountException {
        if (e is GoogleSignInAccountException) return e

        val sanitizedMsg = sanitizeLogMessage(e.message)
        val isDeveloperConfigMismatch = sanitizedMsg.contains("28444") ||
            sanitizedMsg.contains("DEVELOPER_ERROR", ignoreCase = true) ||
            sanitizedMsg.contains("Developer console is not set up correctly", ignoreCase = true) ||
            sanitizedMsg.contains("10:") ||
            sanitizedMsg.contains("caller is not authorized", ignoreCase = true)

        return when (e) {
            is GetCredentialCancellationException -> {
                if (isDeveloperConfigMismatch) {
                    Log.e(
                        tag,
                        "Google Sign-In rejected due to OAuth/SHA-1 developer configuration mismatch " +
                            "(type=${e.type}): $sanitizedMsg",
                        e
                    )
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CONFIGURATION_ERROR,
                        userMessageHebrew = "שגיאת הגדרת OAuth / SHA-1 מול Firebase: חתימת ה-APK אינה רשומה בפרויקט Firebase.",
                        userMessageEnglish = "Google OAuth / SHA-1 configuration mismatch: APK signing certificate is not registered in Firebase.",
                        diagnosticCode = "OAUTH_SHA1_MISMATCH",
                        cause = e
                    )
                } else {
                    Log.w(tag, "Google Sign-In cancelled by user (type=${e.type}): $sanitizedMsg", e)
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CANCELLED,
                        userMessageHebrew = "בחירת חשבון Google בוטלה. לחץ על 'התחבר עם Google' כדי לבחור חשבון.",
                        userMessageEnglish = "Google account selection was cancelled. Tap 'Sign in with Google' to choose an account.",
                        diagnosticCode = "USER_CANCELLED",
                        cause = e
                    )
                }
            }

            is NoCredentialException -> {
                Log.w(
                    tag,
                    "NoCredentialException during Google Sign-In (type=${e.type}): $sanitizedMsg. " +
                        "Verify a Google account is signed in on the device and APK SHA-1/SHA-256 is registered in Firebase.",
                    e
                )
                GoogleSignInAccountException(
                    reason = AuthFailureReason.NO_CREDENTIAL,
                    userMessageHebrew = "לא נמצא חשבון Google זמין במכשיר או שחתימת ה-APK (SHA-1) אינה תואמת ל-Firebase. ודא שקיים חשבון Google בהגדרות המכשיר.",
                    userMessageEnglish = "No Google account found on device, or APK signing key (SHA-1) is not registered in Firebase. Check device Google accounts.",
                    diagnosticCode = "NO_CREDENTIAL",
                    cause = e
                )
            }

            is GetCredentialProviderConfigurationException,
            is GetCredentialUnsupportedException -> {
                val errType = (e as? GetCredentialException)?.type ?: e.javaClass.simpleName
                Log.e(tag, "Credential provider configuration/support error ($errType): $sanitizedMsg", e)
                GoogleSignInAccountException(
                    reason = AuthFailureReason.CONFIGURATION_ERROR,
                    userMessageHebrew = "שירות Google Play Services לחיבור חשבון אינו זמין או דורש עדכון במכשיר.",
                    userMessageEnglish = "Google Play Services credential provider is unavailable or needs updating on this device.",
                    diagnosticCode = errType.substringAfterLast('.'),
                    cause = e
                )
            }

            is GetCredentialException -> {
                val shortType = e.type.substringAfterLast('.')
                if (isDeveloperConfigMismatch) {
                    Log.e(
                        tag,
                        "GetCredentialException indicates SHA-1 / OAuth client mismatch (type=${e.type}): $sanitizedMsg",
                        e
                    )
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CONFIGURATION_ERROR,
                        userMessageHebrew = "חתימת ה-APK (SHA-1) או מזהה OAuth אינם תואמים להגדרות Firebase.",
                        userMessageEnglish = "APK signing certificate (SHA-1) or OAuth client ID does not match Firebase configuration.",
                        diagnosticCode = "SHA1_OR_CLIENT_MISMATCH",
                        cause = e
                    )
                } else if (sanitizedMsg.contains("network", ignoreCase = true) ||
                    sanitizedMsg.contains("7:", ignoreCase = true)
                ) {
                    Log.e(tag, "GetCredentialException network failure (type=${e.type}): $sanitizedMsg", e)
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.NETWORK_ERROR,
                        userMessageHebrew = "שגיאת תקשורת בעת פתיחת בחירת חשבון Google. בדוק חיבור לאינטרנט.",
                        userMessageEnglish = "Network error while communicating with Google Identity Services. Check internet connection.",
                        diagnosticCode = "CREDENTIAL_NETWORK_ERROR",
                        cause = e
                    )
                } else {
                    Log.e(tag, "GetCredentialException during Google Sign-In (type=${e.type}): $sanitizedMsg", e)
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                        userMessageHebrew = "שגיאה בפתיחת חלון בחירת חשבון Google ($shortType). נסה שוב.",
                        userMessageEnglish = "Failed to launch Google account selector ($shortType). Please try again.",
                        diagnosticCode = shortType,
                        cause = e
                    )
                }
            }

            is FirebaseAuthException -> {
                val code = e.errorCode
                Log.e(tag, "FirebaseAuthException during Google Sign-In (errorCode=$code): $sanitizedMsg", e)
                when (code) {
                    "ERROR_OPERATION_NOT_ALLOWED" -> GoogleSignInAccountException(
                        reason = AuthFailureReason.CONFIGURATION_ERROR,
                        userMessageHebrew = "ספק ההתחברות של Google אינו מופעל ב-Firebase Authentication. יש להפעיל את Google ב-Firebase Console.",
                        userMessageEnglish = "Google sign-in provider is not enabled in Firebase Authentication. Enable Google in Firebase Console.",
                        diagnosticCode = code,
                        cause = e
                    )
                    "ERROR_USER_DISABLED" -> GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "חשבון המשתמש הושבת ב-Firebase Authentication.",
                        userMessageEnglish = "This user account has been disabled in Firebase Authentication.",
                        diagnosticCode = code,
                        cause = e
                    )
                    "ERROR_INVALID_CREDENTIAL",
                    "ERROR_CREDENTIAL_ALREADY_IN_USE" -> GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "פרטי ההתחברות של Google נדחו על ידי Firebase ($code). נסה לבחור חשבון שוב.",
                        userMessageEnglish = "Google credential was rejected by Firebase ($code). Please try signing in again.",
                        diagnosticCode = code,
                        cause = e
                    )
                    else -> GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "שגיאת אימות מול שרת Firebase ($code).",
                        userMessageEnglish = "Firebase authentication error ($code).",
                        diagnosticCode = code,
                        cause = e
                    )
                }
            }

            is FirebaseNetworkException -> {
                Log.e(tag, "FirebaseNetworkException during Google Sign-In: $sanitizedMsg", e)
                GoogleSignInAccountException(
                    reason = AuthFailureReason.NETWORK_ERROR,
                    userMessageHebrew = "שגיאת רשת מול Firebase. בדוק את החיבור לאינטרנט ונסה שוב.",
                    userMessageEnglish = "Network error communicating with Firebase. Check your internet connection and try again.",
                    diagnosticCode = "FIREBASE_NETWORK_ERROR",
                    cause = e
                )
            }

            is FirebaseException -> {
                Log.e(tag, "FirebaseException during Google Sign-In: $sanitizedMsg", e)
                val isConfig = sanitizedMsg.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) ||
                    sanitizedMsg.contains("API key not valid", ignoreCase = true) ||
                    sanitizedMsg.contains("blocked", ignoreCase = true)
                if (isConfig) {
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CONFIGURATION_ERROR,
                        userMessageHebrew = "הגדרת ספק Google ב-Firebase Authentication חסרה או חסומה.",
                        userMessageEnglish = "Firebase Authentication Google provider configuration is missing or blocked.",
                        diagnosticCode = "FIREBASE_CONFIG_ERROR",
                        cause = e
                    )
                } else {
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "שגיאת Firebase בעת ההתחברות עם Google. נסה שוב.",
                        userMessageEnglish = "Firebase error during Google Sign-In. Please try again.",
                        diagnosticCode = "FIREBASE_ERROR",
                        cause = e
                    )
                }
            }

            else -> {
                Log.e(tag, "Unexpected exception during Google Sign-In (${e.javaClass.simpleName}): $sanitizedMsg", e)
                GoogleSignInAccountException(
                    reason = AuthFailureReason.UNKNOWN_ERROR,
                    userMessageHebrew = "שגיאה בהתחברות עם Google (${e.javaClass.simpleName}). בדוק חיבור ונסה שוב.",
                    userMessageEnglish = "Google Sign-In error (${e.javaClass.simpleName}). Please try again.",
                    diagnosticCode = e.javaClass.simpleName,
                    cause = e
                )
            }
        }
    }

    /**
     * Signs out the current Firebase user and clears Credential Manager session state when Context is provided.
     */
    fun signOut(context: Context? = null) {
        auth.signOut()
        _currentUser.value = null
        _authState.value = AuthState.Unauthenticated
        if (context != null) {
            runCatching {
                val cm = CredentialManager.create(context)
                cm.clearCredentialStateAsync(
                    ClearCredentialStateRequest(),
                    null,
                    { it.run() },
                    object : androidx.credentials.CredentialManagerCallback<Void?, androidx.credentials.exceptions.ClearCredentialException> {
                        override fun onResult(result: Void?) {
                            Log.d(tag, "CredentialManager state cleared on signOut")
                        }

                        override fun onError(e: androidx.credentials.exceptions.ClearCredentialException) {
                            Log.w(tag, "Failed to clear CredentialManager state: ${sanitizeLogMessage(e.message)}")
                        }
                    }
                )
            }
        }
    }

    /**
     * Cleans up listeners when no longer needed.
     */
    fun cleanup() {
        auth.removeAuthStateListener(authStateListener)
    }

    companion object {
        @Volatile
        private var instance: FirebaseAuthManager? = null

        /**
         * Singleton accessor for application-wide authentication management.
         */
        fun getInstance(firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()): FirebaseAuthManager {
            return instance ?: synchronized(this) {
                instance ?: FirebaseAuthManager(firebaseAuth).also { instance = it }
            }
        }
    }
}

/**
 * Extension function converting Google Play Services Tasks to Kotlin Coroutines.
 */
private suspend fun <T> GmsTask<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { exception ->
        if (continuation.isActive) continuation.resumeWithException(exception)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel()
    }
}
