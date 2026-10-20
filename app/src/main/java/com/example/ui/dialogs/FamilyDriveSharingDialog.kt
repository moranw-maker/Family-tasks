package com.example.ui.dialogs

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*

/**
 * Compact, high-visibility Google Drive `FamilyTasks` connection & sharing banner
 * displayed at the top of the Files section.
 */
@Composable
fun GoogleDriveFamilyTasksBanner(
    familyDriveConfig: FamilyDriveConfig,
    currentMember: FamilyMember,
    membersCount: Int,
    isHebrew: Boolean,
    onConnectDrive: () -> Unit,
    onOpenSharingDialog: () -> Unit,
    onOpenGooglePicker: (() -> Unit)? = null
) {
    val isConnected = familyDriveConfig.isConfigured &&
        familyDriveConfig.connectionStatus == DriveConnectionStatus.CONNECTED
    val isConnecting = familyDriveConfig.connectionStatus == DriveConnectionStatus.CONNECTING
    val needsSharedFolderPicker =
        familyDriveConfig.connectionStatus == DriveConnectionStatus.NEEDS_SHARED_FOLDER_PICKER

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("google_drive_familytasks_banner"),
        shape = RoundedCornerShape(16.dp),
        color = when {
            isConnected -> Color(0xFF059669).copy(alpha = 0.10f)
            needsSharedFolderPicker -> Color(0xFFD97706).copy(alpha = 0.14f)
            else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    isConnected -> Color(0xFF059669).copy(alpha = 0.18f)
                                    needsSharedFolderPicker -> Color(0xFFD97706).copy(alpha = 0.22f)
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isConnected -> Icons.Default.CloudDone
                                needsSharedFolderPicker -> Icons.Default.FolderShared
                                else -> Icons.Default.AddToDrive
                            },
                            contentDescription = "Google Drive FamilyTasks",
                            tint = when {
                                isConnected -> Color(0xFF059669)
                                needsSharedFolderPicker -> Color(0xFFD97706)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isHebrew) {
                                "Google Drive • תיקיית $GOOGLE_DRIVE_ROOT_FOLDER_NAME"
                            } else {
                                "Google Drive • $GOOGLE_DRIVE_ROOT_FOLDER_NAME Folder"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val statusSubtitle = when {
                            isConnecting ->
                                if (isHebrew) "מתחבר לתיקיית FamilyTasks ב-Google Drive..."
                                else "Connecting to Google Drive FamilyTasks..."
                            isConnected ->
                                if (isHebrew) "מחובר • תיקיית FamilyTasks משותפת יחידה לכל המשפחה"
                                else "Connected • Single shared FamilyTasks folder for all family members"
                            needsSharedFolderPicker ->
                                familyDriveConfig.errorMessageHe?.takeIf { it.isNotBlank() && isHebrew }
                                    ?: if (isHebrew) {
                                        "נדרש אישור גישה לתיקיית FamilyTasks המשותפת באמצעות בורר הקבצים של Google Drive"
                                    } else {
                                        "Please authorize the shared FamilyTasks folder in Google Picker"
                                    }
                            familyDriveConfig.connectionStatus == DriveConnectionStatus.WAITING_FOR_PARENT_SETUP ->
                                if (isHebrew) "ממתין שהורה יגדיר את תיקיית FamilyTasks ב-Google Drive"
                                else "Waiting for a parent to set up the FamilyTasks folder"
                            familyDriveConfig.connectionStatus == DriveConnectionStatus.NEEDS_OAUTH_AUTHORIZATION ->
                                if (isHebrew) "נדרש אישור גישה ל-Google Drive עבור תיקיית FamilyTasks"
                                else "Google Drive authorization required"
                            else ->
                                if (isHebrew) {
                                    if (currentMember.isParent) {
                                        "הגדירו תיקיית FamilyTasks ב-Google Drive האישי ושיתוף משפחתי"
                                    } else {
                                        "תיקייה משותפת ב-Google Drive עם הרשאות שוות לכל המשפחה"
                                    }
                                } else {
                                    "Shared Google Drive FamilyTasks workspace"
                                }
                        }
                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (needsSharedFolderPicker && onOpenGooglePicker != null) {
                        Button(
                            onClick = onOpenGooglePicker,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("open_google_picker_button")
                        ) {
                            Icon(
                                Icons.Default.FolderShared,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isHebrew) "אישור תיקייה משותפת" else "Select Shared Folder",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    } else if (!isConnected) {
                        Button(
                            onClick = onConnectDrive,
                            enabled = !isConnecting,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("connect_family_drive_button")
                        ) {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isHebrew) "חיבור Drive" else "Connect Drive",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = onOpenSharingDialog,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("open_drive_sharing_button")
                    ) {
                        Icon(
                            Icons.Default.GroupAdd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isHebrew) "שיתוף ($membersCount)" else "Share ($membersCount)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for managing the shared `FamilyTasks` Google Drive root folder and sharing
 * Editor permissions with every family member (both PARENT and CHILD).
 */
@Composable
fun FamilyDriveSharingDialog(
    familyDriveConfig: FamilyDriveConfig,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onConnectOrSetupDrive: () -> Unit,
    onShareWithMember: (memberId: String, email: String) -> Unit,
    onShareWithAllMembers: () -> Unit,
    onUpdateMemberEmail: (memberId: String, email: String) -> Unit,
    onDismiss: () -> Unit
) {
    var editingMemberId by remember { mutableStateOf<String?>(null) }
    var emailDraft by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("family_drive_sharing_dialog"),
        icon = {
            Icon(
                Icons.Default.FolderShared,
                contentDescription = null,
                tint = Color(0xFF059669),
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isHebrew) {
                        "שיתוף תיקיית $GOOGLE_DRIVE_ROOT_FOLDER_NAME ב-Google Drive"
                    } else {
                        "Google Drive $GOOGLE_DRIVE_ROOT_FOLDER_NAME Sharing"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isHebrew) {
                        "כל בני המשפחה (הורים וילדים) מקבלים הרשאות עריכה שוות ומלאות בתיקייה המשותפת"
                    } else {
                        "All family members (parents & children) have equal Editor permissions"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Root folder status card
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isHebrew) "תיקייה ראשית: $GOOGLE_DRIVE_ROOT_FOLDER_NAME"
                                    else "Root Folder: $GOOGLE_DRIVE_ROOT_FOLDER_NAME",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (familyDriveConfig.isConfigured) {
                                        Color(0xFF059669).copy(alpha = 0.16f)
                                    } else {
                                        Color(0xFFD97706).copy(alpha = 0.16f)
                                    }
                                ) {
                                    Text(
                                        text = if (familyDriveConfig.isConfigured) {
                                            if (isHebrew) "פעיל ומסונכרן" else "Active"
                                        } else {
                                            if (isHebrew) "ממתין לחיבור" else "Not Connected"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (familyDriveConfig.isConfigured) Color(0xFF059669) else Color(0xFFD97706),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            if (familyDriveConfig.rootFolderId.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Drive ID: ${familyDriveConfig.rootFolderId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!familyDriveConfig.isConfigured) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = onConnectOrSetupDrive,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("dialog_connect_drive_button"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isHebrew) "צור / חבר תיקיית FamilyTasks ב-Google Drive"
                                        else "Create / Connect FamilyTasks in Google Drive",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = if (isHebrew) "בני המשפחה והרשאות Google Drive:" else "Family Members & Drive Access:",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                items(members, key = { it.id }) { member ->
                    val memberEmail = member.googleEmail.orEmpty()
                    val shareStatus = familyDriveConfig.memberShareStatuses[member.id]
                        ?: when {
                            memberEmail.isNotBlank() && familyDriveConfig.ownerEmail.equals(memberEmail, ignoreCase = true) ->
                                DriveMemberSharingStatus.OWNER
                            memberEmail.isNotBlank() && familyDriveConfig.sharedWithEmails.any { it.equals(memberEmail, ignoreCase = true) } ->
                                DriveMemberSharingStatus.SHARED_EDITOR
                            else -> DriveMemberSharingStatus.PENDING_SHARE
                        }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drive_share_member_row_${member.id}")
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(member.colorHex).copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = member.avatarEmoji, fontSize = 18.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "${member.getDisplayName(isHebrew)} (${member.role.getDisplayName(isHebrew)})",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            text = if (memberEmail.isNotBlank()) {
                                                if (isHebrew) "$memberEmail • מאומת אוטומטית" else "$memberEmail • Verified"
                                            } else {
                                                if (isHebrew) "לחצו להוספת אימייל Google לשיתוף" else "Tap to set Google email"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (memberEmail.isNotBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable {
                                                editingMemberId = member.id
                                                emailDraft = memberEmail
                                            }
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = when (shareStatus) {
                                            DriveMemberSharingStatus.OWNER,
                                            DriveMemberSharingStatus.EDITOR_SHARED,
                                            DriveMemberSharingStatus.SHARED_EDITOR -> Color(0xFF059669).copy(alpha = 0.14f)
                                            DriveMemberSharingStatus.INVITED_PENDING,
                                            DriveMemberSharingStatus.PENDING_SHARE,
                                            DriveMemberSharingStatus.NOT_SHARED -> Color(0xFFD97706).copy(alpha = 0.14f)
                                            DriveMemberSharingStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                                        }
                                    ) {
                                        Text(
                                            text = shareStatus.getLabel(isHebrew),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = when (shareStatus) {
                                                DriveMemberSharingStatus.OWNER,
                                                DriveMemberSharingStatus.EDITOR_SHARED,
                                                DriveMemberSharingStatus.SHARED_EDITOR -> Color(0xFF059669)
                                                DriveMemberSharingStatus.INVITED_PENDING,
                                                DriveMemberSharingStatus.PENDING_SHARE,
                                                DriveMemberSharingStatus.NOT_SHARED -> Color(0xFFD97706)
                                                DriveMemberSharingStatus.ERROR -> MaterialTheme.colorScheme.error
                                            },
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            if (memberEmail.isNotBlank()) {
                                                onShareWithMember(member.id, memberEmail)
                                            } else {
                                                editingMemberId = member.id
                                                emailDraft = ""
                                            }
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("share_drive_member_${member.id}")
                                    ) {
                                        Icon(
                                            imageVector = if (memberEmail.isNotBlank()) Icons.Default.Share else Icons.Default.Edit,
                                            contentDescription = if (isHebrew) "שתף תיקייה" else "Share folder",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            if (editingMemberId == member.id) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = emailDraft,
                                        onValueChange = { emailDraft = it },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("member_drive_email_input_${member.id}"),
                                        placeholder = { Text("member@gmail.com") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    Button(
                                        onClick = {
                                            val clean = emailDraft.trim()
                                            if (clean.isNotBlank()) {
                                                onUpdateMemberEmail(member.id, clean)
                                                onShareWithMember(member.id, clean)
                                                editingMemberId = null
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.testTag("confirm_member_drive_share_${member.id}")
                                    ) {
                                        Text(if (isHebrew) "שתף" else "Share", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    FilledTonalButton(
                        onClick = onShareWithAllMembers,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("share_drive_all_members_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHebrew) {
                                "שתף את תיקיית FamilyTasks עם כל המשפחה"
                            } else {
                                "Share FamilyTasks Folder with Entire Family"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_drive_sharing_dialog_button")
            ) {
                Text(if (isHebrew) "סגור" else "Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Dialog for renaming a shared folder inside `FamilyTasks`.
 * Available equally to both PARENT and CHILD members.
 */
@Composable
fun RenameFolderDialog(
    folder: DocumentFolder,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onConfirmRename: (folderId: String, newName: String) -> Unit
) {
    var folderNameInput by rememberSaveable(folder.id) {
        mutableStateOf(folder.getDisplayName(isHebrew))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("rename_folder_dialog"),
        icon = {
            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text(
                text = if (isHebrew) "שינוי שם תיקייה ב-FamilyTasks" else "Rename Shared Folder",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = folderNameInput,
                onValueChange = { folderNameInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_folder_input"),
                label = { Text(if (isHebrew) "שם התיקייה החדש" else "New Folder Name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = folderNameInput.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirmRename(folder.id, trimmed)
                    }
                },
                enabled = folderNameInput.trim().isNotEmpty(),
                modifier = Modifier.testTag("confirm_rename_folder_button")
            ) {
                Text(if (isHebrew) "שמור" else "Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isHebrew) "ביטול" else "Cancel")
            }
        }
    )
}

/**
 * Dialog for moving a file to another folder inside the shared `FamilyTasks` Google Drive workspace.
 * Available equally to both PARENT and CHILD members.
 */
@Composable
fun MoveFileToFolderDialog(
    file: FamilyDocument,
    folders: List<DocumentFolder>,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onConfirmMove: (fileId: String, targetFolderId: String) -> Unit
) {
    var selectedTargetFolderId by rememberSaveable(file.id) {
        mutableStateOf(file.folderId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("move_file_dialog"),
        icon = {
            Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text(
                text = if (isHebrew) "העברת קובץ לתיקייה אחרת" else "Move File to Folder",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isHebrew) {
                        "בחרו תיקיית יעד עבור \"${file.name}\" בתוך תיקיית FamilyTasks:"
                    } else {
                        "Choose destination folder for \"${file.name}\":"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(folders, key = { it.id }) { folder ->
                        val isSelected = selectedTargetFolderId == folder.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedTargetFolderId = folder.id }
                                .testTag("move_target_folder_${folder.id}"),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = folder.iconEmoji, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder.getDisplayName(isHebrew),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmMove(file.id, selectedTargetFolderId) },
                modifier = Modifier.testTag("confirm_move_file_button")
            ) {
                Text(if (isHebrew) "העבר קובץ" else "Move Here", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isHebrew) "ביטול" else "Cancel")
            }
        }
    )
}
