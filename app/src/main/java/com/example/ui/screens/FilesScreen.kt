package com.example.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.result.IntentSenderRequest
import com.example.files.FamilyFileManagerHelper
import com.example.model.*
import com.example.ui.dialogs.CameraScannerDialog
import com.example.ui.dialogs.FamilyDriveSharingDialog
import com.example.ui.dialogs.FilePreviewDialog
import com.example.ui.dialogs.GoogleDriveFamilyTasksBanner
import com.example.ui.dialogs.GoogleDrivePickerDialog
import com.example.ui.dialogs.MoveFileToFolderDialog
import com.example.ui.dialogs.RenameFileDialog
import com.example.ui.dialogs.RenameFolderDialog
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    folders: List<DocumentFolder>,
    selectedFolderId: String?,
    files: List<FamilyDocument>,
    currentMember: FamilyMember,
    members: List<FamilyMember> = emptyList(),
    viewMode: FilesViewMode,
    isHebrew: Boolean,
    activeUploads: List<FileUploadTaskState> = emptyList(),
    sortOption: FileSortOption = FileSortOption.DATE,
    sortAscending: Boolean = false,
    searchQuery: String = "",
    onSelectFolder: (String?) -> Unit,
    onNavigateUpFolder: () -> Boolean = {
        val current = folders.find { it.id == selectedFolderId }
        onSelectFolder(current?.parentFolderId?.takeIf { it.isNotBlank() })
        true
    },
    onViewModeChange: (FilesViewMode) -> Unit,
    onSortOptionChange: (FileSortOption) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onAddFolder: () -> Unit,
    onDeleteFolder: (String) -> Unit = {},
    onUploadFile: (folderId: String, fileName: String, extension: String, sizeFormatted: String) -> Unit,
    onUploadBinaryFile: ((folderId: String?, fileName: String, extension: String, bytes: ByteArray) -> Unit)? = null,
    onCancelUpload: (String) -> Unit = {},
    onRetryUpload: (String) -> Unit = {},
    onDismissUpload: (String) -> Unit = {},
    onRenameFile: (fileId: String, newName: String) -> Unit = { _, _ -> },
    onMoveFile: (fileId: String, targetFolderId: String) -> Unit = { _, _ -> },
    onRenameFolder: (folderId: String, newNameHe: String) -> Unit = { _, _ -> },
    onDeleteFile: (String) -> Unit,
    familyDriveConfig: FamilyDriveConfig = FamilyDriveConfig(),
    driveConsentPendingIntent: android.app.PendingIntent? = null,
    driveConsentIntent: android.content.Intent? = null,
    onConnectFamilyDrive: () -> Unit = {},
    onClearDriveConsentPrompts: () -> Unit = {},
    onShareDriveWithMember: (memberId: String, email: String) -> Unit = { _, _ -> },
    onShareDriveWithAllMembers: () -> Unit = {},
    onUpdateMemberGoogleEmail: (memberId: String, email: String) -> Unit = { _, _ -> },
    pendingDrivePickerRequest: DrivePickerAuthorizationRequest? = null,
    drivePickerVerificationError: String? = null,
    onRequestSharedFolderPicker: () -> Unit = {},
    onVerifyPickedSharedFolder: (pickedDriveId: String, pickedName: String) -> Unit = { _, _ -> },
    onDismissDrivePicker: () -> Unit = {},
    fileBytesProvider: (suspend (FamilyDocument) -> ByteArray?)? = null,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    var localSearchQuery by rememberSaveable { mutableStateOf(searchQuery) }
    var localSortOption by rememberSaveable { mutableStateOf(sortOption) }
    var localSortAsc by rememberSaveable { mutableStateOf(sortAscending) }

    LaunchedEffect(searchQuery) {
        if (searchQuery != localSearchQuery) localSearchQuery = searchQuery
    }
    LaunchedEffect(sortOption, sortAscending) {
        localSortOption = sortOption
        localSortAsc = sortAscending
    }

    var showFabMenu by remember { mutableStateOf(false) }
    var showQuickUploadDialog by remember { mutableStateOf(false) }
    var showCameraDialog by rememberSaveable { mutableStateOf(false) }
    var cameraInitialDocMode by rememberSaveable { mutableStateOf(true) }
    var showStorageQuotaDialog by remember { mutableStateOf(false) }
    var showDriveSharingDialog by remember { mutableStateOf(false) }

    var previewFile by remember { mutableStateOf<FamilyDocument?>(null) }
    var fileToRename by remember { mutableStateOf<FamilyDocument?>(null) }
    var fileToMove by remember { mutableStateOf<FamilyDocument?>(null) }
    var folderToRename by remember { mutableStateOf<DocumentFolder?>(null) }
    var filePendingDelete by remember { mutableStateOf<FamilyDocument?>(null) }
    var folderPendingDelete by remember { mutableStateOf<DocumentFolder?>(null) }

    // Google Drive OAuth 2.0 Consent Launchers (Play Services Identity / UserRecoverableAuth)
    val driveIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onClearDriveConsentPrompts()
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            com.example.files.GoogleDriveFamilyService.handleAuthorizationActivityResult(
                context = context,
                data = result.data,
                accountEmail = currentMember.googleEmail
            )
            onConnectFamilyDrive()
        }
    }

    val driveLegacyConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onClearDriveConsentPrompts()
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            onConnectFamilyDrive()
        }
    }

    LaunchedEffect(driveConsentPendingIntent) {
        driveConsentPendingIntent?.let { pendingIntent ->
            runCatching {
                driveIntentSenderLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            }.onFailure {
                onClearDriveConsentPrompts()
            }
        }
    }

    LaunchedEffect(driveConsentIntent) {
        driveConsentIntent?.let { intent ->
            runCatching {
                driveLegacyConsentLauncher.launch(intent)
            }.onFailure {
                onClearDriveConsentPrompts()
            }
        }
    }

    // Support system Back navigation when inside a folder/subfolder
    BackHandler(enabled = selectedFolderId != null) {
        onNavigateUpFolder()
    }

    // Native Storage Access Framework (SAF) file picker launcher
    val safDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var displayName = "קובץ_משפחתי.pdf"
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                }
            }
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()

            if (bytes != null) {
                val ext = FamilyFileManagerHelper.extractExtension(displayName, "PDF")
                val targetFolder = selectedFolderId ?: folders.firstOrNull()?.id ?: "f_school"
                if (onUploadBinaryFile != null) {
                    onUploadBinaryFile(targetFolder, displayName, ext, bytes)
                } else {
                    onUploadFile(
                        targetFolder,
                        displayName,
                        ext,
                        FamilyFileManagerHelper.formatFileSize(bytes.size.toLong())
                    )
                }
            } else {
                onShowToast(
                    if (isHebrew) "לא ניתן לקרוא את הקובץ שנבחר. נסו קובץ אחר."
                    else "Could not read selected file."
                )
            }
        }
    }

    // Compute breadcrumb chain from root to selectedFolderId
    val breadcrumbChain = remember(folders, selectedFolderId) {
        val chain = mutableListOf<DocumentFolder>()
        var cursor = folders.find { it.id == selectedFolderId }
        val visited = mutableSetOf<String>()
        while (cursor != null && visited.add(cursor.id)) {
            chain.add(0, cursor)
            val parentId = cursor.parentFolderId?.takeIf { it.isNotBlank() }
            cursor = if (parentId != null) folders.find { it.id == parentId } else null
        }
        chain
    }

    // Filter subfolders for current level
    val currentLevelFolders = remember(folders, selectedFolderId, localSearchQuery) {
        val q = localSearchQuery.trim()
        if (q.isNotBlank()) {
            folders.filter {
                it.nameHe.contains(q, ignoreCase = true) ||
                    it.nameEn.contains(q, ignoreCase = true)
            }
        } else {
            folders.filter { folder ->
                val parent = folder.parentFolderId?.takeIf { it.isNotBlank() }
                parent == selectedFolderId
            }
        }
    }

    // Filter and sort files
    val displayedFiles = remember(files, selectedFolderId, localSearchQuery, localSortOption, localSortAsc) {
        val q = localSearchQuery.trim()
        val base = files.filter { doc ->
            val matchesFolder = if (q.isNotBlank()) {
                true
            } else {
                selectedFolderId == null || doc.folderId == selectedFolderId
            }
            val matchesQuery = q.isBlank() ||
                doc.name.contains(q, ignoreCase = true) ||
                doc.extension.contains(q, ignoreCase = true) ||
                doc.uploadedByName.contains(q, ignoreCase = true)
            matchesFolder && matchesQuery
        }

        val comparator = when (localSortOption) {
            FileSortOption.NAME -> compareBy<FamilyDocument> { it.name.lowercase() }
            FileSortOption.DATE -> compareBy<FamilyDocument> { it.uploadedAtMillis }
            FileSortOption.SIZE -> compareBy<FamilyDocument> {
                if (it.sizeBytes > 0L) it.sizeBytes else FamilyFileManagerHelper.parseApproximateSizeBytes(it.sizeFormatted)
            }
            FileSortOption.TYPE -> compareBy<FamilyDocument> { it.extension.uppercase() }
                .thenBy { it.name.lowercase() }
        }
        if (localSortAsc) base.sortedWith(comparator) else base.sortedWith(comparator.reversed())
    }

    val currentFolderObj = remember(folders, selectedFolderId) {
        folders.find { it.id == selectedFolderId }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Top Bar: Title + Back action + View Mode Toggle + Storage Info
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (selectedFolderId != null) {
                                IconButton(
                                    onClick = { onNavigateUpFolder() },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("files_back_button")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = if (isHebrew) "חזרה לתיקייה הקודמת" else "Back"
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Column {
                                Text(
                                    text = currentFolderObj?.getDisplayName(isHebrew)
                                        ?: (if (isHebrew) "קבצים ותיקיות משפחתיות" else "Shared Family Files"),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (isHebrew) {
                                        "${currentLevelFolders.size} תיקיות • ${displayedFiles.size} קבצים ב-Google Drive ($GOOGLE_DRIVE_ROOT_FOLDER_NAME)"
                                    } else {
                                        "${currentLevelFolders.size} folders • ${displayedFiles.size} files in Google Drive ($GOOGLE_DRIVE_ROOT_FOLDER_NAME)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Grid / List View Switcher
                            IconButton(
                                onClick = {
                                    val next = if (viewMode == FilesViewMode.GRID) FilesViewMode.LIST else FilesViewMode.GRID
                                    onViewModeChange(next)
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .testTag("toggle_files_view_mode")
                            ) {
                                Icon(
                                    imageVector = if (viewMode == FilesViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                                    contentDescription = if (isHebrew) "החלף תצוגת רשת/רשימה" else "Toggle Grid/List View",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            // Storage Quotas & Security Info Button
                            IconButton(
                                onClick = { showStorageQuotaDialog = true },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .testTag("storage_quota_info_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = if (isHebrew) "מידע על אחסון ואבטחה" else "Storage & Security Info",
                                    tint = Color(0xFF059669)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Breadcrumb Navigation Bar
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("files_breadcrumb_bar"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item {
                            AssistChip(
                                onClick = { onSelectFolder(null) },
                                label = {
                                    Text(
                                        text = if (isHebrew) "🏠 כל התיקיות" else "🏠 All Folders",
                                        fontWeight = if (selectedFolderId == null) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (selectedFolderId == null) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    }
                                ),
                                border = null,
                                modifier = Modifier.testTag("breadcrumb_root_chip")
                            )
                        }

                        items(breadcrumbChain, key = { it.id }) { crumb ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = " / ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val isCurrent = crumb.id == selectedFolderId
                                AssistChip(
                                    onClick = { onSelectFolder(crumb.id) },
                                    label = {
                                        Text(
                                            text = "${crumb.iconEmoji} ${crumb.getDisplayName(isHebrew)}",
                                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (isCurrent) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        }
                                    ),
                                    border = null,
                                    modifier = Modifier.testTag("breadcrumb_folder_${crumb.id}")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Search Bar
                    OutlinedTextField(
                        value = localSearchQuery,
                        onValueChange = {
                            localSearchQuery = it
                            onSearchQueryChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("files_search_input"),
                        placeholder = {
                            Text(
                                if (isHebrew) "חיפוש קובץ, תיקייה או מעלה..." else "Search files, folders, or uploader...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (localSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        localSearchQuery = ""
                                        onSearchQueryChange("")
                                    }
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            focusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Sorting Row (Name, Date, Size, Type)
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("files_sort_bar"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isHebrew) "מיון:" else "Sort:",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(FileSortOption.entries) { option ->
                            val isSelected = localSortOption == option
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (localSortOption == option) {
                                        localSortAsc = !localSortAsc
                                    } else {
                                        localSortOption = option
                                        localSortAsc = (option == FileSortOption.NAME || option == FileSortOption.TYPE)
                                    }
                                    onSortOptionChange(option)
                                },
                                label = {
                                    val arrow = if (isSelected) (if (localSortAsc) " ↑" else " ↓") else ""
                                    Text("${option.getDisplayName(isHebrew)}$arrow")
                                },
                                modifier = Modifier.testTag("sort_chip_${option.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            // Google Drive `FamilyTasks` Shared Folder Setup & Family Sharing Bar
            GoogleDriveFamilyTasksBanner(
                familyDriveConfig = familyDriveConfig,
                currentMember = currentMember,
                membersCount = members.size.coerceAtLeast(1),
                isHebrew = isHebrew,
                onConnectDrive = onConnectFamilyDrive,
                onOpenSharingDialog = { showDriveSharingDialog = true },
                onOpenGooglePicker = onRequestSharedFolderPicker
            )

            // Active Uploads Progress & Retry Panel
            AnimatedVisibility(visible = activeUploads.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("active_uploads_panel"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeUploads.forEach { task ->
                        UploadTaskStatusCard(
                            task = task,
                            isHebrew = isHebrew,
                            onCancel = { onCancelUpload(task.uploadId) },
                            onRetry = { onRetryUpload(task.uploadId) },
                            onDismiss = { onDismissUpload(task.uploadId) }
                        )
                    }
                }
            }

            // Main Folders & Files Content Area (Grid or List View)
            if (viewMode == FilesViewMode.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("files_grid_container"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 96.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (currentLevelFolders.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = if (selectedFolderId == null) {
                                    if (isHebrew) "תיקיות משותפות" else "Shared Folders"
                                } else {
                                    if (isHebrew) "תתי-תיקיות" else "Subfolders"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(currentLevelFolders, key = { "folder_${it.id}" }) { folder ->
                            val itemCount = files.count { it.folderId == folder.id } +
                                folders.count { it.parentFolderId == folder.id }
                            FolderGridCard(
                                folder = folder,
                                fileCount = itemCount,
                                canDelete = FamilyFileManagerHelper.canMemberDeleteFolder(currentMember),
                                isHebrew = isHebrew,
                                onClick = { onSelectFolder(folder.id) },
                                onRename = { folderToRename = folder },
                                onDelete = { folderPendingDelete = folder }
                            )
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isHebrew) "קבצים (${displayedFiles.size})" else "Files (${displayedFiles.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (displayedFiles.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyFilesCard(
                                isHebrew = isHebrew,
                                onUploadClick = { showQuickUploadDialog = true },
                                onCameraClick = {
                                    cameraInitialDocMode = false
                                    showCameraDialog = true
                                }
                            )
                        }
                    } else {
                        items(displayedFiles, key = { "file_${it.id}" }) { doc ->
                            val folder = folders.find { it.id == doc.folderId }
                            val uploader = members.find { it.id == doc.uploadedByUid || it.id == doc.uploadedByMemberId }
                            val canModify = FamilyFileManagerHelper.canMemberModifyOrDeleteFile(
                                file = doc,
                                member = currentMember,
                                authenticatedUid = currentMember.id
                            )
                            FileGridCard(
                                doc = doc,
                                folder = folder,
                                uploader = uploader,
                                canModify = canModify,
                                isHebrew = isHebrew,
                                onOpen = {
                                    handleSingleTapFileOpen(
                                        context = context,
                                        doc = doc,
                                        isHebrew = isHebrew,
                                        onOpenPreview = { previewFile = it },
                                        onShowToast = onShowToast
                                    )
                                },
                                onShare = {
                                    FamilyFileManagerHelper.shareFileViaSharesheet(
                                        context = context,
                                        doc = doc,
                                        rawBytes = null,
                                        isHebrew = isHebrew
                                    )
                                },
                                onMove = { fileToMove = doc },
                                onRename = { fileToRename = doc },
                                onDelete = { filePendingDelete = doc }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("files_list_container"),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (currentLevelFolders.isNotEmpty()) {
                        item {
                            Text(
                                text = if (selectedFolderId == null) {
                                    if (isHebrew) "תיקיות משותפות" else "Shared Folders"
                                } else {
                                    if (isHebrew) "תתי-תיקיות" else "Subfolders"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(currentLevelFolders, key = { "list_folder_${it.id}" }) { folder ->
                            val itemCount = files.count { it.folderId == folder.id } +
                                folders.count { it.parentFolderId == folder.id }
                            FolderListItemCard(
                                folder = folder,
                                fileCount = itemCount,
                                canDelete = FamilyFileManagerHelper.canMemberDeleteFolder(currentMember),
                                isHebrew = isHebrew,
                                onClick = { onSelectFolder(folder.id) },
                                onRename = { folderToRename = folder },
                                onDelete = { folderPendingDelete = folder }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isHebrew) "קבצים (${displayedFiles.size})" else "Files (${displayedFiles.size})",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (displayedFiles.isEmpty()) {
                        item {
                            EmptyFilesCard(
                                isHebrew = isHebrew,
                                onUploadClick = { showQuickUploadDialog = true },
                                onCameraClick = {
                                    cameraInitialDocMode = false
                                    showCameraDialog = true
                                }
                            )
                        }
                    } else {
                        items(displayedFiles, key = { "list_file_${it.id}" }) { doc ->
                            val folder = folders.find { it.id == doc.folderId }
                            val uploader = members.find { it.id == doc.uploadedByUid || it.id == doc.uploadedByMemberId }
                            val canModify = FamilyFileManagerHelper.canMemberModifyOrDeleteFile(
                                file = doc,
                                member = currentMember,
                                authenticatedUid = currentMember.id
                            )
                            FileListItemCard(
                                doc = doc,
                                folder = folder,
                                uploader = uploader,
                                canModify = canModify,
                                isHebrew = isHebrew,
                                onOpen = {
                                    handleSingleTapFileOpen(
                                        context = context,
                                        doc = doc,
                                        isHebrew = isHebrew,
                                        onOpenPreview = { previewFile = it },
                                        onShowToast = onShowToast
                                    )
                                },
                                onShare = {
                                    FamilyFileManagerHelper.shareFileViaSharesheet(
                                        context = context,
                                        doc = doc,
                                        rawBytes = null,
                                        isHebrew = isHebrew
                                    )
                                },
                                onMove = { fileToMove = doc },
                                onRename = { fileToRename = doc },
                                onDelete = { filePendingDelete = doc }
                            )
                        }
                    }
                }
            }
        }

        // Floating + Action Button with the 4 required actions
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            FloatingActionButton(
                onClick = { showFabMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("files_fab_add")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = if (isHebrew) "הוספה" else "Add")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isHebrew) "הוספה" else "New",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            DropdownMenu(
                expanded = showFabMenu,
                onDismissRequest = { showFabMenu = false },
                modifier = Modifier
                    .width(230.dp)
                    .testTag("files_fab_menu")
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isHebrew) "העלאת קובץ" else "Upload File",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {
                        showFabMenu = false
                        showQuickUploadDialog = true
                    },
                    modifier = Modifier.testTag("fab_action_upload_file")
                )

                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isHebrew) "צילום תמונה" else "Take Photo",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Color(0xFF10B981))
                    },
                    onClick = {
                        showFabMenu = false
                        cameraInitialDocMode = false
                        showCameraDialog = true
                    },
                    modifier = Modifier.testTag("fab_action_take_photo")
                )

                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isHebrew) "צילום מסמך" else "Scan Document",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.DocumentScanner, contentDescription = null, tint = Color(0xFFEF4444))
                    },
                    onClick = {
                        showFabMenu = false
                        cameraInitialDocMode = true
                        showCameraDialog = true
                    },
                    modifier = Modifier.testTag("fab_action_scan_document")
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isHebrew) "תיקייה חדשה" else "New Folder",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Color(0xFFD97706))
                    },
                    onClick = {
                        showFabMenu = false
                        onAddFolder()
                    },
                    modifier = Modifier.testTag("fab_action_new_folder")
                )
            }
        }
    }

    // --- Dialogs & Viewers ---

    // 1. One-Tap File Viewer (Images, PDFs, Documents)
    previewFile?.let { doc ->
        val folder = folders.find { it.id == doc.folderId }
        val uploader = members.find { it.id == doc.uploadedByUid || it.id == doc.uploadedByMemberId }
        val canModify = FamilyFileManagerHelper.canMemberModifyOrDeleteFile(
            file = doc,
            member = currentMember,
            authenticatedUid = currentMember.id
        )
        FilePreviewDialog(
            file = doc,
            folder = folder,
            uploader = uploader,
            isHebrew = isHebrew,
            fileBytesProvider = fileBytesProvider,
            onDismiss = { previewFile = null },
            onMove = if (canModify) {
                {
                    val target = doc
                    previewFile = null
                    fileToMove = target
                }
            } else null,
            onRename = if (canModify) {
                {
                    val target = doc
                    previewFile = null
                    fileToRename = target
                }
            } else null,
            onDelete = if (canModify) {
                {
                    val target = doc
                    previewFile = null
                    filePendingDelete = target
                }
            } else null,
            onShowToast = onShowToast
        )
    }

    // 2. Rename File Dialog
    fileToRename?.let { doc ->
        RenameFileDialog(
            file = doc,
            isHebrew = isHebrew,
            onDismiss = { fileToRename = null },
            onConfirmRename = { fileId, newName ->
                onRenameFile(fileId, newName)
                fileToRename = null
            }
        )
    }

    // 2b. Move File to Folder Dialog
    fileToMove?.let { doc ->
        MoveFileToFolderDialog(
            file = doc,
            folders = folders,
            isHebrew = isHebrew,
            onDismiss = { fileToMove = null },
            onConfirmMove = { fileId, targetFolderId ->
                onMoveFile(fileId, targetFolderId)
                fileToMove = null
            }
        )
    }

    // 2c. Rename Folder Dialog (Available to both Parents and Children)
    folderToRename?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            isHebrew = isHebrew,
            onDismiss = { folderToRename = null },
            onConfirmRename = { folderId, newName ->
                onRenameFolder(folderId, newName)
                folderToRename = null
            }
        )
    }

    // 2d. Family Google Drive Sharing & Permissions Dialog
    if (showDriveSharingDialog) {
        FamilyDriveSharingDialog(
            familyDriveConfig = familyDriveConfig,
            members = members.ifEmpty { listOf(currentMember) },
            currentMember = currentMember,
            isHebrew = isHebrew,
            onConnectOrSetupDrive = onConnectFamilyDrive,
            onShareWithMember = onShareDriveWithMember,
            onShareWithAllMembers = onShareDriveWithAllMembers,
            onUpdateMemberEmail = onUpdateMemberGoogleEmail,
            onDismiss = { showDriveSharingDialog = false }
        )
    }

    // 2e. Google Picker Shared Folder Authorization & ID Verification Dialog
    pendingDrivePickerRequest?.let { pickerReq ->
        GoogleDrivePickerDialog(
            request = pickerReq,
            accessToken = com.example.files.GoogleDriveFamilyService.getActiveAccessToken(),
            isHebrew = isHebrew,
            verificationError = drivePickerVerificationError,
            onFolderPickedAndVerify = { pickedDriveId, pickedName ->
                onVerifyPickedSharedFolder(pickedDriveId, pickedName)
            },
            onDismiss = onDismissDrivePicker
        )
    }

    // 3. Safe File Deletion Confirmation Dialog
    filePendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { filePendingDelete = null },
            modifier = Modifier.testTag("confirm_delete_file_dialog"),
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = if (isHebrew) "מחיקת קובץ משפחתי" else "Delete Shared File",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isHebrew) {
                        "האם למחוק את הקובץ \"${doc.name}\" מהתיקייה המשפחתית? פעולה זו תסיר את הקובץ עבור כל בני המשפחה."
                    } else {
                        "Are you sure you want to delete \"${doc.name}\" from the shared family folder?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFile(doc.id)
                        filePendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_file_button")
                ) {
                    Text(if (isHebrew) "מחק קובץ" else "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingDelete = null }) {
                    Text(if (isHebrew) "ביטול" else "Cancel")
                }
            }
        )
    }

    // 4. Safe Folder Deletion Confirmation Dialog (Parents only)
    folderPendingDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderPendingDelete = null },
            modifier = Modifier.testTag("confirm_delete_folder_dialog"),
            icon = {
                Icon(
                    Icons.Default.FolderDelete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = if (isHebrew) "מחיקת תיקייה משותפת" else "Delete Shared Folder",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isHebrew) {
                        "האם למחוק את התיקייה \"${folder.getDisplayName(true)}\"?"
                    } else {
                        "Delete shared folder \"${folder.getDisplayName(false)}\"?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFolder(folder.id)
                        folderPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_folder_button")
                ) {
                    Text(if (isHebrew) "מחק תיקייה" else "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingDelete = null }) {
                    Text(if (isHebrew) "ביטול" else "Cancel")
                }
            }
        )
    }

    // 5. Camera & Document Capture Dialog
    if (showCameraDialog) {
        CameraScannerDialog(
            folders = folders,
            selectedFolderId = selectedFolderId,
            isHebrew = isHebrew,
            initialDocumentMode = cameraInitialDocMode,
            onDismiss = { showCameraDialog = false },
            onUploadFile = onUploadFile,
            onUploadBinaryFile = { folderId, name, ext, bytes ->
                if (onUploadBinaryFile != null) {
                    onUploadBinaryFile(folderId, name, ext, bytes)
                } else {
                    onUploadFile(folderId, name, ext, FamilyFileManagerHelper.formatFileSize(bytes.size.toLong()))
                }
            },
            onShowToast = onShowToast
        )
    }

    // 6. File Upload Selection Dialog (Native SAF Picker + Supported Format Upload)
    if (showQuickUploadDialog) {
        FileUploadSelectionDialog(
            folders = folders,
            selectedFolderId = selectedFolderId,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onDismiss = { showQuickUploadDialog = false },
            onLaunchSystemFilePicker = {
                showQuickUploadDialog = false
                runCatching {
                    safDocumentLauncher.launch(FamilyFileManagerHelper.SAF_MIME_TYPES)
                }.onFailure {
                    onShowToast(
                        if (isHebrew) "לא ניתן לפתוח את בורר הקבצים במכשיר זה"
                        else "Could not launch system file picker"
                    )
                }
            },
            onUploadConfirmed = { folderId, fileName, ext, bytes ->
                showQuickUploadDialog = false
                if (onUploadBinaryFile != null) {
                    onUploadBinaryFile(folderId, fileName, ext, bytes)
                } else {
                    onUploadFile(
                        folderId,
                        fileName,
                        ext,
                        FamilyFileManagerHelper.formatFileSize(bytes.size.toLong())
                    )
                }
            }
        )
    }

    // 7. Google Drive FamilyTasks Storage & Security Info Dialog
    if (showStorageQuotaDialog) {
        AlertDialog(
            onDismissRequest = { showStorageQuotaDialog = false },
            modifier = Modifier.testTag("storage_quota_dialog"),
            icon = {
                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF059669))
            },
            title = {
                Text(
                    text = if (isHebrew) "אחסון Google Drive (FamilyTasks) והרשאות משפחתיות" else "Google Drive FamilyTasks & Permissions",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isHebrew) {
                        "• Google Drive בלבד: כל הקבצים והתיקיות נשמרים אך ורק בתיקיית FamilyTasks המשותפת ב-Google Drive של ההורה.\n" +
                            "• הרשאות שוות לכל המשפחה: בתיקיית FamilyTasks המשותפת להורים ולילדים יש אותן הרשאות מלאות (העלאה, צפייה, עריכה, שינוי שם, העברה בין תיקיות, יצירת תיקיות ומחיקה).\n" +
                            "• אבטחה ופרטיות: האפליקציה ניגשת אך ורק לתיקיית FamilyTasks המורשית לפי מזהה התיקייה ב-Google Drive, ואינה ניגשת לתיקיות פרטיות אחרות.\n" +
                            "• ללא עלויות נוספות: האחסון מתבצע ישירות בחשבון ה-Google Drive המשפחתי ללא שימוש ב-Firebase Storage או חיובים בתשלום."
                    } else {
                        "• Exclusive Google Drive Storage: All files and folders are stored exclusively in the shared FamilyTasks folder in Google Drive.\n" +
                            "• Equal Family Permissions: Parents and children have identical Editor permissions inside FamilyTasks (upload, open, edit, rename, move, create folders, delete).\n" +
                            "• Scoped Privacy: Only the registered FamilyTasks folder ID is accessed; unrelated Drive folders are never touched.\n" +
                            "• Zero Extra Billing: Uses your Google Drive storage directly without Firebase Cloud Storage or Firestore binary chunks."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(onClick = { showStorageQuotaDialog = false }) {
                    Text(if (isHebrew) "הבנתי" else "Got it", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

private fun handleSingleTapFileOpen(
    context: android.content.Context,
    doc: FamilyDocument,
    isHebrew: Boolean,
    onOpenPreview: (FamilyDocument) -> Unit,
    onShowToast: (String) -> Unit
) {
    val ext = doc.extension.uppercase()
    if (ext in setOf("JPG", "JPEG", "PNG", "WEBP", "PDF")) {
        // Images & PDFs open directly in our built-in full-screen / PdfRenderer viewer
        onOpenPreview(doc)
    } else {
        // Office documents (DOCX, XLSX, PPTX), TXT, ZIP: try secure ACTION_VIEW with FileProvider first,
        // and also open the built-in document sheet viewer if no external app is installed on the emulator/device
        val externalRes = FamilyFileManagerHelper.openFileWithExternalApp(
            context = context,
            doc = doc,
            rawBytes = null,
            isHebrew = isHebrew
        )
        if (externalRes.isFailure) {
            onOpenPreview(doc)
        }
    }
}

@Composable
private fun UploadTaskStatusCard(
    task: FileUploadTaskState,
    isHebrew: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val statusColor = when (task.status) {
        FileUploadStatus.UPLOADING -> MaterialTheme.colorScheme.primary
        FileUploadStatus.SUCCESS -> Color(0xFF059669)
        FileUploadStatus.FAILED -> MaterialTheme.colorScheme.error
        FileUploadStatus.CANCELLED -> Color(0xFFD97706)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("upload_task_card_${task.uploadId}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = when (task.status) {
                            FileUploadStatus.UPLOADING -> Icons.Default.CloudUpload
                            FileUploadStatus.SUCCESS -> Icons.Default.CheckCircle
                            FileUploadStatus.FAILED -> Icons.Default.ErrorOutline
                            FileUploadStatus.CANCELLED -> Icons.Default.Cancel
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = task.fileName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when (task.status) {
                            FileUploadStatus.UPLOADING ->
                                if (isHebrew) "מעלה לענן המשפחתי... ${(task.progress * 100).toInt()}%"
                                else "Uploading... ${(task.progress * 100).toInt()}%"
                            FileUploadStatus.SUCCESS ->
                                if (isHebrew) "הועלה וסונכרן בהצלחה ✓" else "Uploaded & synced ✓"
                            FileUploadStatus.FAILED ->
                                task.getErrorMessage(isHebrew) ?: (if (isHebrew) "ההעלאה נכשלה" else "Upload failed")
                            FileUploadStatus.CANCELLED ->
                                if (isHebrew) "ההעלאה בוטלה" else "Upload cancelled"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.status == FileUploadStatus.UPLOADING) {
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag("cancel_upload_${task.uploadId}")
                        ) {
                            Text(if (isHebrew) "ביטול" else "Cancel", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (task.status == FileUploadStatus.FAILED || task.status == FileUploadStatus.CANCELLED) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.testTag("retry_upload_${task.uploadId}")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isHebrew) "נסה שוב" else "Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (task.status != FileUploadStatus.UPLOADING) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("dismiss_upload_${task.uploadId}")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (task.status == FileUploadStatus.UPLOADING) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = statusColor
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGridCard(
    folder: DocumentFolder,
    fileCount: Int,
    canDelete: Boolean,
    isHebrew: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onDelete: () -> Unit
) {
    val folderColor = Color(folder.colorHex)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (canDelete) onDelete() }
            )
            .testTag("folder_card_${folder.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(folderColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = folder.iconEmoji, fontSize = 22.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = folder.getDisplayName(isHebrew),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isHebrew) "$fileCount פריטים" else "$fileCount items",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canDelete) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("rename_folder_${folder.id}")
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = if (isHebrew) "שנה שם תיקייה" else "Rename folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("delete_folder_${folder.id}")
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = if (isHebrew) "מחק תיקייה" else "Delete folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderListItemCard(
    folder: DocumentFolder,
    fileCount: Int,
    canDelete: Boolean,
    isHebrew: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onDelete: () -> Unit
) {
    val folderColor = Color(folder.colorHex)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("folder_card_${folder.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(folderColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = folder.iconEmoji, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = folder.getDisplayName(isHebrew),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (isHebrew) "$fileCount פריטים בתיקייה" else "$fileCount items in folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (canDelete) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.testTag("rename_folder_${folder.id}")
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = if (isHebrew) "שנה שם תיקייה" else "Rename folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_folder_${folder.id}")
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = if (isHebrew) "מחק תיקייה" else "Delete folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FileGridCard(
    doc: FamilyDocument,
    folder: DocumentFolder?,
    uploader: FamilyMember?,
    canModify: Boolean,
    isHebrew: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit = {},
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val (typeIcon, typeColor) = fileVisualSpec(doc.extension)
    val uploaderName = uploader?.getDisplayName(isHebrew)?.takeIf { it.isNotBlank() }
        ?: doc.uploadedByName.ifBlank { if (isHebrew) "חבר משפחה" else "Family Member" }
    val dateLabel = doc.uploadDateFormatted.ifBlank { doc.uploadedDateFormatted }

    val thumbnailBitmap = remember(doc.id, doc.thumbnailBase64) {
        doc.thumbnailBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val raw = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(raw, 0, raw.size)
            }.getOrNull()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
            .testTag("file_card_${doc.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top Preview / Thumbnail Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(typeColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailBitmap != null) {
                    Image(
                        bitmap = thumbnailBitmap.asImageBitmap(),
                        contentDescription = doc.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = doc.extension,
                        tint = typeColor,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = doc.extension.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = typeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = doc.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "${doc.sizeFormatted} • $dateLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (isHebrew) "הועלה ע\"י $uploaderName" else "By $uploaderName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("share_file_${doc.id}")
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = if (isHebrew) "שיתוף" else "Share",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canModify) {
                    IconButton(
                        onClick = onMove,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("move_file_${doc.id}")
                    ) {
                        Icon(
                            Icons.Default.DriveFileMove,
                            contentDescription = if (isHebrew) "העברה לתיקייה" else "Move",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("rename_file_${doc.id}")
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = if (isHebrew) "שינוי שם" else "Rename",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(30.dp)
                            .testTag("delete_file_${doc.id}")
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = if (isHebrew) "מחיקה" else "Delete",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileListItemCard(
    doc: FamilyDocument,
    folder: DocumentFolder?,
    uploader: FamilyMember?,
    canModify: Boolean,
    isHebrew: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit = {},
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val (typeIcon, typeColor) = fileVisualSpec(doc.extension)
    val uploaderName = uploader?.getDisplayName(isHebrew)?.takeIf { it.isNotBlank() }
        ?: doc.uploadedByName.ifBlank { if (isHebrew) "חבר משפחה" else "Family Member" }
    val dateLabel = doc.uploadDateFormatted.ifBlank { doc.uploadedDateFormatted }

    val thumbnailBitmap = remember(doc.id, doc.thumbnailBase64) {
        doc.thumbnailBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val raw = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(raw, 0, raw.size)
            }.getOrNull()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .testTag("file_card_${doc.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(typeColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnailBitmap != null) {
                        Image(
                            bitmap = thumbnailBitmap.asImageBitmap(),
                            contentDescription = doc.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = doc.extension,
                            tint = typeColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = doc.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${doc.extension.uppercase()} • ${doc.sizeFormatted} • $dateLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isHebrew) {
                            "הועלה ע\"י $uploaderName" + (folder?.let { " • ${it.getDisplayName(true)}" } ?: "")
                        } else {
                            "Uploaded by $uploaderName" + (folder?.let { " • ${it.getDisplayName(false)}" } ?: "")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("share_file_${doc.id}")
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = if (isHebrew) "שיתוף" else "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (canModify) {
                    IconButton(
                        onClick = onMove,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("move_file_${doc.id}")
                    ) {
                        Icon(
                            Icons.Default.DriveFileMove,
                            contentDescription = if (isHebrew) "העברה לתיקייה" else "Move",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("rename_file_${doc.id}")
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = if (isHebrew) "שינוי שם" else "Rename",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("delete_file_${doc.id}")
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = if (isHebrew) "מחיקה" else "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFilesCard(
    isHebrew: Boolean,
    onUploadClick: () -> Unit,
    onCameraClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("empty_files_state"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (isHebrew) "אין עדיין קבצים בתיקייה זו" else "No files in this folder yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isHebrew) {
                    "לחצו על כפתור ה-+ להוספת קובץ, צילום תמונה או סריקת מסמך למשפחה."
                } else {
                    "Tap the + button to upload a file, take a photo, or scan a document."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onUploadClick,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("empty_state_upload_button")
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isHebrew) "העלאת קובץ" else "Upload File", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onCameraClick,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("empty_state_camera_button")
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isHebrew) "צילום תמונה" else "Take Photo", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileUploadSelectionDialog(
    folders: List<DocumentFolder>,
    selectedFolderId: String?,
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onLaunchSystemFilePicker: () -> Unit,
    onUploadConfirmed: (folderId: String, fileName: String, extension: String, bytes: ByteArray) -> Unit
) {
    var fileNameInput by rememberSaveable { mutableStateOf("") }
    var selectedExt by rememberSaveable { mutableStateOf("PDF") }
    var targetFolderId by rememberSaveable {
        mutableStateOf(selectedFolderId ?: folders.firstOrNull()?.id ?: "f_school")
    }
    val supportedFormats = listOf("PDF", "JPG", "PNG", "DOCX", "XLSX", "PPTX", "TXT", "ZIP")

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("file_upload_dialog"),
        title = {
            Text(
                text = if (isHebrew) "העלאת קובץ לתיקייה המשפחתית" else "Upload Shared Family File",
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Button 1: Open Android Storage Access Framework (SAF)
                FilledTonalButton(
                    onClick = onLaunchSystemFilePicker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("launch_saf_picker_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isHebrew) "בחירת קובץ מהמכשיר (SAF)" else "Select File from Device",
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider()

                Text(
                    text = if (isHebrew) "או יצירה והעלאה מהירה של קובץ:" else "Or quick file upload:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = fileNameInput,
                    onValueChange = { fileNameInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("upload_file_name_input"),
                    label = {
                        Text(if (isHebrew) "שם הקובץ" else "File Name")
                    },
                    placeholder = {
                        Text(if (isHebrew) "למשל: חוזר_לימודים" else "e.g., School_Circular")
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    text = if (isHebrew) "סוג קובץ:" else "Format:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(supportedFormats) { ext ->
                        FilterChip(
                            selected = selectedExt == ext,
                            onClick = { selectedExt = ext },
                            label = { Text(ext) },
                            modifier = Modifier.testTag("format_chip_${ext.lowercase()}")
                        )
                    }
                }

                if (folders.isNotEmpty()) {
                    Text(
                        text = if (isHebrew) "תיקיית יעד:" else "Target Folder:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(folders, key = { it.id }) { folder ->
                            FilterChip(
                                selected = targetFolderId == folder.id,
                                onClick = { targetFolderId = folder.id },
                                label = { Text("${folder.iconEmoji} ${folder.getDisplayName(isHebrew)}") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val base = fileNameInput.trim().ifBlank {
                        if (isHebrew) "קובץ_משפחתי" else "Family_File"
                    }
                    val sanitizedName = FamilyFileManagerHelper.sanitizeFileName(base, selectedExt.lowercase())
                    val bytes = when (selectedExt) {
                        "PDF" -> FamilyFileManagerHelper.generateDocumentPdfBytes(
                            title = sanitizedName,
                            uploaderName = currentMember.name,
                            dateFormatted = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                            bodyLines = listOf("מסמך משפחתי משותף: $sanitizedName")
                        )
                        "JPG", "PNG" -> FamilyFileManagerHelper.generateSampleImageBytes(
                            title = sanitizedName,
                            uploaderName = currentMember.name
                        )
                        else -> "קובץ משפחתי משותף: $sanitizedName".toByteArray(Charsets.UTF_8)
                    }
                    onUploadConfirmed(targetFolderId, sanitizedName, selectedExt, bytes)
                },
                modifier = Modifier.testTag("confirm_upload_file_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isHebrew) "העלה קובץ" else "Upload", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isHebrew) "ביטול" else "Cancel")
            }
        }
    )
}

private fun fileVisualSpec(extension: String): Pair<ImageVector, Color> {
    return when (extension.uppercase()) {
        "PDF" -> Icons.Default.PictureAsPdf to Color(0xFFEF4444)
        "JPG", "JPEG", "PNG", "WEBP" -> Icons.Default.Image to Color(0xFF10B981)
        "DOCX", "DOC" -> Icons.Default.Description to Color(0xFF3B82F6)
        "XLSX", "XLS" -> Icons.Default.TableChart to Color(0xFF059669)
        "PPTX", "PPT" -> Icons.Default.Slideshow to Color(0xFFD97706)
        "TXT" -> Icons.Default.Article to Color(0xFF0284C7)
        "ZIP" -> Icons.Default.FolderZip to Color(0xFF7C3AED)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF6366F1)
    }
}
