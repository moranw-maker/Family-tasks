package com.example.ui.components

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.CloudFamilyGroup
import com.example.model.FamilyMember
import com.example.model.UserRole
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleProfileSheet(
    familyName: String,
    currentMember: FamilyMember,
    allMembers: List<FamilyMember>,
    isGoogleSignedIn: Boolean,
    googleEmail: String,
    cloudFamilyGroup: CloudFamilyGroup,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSwitchMember: (FamilyMember) -> Unit,
    onOpenInviteDialog: () -> Unit,
    onOpenEditFamilyName: () -> Unit,
    onOpenGoogleSignIn: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onUpdateMemberAvatar: (targetMemberId: String, avatarId: String) -> Unit = { _, _ -> }
) {
    val activeMembers = remember(allMembers) {
        allMembers.filter { it.invitationAccepted && !it.invitationPending }
    }
    var memberForAvatarPicker by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<FamilyMember?>(null)
    }

    memberForAvatarPicker?.let { target ->
        AvatarPickerDialog(
            targetMember = target,
            isHebrew = isHebrew,
            onDismiss = { memberForAvatarPicker = null },
            onSaveAvatar = { chosenAvatarId ->
                onUpdateMemberAvatar(target.id, chosenAvatarId)
                memberForAvatarPicker = null
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("google_profile_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // 1. Google Account & Cloud Family Group Card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = GoogleBlueLight,
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!currentMember.photoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = currentMember.photoUrl,
                                contentDescription = currentMember.name,
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
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "G",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 24.sp,
                                    color = GoogleBlue
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (isGoogleSignedIn) {
                                        if (isHebrew) "מחובר לענן המשפחתי" else "Connected to Cloud Family"
                                    } else {
                                        if (isHebrew) "חשבון Google מקומי" else "Local Google Account"
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = GoogleBlueDark,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = null,
                                    tint = GoogleGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Text(
                                text = familyName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary
                                )
                            )

                            Text(
                                text = googleEmail,
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }

                        if (currentMember.isParent) {
                            IconButton(
                                onClick = onOpenEditFamilyName,
                                modifier = Modifier.testTag("sheet_edit_family_name_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Family Name",
                                    tint = GoogleBlueDark
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Cloud Sync Info & Sign-in/Invite Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenGoogleSignIn,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sheet_google_sync_btn")
                        ) {
                            Icon(imageVector = Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isHebrew) "חשבון וענן" else "Cloud Sync",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        if (currentMember.isParent) {
                            OutlinedButton(
                                onClick = onOpenInviteDialog,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sheet_invite_member_btn")
                            ) {
                                Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isHebrew) "+ הזמן חבר" else "+ Invite",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { memberForAvatarPicker = currentMember },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sheet_choose_avatar_btn")
                    ) {
                        Text(
                            text = if (isHebrew) "🎨 בחר אווטאר" else "🎨 Choose Avatar",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = GoogleBlueDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Active Member Switcher Header
            Text(
                text = if (isHebrew)
                    "חברי המשפחה הפעילים (${activeMembers.size}):"
                else
                    "Active Family Members (${activeMembers.size}):",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
            ) {
                items(activeMembers, key = { it.id }) { member ->
                    val isSelected = member.id == currentMember.id
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) GoogleBlueLight else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                onSwitchMember(member)
                                onDismiss()
                            }
                            .testTag("profile_sheet_member_${member.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (!member.photoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = member.photoUrl,
                                        contentDescription = member.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                    )
                                } else {
                                    FamilyMemberAvatar(
                                        member = member,
                                        size = 38.dp,
                                        isHighlighted = isSelected
                                    )
                                }

                                Column {
                                    Text(
                                        text = member.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) GoogleBlueDark else MaterialTheme.colorScheme.onSurface
                                        )
                                    )

                                    val roleColor = if (member.role == UserRole.PARENT) GoogleBlue else GoogleGreenDark
                                    Text(
                                        text = "${member.role.getDisplayName(isHebrew)} • ⭐ ${member.earnedPoints} ${if (isHebrew) "נק'" else "pts"}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = roleColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Active",
                                    tint = GoogleBlue
                                )
                            }
                        }
                    }
                }
            }

            if (isGoogleSignedIn) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onSignOutGoogle,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .testTag("sheet_sign_out_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = null,
                        tint = GoogleRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "התנתק מחשבון Google" else "Sign out of Google",
                        color = GoogleRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
