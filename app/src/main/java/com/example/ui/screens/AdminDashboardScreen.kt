package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilyMemberAvatar
import com.example.ui.components.FamilyPrimaryButton
import com.example.ui.components.FamilySecondaryButton
import com.example.ui.components.FamilySectionHeader
import com.example.ui.components.FamilyStatusChip
import com.example.ui.components.SoftGlassScreenBackground
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
    onFormatInvitationShareText: ((FamilyInvitation) -> String)? = null,
    listState: LazyListState = rememberLazyListState()
) {
    val glass = LocalSoftGlassColors.current
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

    SoftGlassScreenBackground(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
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

            // 1. Soft Glass Family Header & Stats Card
            item {
                FamilyGlassCard(
                    cornerRadius = 26.dp,
                    isTintedBlue = true,
                    elevation = 5.dp,
                    contentPadding = PaddingValues(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = glass.primaryAccentSoftBg,
                                border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = glass.primaryAccent,
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
                                        color = glass.primaryAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = familyName,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.textPrimary
                                    )
                                )
                            }
                        }

                        if (currentMember.isParent) {
                            FilledTonalIconButton(
                                onClick = onEditFamilyName,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = glass.primaryAccentSoftBg,
                                    contentColor = glass.primaryAccent
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

            // 2. Role Permissions Explanation Card (PARENT vs CHILD)
            item {
                FamilyGlassCard(
                    cornerRadius = 22.dp,
                    elevation = 2.dp,
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isHebrew) "🔐 הרשאות במשפחה (2 תפקידים בלבד)" else "🔐 Family Roles (Strictly 2 Roles)",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.textPrimary
                            )
                        )

                        RoleBadgeExplainRow(
                            badgeText = if (isHebrew) "הורה (PARENT)" else "PARENT",
                            badgeColor = glass.primaryAccent,
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        FamilySectionHeader(
                            title = if (isHebrew)
                                "חברי המשפחה הפעילים (${activeMembers.size})"
                            else
                                "Active Family Members (${activeMembers.size})"
                        )
                        FamilyStatusChip(
                            label = "LIVE",
                            containerColor = glass.successBg,
                            contentColor = glass.successText
                        )
                    }

                    if (currentMember.isParent) {
                        FamilyPrimaryButton(
                            text = if (isHebrew) "+ הזמן חבר משפחה" else "+ Invite Member",
                            leadingIcon = Icons.Default.PersonAdd,
                            onClick = onInviteMember,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("admin_invite_member_btn")
                        )
                    }
                }
            }

            // 4. Active Members List (Real-time updated from Firestore)
            items(activeMembers, key = { it.id }) { member ->
                val isActiveProfile = member.id == currentMember.id
                val isLastParent = member.isParent && activeParentCount <= 1

                FamilyGlassCard(
                    cornerRadius = 22.dp,
                    isSelected = isActiveProfile,
                    elevation = 3.dp,
                    contentPadding = PaddingValues(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_member_card_${member.id}")
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
                            // Profile Picture when available, otherwise FamilyMemberAvatar
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
                                FamilyMemberAvatar(
                                    member = member,
                                    size = 48.dp,
                                    isHighlighted = isActiveProfile
                                )
                            }

                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = member.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glass.textPrimary
                                        )
                                    )
                                    if (isActiveProfile) {
                                        FamilyStatusChip(
                                            label = if (isHebrew) "פעיל" else "Active"
                                        )
                                    }
                                }

                                val roleColor = if (member.isParent) glass.primaryAccent else glass.successText
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
                                            color = glass.textSecondary,
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
                                    tint = glass.dangerText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Role Management Controls (Only PARENT can change roles; last PARENT is protected)
                    if (currentMember.isParent) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = glass.divider)
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
                                        tint = glass.primaryAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isHebrew)
                                            "הורה יחיד במשפחה (מוגן משנמוך או הסרה)"
                                        else
                                            "Last remaining Parent (protected)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = glass.primaryAccent,
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

                                FamilySecondaryButton(
                                    text = buttonLabel,
                                    leadingIcon = Icons.Default.SwapHoriz,
                                    onClick = { onChangeRole(member.id, targetRole) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("change_role_btn_${member.id}")
                                )
                            }

                            if (!isActiveProfile) {
                                TextButton(
                                    onClick = { onSwitchMember(member) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .height(32.dp)
                                        .testTag("admin_switch_view_btn_${member.id}")
                                ) {
                                    Text(
                                        text = if (isHebrew) "החלף תצוגה" else "Preview as",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = glass.primaryAccent
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Separate Pending Invitations Section (V1.6 Requirement 6)
            item {
                Spacer(modifier = Modifier.height(6.dp))
                FamilySectionHeader(
                    title = if (isHebrew)
                        "הזמנות ממתינות לאישור (${pendingInvitations.size})"
                    else
                        "Pending Invitations (${pendingInvitations.size})",
                    modifier = Modifier.testTag("pending_invitations_section_header")
                )
            }

            if (pendingInvitations.isEmpty()) {
                item {
                    FamilyGlassCard(
                        cornerRadius = 20.dp,
                        isTintedBlue = true,
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("no_pending_invitations_card")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(text = "✉️", fontSize = 22.sp)
                            Column {
                                Text(
                                    text = if (isHebrew) "אין הזמנות ממתינות כרגע" else "No pending invitations",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = glass.textPrimary
                                    )
                                )
                                Text(
                                    text = if (isHebrew)
                                        "לחצו על \"+ הזמן חבר משפחה\" כדי ליצור קוד הזמנה להורה או לילד/ה."
                                    else
                                        "Tap \"+ Invite Member\" to generate a PARENT or CHILD invitation code.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
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

                    FamilyGlassCard(
                        cornerRadius = 20.dp,
                        accentColor = GoogleYellow,
                        contentPadding = PaddingValues(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pending_invite_card_${invite.code}")
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                                color = glass.warningText
                                            )
                                        )
                                        FamilyStatusChip(
                                            label = if (isHebrew) "ממתינה לאישור ⏳" else "Pending ⏳",
                                            containerColor = glass.warningBg,
                                            contentColor = glass.warningText
                                        )
                                    }

                                    Text(
                                        text = if (isHebrew)
                                            "קוד הזמנה: ${invite.code} • תפקיד קבוע: $roleLabel"
                                        else
                                            "Invite Code: ${invite.code} • Locked Role: $roleLabel",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = glass.textPrimary
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
                                            tint = glass.dangerText
                                        )
                                    }
                                }
                            }

                            // Copy & WhatsApp Share actions for pending invitation
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FamilySecondaryButton(
                                    text = if (isHebrew) "העתק ${invite.code}" else "Copy ${invite.code}",
                                    leadingIcon = Icons.Default.ContentCopy,
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(invite.code))
                                        android.widget.Toast.makeText(
                                            context,
                                            if (isHebrew) "קוד ${invite.code} הועתק!" else "Code ${invite.code} copied!",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .testTag("copy_pending_invite_${invite.code}")
                                )

                                FamilyPrimaryButton(
                                    text = "WhatsApp 💬",
                                    leadingIcon = Icons.Default.Share,
                                    containerColor = Color(0xFF25D366),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
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
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .testTag("whatsapp_pending_invite_${invite.code}")
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
    val glass = LocalSoftGlassColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = glass.cardBackground,
        border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.25f)),
        shadowElevation = 2.dp,
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
                    color = glass.primaryAccent
                )
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = glass.textSecondary,
                    fontWeight = FontWeight.SemiBold
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
    val glass = LocalSoftGlassColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = badgeColor.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
            modifier = Modifier.width(108.dp)
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = badgeColor
                ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(
                color = glass.textSecondary,
                fontSize = 11.sp
            ),
            modifier = Modifier.weight(1f)
        )
    }
}
