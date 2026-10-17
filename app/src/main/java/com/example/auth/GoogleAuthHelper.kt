package com.example.auth

import android.content.Context
import com.google.firebase.auth.FirebaseUser

data class GoogleSignInResult(
    val email: String,
    val displayName: String?,
    val idToken: String?,
    val profilePictureUri: String?,
    val firebaseUser: FirebaseUser? = null
)

/**
 * Helper object providing Google Sign-In with Jetpack Credential Manager
 * and unified Firebase Authentication session management.
 */
object GoogleAuthHelper {

    val authManager: FirebaseAuthManager
        get() = FirebaseAuthManager.getInstance()

    val currentFirebaseUser: FirebaseUser?
        get() = authManager.currentUser.value

    val currentUserId: String?
        get() = authManager.currentUserId

    val isAuthenticated: Boolean
        get() = authManager.isAuthenticated

    /**
     * Performs Google Sign-In by delegating to [FirebaseAuthManager], ensuring
     * a single, unified authentication flow across the application.
     */
    suspend fun signInWithGoogle(
        context: Context,
        serverClientId: String? = null
    ): Result<GoogleSignInResult> {
        val authResult = authManager.signInWithGoogle(context, serverClientId)
        return authResult.map { user ->
            GoogleSignInResult(
                email = user.email ?: "",
                displayName = user.displayName,
                idToken = null,
                profilePictureUri = user.photoUrl?.toString(),
                firebaseUser = user
            )
        }
    }

    /**
     * Formats a user-safe, localized diagnostic error message from a failed sign-in [Throwable].
     */
    fun getDiagnosticErrorMessage(error: Throwable?, isHebrew: Boolean): String {
        val classified = if (error != null) {
            authManager.classifyAuthException(error)
        } else {
            GoogleSignInAccountException(
                reason = AuthFailureReason.UNKNOWN_ERROR,
                userMessageHebrew = "שגיאה בהתחברות עם Google. נסה שוב.",
                userMessageEnglish = "Google Sign-In failed. Please try again."
            )
        }
        return classified.getLocalizedUserMessage(isHebrew)
    }

    /**
     * Signs out the user from Firebase and clears session state.
     */
    fun signOut(context: Context? = null) {
        authManager.signOut(context)
    }
}
