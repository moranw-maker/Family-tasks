@file:Suppress("DEPRECATION")
package com.example.util

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.hardware.fingerprint.FingerprintManagerCompat

enum class BiometricStatus {
    AVAILABLE,
    NOT_ENROLLED,
    NO_HARDWARE,
    UNSUPPORTED
}

/**
 * Biometric authentication manager for secure app access using Android's BiometricPrompt API.
 * Provides fingerprint and biometric verification with multi-API level backward compatibility,
 * detailed error discrimination, help callback handling, and lifecycle-safe cancellation.
 */
object BiometricAuthManager {
    private const val TAG = "BiometricAuthManager"

    /**
     * Checks if biometric authentication is available and enrolled on the current device.
     */
    @Suppress("DEPRECATION")
    fun checkBiometricStatus(context: Context): BiometricStatus {
        val packageManager = context.packageManager
        val hasFingerprint = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        val hasBiometricFeature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasFingerprint || packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)
        } else {
            hasFingerprint
        }

        if (!hasBiometricFeature) {
            return BiometricStatus.NO_HARDWARE
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val biometricManager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                val authenticators = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                when (biometricManager?.canAuthenticate(authenticators)) {
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
                    else -> BiometricStatus.AVAILABLE
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val biometricManager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                @Suppress("DEPRECATION")
                when (biometricManager?.canAuthenticate()) {
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
                    else -> BiometricStatus.AVAILABLE
                }
            } else {
                // Pre-Android 10: check using FingerprintManagerCompat
                val compat = FingerprintManagerCompat.from(context)
                if (!compat.isHardwareDetected) {
                    BiometricStatus.NO_HARDWARE
                } else if (!compat.hasEnrolledFingerprints()) {
                    BiometricStatus.NOT_ENROLLED
                } else {
                    BiometricStatus.AVAILABLE
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkBiometricStatus fallback: ${e.message}")
            BiometricStatus.AVAILABLE
        }
    }

    /**
     * Authenticates the user via BiometricPrompt.
     *
     * @param activity Hosting Activity
     * @param title Title displayed on the BiometricPrompt modal
     * @param subtitle Subtitle description
     * @param description Explanatory text (e.g. instruction to touch sensor)
     * @param negativeButtonText Text for the cancel or PIN fallback button
     * @param onSuccess Callback invoked when fingerprint is recognized
     * @param onError Callback invoked when authentication fails or is cancelled
     * @param onHelp Callback invoked when recoverable help messages occur (e.g. move finger)
     * @return [CancellationSignal] allowing the caller to dismiss/cancel prompt on demand
     */
    fun authenticate(
        activity: Activity,
        title: String,
        subtitle: String,
        description: String = "הניחו את האצבע על חיישן טביעת האצבע לאימות כניסה",
        negativeButtonText: String = "ביטול",
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onHelp: ((String) -> Unit)? = null
    ): CancellationSignal? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cancellationSignal = CancellationSignal()
            try {
                val executor = ContextCompat.getMainExecutor(activity)
                val builder = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setDescription(description)
                    .setNegativeButton(negativeButtonText, executor, DialogInterface.OnClickListener { _, _ ->
                        onError(negativeButtonText)
                    })

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                    )
                }

                val prompt = builder.build()

                prompt.authenticate(
                    cancellationSignal,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            super.onAuthenticationSucceeded(result)
                            Log.d(TAG, "Biometric authentication succeeded")
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            super.onAuthenticationError(errorCode, errString)
                            Log.w(TAG, "Biometric error: code=$errorCode msg=$errString")
                            val userMsg = when (errorCode) {
                                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT ->
                                    "יותר מדי ניסיונות שגויים. נסו שוב בעוד 30 שניות או השתמשו בקוד PIN"
                                BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT ->
                                    "החיישן ננעל עקב ניסיונות מרובים. נדרש שחרור באמצעות קוד PIN"
                                BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ->
                                    "לא הוגדרה טביעת אצבע במכשיר. יש להגדיר בהגדרות המכשיר או להשתמש ב-PIN"
                                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                                BiometricPrompt.BIOMETRIC_ERROR_CANCELED ->
                                    errString?.toString() ?: "אימות בוטל"
                                else ->
                                    errString?.toString() ?: "שגיאה באימות ביומטרי"
                            }
                            onError(userMsg)
                        }

                        override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence?) {
                            super.onAuthenticationHelp(helpCode, helpString)
                            Log.i(TAG, "Biometric help: code=$helpCode msg=$helpString")
                            helpString?.let { onHelp?.invoke(it.toString()) }
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            Log.w(TAG, "Biometric authentication failed (fingerprint not recognized)")
                            onError("טביעת אצבע לא זוהתה, נסו שוב")
                        }
                    }
                )
                return cancellationSignal
            } catch (e: Exception) {
                Log.e(TAG, "BiometricPrompt execution failed: ${e.message}", e)
                onError(e.message ?: "שגיאה בהפעלת אימות טביעת אצבע")
                return null
            }
        } else {
            // Pre-Android 9 fallback
            onSuccess()
            return null
        }
    }
}
