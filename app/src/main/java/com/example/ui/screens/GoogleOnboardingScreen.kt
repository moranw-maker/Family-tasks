package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.auth.GoogleAuthHelper
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun GoogleOnboardingScreen(
    initialEmail: String = "",
    isHebrew: Boolean = true,
    onStartCleanFamily: (name: String, email: String, familyName: String, avatar: String) -> Unit,
    onJoinWithCode: (inviteCode: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var isSigningInWithGoogle by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }

    var adminName by remember { mutableStateOf("") }
    var adminEmail by remember { mutableStateOf(initialEmail) }
    var familyName by remember { mutableStateOf("וקסלר") }
    var selectedAvatar by remember { mutableStateOf("👩‍💻") }
    var showManualSetup by remember { mutableStateOf(false) }
    var showInviteCodeSection by remember { mutableStateOf(false) }
    var inviteCodeInput by remember { mutableStateOf("") }

    val avatars = listOf("👩‍💻", "👨‍💻", "👩", "👨", "👧", "👦", "👑", "🌟")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFEBF3FE),
                        Color(0xFFF7F9FD),
                        Color.White
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 1. Hero 3D Family House Illustration
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .shadow(10.dp, RoundedCornerShape(28.dp), spotColor = GoogleBlue.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_family_house),
                    contentDescription = "FamilyTasks House",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. App Branding & Slogans
            Text(
                text = "FamilyTasks",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF1E293B),
                    letterSpacing = 0.5.sp
                )
            )

            Text(
                text = if (isHebrew) "משימות קטנות • הישגים גדולים יחד" else "Together • Organize • Achieve",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = VibrantActionBlue
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Text(
                text = if (isHebrew) "!ברוכים הבאים" else "Welcome!",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = VibrantActionBlue
                )
            )

            Text(
                text = if (isHebrew)
                    "ניהול משימות משפחתי בצורה חכמה, מהנה ומאובטחת"
                else
                    "Smart, fun and secure family chore & schedule management",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 3. Primary Vibrant Google Sign-In Button (launches Credential Manager directly without requiring manual entry)
            Surface(
                onClick = {
                    if (isSigningInWithGoogle) return@Surface
                    focusManager.clearFocus()
                    authErrorMessage = null
                    isSigningInWithGoogle = true
                    coroutineScope.launch {
                        val result = GoogleAuthHelper.signInWithGoogle(context)
                        isSigningInWithGoogle = false
                        result.onSuccess { googleUser ->
                            val resolvedEmail = googleUser.email
                                .ifBlank { googleUser.firebaseUser?.email.orEmpty() }
                                .ifBlank { adminEmail.trim() }
                            val resolvedName = googleUser.displayName?.takeIf { it.isNotBlank() }
                                ?: googleUser.firebaseUser?.displayName?.takeIf { it.isNotBlank() }
                                ?: adminName.trim().ifBlank {
                                    resolvedEmail.substringBefore('@').ifBlank {
                                        if (isHebrew) "מנהל ראשי" else "Primary Admin"
                                    }
                                }
                            val resolvedFamilyName = familyName.trim().ifBlank {
                                googleUser.displayName?.trim()?.substringAfter(' ', "")?.takeIf { it.isNotBlank() }
                                    ?: if (isHebrew) "וקסלר" else "Family"
                            }
                            adminEmail = resolvedEmail
                            adminName = resolvedName
                            onStartCleanFamily(
                                resolvedName,
                                resolvedEmail,
                                resolvedFamilyName,
                                selectedAvatar
                            )
                        }.onFailure { error ->
                            val diagnosticMsg = GoogleAuthHelper.getDiagnosticErrorMessage(error, isHebrew)
                            authErrorMessage = diagnosticMsg
                            android.widget.Toast.makeText(
                                context,
                                diagnosticMsg,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                color = VibrantActionBlue,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("google_login_primary_button")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isSigningInWithGoogle) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isHebrew) "מתחבר עם Google..." else "Signing in...",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    } else {
                        // Google 'G' icon badge
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "G",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = GoogleBlue
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isHebrew) "התחבר עם Google" else "Sign in with Google",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // Diagnostic Error Banner when Google Sign-In fails
            AnimatedVisibility(visible = authErrorMessage != null) {
                authErrorMessage?.let { errorText ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFEF2F2),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag("google_auth_error_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = if (isHebrew) "שגיאת התחברות" else "Authentication Error",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color(0xFF991B1B),
                                    fontWeight = FontWeight.SemiBold
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { authErrorMessage = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = if (isHebrew) "סגור הודעת שגיאה" else "Dismiss error",
                                    tint = Color(0xFF991B1B),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Divider with "או"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = OutlineLight)
                Text(
                    text = if (isHebrew) " או " else " OR ",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = OutlineLight)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Secondary Outlined Button: Customize Family Setup before Google Sign-In
            OutlinedButton(
                onClick = { showManualSetup = !showManualSetup },
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.5.dp, OutlineLight),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.White,
                    contentColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("onboarding_manual_toggle_btn")
            ) {
                Text(
                    text = if (isHebrew) "התחבר עם חשבון אחר" else "Sign in with another account",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
            }

            // 6. Expandable Family Customization (Optional fields; Google Sign-In does not require manual entry)
            AnimatedVisibility(visible = showManualSetup) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, OutlineLight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = if (isHebrew) "הגדרת בית ומשפחה חדשה" else "Set Up New Household",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = adminName,
                            onValueChange = { adminName = it },
                            label = { Text(if (isHebrew) "שם מלא (אופציונלי - יילקח מ-Google)" else "Full Name (Optional - from Google)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_name_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = adminEmail,
                            onValueChange = { adminEmail = it },
                            label = { Text(if (isHebrew) "אימייל (אופציונלי - יילקח מ-Google)" else "Email (Optional - from Google)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_email_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = familyName,
                            onValueChange = { familyName = it },
                            label = { Text(if (isHebrew) "שם המשפחה (לדוגמה: וקסלר)" else "Family Name (e.g. Wexler)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_family_name_input")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (isHebrew) "בחר סמליל לפרופיל:" else "Choose profile avatar:",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(avatars) { av ->
                                val isSelected = av == selectedAvatar
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) GoogleBlueLight else Color(0xFFF1F5F9))
                                        .border(
                                            2.dp,
                                            if (isSelected) VibrantActionBlue else Color.Transparent,
                                            CircleShape
                                        )
                                        .clickable { selectedAvatar = av },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = av, fontSize = 20.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (isSigningInWithGoogle) return@Button
                                focusManager.clearFocus()
                                authErrorMessage = null
                                coroutineScope.launch {
                                    val existingUser = GoogleAuthHelper.currentFirebaseUser
                                    if (existingUser == null) {
                                        isSigningInWithGoogle = true
                                        val authResult = GoogleAuthHelper.signInWithGoogle(context)
                                        isSigningInWithGoogle = false
                                        authResult.onSuccess { googleUser ->
                                            val finalEmail = adminEmail.trim().ifBlank { googleUser.email.ifBlank { "user@gmail.com" } }
                                            val finalName = adminName.trim().ifBlank {
                                                googleUser.displayName?.takeIf { it.isNotBlank() }
                                                    ?: finalEmail.substringBefore('@').ifBlank { if (isHebrew) "מנהל ראשי" else "Primary Admin" }
                                            }
                                            onStartCleanFamily(
                                                finalName,
                                                finalEmail,
                                                familyName.trim().ifBlank { "וקסלר" },
                                                selectedAvatar
                                            )
                                        }.onFailure { error ->
                                            val diagnosticMsg = GoogleAuthHelper.getDiagnosticErrorMessage(error, isHebrew)
                                            authErrorMessage = diagnosticMsg
                                            android.widget.Toast.makeText(
                                                context,
                                                diagnosticMsg,
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        val finalEmail = adminEmail.trim().ifBlank { existingUser.email.orEmpty().ifBlank { "user@gmail.com" } }
                                        val finalName = adminName.trim().ifBlank {
                                            existingUser.displayName?.takeIf { it.isNotBlank() }
                                                ?: finalEmail.substringBefore('@').ifBlank { if (isHebrew) "מנהל ראשי" else "Primary Admin" }
                                        }
                                        onStartCleanFamily(
                                            finalName,
                                            finalEmail,
                                            familyName.trim().ifBlank { "וקסלר" },
                                            selectedAvatar
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("onboarding_save_and_start_btn")
                        ) {
                            Text(
                                text = if (isHebrew) "התחל ניהול משימות משפחתי ✓" else "Start FamilyTasks ✓",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 7. Join Existing Family link
            TextButton(
                onClick = { showInviteCodeSection = !showInviteCodeSection },
                modifier = Modifier.testTag("onboarding_toggle_join_code_btn")
            ) {
                Text(
                    text = if (isHebrew) "יש לך כבר קוד הזמנה למשפחה? לחץ כאן" else "Have a family invite code? Tap here",
                    color = VibrantActionBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            AnimatedVisibility(visible = showInviteCodeSection) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = PastelBlueBackground,
                    border = BorderStroke(1.dp, PastelBlueBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        OutlinedTextField(
                            value = inviteCodeInput,
                            onValueChange = { inviteCodeInput = it.uppercase() },
                            label = { Text(if (isHebrew) "קוד הזמנה (למשל: FAM-WEXLER-7749)" else "Invite Code") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("invite_code_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (inviteCodeInput.isNotBlank() && !isSigningInWithGoogle) {
                                    focusManager.clearFocus()
                                    authErrorMessage = null
                                    coroutineScope.launch {
                                        if (GoogleAuthHelper.currentFirebaseUser == null) {
                                            isSigningInWithGoogle = true
                                            val authResult = GoogleAuthHelper.signInWithGoogle(context)
                                            isSigningInWithGoogle = false
                                            authResult.onSuccess {
                                                onJoinWithCode(inviteCodeInput.trim())
                                            }.onFailure { error ->
                                                val diagnosticMsg = GoogleAuthHelper.getDiagnosticErrorMessage(error, isHebrew)
                                                authErrorMessage = diagnosticMsg
                                                android.widget.Toast.makeText(
                                                    context,
                                                    diagnosticMsg,
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } else {
                                            onJoinWithCode(inviteCodeInput.trim())
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("submit_join_code_btn")
                        ) {
                            Text(
                                text = if (isHebrew) "הצטרף למשפחה" else "Join Family",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 8. Bottom Trophy & Features Banner (matches reference)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF1F6FE),
                                Color(0xFFE4EDFD)
                            )
                        )
                    )
                    .border(1.dp, Color(0xFFD6E4FC), RoundedCornerShape(24.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_golden_trophy),
                            contentDescription = "Trophy",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isHebrew) "משימות • ניקוד • פרסים" else "Tasks • Points • Rewards",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E3A8A)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Feature highlights row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FeatureMiniPill(icon = "🔄", label = if (isHebrew) "סנכרון חי" else "Live Sync")
                        FeatureMiniPill(icon = "🛡️", label = if (isHebrew) "Firebase" else "Firebase")
                        FeatureMiniPill(icon = "🔔", label = if (isHebrew) "התראות" else "Push Alerts")
                        FeatureMiniPill(icon = "📱", label = if (isHebrew) "אופליין" else "Offline")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FeatureMiniPill(icon: String, label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.padding(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = icon, fontSize = 12.sp)
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF334155)
            )
        }
    }
}
