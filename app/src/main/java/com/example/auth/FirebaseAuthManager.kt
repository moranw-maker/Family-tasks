package com.example.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.tasks.Task as GmsTask
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
     */
    data class Error(val message: String, val cause: Throwable? = null) : AuthState()
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
     * Initiates Google Sign-In via Jetpack Credential Manager using the official [GetGoogleIdOption],
     * and signs into Firebase Authentication with the retrieved Google ID token.
     *
     * @param context Context used to invoke CredentialManager.
     * @param serverClientId Optional OAuth web client ID override. If null, resolves R.string.default_web_client_id.
     * @return Result containing the authenticated [FirebaseUser] on success.
     */
    suspend fun signInWithGoogle(
        context: Context,
        serverClientId: String? = null
    ): Result<FirebaseUser> {
        _authState.value = AuthState.Loading
        return try {
            val resolvedClientId = serverClientId
                ?: runCatching { context.getString(com.example.R.string.default_web_client_id) }.getOrNull()
                ?: runCatching {
                    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
                    if (id != 0) context.getString(id) else null
                }.getOrNull()
                ?: "mock_client_id"

            val credentialManager = CredentialManager.create(context)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(resolvedClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = context, request = request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                // Authenticate with Firebase using the Google ID Token
                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult: AuthResult = auth.signInWithCredential(authCredential).await()
                val signedInUser = authResult.user
                    ?: throw IllegalStateException("Firebase sign-in completed without user object")

                _currentUser.value = signedInUser
                _authState.value = AuthState.Authenticated(signedInUser)
                Result.success(signedInUser)
            } else {
                val err = "Unsupported credential type: ${credential.type}"
                _authState.value = AuthState.Error(err)
                Result.failure(Exception(err))
            }
        } catch (e: GetCredentialCancellationException) {
            // User cancelled the sign-in prompt; restore prior state
            val priorUser = auth.currentUser
            _authState.value = if (priorUser != null) AuthState.Authenticated(priorUser) else AuthState.Unauthenticated
            Result.failure(e)
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            Log.w(tag, "No credentials available on device: ${e.message}")
            val priorUser = auth.currentUser
            _authState.value = if (priorUser != null) AuthState.Authenticated(priorUser) else AuthState.Unauthenticated
            Result.failure(e)
        } catch (e: GetCredentialException) {
            Log.w(tag, "CredentialManager warning: ${e.message}")
            _authState.value = AuthState.Error(e.message ?: "Google Sign-In failed", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(tag, "Authentication warning: ${e.message}")
            _authState.value = AuthState.Error(e.message ?: "Authentication error", e)
            Result.failure(e)
        }
    }

    /**
     * Signs out the current Firebase user and resets authentication state.
     */
    fun signOut() {
        auth.signOut()
        _currentUser.value = null
        _authState.value = AuthState.Unauthenticated
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
