package com.example.ui.dialogs

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.FamilyInvitation
import com.example.model.UserRole
import com.example.ui.theme.*

@Composable
fun InviteMemberDialog(
    familyName: String,
    isPrimaryAdmin: Boolean,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onGenerateShareText: (name: String, role: UserRole) -> String,
    onFormatInvitationShareText: ((FamilyInvitation) -> String)? = null,
    isCreatingInvitation: Boolean = false,
    invitationErrorMessage: String? = null,
    onCreateInvitationAsync: ((
        name: String,
        emoji: String,
        role: UserRole,
        googleEmail: String?,
        schoolGrade: String?,
        onConfirmed: (FamilyInvitation) -> Unit
    ) -> Unit)? = null,
    onInvite: (
        name: String,
        emoji: String,
        role: UserRole,
        googleEmail: String?,
        schoolGrade: String?
    ) -> FamilyInvitation?
) {
    var selectedRole by remember { mutableStateOf(UserRole.CHILD) }
    var nickname by remember { mutableStateOf("") }
    var generatedInvitation by remember { mutableStateOf<FamilyInvitation?>(null) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val triggerCreateInvite = {
        val emoji = if (selectedRole == UserRole.PARENT) "👩" else "🧒"
        val cleanNickname = nickname.trim()
        if (onCreateInvitationAsync != null) {
            onCreateInvitationAsync(
                cleanNickname,
                emoji,
                selectedRole,
                null,
                null
            ) { confirmedInvitation ->
                generatedInvitation = confirmedInvitation
            }
        } else {
            val created = onInvite(
                cleanNickname,
                emoji,
                selectedRole,
                null,
                null
            )
            if (created != null) {
                generatedInvitation = created
            }
        }
    }

    fun buildDomainFreeShareMessage(invitation: FamilyInvitation): String {
        if (onFormatInvitationShareText != null) {
            return onFormatInvitationShareText(invitation)
        }
        val roleHe = if (invitation.invitedRole == UserRole.PARENT) "הורה (מנהל משפחה)" else "ילד/ה (חבר משפחה)"
        val roleEn = if (invitation.invitedRole == UserRole.PARENT) "Parent (Family Admin)" else "Child (Family Member)"
        return if (isHebrew) {
            buildString {
                appendLine("שלום! הוזמנת להצטרף למשפחת \"${invitation.familyName}\" באפליקציית FamilyTasks 🏠✨")
                appendLine()
                appendLine("👤 תפקיד במשפחה: $roleHe")
                appendLine("🔑 קוד הזמנה אישי: ${invitation.code}")
                appendLine()
                appendLine("איך מצטרפים?")
                appendLine("1. פותחים את אפליקציית FamilyTasks במכשיר.")
                appendLine("2. מתחברים עם חשבון Google.")
                appendLine("3. בוחרים ב-\"הצטרפות למשפחה קיימת\".")
                append("4. מזינים את קוד ההזמנה: ${invitation.code} ומאשרים הצטרפות!")
            }
        } else {
            buildString {
                appendLine("Hi! You are invited to join \"${invitation.familyName}\" in FamilyTasks 🏠✨")
                appendLine()
                appendLine("👤 Invited Role: $roleEn")
                appendLine("🔑 Invitation Code: ${invitation.code}")
                appendLine()
                appendLine("How to join:")
                appendLine("1. Open FamilyTasks on your device.")
                appendLine("2. Sign in with your Google account.")
                appendLine("3. Select \"Join an existing family\".")
                append("4. Enter invitation code: ${invitation.code} and confirm!")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("invite_member_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "✉️", fontSize = 22.sp)
                        Text(
                            text = if (isHebrew) "הזמנת חבר משפחה" else "Invite Family Member",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = if (isHebrew)
                        "בחרו את התפקיד המבוקש (ילד/ה או הורה) וצרו קוד הזמנה חד-פעמי ומאובטח למשפחת \"$familyName\". התפקיד בהזמנה קבוע ואינו ניתן לשינוי על ידי המוזמן."
                    else
                        "Choose the invited role (Child or Parent) and generate a secure one-time invitation code for \"$familyName\". The role is locked upon creation.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Step 1: Role Selection (Locked once an invitation is generated)
                Text(
                    text = if (isHebrew) "1. בחירת תפקיד במשפחה (קבוע):" else "1. Select Family Role (Immutable):",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(8.dp))

                val roles = listOf(UserRole.CHILD, UserRole.PARENT)
                roles.forEach { r ->
                    val activeRole = generatedInvitation?.invitedRole ?: selectedRole
                    val isSelected = activeRole == r
                    val isLocked = generatedInvitation != null || isCreatingInvitation
                    val desc = when (r) {
                        UserRole.CHILD -> if (isHebrew)
                            "ילד/ה (חבר משפחה): צפייה במשימות, סימון משימות אישיות כבוצע וצבירת נקודות לפרסים (ללא הרשאות ניהול)"
                        else
                            "Child: View tasks, complete assigned tasks, and earn reward points (no admin access)"
                        UserRole.PARENT -> if (isHebrew)
                            "הורה (מנהל משפחה): הרשאות ניהול מלאות — יצירת משימות, הזמנת חברים וניהול הגדרות המשפחה"
                        else
                            "Parent: Full management permissions — tasks, invitations, members, and family settings"
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) GoogleBlue else Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !isLocked) { selectedRole = r }
                            .testTag("invite_role_${r.name}")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { if (!isLocked) selectedRole = r },
                                enabled = !isLocked
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = r.getDisplayName(isHebrew),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) GoogleBlueDark else MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Optional nickname / label for the parent's pending invitations list
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { if (generatedInvitation == null && !isCreatingInvitation) nickname = it },
                    enabled = generatedInvitation == null && !isCreatingInvitation,
                    label = {
                        Text(
                            if (isHebrew) "שם / כינוי המוזמן (אופציונלי, לזיהוי ברשימת ההמתנה)"
                            else "Invitee Name / Note (optional)"
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("invite_member_name_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Error Banner & Retry Button if Firestore write failed (Requirement 4 & 5)
                if (generatedInvitation == null && !invitationErrorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("invite_creation_error_banner")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = invitationErrorMessage,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            )
                            OutlinedButton(
                                onClick = { triggerCreateInvite() },
                                enabled = !isCreatingInvitation,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("retry_create_invite_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isHebrew) "נסה שוב ליצור קוד הזמנה" else "Retry Creating Invite Code",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Generate Button (if not yet generated and confirmed in Firestore)
                if (generatedInvitation == null) {
                    Button(
                        onClick = { triggerCreateInvite() },
                        enabled = !isCreatingInvitation,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("generate_invite_code_btn")
                    ) {
                        if (isCreatingInvitation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isHebrew) "שומר ומאמת קוד הזמנה בענן..." else "Saving & Verifying Invite in Cloud...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        } else {
                            Icon(imageVector = Icons.Default.VpnKey, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isHebrew) "צור קוד הזמנה מאובטח" else "Generate Secure Invite Code",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                // Step 2: Generated Invitation Code & Share Actions (WhatsApp / Copy / Sharesheet)
                AnimatedVisibility(visible = generatedInvitation != null) {
                    generatedInvitation?.let { invitation ->
                        val roleBadge = if (invitation.invitedRole == UserRole.PARENT) {
                            if (isHebrew) "הורה (מנהל משפחה)" else "Parent (PARENT)"
                        } else {
                            if (isHebrew) "ילד/ה (חבר משפחה)" else "Child (CHILD)"
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = GoogleGreenLight,
                            border = BorderStroke(1.5.dp, GoogleGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("generated_invitation_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = GoogleGreenDark,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (isHebrew)
                                            "קוד הזמנה מוכן לשיתוף! (תפקיד קבוע: $roleBadge)"
                                        else
                                            "Invite Code Ready! (Locked Role: $roleBadge)",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleGreenDark
                                        ),
                                        textAlign = TextAlign.Center
                                    )
                                }

                                // Large Prominent Code Display + Copy Button
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.White,
                                    border = BorderStroke(1.5.dp, GoogleGreen.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = if (isHebrew) "קוד הזמנה חד-פעמי (תקף ל-7 ימים):" else "One-Time Invite Code (Valid 7 days):",
                                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                            )
                                            Text(
                                                text = invitation.code,
                                                style = MaterialTheme.typography.headlineMedium.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = GoogleGreenDark,
                                                    letterSpacing = 3.sp
                                                ),
                                                modifier = Modifier.testTag("invite_code_display")
                                            )
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(invitation.code))
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (isHebrew) "קוד ההזמנה ${invitation.code} הועתק!" else "Code ${invitation.code} copied!",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.testTag("copy_invite_code_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy Code",
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isHebrew) "העתק" else "Copy",
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Share directly via WhatsApp (Readable Hebrew message without broken domain links)
                                Button(
                                    onClick = {
                                        val shareText = buildDomainFreeShareMessage(invitation)
                                        val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            setPackage("com.whatsapp")
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        }
                                        try {
                                            context.startActivity(whatsappIntent)
                                        } catch (_: Exception) {
                                            val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(
                                                    fallbackIntent,
                                                    if (isHebrew) "שתף הזמנה למשפחה" else "Share Family Invite"
                                                )
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF25D366),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("whatsapp_share_btn")
                                ) {
                                    Icon(imageVector = Icons.Default.Share, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isHebrew) "שלח הזמנה ב-WhatsApp 💬" else "Share via WhatsApp 💬",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // System Sharesheet Button
                                OutlinedButton(
                                    onClick = {
                                        val shareText = buildDomainFreeShareText(invitation, ::buildDomainFreeShareMessage)
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(
                                                sendIntent,
                                                if (isHebrew) "שתף קוד הזמנה למשפחה" else "Share Invite Code"
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("system_share_btn")
                                ) {
                                    Icon(imageVector = Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isHebrew) "שיתוף באפליקציות אחרות" else "More Share Options",
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Done / Close button
                                TextButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("confirm_invite_btn")
                                ) {
                                    Text(
                                        text = if (isHebrew) "סיום וסגירה ✓" else "Done ✓",
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleGreenDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildDomainFreeShareText(
    invitation: FamilyInvitation,
    formatter: (FamilyInvitation) -> String
): String = formatter(invitation)
