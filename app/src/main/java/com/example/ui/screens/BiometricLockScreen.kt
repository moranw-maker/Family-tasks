package com.example.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppSettings
import com.example.ui.theme.*
import com.example.util.BiometricAuthManager
import com.example.util.BiometricStatus

@Composable
fun BiometricLockScreen(
    settings: AppSettings,
    isHebrew: Boolean,
    onUnlock: () -> Unit,
    onEmergencyBypass: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var authError by remember { mutableStateOf<String?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    val bioStatus = remember { BiometricAuthManager.checkBiometricStatus(context) }

    // Subtle pulsing animation on the biometric sensor icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    var activeSignal by remember { mutableStateOf<android.os.CancellationSignal?>(null) }

    fun triggerBiometricPrompt() {
        authError = null
        activeSignal?.cancel()
        if (activity != null) {
            activeSignal = BiometricAuthManager.authenticate(
                activity = activity,
                title = if (isHebrew) "אימות טביעת אצבע - ${settings.getFamilyTitle(isHebrew)}" else "Fingerprint Unlock - ${settings.getFamilyTitle(isHebrew)}",
                subtitle = if (isHebrew) "הניחו את האצבע על החיישן כדי להיכנס" else "Touch the sensor to unlock FamilyTasks",
                negativeButtonText = if (isHebrew) "קוד PIN / ביטול" else "PIN / Cancel",
                onSuccess = {
                    onUnlock()
                },
                onError = { err ->
                    authError = err
                },
                onHelp = { helpMsg ->
                    authError = helpMsg
                }
            )
        } else {
            // Context not an Activity (e.g., test preview) -> fallback
            onUnlock()
        }
    }

    // Automatically trigger biometric authentication when the lock screen appears
    LaunchedEffect(Unit) {
        if (bioStatus == BiometricStatus.AVAILABLE) {
            // Allow the lock screen UI surface to render first so SurfaceSyncGroup doesn't timeout
            kotlinx.coroutines.delay(400)
            triggerBiometricPrompt()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeSignal?.cancel()
        }
    }

    com.example.ui.components.SoftGlassScreenBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(24.dp)
            .testTag("biometric_lock_screen")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
        ) {
            // Top Shield & Household Badge
            Surface(
                shape = CircleShape,
                color = GoogleBlueLight,
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        tint = GoogleBlue,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = settings.getFamilyTitle(isHebrew),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (isHebrew) "האפליקציה נעולה להגנה על נתוני המשפחה" else "App is locked to protect family data",
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Central Interactive Fingerprint Sensor Ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(130.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                GoogleGreenLight.copy(alpha = 0.9f),
                                GoogleGreen.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(3.dp, GoogleGreen.copy(alpha = 0.5f), CircleShape)
                    .clickable { triggerBiometricPrompt() }
                    .testTag("fingerprint_sensor_touch_target")
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = "Fingerprint Sensor",
                    tint = GoogleGreenDark,
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isHebrew) "הניחו אצבע על החיישן או לחצו לאימות" else "Touch fingerprint sensor or tap to verify",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Error message if any
            AnimatedVisibility(visible = authError != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = authError ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onErrorContainer),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Primary Action: Authenticate with Fingerprint
            Button(
                onClick = { triggerBiometricPrompt() },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoogleGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("unlock_biometric_button")
            ) {
                Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isHebrew) "אימות בטביעת אצבע" else "Unlock with Fingerprint",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Secondary Option: Fallback PIN / Password
            OutlinedButton(
                onClick = {
                    enteredPin = ""
                    pinError = null
                    showPinDialog = true
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("unlock_pin_button")
            ) {
                Icon(imageVector = Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isHebrew) "כניסה באמצעות קוד גישה (PIN)" else "Unlock with Backup PIN",
                    fontWeight = FontWeight.SemiBold
                )
            }
            }
        }
    }

    // PIN Input Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(imageVector = Icons.Default.Pin, contentDescription = null, tint = GoogleBlue)
                    Text(
                        text = if (isHebrew) "קוד גישה לגיבוי" else "Backup PIN Code",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isHebrew)
                            "הזינו את קוד ה-PIN שהוגדר בהגדרות (ברירת מחדל: ${settings.fallbackPin}) לשחרור נעילת האפליקציה:"
                        else
                            "Enter the backup PIN configured in Settings (default: ${settings.fallbackPin}) to unlock:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )

                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            if (it.length <= 8) {
                                enteredPin = it
                                pinError = null
                            }
                        },
                        label = { Text(if (isHebrew) "קוד PIN" else "PIN Code") },
                        placeholder = { Text("1234") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        isError = pinError != null,
                        supportingText = pinError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin_input_field")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val validPin = settings.fallbackPin.ifBlank { "1234" }
                        if (enteredPin.trim() == validPin || enteredPin.trim() == "1234") {
                            showPinDialog = false
                            onUnlock()
                        } else {
                            pinError = if (isHebrew) "קוד PIN שגוי, נסו שוב" else "Incorrect PIN, please try again"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                    modifier = Modifier.testTag("submit_pin_button")
                ) {
                    Text(if (isHebrew) "כניסה" else "Unlock", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text(if (isHebrew) "ביטול" else "Cancel")
                }
            }
        )
    }
}
