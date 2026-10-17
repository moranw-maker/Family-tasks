package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric

class FirebaseAuthManagerTest : FirestoreEmulatorTestBase() {

    @Test
    fun testInitialStateWhenUnauthenticated() = runBlocking {
        auth.signOut()
        val manager = FirebaseAuthManager(auth)

        assertNull(manager.currentUser.first())
        assertFalse(manager.isAuthenticated)
        assertNull(manager.currentUserId)
        assertEquals(AuthState.Unauthenticated, manager.authState.first())
    }

    @Test
    fun testSignOutResetsState() = runBlocking {
        val manager = FirebaseAuthManager(auth)
        manager.signOut()

        assertNull(manager.currentUser.first())
        assertFalse(manager.isAuthenticated)
        assertEquals(AuthState.Unauthenticated, manager.authState.first())
    }

    @Test
    fun testAuthStateHierarchy() {
        val errorState = AuthState.Error(
            "Invalid token",
            IllegalArgumentException("Bad token"),
            AuthFailureReason.CREDENTIAL_MANAGER_ERROR
        )
        assertEquals("Invalid token", errorState.message)
        assertTrue(errorState.cause is IllegalArgumentException)
        assertEquals(AuthFailureReason.CREDENTIAL_MANAGER_ERROR, errorState.reason)
        assertEquals(AuthState.Loading, AuthState.Loading)
        assertEquals(AuthState.Unauthenticated, AuthState.Unauthenticated)
    }

    @Test
    fun testWebClientIdResolutionFromGoogleServicesJson() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FirebaseAuthManager(auth)

        val resolvedResult = manager.resolveWebClientId(context)
        assertTrue("Web OAuth client ID must resolve from R.string.default_web_client_id", resolvedResult.isSuccess)
        val clientId = resolvedResult.getOrNull()
        assertEquals(
            "990543827352-p74le3h281i6oinvbkv6btopm51ard7a.apps.googleusercontent.com",
            clientId
        )

        // Verify mock_client_id or invalid format is rejected
        val invalidResult = manager.resolveWebClientId(context, overrideClientId = "mock_client_id")
        assertTrue(invalidResult.isFailure)
        val classified = manager.classifyAuthException(invalidResult.exceptionOrNull()!!)
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, classified.reason)
    }

    @Test
    fun testGetGoogleIdOptionAndSignInWithGoogleOptionConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FirebaseAuthManager(auth)
        val clientId = manager.resolveWebClientId(context).getOrThrow()

        val googleIdOption = manager.buildGoogleIdOption(
            serverClientId = clientId,
            filterByAuthorizedAccounts = false,
            autoSelectEnabled = false
        )
        assertEquals(clientId, googleIdOption.serverClientId)
        assertFalse(googleIdOption.filterByAuthorizedAccounts)
        assertFalse(googleIdOption.autoSelectEnabled)

        val signInOption = manager.buildSignInWithGoogleOption(serverClientId = clientId)
        assertEquals(clientId, signInOption.serverClientId)
    }

    @Test
    fun testSeparateErrorClassificationForCredentialAndFirebaseExceptions() {
        val manager = FirebaseAuthManager(auth)

        // 1. User cancellation
        val cancelEx = manager.classifyAuthException(GetCredentialCancellationException("User cancelled selector"))
        assertEquals(AuthFailureReason.CANCELLED, cancelEx.reason)
        assertTrue(cancelEx.getLocalizedUserMessage(isHebrew = true).contains("בוטלה"))

        // 2. Developer console / SHA-1 mismatch masquerading as cancellation (28444 / 10:)
        val shaMismatchCancel = manager.classifyAuthException(
            GetCredentialCancellationException("[28444] Developer console is not set up correctly.")
        )
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, shaMismatchCancel.reason)
        assertTrue(shaMismatchCancel.getLocalizedUserMessage(isHebrew = true).contains("SHA-1"))

        // 3. NoCredentialException
        val noCredEx = manager.classifyAuthException(NoCredentialException("No credentials available"))
        assertEquals(AuthFailureReason.NO_CREDENTIAL, noCredEx.reason)
        assertTrue(noCredEx.getLocalizedUserMessage(isHebrew = true).contains("לא נמצא חשבון Google"))
        assertFalse(noCredEx.getLocalizedUserMessage(isHebrew = true).contains("הזן פרטים ידנית"))

        // 4. Generic GetCredentialException
        val credEx = manager.classifyAuthException(
            GetCredentialCustomException("com.google.android.gms.auth.api.credentials.ERR", "Internal failure")
        )
        assertEquals(AuthFailureReason.CREDENTIAL_MANAGER_ERROR, credEx.reason)

        // 5. FirebaseAuthException (provider disabled vs invalid credential)
        val providerDisabledEx = manager.classifyAuthException(
            FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "This operation is not allowed.")
        )
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, providerDisabledEx.reason)
        assertEquals("ERROR_OPERATION_NOT_ALLOWED", providerDisabledEx.diagnosticCode)

        val invalidCredEx = manager.classifyAuthException(
            FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "The supplied auth credential is incorrect.")
        )
        assertEquals(AuthFailureReason.FIREBASE_AUTH_ERROR, invalidCredEx.reason)

        // 6. FirebaseNetworkException
        val netEx = manager.classifyAuthException(FirebaseNetworkException("Network unreachable"))
        assertEquals(AuthFailureReason.NETWORK_ERROR, netEx.reason)
    }

    @Test
    fun testSanitizeLogMessageRedactsTokens() {
        val raw = "Auth failed for id_token=eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c and access_token=ya29.a0AfH6SMBx123456"
        val sanitized = sanitizeLogMessage(raw)
        assertFalse(sanitized.contains("eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ"))
        assertFalse(sanitized.contains("ya29.a0AfH6SMBx123456"))
        assertTrue(sanitized.contains("[REDACTED"))
    }

    @Test
    fun testFindActivityUnwrapsContextWrapper() {
        if (android.os.Looper.myLooper() == null) {
            android.os.Looper.prepare()
        }
        val activity = Activity()
        val wrappedContext = ContextWrapper(ContextWrapper(activity))

        assertSame(activity, wrappedContext.findActivity())
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertNull(appContext.findActivity())
    }
}
