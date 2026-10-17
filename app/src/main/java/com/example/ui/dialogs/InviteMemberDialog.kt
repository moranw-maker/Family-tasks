package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.FamilyMember
import com.example.model.UserRole
import com.example.ui.theme.*

import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
fun InviteMemberDialog(
    currentMemberCount: Int,
    isHebrew: Boolean,
    memberToEdit: FamilyMember? = null,
    familyTitle: String = if (isHebrew) "משפחת וקסלר" else "Wexler Family",
    inviteCode: String = "FAM-WEXLER-7749",
    inviteLink: String = "https://familytasks.app/join/$inviteCode",
    onDismiss: () -> Unit,
    onConfirm: (name: String, email: String, role: UserRole, avatarEmoji: String, schoolGrade: String?) -> Unit,
    onDeleteMember: ((String) -> Unit)? = null
) {
    val isEditMode = memberToEdit != null
    var name by remember(memberToEdit) { mutableStateOf(memberToEdit?.name ?: "") }
    var email by remember(memberToEdit) { mutableStateOf(memberToEdit?.email ?: "") }
    var selectedRole by remember(memberToEdit) { mutableStateOf(memberToEdit?.role ?: UserRole.MEMBER_KID) }
    var selectedEmoji by remember(memberToEdit) { mutableStateOf(memberToEdit?.avatarEmoji ?: "🧒") }
    var schoolGrade by remember(memberToEdit) { mutableStateOf(memberToEdit?.schoolGrade ?: "") }

    val emojis = listOf("🧒", "👧", "👦", "👶", "👨", "👩", "👵", "👴", "🐶", "🐱", "🦁", "🦄", "⚽", "🎨")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("invite_member_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = if (isEditMode) Icons.Default.Edit else Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = GoogleBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isEditMode) {
                                    if (isHebrew) "עריכת פרטי משתמש" else "Edit Family Member"
                                } else {
                                    if (isHebrew) "הזמנת חבר משפחה חדש" else "Invite Family Member"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlue
                                )
                            )
                        }
                        Text(
                            text = if (isEditMode) {
                                if (isHebrew) "עדכון שם, הרשאות תפקיד ואימוג'י" else "Update profile, role permissions & avatar"
                            } else {
                                if (isHebrew) "משפחה נוכחית: $currentMemberCount/10 משתמשים" else "Household: $currentMemberCount/10 members"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Name Input
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isHebrew) "שם מלא (לדוגמה: אורי)" else "Full Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("member_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Email Input
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(if (isHebrew) "כתובת אימייל (חשבון Google)" else "Email (Google account)") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("member_email_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Role Selector (Kid, Secondary Admin, Primary Admin)
                Text(
                    text = if (isHebrew) "תפקיד והרשאות:" else "Role & Permissions:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val availableRoles = if (memberToEdit?.role == UserRole.PRIMARY_ADMIN) {
                        listOf(
                            UserRole.PRIMARY_ADMIN to (if (isHebrew) "מנהל ראשי" else "Primary Admin")
                        )
                    } else {
                        listOf(
                            UserRole.MEMBER_KID to (if (isHebrew) "ילד / חבר" else "Kid / Member"),
                            UserRole.SECONDARY_ADMIN to (if (isHebrew) "מנהל משני" else "Co-Admin")
                        )
                    }

                    availableRoles.forEach { (role, label) ->
                        val isSelected = selectedRole == role
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedRole = role }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                // Optional Grade for kids
                if (selectedRole == UserRole.MEMBER_KID) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = schoolGrade,
                        onValueChange = { schoolGrade = it },
                        label = { Text(if (isHebrew) "כיתה / מסגרת (לדוגמה: כיתה ד׳)" else "Grade (optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Avatar Emoji Selector
                Text(
                    text = if (isHebrew) "בחר אייקון / אימוג'י:" else "Pick Avatar:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(emojis) { emoji ->
                        val isSelected = selectedEmoji == emoji
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { selectedEmoji = emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = emoji, fontSize = 20.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!isEditMode) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val clipboardManager = LocalClipboardManager.current

                    // Dynamic Family Code Card
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isHebrew) "קוד הזמנה ייחודי ל-$familyTitle:" else "Invite Code for $familyTitle:",
                                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                    Text(
                                        text = inviteCode,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleBlue
                                        )
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(inviteLink))
                                        android.widget.Toast.makeText(
                                            context,
                                            if (isHebrew) "הקישור הועתק ללוח! $inviteLink" else "Invite link copied! $inviteLink",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = GoogleBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GoogleBlueLight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val shareText = if (isHebrew) {
                                    "הצטרף למרכז המשימות והניהול של $familyTitle! הורד את האפליקציה או התחבר דרך הקוד: $inviteCode\nקישור ישיר: $inviteLink"
                                } else {
                                    "Join the task and family hub for $familyTitle! Connect with code: $inviteCode\nDirect link: $inviteLink"
                                }
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, if (isHebrew) "שתף הזמנה למשפחה" else "Share Family Invite"))
                            }
                            .testTag("share_invite_link_btn")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = GoogleBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isHebrew) "שיתוף קישור הזמנה (WhatsApp / הודעה)" else "Share Invite Link (WhatsApp / Messages)",
                                style = MaterialTheme.typography.bodySmall.copy(color = GoogleBlue, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Actions: Save/Invite + Delete (if editing & not last admin)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (memberToEdit != null && !memberToEdit.isPrimaryAdmin && onDeleteMember != null) {
                        OutlinedButton(
                            onClick = {
                                onDeleteMember(memberToEdit.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleRed),
                            modifier = Modifier
                                .weight(0.35f)
                                .height(48.dp)
                                .testTag("delete_member_btn")
                        ) {
                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                        }
                    }

                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                onConfirm(
                                    name.trim(),
                                    email.trim().ifBlank { "${name.lowercase().replace(" ", "")}@familyhub.app" },
                                    selectedRole,
                                    selectedEmoji,
                                    schoolGrade.trim().ifBlank { null }
                                )
                            }
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("submit_invite_btn")
                    ) {
                        Text(
                            text = if (isEditMode) {
                                if (isHebrew) "שמור שינויים ✓" else "Save Changes ✓"
                            } else {
                                if (isHebrew) "שלח הזמנה והוסף למשפחה" else "Send Invite & Add Member"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
