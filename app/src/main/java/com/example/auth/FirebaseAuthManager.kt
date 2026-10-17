package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
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
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Distinct execution stages of the Google Sign-In + Firebase Authentication pipeline.
 * Displayed in UI error banners and Logcat diagnostics so failures can be pinpointed without ADB.
 */
enum class AuthStage(
    val code: String,
    val descriptionHebrew: String,
    val descriptionEnglish: String
) {
    CONFIG_VERIFICATION(
        code = "STAGE_1_CONFIG",
        descriptionHebrew = "בדיקת הגדרות OAuth ו-SHA-1",
        descriptionEnglish = "OAuth & SHA-1 Config Verification"
    ),
    CREDENTIAL_REQUEST(
        code = "STAGE_2_CREDENTIAL_REQUEST",
        descriptionHebrew = "בקשת חשבון מ-Credential Manager",
        descriptionEnglish = "Credential Manager Account Selection"
    ),
    CREDENTIAL_PARSE(
        code = "STAGE_3_CREDENTIAL_PARSE",
        descriptionHebrew = "פענוח אסימון Google ID",
        descriptionEnglish = "Google ID Token Parsing"
    ),
    FIREBASE_AUTH(
        code = "STAGE_4_FIREBASE_AUTH",
        descriptionHebrew = "אימות מול שרת Firebase",
        descriptionEnglish = "Firebase Sign-In with Credential"
    )
}

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
 * Result of comparing the installed APK's runtime signing certificate and package name
 * against known project certificates and Firebase Android app configuration.
 */
data class ApkCertificateVerificationResult(
    val packageName: String,
    val expectedPackageName: String,
    val actualSha1Hex: String?,
    val actualSha256Hex: String?,
    val isPackageMatch: Boolean,
    val isRecognizedCert: Boolean,
    val hasPackageMismatch: Boolean
) {
    val shortActualSha1: String
        get() = actualSha1Hex?.take(8) ?: "unknown"
}

/**
 * Structured, user-safe exception carrying localized Hebrew and English diagnostic messages,
 * stage, and concise diagnostic code without exposing raw tokens or personal account details.
 */
