package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun AdminDashboardScreen(
    familyName: String,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    tasks: List<FamilyTask>,
    documents: List<FamilyDocument>,
    pendingInvitations: List<FamilyInvitation> = emptyList(),
    isHebrew: Boolean,
    onEditFamilyName: () -> Unit,
    onInviteMember: () -> Unit,
    onSwitchMember: (FamilyMember) -> Unit,
    onChangeRole: (memberId: String, newRole: UserRole) -> Unit,
    onAcceptInvite: (memberId: String) -> Unit,
    onRemoveMember: (memberId: String) -> Unit,
    onRevokeInvitation: (code: String) -> Unit = {},
    onGenerateShareText: (name: String, role: UserRole) -> String = { _, _ -> "" },
    onFormatInvitationShareText: ((FamilyInvitation) -> String)? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // V1.6 Requirement 6: Active members NEVER include pending invitations
    val activeMembers = remember(members) {
        members.filter { it.invitationAccepted && !it.invitationPending }
    }
    val activeParentCount = remember(activeMembers) {
        activeMembers.count { it.isParent }
    }

    val totalTasks = tasks.size
    val completedTasks = tasks.count { it.isCompleted }
    val completionRate = if (totalTasks > 0) (completedTasks * 100) / totalTasks else 0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("admin_dashboard_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick Screen Help Guide
        item {
            com.example.ui.components.ScreenHelpBanner(
                title = if (isHebrew)
                    "ניהול משפחה והרשאות (${com.example.util.AppVersionManager.displayVersionLabel})"
                else
                    "Family & Permissions Management (${com.example.util.AppVersionManager.displayVersionLabel})",
                tips = if (isHebrew) listOf(
                    "שני תפקידים במשפחה: הורה (מנהל משפחה עם הרשאות מלאות) וילד/ה (צפייה וביצוע משימות אישיות).",
                    "הזמנת חברים: צרו קוד הזמנה ייחודי לתפקיד הורה או ילד/ה ושתפו אותו ב-WhatsApp ללא צורך באתר חיצוני.",
                    "עדכון בזמן אמת: ברגע שהמוזמן מאשר הצטרפות במכשיר שלו, הוא יופיע מיד ברשימת החברים הפעילים.",
                    "הגנת הורה אחרון: המערכת מונעת הסרה או שנמוך של ההורה האחרון במשפחה."
                ) else listOf(
                    "Two Family Roles: PARENT (full management permissions) and CHILD (view & complete assigned tasks).",
                    "Role-Specific Invitations: Generate a unique invite code for PARENT or CHILD and share via WhatsApp.",
                    "Real-Time Member List: Newly joined members appear automatically without restarting.",
                    "Last Parent Protection: The last remaining PARENT cannot be removed or demoted."
                ),
                isHebrew = isHebrew
            )
        }

        // 1. Family Header & Edit Name Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = GoogleBlue,
                contentColor = Color.White,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.2f),
                                modifier = Modifier.size(46.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = if (isHebrew)
                                        "ניהול משפחה והרשאות • ${com.example.util.AppVersionManager.displayVersionLabel}"
                                    else
                                        "Family Management • ${com.example.util.AppVersionManager.displayVersionLabel}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = Color.White.copy(alpha = 0.85f)
                                    )
                                )
                                Text(
                                    text = familyName,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                )
                            }
                        }

                        if (currentMember.isParent) {
                            FilledTonalIconButton(
                                onClick = onEditFamilyName,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.22f),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.testTag("admin_edit_family_name_btn")
                            ) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Family Name")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Stats summary row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AdminStatBox(
                            modifier = Modifier.weight(1f),
                            value = "${activeMembers.size}",
                            label = if (isHebrew) "חברים פעילים" else "Active Members"
                        )
                        AdminStatBox(
                            modifier = Modifier.weight(1f),
                            value = "$completionRate%",
                            label = if (isHebrew) "השלמת מטלות" else "Tasks Done"
                        )
                        AdminStatBox(
                            modifier = Modifier.weight(1f),
                            value = "${pendingInvitations.size}",
                            label = if (isHebrew) "הזמנות ממתינות" else "Pending Invites"
                        )
                    }
                }
            }
        }

        // 2. Role Permissions Explanation Card (PARENT vs CHILD)
        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isHebrew) "🔐 הרשאות במשפחה (2 תפקידים בלבד)" else "🔐 Family Roles (Strictly 2 Roles)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )

                    RoleBadgeExplainRow(
                        badgeText = if (isHebrew) "הורה (PARENT)" else "PARENT",
                        badgeColor = GoogleBlue,
                        description = if (isHebrew)
                            "הרשאות ניהול מלאות: יצירה, עריכה ומחיקה של משימות, הזמנת הורים או ילדים, שינוי תפקידים וניהול הגדרות."
                        else
                            "Full management permissions: create/edit/delete tasks, invite members, manage roles and settings."
                    )

                    RoleBadgeExplainRow(
                        badgeText = if (isHebrew) "ילד/ה (CHILD)" else "CHILD",
                        badgeColor = GoogleGreen,
                        description = if (isHebrew)
                            "צפייה במשימות המשפחה, סימון משימות אישיות כבוצע וצבירת נקודות לפרסים (ללא הרשאות ניהול או הזמנה)."
                        else
                            "View tasks, mark assigned tasks completed, and view rewards (cannot create/edit tasks or manage users)."
                    )
                }
            }
        }

        // 3. Active Members Header + Invite Button (PARENT only)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isHebrew)
                            "חברי המשפחה הפעילים (${activeMembers.size})"
                        else
                            "Active Family Members (${activeMembers.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PastelGreenBackground
                    ) {
                        Text(
                            text = "LIVE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = PastelGreenDark,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (currentMember.isParent) {
                    Button(
                        onClick = onInviteMember,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("admin_invite_member_btn")
                    ) {
                        Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHebrew) "+ הזמן חבר משפחה" else "+ Invite Member",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // 4. Active Members List (Real-time updated from Firestore)
        items(activeMembers, key = { it.id }) { member ->
            val isActiveProfile = member.id == currentMember.id
            val isLastParent = member.isParent && activeParentCount <= 1

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = if (isActiveProfile) BorderStroke(2.dp, GoogleBlue) else BorderStroke(1.dp, OutlineLight),
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("admin_member_card_${member.id}")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSwitchMember(member) }
                        ) {
                            // Profile Picture when available, otherwise emoji avatar
                            if (!member.photoUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = member.photoUrl,
                                    contentDescription = member.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(member.colorHex).copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = member.avatarEmoji, fontSize = 24.sp)
                                }
                            }

                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = member.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    if (isActiveProfile) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = GoogleBlueLight
                                        ) {
                                            Text(
                                                text = if (isHebrew) "פעיל" else "Active",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = GoogleBlueDark,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                val roleColor = if (member.isParent) GoogleBlue else GoogleGreenDark
                                val roleBadge = if (member.isParent) {
                                    if (isHebrew) "הורה (PARENT)" else "PARENT"
                                } else {
                                    if (isHebrew) "ילד/ה (CHILD)" else "CHILD"
                                }

                                Text(
                                    text = "$roleBadge • ⭐ ${member.earnedPoints} ${if (isHebrew) "נק'" else "pts"}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = roleColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                )

                                if (member.googleEmail != null) {
                                    Text(
                                        text = "📧 ${member.googleEmail}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }

                        // Remove button (only for PARENT, and disabled for the last remaining PARENT)
                        if (currentMember.isParent && !isLastParent) {
                            IconButton(
                                onClick = { onRemoveMember(member.id) },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("remove_member_btn_${member.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PersonRemove,
                                    contentDescription = "Remove Member",
                                    tint = GoogleRed,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Role Management Controls (Only PARENT can change roles; last PARENT is protected)
                    if (currentMember.isParent) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (isLastParent) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = GoogleBlueDark,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isHebrew)
                                            "הורה יחיד במשפחה (מוגן משנמוך או הסרה)"
                                        else
                                            "Last remaining Parent (protected)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = GoogleBlueDark,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.testTag("last_parent_protected_badge_${member.id}")
                                    )
                                }
                            } else {
                                val targetRole = if (member.isParent) UserRole.CHILD else UserRole.PARENT
                                val buttonLabel = if (member.isParent) {
                                    if (isHebrew) "שנה תפקיד לילד/ה (CHILD)" else "Change to CHILD"
                                } else {
                                    if (isHebrew) "הגדר כהורה (PARENT)" else "Promote to PARENT"
                                }

                                OutlinedButton(
                                    onClick = { onChangeRole(member.id, targetRole) },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("change_role_btn_${member.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapHoriz,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = buttonLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (!isActiveProfile) {
                                TextButton(
                                    onClick = { onSwitchMember(member) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "החלף תצוגה" else "Preview as",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleBlue
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Separate Pending Invitations Section (V1.6 Requirement 6)
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isHebrew)
                        "הזמנות ממתינות לאישור (${pendingInvitations.size})"
                    else
                        "Pending Invitations (${pendingInvitations.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("pending_invitations_section_header")
                )
            }
        }

        if (pendingInvitations.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, OutlineLight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("no_pending_invitations_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = "✉️", fontSize = 22.sp)
                        Column {
                            Text(
                                text = if (isHebrew) "אין הזמנות ממתינות כרגע" else "No pending invitations",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (isHebrew)
                                    "לחצו על \"+ הזמן חבר משפחה\" כדי ליצור קוד הזמנה להורה או לילד/ה."
                                else
                                    "Tap \"+ Invite Member\" to generate a PARENT or CHILD invitation code.",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }
                    }
                }
            }
        } else {
            items(pendingInvitations, key = { it.code }) { invite ->
                val roleLabel = if (invite.invitedRole == UserRole.PARENT) {
                    if (isHebrew) "הורה (PARENT)" else "PARENT"
                } else {
                    if (isHebrew) "ילד/ה (CHILD)" else "CHILD"
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = PastelYellowBackground,
                    border = BorderStroke(1.dp, PastelYellowBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pending_invite_card_${invite.code}")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (invite.invitedNickname.isNotBlank())
                                            "${invite.invitedNickname} ($roleLabel)"
                                        else
                                            if (isHebrew) "הזמנה לתפקיד $roleLabel" else "Invite for $roleLabel",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = PastelYellowDark
                                        )
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.White
                                    ) {
                                        Text(
                                            text = if (isHebrew) "ממתינה לאישור ⏳" else "Pending ⏳",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = PastelYellowDark,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = if (isHebrew)
                                        "קוד הזמנה: ${invite.code} • תפקיד קבוע: $roleLabel"
                                    else
                                        "Invite Code: ${invite.code} • Locked Role: $roleLabel",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                )
                            }

                            if (currentMember.isParent) {
                                IconButton(
                                    onClick = { onRevokeInvitation(invite.code) },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("revoke_invite_btn_${invite.code}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = "Revoke Invitation",
                                        tint = GoogleRed
                                    )
                                }
                            }
                        }

                        // Copy & WhatsApp Share actions for pending invitation
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(invite.code))
                                    android.widget.Toast.makeText(
                                        context,
                                        if (isHebrew) "קוד ${invite.code} הועתק!" else "Code ${invite.code} copied!",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .testTag("copy_pending_invite_${invite.code}")
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isHebrew) "העתק ${invite.code}" else "Copy ${invite.code}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = {
                                    val shareText = onFormatInvitationShareText?.invoke(invite)
                                        ?: onGenerateShareText(invite.invitedNickname, invite.invitedRole)
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
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF25D366),
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .testTag("whatsapp_pending_invite_${invite.code}")
                            ) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "WhatsApp 💬",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminStatBox(
    modifier: Modifier = Modifier,
    value: String,
    label: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.16f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.White.copy(alpha = 0.85f)
                )
            )
        }
    }
}

@Composable
private fun RoleBadgeExplainRow(
    badgeText: String,
    badgeColor: Color,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = badgeColor.copy(alpha = 0.15f),
            modifier = Modifier.width(105.dp)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            ),
            modifier = Modifier.weight(1f)
        )
    }
}
