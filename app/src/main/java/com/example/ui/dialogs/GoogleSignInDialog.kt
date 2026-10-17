package com.example.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.auth.GoogleAuthHelper
import com.example.model.FamilyMember
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun GoogleSignInDialog(
    currentEmail: String,
    members: List<FamilyMember>,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSignInSuccess: (email: String) -> Unit,
    onSelectMember: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var emailInput by remember { mutableStateOf(currentEmail) }
    var selectedMemberId by remember { mutableStateOf(members.firstOrNull()?.id ?: "") }
    var inviteCodeInput by remember { mutableStateOf("") }
    var showInviteCodeField by remember { mutableStateOf(false) }
    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val safeDismiss = {
        onDismiss()
    }

    Dialog(
        onDismissRequest = safeDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp)
                .testTag("google_signin_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
                    .verticalScroll(scrollState)
            ) {
                // Header with Google Colors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = GoogleBlueLight,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "G",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = GoogleBlue
                                )
                            }
                        }

                        Column {
                            Text(
                                text = if (isHebrew) "כניסה באמצעות Google" else "Google Account Sign-In",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlue
                                )
                            )
                            Text(
                                text = if (isHebrew) "סנכרון ענן, יומן ו-Drive משפחתי" else "Cloud, Calendar & Family Drive sync",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    IconButton(
                        onClick = safeDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_google_signin_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Account Email Field (optional — populated automatically by Google Sign-In)
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    label = { Text(if (isHebrew) "כתובת חשבון Google (אופציונלי)" else "Google Account Email (Optional)") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = GoogleBlue
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("google_email_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Choose your household profile
                Text(
                    text = if (isHebrew) "בחר את הפרופיל שלך במשפחה:" else "Select your profile in the household:",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    members.take(5).forEach { member ->
                        val isSelected = member.id == selectedMemberId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) BorderStroke(1.5.dp, GoogleBlue) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedMemberId = member.id
                                    onSelectMember(member.id)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(text = member.avatarEmoji, fontSize = 20.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = member.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                    Text(
                                        text = member.role.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = GoogleBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Join with Invite Code toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { showInviteCodeField = !showInviteCodeField }
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = GoogleBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "קיבלת קוד הזמנה למשפחה אחרת? לחץ כאן" else "Have a family invite code? Tap here",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GoogleBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                if (showInviteCodeField) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = inviteCodeInput,
                        onValueChange = { inviteCodeInput = it },
                        label = { Text(if (isHebrew) "הזן קוד הזמנה (לדוגמה: FAM-8492)" else "Invite Code (e.g. FAM-8492)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFEF2F2),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_google_auth_error")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color(0xFF991B1B),
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Security Note
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = GoogleGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isHebrew) "מאובטח בסטנדרט Google Workspace & Drive" else "Secured with Google Workspace & Drive",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Google Sign-In Button (launches Credential Manager without requiring manual email entry)
                Button(
                    onClick = {
                        if (isSigningIn) return@Button
                        errorMessage = null
                        isSigningIn = true
                        coroutineScope.launch {
                            val result = GoogleAuthHelper.signInWithGoogle(context)
                            isSigningIn = false
                            result.onSuccess { googleUser ->
                                val resolvedEmail = googleUser.email
                                    .ifBlank { googleUser.firebaseUser?.email.orEmpty() }
                                    .ifBlank { emailInput.trim() }
                                    .ifBlank { "user@gmail.com" }
                                emailInput = resolvedEmail
                                onSignInSuccess(resolvedEmail)
                            }.onFailure { error ->
                                val diagnosticMsg = GoogleAuthHelper.getDiagnosticErrorMessage(error, isHebrew)
                                errorMessage = diagnosticMsg
                                android.widget.Toast.makeText(
                                    context,
                                    diagnosticMsg,
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = !isSigningIn,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_google_signin_btn")
                ) {
                    if (isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isHebrew) "המשך והתחבר עם Google ✓" else "Continue with Google ✓",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
