package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun GoogleOnboardingScreen(
    initialEmail: String = "Moranw@gmail.com",
    isHebrew: Boolean = true,
    onStartCleanFamily: (name: String, email: String, familyName: String, avatar: String) -> Unit,
    onJoinWithCode: (inviteCode: String) -> Unit
) {
    var adminName by remember { mutableStateOf("מורן") }
    var adminEmail by remember { mutableStateOf(initialEmail) }
    var familyName by remember { mutableStateOf("וקסלר") }
    var selectedAvatar by remember { mutableStateOf("👩‍💻") }
    var showInviteCodeSection by remember { mutableStateOf(false) }
    var inviteCodeInput by remember { mutableStateOf("") }

    val avatars = listOf("👩‍💻", "👨‍💻", "👩", "👨", "👑", "🏡", "🌟", "🦁")

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(20.dp))

                // Google Brand Icon
                Surface(
                    shape = CircleShape,
                    color = GoogleBlueLight,
                    modifier = Modifier.size(76.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "G",
                            fontSize = 42.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = GoogleBlue
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isHebrew) "ברוכים הבאים ל-Family Hub" else "Welcome to Family Hub",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = if (isHebrew)
                        "המרכז המשפחתי החכם לניהול משימות, יומן, מערכת שעות ומסמכים"
                    else
                        "Smart Family Hub for tasks, shared calendar, school timetables & Drive",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Sign-In & Household Card
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = GoogleBlue,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (isHebrew) "כניסה ראשונית והקמת משפחה נקייה" else "First-Time Sign-In & Clean Setup",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlue
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Admin Name
                        OutlinedTextField(
                            value = adminName,
                            onValueChange = { adminName = it },
                            label = { Text(if (isHebrew) "שם המנהל/ת הראשי/ת" else "Your Name (Primary Admin)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_name_input")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Google Email
                        OutlinedTextField(
                            value = adminEmail,
                            onValueChange = { adminEmail = it },
                            label = { Text(if (isHebrew) "כתובת חשבון Google" else "Google Account Email") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_email_input")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Family Name
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

                        Spacer(modifier = Modifier.height(14.dp))

                        // Choose Avatar
                        Text(
                            text = if (isHebrew) "בחר אימוג'י פרופיל:" else "Choose Avatar:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(avatars) { avatar ->
                                val isSelected = selectedAvatar == avatar
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { selectedAvatar = avatar },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = avatar, fontSize = 22.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Clean Setup Note
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GoogleGreenLight.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(text = "✨", fontSize = 16.sp)
                                Text(
                                    text = if (isHebrew)
                                        "האפליקציה תיפתח נקייה מנתונים, מוכנה להזמנת בני המשפחה האמיתיים שלכם"
                                    else
                                        "App will start completely clean, ready to invite your real household",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = GoogleGreenDark,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Main Sign-In Button
                        Button(
                            onClick = {
                                if (adminName.isNotBlank() && adminEmail.isNotBlank()) {
                                    onStartCleanFamily(adminName.trim(), adminEmail.trim(), familyName.trim(), selectedAvatar)
                                }
                            },
                            enabled = adminName.isNotBlank() && adminEmail.isNotBlank(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("start_clean_family_btn")
                        ) {
                            Text(
                                text = if (isHebrew) "התחבר עם Google והקם משפחה נקייה ✓" else "Continue with Google & Start Clean ✓",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Toggle Join with Invite Code
                TextButton(
                    onClick = { showInviteCodeSection = !showInviteCodeSection }
                ) {
                    Text(
                        text = if (isHebrew) "קיבלתם קוד הזמנה ממשפחה קיימת? לחצו כאן" else "Have an invite code from family? Tap here",
                        color = GoogleBlue,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (showInviteCodeSection) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = inviteCodeInput,
                                onValueChange = { inviteCodeInput = it },
                                label = { Text(if (isHebrew) "קוד הזמנה (למשל: FAM-WEXLER-7749)" else "Invite Code") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (inviteCodeInput.isNotBlank()) {
                                        onJoinWithCode(inviteCodeInput.trim())
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue)
                            ) {
                                Text(if (isHebrew) "הצטרף" else "Join")
                            }
                        }
                    }
                }
            }

            // Footer Security Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = GoogleGreen,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isHebrew) "מאובטח ע\"י Google Cloud Workspace & Drive" else "Secured by Google Cloud Workspace & Drive",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
        }
    }
}
