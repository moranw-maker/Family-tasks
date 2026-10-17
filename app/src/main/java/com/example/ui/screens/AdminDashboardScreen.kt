package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*

@Composable
fun AdminDashboardScreen(
    members: List<FamilyMember>,
    tasks: List<FamilyTask>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    familyTitle: String = if (isHebrew) "משפחת וקסלר" else "Wexler Family",
    onInviteMember: () -> Unit,
    onEditMember: (FamilyMember) -> Unit,
    onEditFamilyName: () -> Unit = {},
    onUpdateRole: (String, UserRole) -> Unit,
    onRemoveMember: (String) -> Unit,
    onSendNudge: (FamilyTask) -> Unit,
    onShowToast: (String) -> Unit
) {
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val totalTasks = tasks.size
    val completedTasks = tasks.count { it.isCompleted }
    val completionRate = if (totalTasks > 0) (completedTasks * 100) / totalTasks else 100

    val kids = members.filter { it.role == UserRole.MEMBER_KID }.sortedByDescending { it.earnedPoints }
    val cleanFamilyTag = familyTitle
        .replace("משפחת", "")
        .replace("Family", "", ignoreCase = true)
        .trim()
        .replace(Regex("[^A-Za-z0-9א-ת]"), "")
        .uppercase()
    val familyCodePrefix = if (cleanFamilyTag.isNotBlank()) cleanFamilyTag else "HOUSE"
    val inviteCode = "FAM-$familyCodePrefix-7749"
    val inviteLink = "https://familytasks.app/join/$inviteCode"

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Quick Screen Help Guide
        item {
            com.example.ui.components.ScreenHelpBanner(
                title = if (isHebrew) "ניהול חברי המשפחה והרשאות" else "Household Admin Guide",
                tips = if (isHebrew) listOf(
                    "הזמנת חברים חדשים: שלחו את קוד ההזמנה או לחצו על 'הזמן' לשיתוף ב-WhatsApp.",
                    "הגדרת הרשאות: מנהל ראשי, מנהל משני (עריכה ואישור) וילדים (צבירת נקודות).",
                    "שינוי שם המשפחה: ניתן לעדכן את שם המשפחה הראשי המוצג בכל חלקי האפליקציה.",
                    "תזכורות (Nudge): לחצו על כפתור התזכורת כדי לשלוח התראה מיידית לבני הבית."
                ) else listOf(
                    "Invite members: Share the unique household code or tap 'Invite' to share via WhatsApp.",
                    "Role permissions: Primary Admin, Co-Admin, and Kids.",
                    "Rename family: Change the main household name displayed across the entire app.",
                    "Send nudges: Remind family members about due tasks instantly."
                ),
                isHebrew = isHebrew,
                testTag = "admin_help_banner"
            )
        }

        // Dashboard Overview Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = GoogleBlueLight,
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
                        Column {
                            Text(
                                text = if (isHebrew) "דאשבורד מנהלי הבית" else "Household Admin Dashboard",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlueDark
                                )
                            )
                            Text(
                                text = if (isHebrew)
                                    "מעקב משימות, ניהול מנהלים משניים וחלוקת תפקידים"
                                else
                                    "Track chores, manage co-admins, and monitor progress",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GoogleBlue
                        ) {
                            Text(
                                text = currentMember.role.getDisplayName(isHebrew),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Progress Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isHebrew) "אחוז ביצוע משימות שבועי" else "Weekly Chores Completion Rate",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "$completionRate%",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = GoogleGreenDark
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { completionRate / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = GoogleGreen,
                        trackColor = Color.White
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        StatPill(
                            label = if (isHebrew) "הושלמו" else "Done",
                            value = "$completedTasks",
                            color = GoogleGreen
                        )
                        StatPill(
                            label = if (isHebrew) "ממתינות" else "Pending",
                            value = "${totalTasks - completedTasks}",
                            color = GoogleYellowDark
                        )
                        StatPill(
                            label = if (isHebrew) "חברים" else "Members",
                            value = "${members.size}/10",
                            color = GoogleBlue
                        )
                    }
                }
            }
        }

        // Leaderboard / Champions Table (Gamification for kids)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "🏆", fontSize = 20.sp)
                        Text(
                            text = if (isHebrew) "טבלת אלופי המשימות (ניקוד ילדים)" else "Kids Chore Leaderboard",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    kids.forEachIndexed { index, kid ->
                        val medal = when (index) {
                            0 -> "🥇"
                            1 -> "🥈"
                            2 -> "🥉"
                            else -> "⭐"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(text = medal, fontSize = 20.sp)

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(kid.colorHex).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = kid.avatarEmoji, fontSize = 18.sp)
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = kid.name + (if (kid.schoolGrade != null) " • ${kid.schoolGrade}" else ""),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${kid.completedTasksCount} " + if (isHebrew) "משימות שבוצעו" else "chores completed",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = GoogleYellowLight
                            ) {
                                Text(
                                    text = "${kid.earnedPoints} " + if (isHebrew) "נק׳" else "pts",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleYellowDark
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (index < kids.size - 1) {
                            HorizontalDivider(color = OutlineLight.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }

        // Family Members Management & Invitation Section (up to 10 members)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = if (isHebrew) "ניהול חברי המשפחה והרשאות" else "Household Members & Roles",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (isHebrew)
                                    "עד 10 משתמשים. מנהל ראשי ומנהל משני"
                                else
                                    "Up to 10 members. Primary & Secondary Admin",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }

                        if (currentMember.isAdmin && members.size < 10) {
                            FilledTonalButton(
                                onClick = onInviteMember,
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("admin_invite_btn")
                            ) {
                                Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = if (isHebrew) "הזמן" else "Invite", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Main Family Name Card with Edit option
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
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isHebrew) "שם המשפחה הראשי באפליקציה:" else "Main Family Name:",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                Text(
                                    text = familyTitle,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleBlue
                                    )
                                )
                            }

                            if (currentMember.isAdmin) {
                                Button(
                                    onClick = onEditFamilyName,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("admin_edit_family_name_btn")
                                ) {
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = if (isHebrew) "ערוך שם" else "Edit Name", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Unique Invite Link & Code Card
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
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isHebrew) "קישור וקוד הזמנה ייחודי למשפחה:" else "Unique Family Invite Link & Code:",
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

                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(inviteLink))
                                    onShowToast(if (isHebrew) "הקישור הועתק ללוח! שתפו עם בני המשפחה" else "Invite link copied to clipboard!")
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("copy_invite_link_btn")
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = if (isHebrew) "העתק" else "Copy", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Members List with Role Promotion / Secondary Admin options
                    members.forEach { member ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(member.colorHex).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = member.avatarEmoji, fontSize = 20.sp)
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = member.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    if (member.isPrimaryAdmin) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = GoogleBlueLight
                                        ) {
                                            Text(
                                                text = "👑 " + member.role.getDisplayName(isHebrew),
                                                color = GoogleBlueDark,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else if (member.role == UserRole.SECONDARY_ADMIN) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = GoogleGreenLight
                                        ) {
                                            Text(
                                                text = "🛡️ " + member.role.getDisplayName(isHebrew),
                                                color = GoogleGreenDark,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = member.email,
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }

                            // Edit Member Details button
                            if (currentMember.isAdmin) {
                                IconButton(
                                    onClick = { onEditMember(member) },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("edit_member_${member.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Member",
                                        tint = GoogleBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Primary Admin controls: Promote to Secondary Admin or Demote
                            if (currentMember.isPrimaryAdmin && !member.isPrimaryAdmin) {
                                if (member.role == UserRole.SECONDARY_ADMIN) {
                                    OutlinedButton(
                                        onClick = {
                                            onUpdateRole(member.id, UserRole.MEMBER_KID)
                                            onShowToast(if (isHebrew) "${member.name} הוגדר כחבר רגיל" else "${member.name} set to Member")
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text(text = if (isHebrew) "הסר ניהול" else "Demote", fontSize = 10.sp)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = {
                                            onUpdateRole(member.id, UserRole.SECONDARY_ADMIN)
                                            onShowToast(if (isHebrew) "${member.name} מונה כמנהל משני בעל הרשאות מלאות!" else "${member.name} appointed as Secondary Admin!")
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("make_secondary_admin_${member.id}")
                                    ) {
                                        Text(text = if (isHebrew) "הפוך למנהל משני" else "Make Co-Admin", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatPill(label: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
            )
        }
    }
}