class GoogleSignInAccountException(
    val reason: AuthFailureReason,
    val userMessageHebrew: String,
    val userMessageEnglish: String,
    val diagnosticCode: String? = null,
    val stage: AuthStage = AuthStage.CREDENTIAL_REQUEST,
    cause: Throwable? = null
) : Exception(userMessageEnglish, cause) {

    fun getLocalizedUserMessage(isHebrew: Boolean): String {
        val base = if (isHebrew) userMessageHebrew else userMessageEnglish
        val codePart = diagnosticCode?.takeIf { it.isNotBlank() } ?: reason.name
        val badge = "[${stage.code} | $codePart]"
        return if (base.contains(codePart) && base.contains(stage.code)) {
            base
        } else {
            "$base $badge"
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
 * Redacts JWTs, ID tokens, OAuth access tokens, secrets, passwords, and personal email addresses
 * from technical error strings before logging to Logcat.
 */
internal fun sanitizeLogMessage(message: String?): String {
    if (message.isNullOrBlank()) return "No message"
    return message
        .replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[REDACTED_JWT]")
        .replace(Regex("ya29\\.[A-Za-z0-9_.-]+"), "[REDACTED_ACCESS_TOKEN]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[REDACTED_EMAIL]")
        .replace(
            Regex("(?i)(id_token|access_token|refresh_token|token|password|passwd|secret|authorization|bearer)\\s*[:=]\\s*(?!\\[REDACTED)([^&\\s,\"]+)"),
            "$1=[REDACTED]"
        )
}

/**
 * Collects the full causal and suppressed exception chain (bounded to prevent cycles)
 * so nested `ApiException` or primary fallback exceptions are never missed.
 */
internal fun buildThrowableChain(root: Throwable): List<Throwable> {
    val visited = LinkedHashSet<Throwable>()
    val queue = ArrayDeque<Throwable>()
    queue.add(root)
    while (queue.isNotEmpty() && visited.size < 16) {
        val current = queue.removeFirst()
        if (visited.add(current)) {
            current.cause?.let { queue.add(it) }
            current.suppressed.forEach { queue.add(it) }
        }
    }
    return visited.toList()
}

/**
 * Extracts a Google Play Services `ApiException` status code from a throwable if present.
 */
internal fun extractGmsStatusCode(t: Throwable): Int? {
    runCatching {
        val method = t.javaClass.getMethod("getStatusCode")
        val result = method.invoke(t)
        if (result is Int) return result
    }
    val msg = t.message ?: return null
    val match = Regex("(?:^|\\s|\\[)(10|7|8|16|12500|12501|12502|28444|28433|28436)(?::|\\])").find(msg)
    return match?.groupValues?.getOrNull(1)?.toIntOrNull()
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

        val expectedSuffix = listOf("apps", "googleusercontent", "com").joinToString(".", prefix = ".")
        if (candidate.isNullOrBlank() ||
            candidate == "mock_client_id" ||
            !candidate.contains("-") ||
            !candidate.endsWith(expectedSuffix)
        ) {
            val ex = GoogleSignInAccountException(
                reason = AuthFailureReason.CONFIGURATION_ERROR,
                userMessageHebrew = "הגדרת Google Sign-In חסרה או לא תקינה (default_web_client_id). ודא שקובץ google-services.json תקין.",
                userMessageEnglish = "Google Sign-In configuration missing or invalid (default_web_client_id). Verify google-services.json.",
                diagnosticCode = "INVALID_WEB_CLIENT_ID",
                stage = AuthStage.CONFIG_VERIFICATION
            )
            Log.e(
                tag,
                "[STAGE:CONFIG_VERIFICATION_FAILED] Web OAuth Client ID validation failed. " +
                    "Ensure default_web_client_id from google-services.json (client_type 3) is present."
            )
            return Result.failure(ex)
        }
        return Result.success(candidate)
    }

    /**
     * Verifies the runtime APK signing certificate SHA-1 against the Android OAuth client (`client_type: 1`)
     * registered in `app/google-services.json`.
     */
    /**
     * Inspects the runtime APK signing certificate SHA-1 and SHA-256 fingerprints and package name
     * for observability and diagnostic logging without blocking or rejecting valid registered certificates.
     */
    @Suppress("DEPRECATION")
    fun verifyApkSigningCertificate(
        context: Context,
        recognizedSha1Hexes: Set<String> = KNOWN_PROJECT_SIGNING_SHA1_HEXES,
        expectedPackageName: String = EXPECTED_FIREBASE_PACKAGE_NAME,
        overrideActualSha1Hex: String? = null
    ): ApkCertificateVerificationResult {
        val pkgName = context.packageName
        val isRobolectric = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
        val normalizedRecognized = recognizedSha1Hexes.map { it.replace(":", "").lowercase() }.toSet()

        var actualSha1: String? = overrideActualSha1Hex?.replace(":", "")?.lowercase()
        var actualSha256: String? = null

        if (actualSha1 == null && !isRobolectric) {
            runCatching {
                val pm = context.packageManager
                val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signingInfo = pkgInfo.signingInfo
                    val signers = if (signingInfo != null) {
                        if (signingInfo.hasMultipleSigners()) {
                            signingInfo.apkContentsSigners
                        } else {
                            signingInfo.signingCertificateHistory
                        }
                    } else {
                        null
                    }
                    signers?.firstOrNull()?.toByteArray()
                } else {
                    val pkgInfo = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
                    pkgInfo.signatures?.firstOrNull()?.toByteArray()
                }

                if (certBytes != null && certBytes.isNotEmpty()) {
                    actualSha1 = MessageDigest.getInstance("SHA-1")
                        .digest(certBytes)
                        .joinToString("") { "%02x".format(it) }
                    actualSha256 = MessageDigest.getInstance("SHA-256")
                        .digest(certBytes)
                        .joinToString("") { "%02x".format(it) }
                }
            }.onFailure { err ->
                Log.w(tag, "[STAGE:CONFIG_VERIFICATION] Unable to read APK signing certificate: ${sanitizeLogMessage(err.message)}")
            }
        }

        val isPkgMatch = pkgName == expectedPackageName
        val isRecognized = actualSha1 == null || actualSha1 in normalizedRecognized

        return ApkCertificateVerificationResult(
            packageName = pkgName,
            expectedPackageName = expectedPackageName,
            actualSha1Hex = actualSha1,
            actualSha256Hex = actualSha256,
            isPackageMatch = isPkgMatch,
            isRecognizedCert = isRecognized,
            hasPackageMismatch = !isPkgMatch
        )
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
     * Logs each stage of the authentication pipeline without exposing tokens or personal account details:
     * 1. Credential request started
     * 2. Credential returned
     * 3. Google ID token credential parsed
     * 4. Firebase authentication started
     * 5. Firebase authentication succeeded or failed
     */
    suspend fun signInWithGoogle(
        context: Context,
        serverClientId: String? = null
    ): Result<FirebaseUser> {
        _authState.value = AuthState.Loading
        var currentStage = AuthStage.CONFIG_VERIFICATION
        var certVerification: ApkCertificateVerificationResult? = null

        return try {
            val resolvedClientId = resolveWebClientId(context, serverClientId).getOrElse { configErr ->
                val mapped = classifyAuthException(configErr, stage = AuthStage.CONFIG_VERIFICATION)
                _authState.value = AuthState.Error(mapped.userMessageEnglish, mapped, mapped.reason)
                return Result.failure(mapped)
            }

            certVerification = verifyApkSigningCertificate(context)
            if (certVerification.hasPackageMismatch) {
                Log.e(
                    tag,
                    "[STAGE:CONFIG_VERIFICATION_FAILED] APK package mismatch detected! " +
                        "pkg=${certVerification.packageName} (expected=${certVerification.expectedPackageName})"
                )
            } else {
                Log.i(
                    tag,
                    "[STAGE:CONFIG_VERIFIED] Web Client ID and APK certificate verified " +
                        "(pkg=${certVerification.packageName}, sha1Prefix=${certVerification.shortActualSha1}, isRecognizedKey=${certVerification.isRecognizedCert})"
                )
            }

            val activity = context.findActivity()
            if (activity == null) {
                Log.w(
                    tag,
                    "[STAGE:CREDENTIAL_REQUEST_WARNING] signInWithGoogle invoked with non-Activity context (${context.javaClass.simpleName}). " +
                        "CredentialManager requires an Activity context on physical devices to display the account picker."
                )
            }
            val uiContext: Context = activity ?: context

            // Stage 1: Credential request started
            currentStage = AuthStage.CREDENTIAL_REQUEST
            val requestStartMs = System.currentTimeMillis()
            Log.i(
                tag,
                "[STAGE:CREDENTIAL_REQUEST_STARTED] Requesting Google credential via CredentialManager " +
                    "(hasActivityContext=${activity != null}, isRecognizedKey=${certVerification.isRecognizedCert}, sha1Prefix=${certVerification.shortActualSha1})"
            )

            val credentialManager = CredentialManager.create(uiContext)
            val credential = requestGoogleCredential(
                credentialManager = credentialManager,
                uiContext = uiContext,
                serverClientId = resolvedClientId
            )

            // Stage 2: Credential returned
            val credentialDurationMs = System.currentTimeMillis() - requestStartMs
            Log.i(
                tag,
                "[STAGE:CREDENTIAL_RETURNED] Credential returned from CredentialManager " +
                    "(type=${sanitizeLogMessage(credential.type)}, isCustom=${credential is CustomCredential}, durationMs=$credentialDurationMs)"
            )

            // Stage 3: Google ID token credential parsed
            currentStage = AuthStage.CREDENTIAL_PARSE
            val idToken = extractGoogleIdToken(credential)
            Log.i(
                tag,
                "[STAGE:GOOGLE_ID_TOKEN_PARSED] GoogleIdTokenCredential parsed successfully " +
                    "(tokenPresent=${idToken.isNotBlank()}, tokenLength=${idToken.length})"
            )

            // Stage 4: Firebase authentication started
            currentStage = AuthStage.FIREBASE_AUTH
            Log.i(tag, "[STAGE:FIREBASE_AUTH_STARTED] Starting FirebaseAuth.signInWithCredential(GoogleAuthProvider)")
            val authCredential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult: AuthResult = auth.signInWithCredential(authCredential).await()
            val signedInUser = authResult.user
                ?: throw GoogleSignInAccountException(
                    reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                    userMessageHebrew = "ההתחברות ל-Firebase הושלמה אך לא התקבל פרופיל משתמש.",
                    userMessageEnglish = "Firebase sign-in completed without a user profile.",
                    diagnosticCode = "NULL_FIREBASE_USER",
                    stage = AuthStage.FIREBASE_AUTH
                )

            // Stage 5: Firebase authentication succeeded
            Log.i(
                tag,
                "[STAGE:FIREBASE_AUTH_SUCCEEDED] Google Sign-In and Firebase Authentication succeeded " +
                    "(uidPresent=${signedInUser.uid.isNotBlank()})"
            )
            _currentUser.value = signedInUser
            _authState.value = AuthState.Authenticated(signedInUser)
            Result.success(signedInUser)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val classified = classifyAuthException(
                e = e,
                stage = currentStage,
                certVerification = certVerification
            )
            // Stage 5 (or earlier stage): Authentication failed
            Log.e(
                tag,
                "[STAGE:FIREBASE_AUTH_FAILED] Authentication failed at stage=${classified.stage.code}, " +
                    "reason=${classified.reason}, code=${classified.diagnosticCode}, " +
                    "exception=${e.javaClass.simpleName}: ${sanitizeLogMessage(e.message)}"
            )
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
     * 2. Only if [NoCredentialException] or [GetCredentialUnsupportedException] is thrown (and it is
     *    not a developer configuration mismatch), falls back to a separate single-option
     *    [GetGoogleIdOption] (`filterByAuthorizedAccounts = false`).
     * 3. If the fallback also fails, attaches the primary exception via `addSuppressed` so the
     *    fallback never hides the original error.
     */
    internal suspend fun requestGoogleCredential(
        credentialManager: CredentialManager,
        uiContext: Context,
        serverClientId: String
    ): Credential {
        val interactiveSignInRequest = GetCredentialRequest.Builder()
            .addCredentialOption(buildSignInWithGoogleOption(serverClientId))
            .build()

        return try {
            credentialManager.getCredential(context = uiContext, request = interactiveSignInRequest).credential
        } catch (primaryErr: Throwable) {
            if (primaryErr is CancellationException) throw primaryErr

            val shouldFallback = (primaryErr is NoCredentialException || primaryErr is GetCredentialUnsupportedException) &&
                !hasDeveloperConfigIndicator(primaryErr)

            if (!shouldFallback) {
                throw primaryErr
            }

            Log.w(
                tag,
                "[STAGE:CREDENTIAL_REQUEST_FALLBACK] Primary GetSignInWithGoogleOption threw ${primaryErr.javaClass.simpleName} " +
                    "(${sanitizeLogMessage(primaryErr.message)}); retrying with GetGoogleIdOption(filterByAuthorizedAccounts=false)"
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

            try {
                credentialManager.getCredential(context = uiContext, request = googleIdRequest).credential
            } catch (fallbackErr: Throwable) {
                if (fallbackErr is CancellationException) throw fallbackErr
                if (fallbackErr !== primaryErr) {
                    fallbackErr.addSuppressed(primaryErr)
                }
                throw fallbackErr
            }
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
                        diagnosticCode = "EMPTY_ID_TOKEN",
                        stage = AuthStage.CREDENTIAL_PARSE
                    )
                }
                return idToken
            } catch (e: GoogleSignInAccountException) {
                throw e
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(
                    tag,
                    "[STAGE:GOOGLE_ID_TOKEN_PARSE_FAILED] Failed to parse GoogleIdTokenCredential: ${sanitizeLogMessage(e.message)}",
                    e
                )
                throw GoogleSignInAccountException(
                    reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                    userMessageHebrew = "שגיאה בפענוח פרטי ההתחברות מ-Google. נסה שוב.",
                    userMessageEnglish = "Failed to parse Google ID token credential. Please try again.",
                    diagnosticCode = "INVALID_GOOGLE_ID_TOKEN",
                    stage = AuthStage.CREDENTIAL_PARSE,
                    cause = e
                )
            } catch (e: Throwable) {
                Log.e(
                    tag,
                    "[STAGE:GOOGLE_ID_TOKEN_PARSE_FAILED] Unexpected error parsing GoogleIdTokenCredential: ${sanitizeLogMessage(e.message)}",
                    e
                )
                throw GoogleSignInAccountException(
                    reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                    userMessageHebrew = "שגיאה בפענוח אסימון Google. נסה שוב.",
                    userMessageEnglish = "Error parsing Google credential. Please try again.",
                    diagnosticCode = "CREDENTIAL_PARSE_ERROR",
                    stage = AuthStage.CREDENTIAL_PARSE,
                    cause = e
                )
            }
        } else {
            val safeType = sanitizeLogMessage(credential.type)
            Log.e(tag, "[STAGE:GOOGLE_ID_TOKEN_PARSE_FAILED] Unsupported credential type returned: $safeType")
            throw GoogleSignInAccountException(
                reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                userMessageHebrew = "סוג פרטי ההתחברות אינו נתמך ($safeType).",
                userMessageEnglish = "Unsupported credential type ($safeType).",
                diagnosticCode = "UNSUPPORTED_CREDENTIAL_TYPE",
                stage = AuthStage.CREDENTIAL_PARSE
            )
        }
    }

    /**
     * Inspects the full causal and suppressed exception chain for OAuth / SHA-1 / Developer Console
     * configuration error indicators (`28444`, `10:`, `12500`, `DEVELOPER_ERROR`, etc.).
     */
    internal fun hasDeveloperConfigIndicator(e: Throwable): Boolean {
        return detectDeveloperConfigDiagnosticCode(e) != null
    }

    /**
     * Returns a specific diagnostic code if any exception in the causal or suppressed chain
     * indicates an OAuth / SHA-1 / Firebase developer configuration problem, or null otherwise.
     */
    internal fun detectDeveloperConfigDiagnosticCode(e: Throwable): String? {
        val chain = buildThrowableChain(e)
        for (item in chain) {
            val statusCode = extractGmsStatusCode(item)
            if (statusCode == 28444 || statusCode == 28433 || statusCode == 28436) {
                return "OAUTH_DEVELOPER_ERROR_$statusCode"
            }
            if (statusCode == 10) {
                return "OAUTH_SHA1_MISMATCH_10"
            }
            if (statusCode == 12500) {
                return "OAUTH_SIGN_IN_FAILED_12500"
            }

            val credType = (item as? GetCredentialException)?.type.orEmpty()
            val errorMsg = (item as? GetCredentialException)?.errorMessage?.toString().orEmpty()
            val combined = sanitizeLogMessage("${item.message.orEmpty()} $errorMsg $credType")

            when {
                combined.contains("28444") -> return "OAUTH_DEVELOPER_ERROR_28444"
                combined.contains("28433") -> return "OAUTH_DEVELOPER_ERROR_28433"
                combined.contains("28436") -> return "OAUTH_DEVELOPER_ERROR_28436"
                combined.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                    combined.contains("Developer console is not set up correctly", ignoreCase = true) ||
                    Regex("(?<!\\d)10:").containsMatchIn(combined) -> return "OAUTH_SHA1_MISMATCH"
                combined.contains("12500") ||
                    combined.contains("SIGN_IN_FAILED", ignoreCase = true) -> return "OAUTH_SIGN_IN_FAILED_12500"
                combined.contains("caller is not authorized", ignoreCase = true) ||
                    combined.contains("unregistered_origin", ignoreCase = true) ||
                    combined.contains("invalid_client", ignoreCase = true) ||
                    combined.contains("unauthorized_client", ignoreCase = true) ||
                    combined.contains("certificate_hash", ignoreCase = true) ||
                    combined.contains("SHA-1", ignoreCase = true) ||
                    combined.contains("SHA1", ignoreCase = true) ||
                    combined.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) -> return "OAUTH_CONFIG_REJECTED"
            }
        }
        return null
    }

    /**
     * Maps raw Credential Manager, Google Play Services, and Firebase exceptions into
     * distinct [GoogleSignInAccountException] instances with stage and diagnostic codes.
     *
     * Does NOT assume [GetCredentialCancellationException] always represents a genuine user cancellation:
     * inspects the runtime APK SHA-1 certificate check, exception type, status code, cause chain,
     * suppressed exceptions, and Play Services `HiddenActivity` `RESULT_CANCELED` signature.
     */
    fun classifyAuthException(
        e: Throwable,
        stage: AuthStage = AuthStage.CREDENTIAL_REQUEST,
        certVerification: ApkCertificateVerificationResult? = null
    ): GoogleSignInAccountException {
        if (e is GoogleSignInAccountException) return e

        val sanitizedMsg = sanitizeLogMessage(e.message)
        val developerConfigCode = detectDeveloperConfigDiagnosticCode(e)

        // 1. Check runtime package name mismatch only (never reject valid registered certificates)
        if (certVerification != null && certVerification.hasPackageMismatch) {
            Log.e(
                tag,
                "[STAGE:${stage.code}] APK package name mismatch (APK_PACKAGE_MISMATCH) while handling " +
                    "${e.javaClass.simpleName}: $sanitizedMsg",
                e
            )
            return GoogleSignInAccountException(
                reason = AuthFailureReason.CONFIGURATION_ERROR,
                userMessageHebrew = "שגיאת הגדרת חבילה: מזהה החבילה (${certVerification.packageName}) אינו תואם ל-Firebase (${certVerification.expectedPackageName}).",
                userMessageEnglish = "Package configuration error: APK package name (${certVerification.packageName}) does not match Firebase (${certVerification.expectedPackageName}).",
                diagnosticCode = "APK_PACKAGE_MISMATCH",
                stage = stage,
                cause = e
            )
        }

        // 2. If any exception in the chain (even inside GetCredentialCancellationException) is a developer config error,
        // surface it as CONFIGURATION_ERROR, NEVER as "User canceled".
        if (developerConfigCode != null) {
            Log.e(
                tag,
                "[STAGE:${stage.code}] Developer configuration error detected ($developerConfigCode) " +
                    "in ${e.javaClass.simpleName}: $sanitizedMsg",
                e
            )
            return GoogleSignInAccountException(
                reason = AuthFailureReason.CONFIGURATION_ERROR,
                userMessageHebrew = "שגיאת הגדרת אימות Google / Firebase (OAuth / SHA-1): החשבון נדחה לאחר הבחירה עקב הגדרת OAuth או חתימת APK ב-Firebase.",
                userMessageEnglish = "Google / Firebase authentication configuration error (OAuth / SHA-1): Sign-in was rejected due to OAuth client or APK signing certificate configuration.",
                diagnosticCode = developerConfigCode,
                stage = stage,
                cause = e
            )
        }

        return when (e) {
            is GetCredentialCancellationException -> {
                // In androidx.credentials:credentials-play-services-auth, HiddenActivity maps
                // ANY Activity.RESULT_CANCELED from Google Play Services to the literal string
                // "activity is cancelled by the user." — including when Play Services aborts token
                // creation AFTER the user selects a Google account in the picker.
                val isPlayServicesResultCanceled = sanitizedMsg.contains(
                    "activity is cancelled by the user",
                    ignoreCase = true
                )
                val sha1Badge = certVerification?.actualSha1Hex?.take(8)?.let { "_SHA1_$it" }.orEmpty()

                if (isPlayServicesResultCanceled) {
                    val code = "GMS_RESULT_CANCELED$sha1Badge"
                    Log.w(
                        tag,
                        "[STAGE:${stage.code}] Google Play Services HiddenActivity returned RESULT_CANCELED " +
                            "(type=${e.type}, code=$code): $sanitizedMsg. " +
                            "If the user selected an account, Play Services aborted token exchange (check Firebase OAuth Consent Screen / SHA-1).",
                        e
                    )
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CANCELLED,
                        userMessageHebrew = "חלון ההתחברות של Google נסגר ללא אסימון (RESULT_CANCELED). אם בחרת חשבון ולא ביטלת, Google Play Services דחה את הנפקת האסימון (בדוק הגדרת OAuth Consent / SHA-1 ב-Firebase).",
                        userMessageEnglish = "Google sign-in closed without a token (RESULT_CANCELED). If you selected an account and did not cancel, Google Play Services rejected token issuance (check Firebase OAuth Consent / SHA-1).",
                        diagnosticCode = code,
                        stage = stage,
                        cause = e
                    )
                } else {
                    Log.w(
                        tag,
                        "[STAGE:${stage.code}] Google Sign-In cancelled by user (type=${e.type}): $sanitizedMsg",
                        e
                    )
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CANCELLED,
                        userMessageHebrew = "בחירת חשבון Google בוטלה. לחץ על 'התחבר עם Google' כדי לבחור חשבון.",
                        userMessageEnglish = "Google account selection was cancelled. Tap 'Sign in with Google' to choose an account.",
                        diagnosticCode = "USER_CANCELLED",
                        stage = stage,
                        cause = e
                    )
                }
            }

            is NoCredentialException -> {
                val sha1Badge = certVerification?.actualSha1Hex?.take(8)?.let { "_SHA1_$it" }.orEmpty()
                val code = "NO_CREDENTIAL$sha1Badge"
                Log.w(
                    tag,
                    "[STAGE:${stage.code}] NoCredentialException during Google Sign-In (type=${e.type}, code=$code): $sanitizedMsg",
                    e
                )
                GoogleSignInAccountException(
                    reason = AuthFailureReason.NO_CREDENTIAL,
                    userMessageHebrew = "לא נמצא חשבון Google זמין במכשיר או שהגדרת OAuth / SHA-1 ב-Firebase אינה תואמת. ודא שקיים חשבון Google במכשיר.",
                    userMessageEnglish = "No authorized Google account found on device, or Firebase OAuth / SHA-1 configuration mismatch. Verify device Google accounts.",
                    diagnosticCode = code,
                    stage = stage,
                    cause = e
                )
            }

            is GetCredentialProviderConfigurationException,
            is GetCredentialUnsupportedException -> {
                val errType = (e as? GetCredentialException)?.type ?: e.javaClass.simpleName
                val shortCode = errType.substringAfterLast('.')
                Log.e(
                    tag,
                    "[STAGE:${stage.code}] Credential provider configuration/support error ($shortCode): $sanitizedMsg",
                    e
                )
                GoogleSignInAccountException(
                    reason = AuthFailureReason.CONFIGURATION_ERROR,
                    userMessageHebrew = "שירות Google Play Services לחיבור חשבון אינו זמין או דורש עדכון במכשיר.",
                    userMessageEnglish = "Google Play Services credential provider is unavailable or needs updating on this device.",
                    diagnosticCode = shortCode,
                    stage = stage,
                    cause = e
                )
            }

            is GetCredentialException -> {
                val shortType = e.type.substringAfterLast('.')
                if (sanitizedMsg.contains("network", ignoreCase = true) ||
                    Regex("(?<!\\d)7:").containsMatchIn(sanitizedMsg) ||
                    extractGmsStatusCode(e) == 7
                ) {
                    Log.e(
                        tag,
                        "[STAGE:${stage.code}] GetCredentialException network failure (type=${e.type}): $sanitizedMsg",
                        e
                    )
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.NETWORK_ERROR,
                        userMessageHebrew = "שגיאת תקשורת בעת ההתחברות עם Google. בדוק חיבור לאינטרנט.",
                        userMessageEnglish = "Network error while communicating with Google Identity Services. Check internet connection.",
                        diagnosticCode = "CREDENTIAL_NETWORK_ERROR",
                        stage = stage,
                        cause = e
                    )
                } else {
                    Log.e(
                        tag,
                        "[STAGE:${stage.code}] GetCredentialException during Google Sign-In (type=${e.type}): $sanitizedMsg",
                        e
                    )
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CREDENTIAL_MANAGER_ERROR,
                        userMessageHebrew = "שגיאה בקבלת פרטי התחברות מ-Google ($shortType). נסה שוב.",
                        userMessageEnglish = "Failed to obtain Google credential ($shortType). Please try again.",
                        diagnosticCode = shortType,
                        stage = stage,
                        cause = e
                    )
                }
            }

            is FirebaseAuthException -> {
                val code = e.errorCode
                val resolvedStage = if (stage == AuthStage.CREDENTIAL_REQUEST) AuthStage.FIREBASE_AUTH else stage
                Log.e(
                    tag,
                    "[STAGE:${resolvedStage.code}] FirebaseAuthException during Google Sign-In (errorCode=$code): $sanitizedMsg",
                    e
                )
                when (code) {
                    "ERROR_OPERATION_NOT_ALLOWED" -> GoogleSignInAccountException(
                        reason = AuthFailureReason.CONFIGURATION_ERROR,
                        userMessageHebrew = "ספק ההתחברות של Google אינו מופעל ב-Firebase Authentication. יש להפעיל את Google ב-Firebase Console.",
                        userMessageEnglish = "Google sign-in provider is not enabled in Firebase Authentication. Enable Google in Firebase Console.",
                        diagnosticCode = code,
                        stage = resolvedStage,
                        cause = e
                    )
                    "ERROR_USER_DISABLED" -> GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "חשבון המשתמש הושבת ב-Firebase Authentication.",
                        userMessageEnglish = "This user account has been disabled in Firebase Authentication.",
                        diagnosticCode = code,
                        stage = resolvedStage,
                        cause = e
                    )
                    "ERROR_INVALID_CREDENTIAL",
                    "ERROR_CREDENTIAL_ALREADY_IN_USE" -> GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "פרטי ההתחברות של Google נדחו על ידי Firebase ($code). נסה לבחור חשבון שוב.",
                        userMessageEnglish = "Google credential was rejected by Firebase ($code). Please try signing in again.",
                        diagnosticCode = code,
                        stage = resolvedStage,
                        cause = e
                    )
                    else -> GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "שגיאת אימות מול שרת Firebase ($code).",
                        userMessageEnglish = "Firebase authentication error ($code).",
                        diagnosticCode = code,
                        stage = resolvedStage,
                        cause = e
                    )
                }
            }

            is FirebaseNetworkException -> {
                val resolvedStage = if (stage == AuthStage.CREDENTIAL_REQUEST) AuthStage.FIREBASE_AUTH else stage
                Log.e(tag, "[STAGE:${resolvedStage.code}] FirebaseNetworkException during Google Sign-In: $sanitizedMsg", e)
                GoogleSignInAccountException(
                    reason = AuthFailureReason.NETWORK_ERROR,
                    userMessageHebrew = "שגיאת רשת מול Firebase. בדוק את החיבור לאינטרנט ונסה שוב.",
                    userMessageEnglish = "Network error communicating with Firebase. Check your internet connection and try again.",
                    diagnosticCode = "FIREBASE_NETWORK_ERROR",
                    stage = resolvedStage,
                    cause = e
                )
            }

            is FirebaseException -> {
                val resolvedStage = if (stage == AuthStage.CREDENTIAL_REQUEST) AuthStage.FIREBASE_AUTH else stage
                Log.e(tag, "[STAGE:${resolvedStage.code}] FirebaseException during Google Sign-In: $sanitizedMsg", e)
                val isConfig = sanitizedMsg.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) ||
                    sanitizedMsg.contains("API key not valid", ignoreCase = true) ||
                    sanitizedMsg.contains("blocked", ignoreCase = true)
                if (isConfig) {
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.CONFIGURATION_ERROR,
                        userMessageHebrew = "הגדרת ספק Google ב-Firebase Authentication חסרה או חסומה.",
                        userMessageEnglish = "Firebase Authentication Google provider configuration is missing or blocked.",
                        diagnosticCode = "FIREBASE_CONFIG_ERROR",
                        stage = resolvedStage,
                        cause = e
                    )
                } else {
                    GoogleSignInAccountException(
                        reason = AuthFailureReason.FIREBASE_AUTH_ERROR,
                        userMessageHebrew = "שגיאת Firebase בעת ההתחברות עם Google. נסה שוב.",
                        userMessageEnglish = "Firebase error during Google Sign-In. Please try again.",
                        diagnosticCode = "FIREBASE_ERROR",
                        stage = resolvedStage,
                        cause = e
                    )
                }
            }

            else -> {
                Log.e(
                    tag,
                    "[STAGE:${stage.code}] Unexpected exception during Google Sign-In (${e.javaClass.simpleName}): $sanitizedMsg",
                    e
                )
                GoogleSignInAccountException(
                    reason = AuthFailureReason.UNKNOWN_ERROR,
                    userMessageHebrew = "שגיאה בהתחברות עם Google (${e.javaClass.simpleName}). בדוק חיבור ונסה שוב.",
                    userMessageEnglish = "Google Sign-In error (${e.javaClass.simpleName}). Please try again.",
                    diagnosticCode = e.javaClass.simpleName,
                    stage = stage,
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
        /**
         * Confirmed signing certificate SHA-1 fingerprints associated with the project.
         * Includes both the physical device working key (EA:67...) and repository debug key (D7:BB...).
         */
        val KNOWN_PROJECT_SIGNING_SHA1_HEXES: Set<String> = setOf(
            "ea671df3701088cde3666f77ff7d973c19c1a387", // Confirmed working on physical device
            "d7bba84fe705a15371084a0fc5152dc12750cec9"  // Project debug keystore
        )

        /**
         * Expected Android applicationId from `app/google-services.json`.
         */
        internal const val EXPECTED_FIREBASE_PACKAGE_NAME = "com.aistudio.familytasks.qkxmva"

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
