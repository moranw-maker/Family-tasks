package com.example.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.AppSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BiometricAuthManagerTest {

    @Test
    fun testBiometricStatusEnumValues() {
        val statuses = BiometricStatus.values()
        assertTrue("Should include AVAILABLE", statuses.contains(BiometricStatus.AVAILABLE))
        assertTrue("Should include NOT_ENROLLED", statuses.contains(BiometricStatus.NOT_ENROLLED))
        assertTrue("Should include NO_HARDWARE", statuses.contains(BiometricStatus.NO_HARDWARE))
        assertTrue("Should include UNSUPPORTED", statuses.contains(BiometricStatus.UNSUPPORTED))
    }

    @Test
    fun testCheckBiometricStatusReturnsNonNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val status = BiometricAuthManager.checkBiometricStatus(context)
        assertNotNull("Biometric status check should never return null", status)
    }

    @Test
    fun testAppSettingsBiometricDefaultsAndFallbackPin() {
        val settings = AppSettings()
        assertTrue("Biometric should be enabled by default in settings", settings.biometricEnabled)
        assertEquals("Default fallback PIN should be 1234", "1234", settings.fallbackPin)
        assertFalse("App launch lock should be false until user enables it", settings.requireBiometricOnStart)

        val updated = settings.copy(requireBiometricOnStart = true, fallbackPin = "5678")
        assertTrue("Should reflect updated biometric require on start", updated.requireBiometricOnStart)
        assertEquals("Should reflect updated fallback PIN", "5678", updated.fallbackPin)
    }
}
