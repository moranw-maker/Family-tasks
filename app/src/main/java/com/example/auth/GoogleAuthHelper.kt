package com.example.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

data class GoogleSignInResult(
    val email: String,
    val displayName: String?,
    val idToken: String?,
    val profilePictureUri: String?,
    val firebaseUser: FirebaseUser? = null
)

/**
 * Helper object providing robust Google Sign-In with Jetpack Credential Manager
 * and unified Firebase Authentication session management.
 */
object GoogleAuthHelper {
    private const val TAG = "GoogleAuthHelper"

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
     * Signs out the user from Firebase and clears session state.
     */
    fun signOut() {
        authManager.signOut()
    }
}
