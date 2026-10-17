package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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

        // Verify mock_client_id or invalid format is rejected at STAGE_1_CONFIG
        val invalidResult = manager.resolveWebClientId(context, overrideClientId = "mock_client_id")
        assertTrue(invalidResult.isFailure)
        val classified = manager.classifyAuthException(invalidResult.exceptionOrNull()!!)
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, classified.reason)
        assertEquals(AuthStage.CONFIG_VERIFICATION, classified.stage)
        assertEquals("INVALID_WEB_CLIENT_ID", classified.diagnosticCode)
        assertTrue(classified.getLocalizedUserMessage(isHebrew = true).contains("STAGE_1_CONFIG"))
        assertTrue(classified.getLocalizedUserMessage(isHebrew = true).contains("INVALID_WEB_CLIENT_ID"))
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
    fun testGenuineCancellationAndPlayServicesResultCanceledClassification() {
        val manager = FirebaseAuthManager(auth)

        // 1. Genuine user cancellation in selector
        val cancelEx = manager.classifyAuthException(
            GetCredentialCancellationException("User cancelled selector"),
            stage = AuthStage.CREDENTIAL_REQUEST
        )
        assertEquals(AuthFailureReason.CANCELLED, cancelEx.reason)
        assertEquals(AuthStage.CREDENTIAL_REQUEST, cancelEx.stage)
        assertEquals("USER_CANCELLED", cancelEx.diagnosticCode)
        val hebrewCancelMsg = cancelEx.getLocalizedUserMessage(isHebrew = true)
        assertTrue(hebrewCancelMsg.contains("בחירת חשבון Google בוטלה"))
        assertTrue(hebrewCancelMsg.contains("[STAGE_2_CREDENTIAL_REQUEST | USER_CANCELLED]"))

        // 2. Play Services HiddenActivity RESULT_CANCELED ("activity is cancelled by the user.")
        val playServicesResultCanceled = manager.classifyAuthException(
            GetCredentialCancellationException("activity is cancelled by the user."),
            stage = AuthStage.CREDENTIAL_REQUEST
        )
        assertEquals(AuthStage.CREDENTIAL_REQUEST, playServicesResultCanceled.stage)
        assertEquals("GMS_RESULT_CANCELED", playServicesResultCanceled.diagnosticCode)
        val gmsHebrewMsg = playServicesResultCanceled.getLocalizedUserMessage(isHebrew = true)
        assertTrue(gmsHebrewMsg.contains("RESULT_CANCELED"))
        assertTrue(gmsHebrewMsg.contains("[STAGE_2_CREDENTIAL_REQUEST | GMS_RESULT_CANCELED]"))
    }

    @Test
    fun testDeveloperConfigurationErrorsAreNeverDisplayedAsUserCancelled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = FirebaseAuthManager(auth)

        // 1. 28444 developer console error inside GetCredentialCancellationException
        val shaMismatchCancel = manager.classifyAuthException(
            GetCredentialCancellationException("[28444] Developer console is not set up correctly.")
        )
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, shaMismatchCancel.reason)
        assertEquals("OAUTH_DEVELOPER_ERROR_28444", shaMismatchCancel.diagnosticCode)
        assertFalse(shaMismatchCancel.getLocalizedUserMessage(isHebrew = true).contains("בחירת חשבון Google בוטלה"))
        assertTrue(shaMismatchCancel.getLocalizedUserMessage(isHebrew = true).contains("STAGE_2_CREDENTIAL_REQUEST"))

        // 2. GetCredentialCancellationException("activity is cancelled by the user.") wrapping a cause with 10: DEVELOPER_ERROR
        val nestedCauseCancel = GetCredentialCancellationException("activity is cancelled by the user.").apply {
            initCause(RuntimeException("10: DEVELOPER_ERROR: certificate_hash mismatch"))
        }
        val classifiedNested = manager.classifyAuthException(nestedCauseCancel)
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, classifiedNested.reason)
        assertEquals("OAUTH_SHA1_MISMATCH_10", classifiedNested.diagnosticCode)
        assertFalse(classifiedNested.getLocalizedUserMessage(isHebrew = false).contains("cancelled"))

        // 3. Package name mismatch flags APK_PACKAGE_MISMATCH
        val mismatchedPkg = manager.verifyApkSigningCertificate(
            context = context,
            expectedPackageName = "com.unauthorized.spoofed.pkg"
        )
        assertTrue(mismatchedPkg.hasPackageMismatch)
        val classifiedPkgMismatch = manager.classifyAuthException(
            e = GetCredentialCancellationException("activity is cancelled by the user."),
            stage = AuthStage.CREDENTIAL_REQUEST,
            certVerification = mismatchedPkg
        )
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, classifiedPkgMismatch.reason)
        assertEquals("APK_PACKAGE_MISMATCH", classifiedPkgMismatch.diagnosticCode)
        assertTrue(classifiedPkgMismatch.getLocalizedUserMessage(isHebrew = true).contains("שגיאת הגדרת חבילה"))

        // 4. Physical device working certificate (EA:67...) and repo key (D7:BB...) are both recognized
        val physicalDeviceWorkingCert = manager.verifyApkSigningCertificate(
            context = context,
            overrideActualSha1Hex = "EA:67:1D:F3:70:10:88:CD:E3:66:6F:77:FF:7D:97:3C:19:C1:A3:87"
        )
        assertFalse("Working EA:67 cert must not have package mismatch", physicalDeviceWorkingCert.hasPackageMismatch)
        assertTrue("Working EA:67 cert must be recognized", physicalDeviceWorkingCert.isRecognizedCert)
        assertEquals("ea671df3", physicalDeviceWorkingCert.shortActualSha1)

        val repoDebugCert = manager.verifyApkSigningCertificate(
            context = context,
            overrideActualSha1Hex = "D7:BB:A8:4F:E7:05:A1:53:71:08:4A:0F:C5:15:2D:C1:27:50:CE:C9"
        )
        assertFalse("Repo D7:BB cert must not have package mismatch", repoDebugCert.hasPackageMismatch)
        assertTrue("Repo D7:BB cert must be recognized", repoDebugCert.isRecognizedCert)
        assertEquals("d7bba84f", repoDebugCert.shortActualSha1)

        // 5. Fallback exception with suppressed primary developer configuration error does not hide the primary error
        val primaryConfigEx = GetCredentialCustomException(
            "com.google.android.gms.common.api.ApiException",
            "10: [28444] Developer console is not set up correctly."
        )
        val fallbackCancelEx = GetCredentialCancellationException("activity is cancelled by the user.").apply {
            addSuppressed(primaryConfigEx)
        }
        val classifiedSuppressed = manager.classifyAuthException(fallbackCancelEx)
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, classifiedSuppressed.reason)
        assertEquals("OAUTH_SHA1_MISMATCH_10", classifiedSuppressed.diagnosticCode)
    }

    @Test
    fun testKnownProjectSigningCertificatesIncludePhysicalWorkingKey() {
        val known = FirebaseAuthManager.KNOWN_PROJECT_SIGNING_SHA1_HEXES
        assertTrue(
            "Physical device working key (EA:67...) must be recognized",
            known.contains("ea671df3701088cde3666f77ff7d973c19c1a387")
        )
        assertTrue(
            "Project debug keystore (D7:BB...) must be recognized",
            known.contains("d7bba84fe705a15371084a0fc5152dc12750cec9")
        )
    }

    @Test
    fun testCredentialParsingFailures() {
        val manager = FirebaseAuthManager(auth)

        // 1. Unsupported credential type
        val unsupportedCred = CustomCredential("com.example.UNSUPPORTED_CREDENTIAL", Bundle())
        val unsupportedEx = assertThrows(GoogleSignInAccountException::class.java) {
            manager.extractGoogleIdToken(unsupportedCred)
        }
        assertEquals(AuthFailureReason.CREDENTIAL_MANAGER_ERROR, unsupportedEx.reason)
        assertEquals(AuthStage.CREDENTIAL_PARSE, unsupportedEx.stage)
        assertEquals("UNSUPPORTED_CREDENTIAL_TYPE", unsupportedEx.diagnosticCode)
        assertTrue(unsupportedEx.getLocalizedUserMessage(isHebrew = true).contains("STAGE_3_CREDENTIAL_PARSE"))

        // 2. Malformed / empty bundle for GoogleIdTokenCredential triggers GoogleIdTokenParsingException
        val malformedGoogleCred = CustomCredential(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            Bundle()
        )
        val parseEx = assertThrows(GoogleSignInAccountException::class.java) {
            manager.extractGoogleIdToken(malformedGoogleCred)
        }
        assertEquals(AuthFailureReason.CREDENTIAL_MANAGER_ERROR, parseEx.reason)
        assertEquals(AuthStage.CREDENTIAL_PARSE, parseEx.stage)
        assertEquals("INVALID_GOOGLE_ID_TOKEN", parseEx.diagnosticCode)
        assertTrue(parseEx.getLocalizedUserMessage(isHebrew = false).contains("STAGE_3_CREDENTIAL_PARSE"))

        // 3. Valid GoogleIdTokenCredential bundle with blank idToken triggers EMPTY_ID_TOKEN
        val emptyTokenCred = GoogleIdTokenCredential.Builder()
            .setId("user@example.com")
            .setIdToken("   ")
            .build()
        val emptyTokenEx = assertThrows(GoogleSignInAccountException::class.java) {
            manager.extractGoogleIdToken(emptyTokenCred)
        }
        assertEquals(AuthFailureReason.CREDENTIAL_MANAGER_ERROR, emptyTokenEx.reason)
        assertEquals(AuthStage.CREDENTIAL_PARSE, emptyTokenEx.stage)
        assertEquals("EMPTY_ID_TOKEN", emptyTokenEx.diagnosticCode)
    }

    @Test
    fun testFirebaseAuthenticationFailures() {
        val manager = FirebaseAuthManager(auth)

        // 1. Google provider disabled in Firebase Console (ERROR_OPERATION_NOT_ALLOWED)
        val providerDisabledEx = manager.classifyAuthException(
            FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "This operation is not allowed."),
            stage = AuthStage.FIREBASE_AUTH
        )
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, providerDisabledEx.reason)
        assertEquals(AuthStage.FIREBASE_AUTH, providerDisabledEx.stage)
        assertEquals("ERROR_OPERATION_NOT_ALLOWED", providerDisabledEx.diagnosticCode)
        assertTrue(providerDisabledEx.getLocalizedUserMessage(isHebrew = true).contains("STAGE_4_FIREBASE_AUTH"))

        // 2. Invalid credential rejected by Firebase (ERROR_INVALID_CREDENTIAL)
        val invalidCredEx = manager.classifyAuthException(
            FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "The supplied auth credential is incorrect."),
            stage = AuthStage.FIREBASE_AUTH
        )
        assertEquals(AuthFailureReason.FIREBASE_AUTH_ERROR, invalidCredEx.reason)
        assertEquals(AuthStage.FIREBASE_AUTH, invalidCredEx.stage)
        assertEquals("ERROR_INVALID_CREDENTIAL", invalidCredEx.diagnosticCode)

        // 3. Disabled user account (ERROR_USER_DISABLED)
        val disabledUserEx = manager.classifyAuthException(
            FirebaseAuthException("ERROR_USER_DISABLED", "The user account has been disabled."),
            stage = AuthStage.FIREBASE_AUTH
        )
        assertEquals(AuthFailureReason.FIREBASE_AUTH_ERROR, disabledUserEx.reason)
        assertEquals(AuthStage.FIREBASE_AUTH, disabledUserEx.stage)
        assertEquals("ERROR_USER_DISABLED", disabledUserEx.diagnosticCode)

        // 4. FirebaseNetworkException
        val netEx = manager.classifyAuthException(
            FirebaseNetworkException("Network unreachable"),
            stage = AuthStage.FIREBASE_AUTH
        )
        assertEquals(AuthFailureReason.NETWORK_ERROR, netEx.reason)
        assertEquals(AuthStage.FIREBASE_AUTH, netEx.stage)
        assertEquals("FIREBASE_NETWORK_ERROR", netEx.diagnosticCode)

        // 5. FirebaseException with CONFIGURATION_NOT_FOUND
        val configNotFoundEx = manager.classifyAuthException(
            FirebaseException("An internal error has occurred. [ CONFIGURATION_NOT_FOUND ]"),
            stage = AuthStage.FIREBASE_AUTH
        )
        assertEquals(AuthFailureReason.CONFIGURATION_ERROR, configNotFoundEx.reason)
        assertEquals(AuthStage.FIREBASE_AUTH, configNotFoundEx.stage)
    }

    @Test
    fun testNoCredentialAndGenericCredentialManagerExceptions() {
        val manager = FirebaseAuthManager(auth)

        val noCredEx = manager.classifyAuthException(NoCredentialException("No credentials available"))
        assertEquals(AuthFailureReason.NO_CREDENTIAL, noCredEx.reason)
        assertEquals(AuthStage.CREDENTIAL_REQUEST, noCredEx.stage)
        assertTrue(noCredEx.getLocalizedUserMessage(isHebrew = true).contains("לא נמצא חשבון Google"))
        assertFalse(noCredEx.getLocalizedUserMessage(isHebrew = true).contains("הזן פרטים ידנית"))

        val credEx = manager.classifyAuthException(
            GetCredentialCustomException("com.google.android.gms.auth.api.credentials.ERR", "Internal failure")
        )
        assertEquals(AuthFailureReason.CREDENTIAL_MANAGER_ERROR, credEx.reason)
        assertEquals("ERR", credEx.diagnosticCode)
    }

    @Test
    fun testSanitizeLogMessageRedactsTokensPasswordsAndPersonalEmails() {
        val raw = "Auth failed for user=parent.test@gmail.com id_token=eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c access_token=ya29.a0AfH6SMBx123456 password=MySecretPass123"
        val sanitized = sanitizeLogMessage(raw)
        assertFalse(sanitized.contains("eyJhbGciOiJSUzI1NiIsImtpZCI6IjEifQ"))
        assertFalse(sanitized.contains("ya29.a0AfH6SMBx123456"))
        assertFalse(sanitized.contains("MySecretPass123"))
        assertFalse(sanitized.contains("parent.test@gmail.com"))
        assertTrue(sanitized.contains("[REDACTED_JWT]"))
        assertTrue(sanitized.contains("[REDACTED_ACCESS_TOKEN]"))
        assertTrue(sanitized.contains("[REDACTED_EMAIL]"))
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
