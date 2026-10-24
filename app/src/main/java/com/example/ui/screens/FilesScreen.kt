package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.files.FamilyFileManagerHelper
import com.example.files.GoogleDriveFamilyService
import com.example.model.DocumentFolder
import com.example.model.DriveConnectionStatus
import com.example.model.DrivePickerAuthorizationRequest
import com.example.model.FamilyDocument
import com.example.model.FamilyDriveConfig
import com.example.model.FamilyMember
import com.example.model.FileSortOption
import com.example.model.FileUploadStatus
import com.example.model.FileUploadTaskState
import com.example.model.FilesViewMode
import com.example.ui.dialogs.CameraScannerDialog
import com.example.ui.dialogs.FamilyDriveSharingDialog
import com.example.ui.dialogs.FilePreviewDialog
import com.example.ui.dialogs.GoogleDrivePickerDialog
import com.example.ui.dialogs.RenameFileDialog
import com.example.ui.dialogs.RenameFolderDialog
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilySectionHeader
import com.example.ui.components.SoftGlassScreenBackground
import com.example.ui.components.VelvetSearchContainer
import com.example.ui.theme.LocalSoftGlassColors
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    folders: List<DocumentFolder>,
    selectedFolderId: String?,
    files: List<FamilyDocument>,
    currentMember: FamilyMember,
    members: List<FamilyMember> = emptyList(),
    viewMode: FilesViewMode = FilesViewMode.LIST,
    isHebrew: Boolean,
    activeUploads: List<FileUploadTaskState> = emptyList(),
    sortOption: FileSortOption = FileSortOption.DATE,
    sortAscending: Boolean = false,
    searchQuery: String = "",
    onSelectFolder: (String?) -> Unit,
    onNavigateUpFolder: () -> Boolean = {
        onSelectFolder(null)
        true
    },
    onViewModeChange: (FilesViewMode) -> Unit = {},
    onSortOptionChange: (FileSortOption) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onAddFolder: () -> Unit,
    onDeleteFolder: (String) -> Unit = {},
    onUploadFile: (folderId: String, fileName: String, extension: String, sizeFormatted: String) -> Unit,
    onUploadBinaryFile: ((folderId: String, fileName: String, extension: String, bytes: ByteArray) -> Unit)? = null,
    onCancelUpload: (String) -> Unit = {},
    onRetryUpload: (String) -> Unit = {},
    onDismissUpload: (String) -> Unit = {},
    onRenameFile: (fileId: String, newName: String) -> Unit = { _, _ -> },
    onMoveFile: (fileId: String, targetFolderId: String) -> Unit = { _, _ -> },
    onRenameFolder: (folderId: String, newNameHe: String) -> Unit = { _, _ -> },
    onDeleteFile: (String) -> Unit,
    familyDriveConfig: FamilyDriveConfig = FamilyDriveConfig(),
    driveConsentPendingIntent: PendingIntent? = null,
    driveConsentIntent: Intent? = null,
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
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // Navigate up folder hierarchy on system Back press when inside a subfolder
    BackHandler(enabled = selectedFolderId != null) {
        onNavigateUpFolder()
    }

    val currentFolder = remember(folders, selectedFolderId) {
        folders.find { it.id == selectedFolderId }
    }
    val breadcrumbTrail = remember(folders, selectedFolderId) {
        FamilyFileManagerHelper.buildFolderBreadcrumbTrail(folders, selectedFolderId)
    }

    val rootDriveId = familyDriveConfig.driveRootFolderId.trim()

    // Visible folders at the current hierarchy level (or matching search query)
    val currentLevelFolders = remember(folders, selectedFolderId, searchQuery, rootDriveId) {
        val q = searchQuery.trim()
        if (q.isNotEmpty()) {
            folders.filter { folder ->
                folder.nameHe.contains(q, ignoreCase = true) ||
                    folder.nameEn.contains(q, ignoreCase = true)
            }
        } else if (selectedFolderId == null) {
            folders.filter { folder ->
                folder.parentFolderId.isNullOrBlank() || folder.parentFolderId == rootDriveId
            }
        } else {
            folders.filter { folder ->
                folder.parentFolderId == selectedFolderId ||
                    (currentFolder != null && currentFolder.driveFolderId.isNotBlank() &&
                        folder.driveParentFolderId == currentFolder.driveFolderId)
            }
        }
    }

    // Visible files at the current hierarchy level (or matching search query)
    val sortedFilteredFiles = remember(files, folders, selectedFolderId, currentFolder, searchQuery, sortOption, sortAscending, rootDriveId) {
        val q = searchQuery.trim()
        val levelFiltered = if (q.isNotEmpty()) {
            files.filter { doc ->
                doc.name.contains(q, ignoreCase = true) ||
                    doc.extension.contains(q, ignoreCase = true) ||
                    doc.uploadedByName.contains(q, ignoreCase = true)
            }
        } else if (selectedFolderId == null) {
            // Root level: show files stored directly in the root FamilyTasks folder (not inside any user subfolder)
            files.filter { doc ->
                doc.folderId.isBlank() ||
                    doc.folderId == rootDriveId ||
                    folders.none { f ->
                        doc.folderId == f.id ||
                            (f.driveFolderId.isNotBlank() && (doc.folderId == f.driveFolderId || doc.driveParentFolderId == f.driveFolderId))
                    }
            }
        } else {
            // Subfolder level: show files inside the selected folder
            files.filter { doc ->
                doc.folderId == selectedFolderId ||
                    (currentFolder != null && currentFolder.driveFolderId.isNotBlank() &&
                        (doc.folderId == currentFolder.driveFolderId || doc.driveParentFolderId == currentFolder.driveFolderId))
            }
        }
        FamilyFileManagerHelper.sortFiles(levelFiltered, sortOption, sortAscending)
    }

    // --- Dialog & Sheet States ---
    var showAddActionBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showDocumentScannerDialog by rememberSaveable { mutableStateOf(false) }
    var showDriveSharingDialog by rememberSaveable { mutableStateOf(false) }
    var previewFile by remember { mutableStateOf<FamilyDocument?>(null) }
    var fileToRename by remember { mutableStateOf<FamilyDocument?>(null) }
    var fileToMove by remember { mutableStateOf<FamilyDocument?>(null) }
    var fileToDeleteConfirm by remember { mutableStateOf<FamilyDocument?>(null) }
    var folderToRename by remember { mutableStateOf<DocumentFolder?>(null) }
    var folderToDeleteConfirm by remember { mutableStateOf<DocumentFolder?>(null) }
    var showSearchBar by rememberSaveable { mutableStateOf(false) }

    // --- Google Drive OAuth Resolution Launchers ---
    val driveIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        onClearDriveConsentPrompts()
        if (result.resultCode == Activity.RESULT_OK) {
            GoogleDriveFamilyService.handleAuthorizationActivityResult(
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
        if (result.resultCode == Activity.RESULT_OK) {
            onConnectFamilyDrive()
        }
    }

    LaunchedEffect(driveConsentPendingIntent, driveConsentIntent) {
        if (driveConsentPendingIntent != null) {
            runCatching {
                val req = IntentSenderRequest.Builder(driveConsentPendingIntent.intentSender).build()
                driveIntentSenderLauncher.launch(req)
            }.onFailure {
                onClearDriveConsentPrompts()
            }
        } else if (driveConsentIntent != null) {
            runCatching {
                driveLegacyConsentLauncher.launch(driveConsentIntent)
            }.onFailure {
                onClearDriveConsentPrompts()
            }
        }
    }

    // --- 1A. Take Photo (צילום תמונה) -> Always JPEG (.jpg, image/jpeg) with EXIF orientation ---
    // CRITICAL: rememberSaveable preserves the temp photo file path across Activity rotation / process recreation
    var pendingDirectCameraPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }

    val takePhotoJpegLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = pendingDirectCameraPhotoPath
        val capturedFile = path?.let { File(it) }
        pendingDirectCameraPhotoPath = null

        if (success && capturedFile != null && capturedFile.exists() && capturedFile.length() > 0L) {
            val targetFolder = selectedFolderId.orEmpty()
            val processed = FamilyFileManagerHelper.processCapturedCameraPhotoJpeg(
                photoFile = capturedFile,
                isHebrew = isHebrew
            )
            processed.fold(
                onSuccess = { payload ->
                    // Strictly upload as JPG (image/jpeg) — NEVER convert a regular camera photo to PDF!
                    if (onUploadBinaryFile != null) {
                        onUploadBinaryFile(
                            targetFolder,
                            payload.sanitizedName,
                            "JPG",
                            payload.bytes
                        )
                    } else {
                        onUploadFile(
                            targetFolder,
                            payload.sanitizedName,
                            "JPG",
                            payload.sizeFormatted
                        )
                    }
                },
                onFailure = { err ->
                    onShowToast(err.localizedMessage ?: if (isHebrew) "שגיאה בעיבוד התמונה" else "Failed to process photo")
                }
            )
        }
    }

    val takePhotoPreviewFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            val targetFolder = selectedFolderId.orEmpty()
            val (jpegBytes, _) = FamilyFileManagerHelper.encodeCameraBitmapToJpegBytes(bmp, quality = 92)
            val photoName = FamilyFileManagerHelper.generateCameraPhotoFileName(isHebrew = isHebrew)
            if (onUploadBinaryFile != null) {
                onUploadBinaryFile(targetFolder, photoName, "JPG", jpegBytes)
            } else {
                onUploadFile(
                    targetFolder,
                    photoName,
                    "JPG",
                    FamilyFileManagerHelper.formatFileSize(jpegBytes.size.toLong())
                )
            }
        }
    }

    fun launchDirectCameraPhotoCapture() {
        runCatching {
            val (file, uri) = FamilyFileManagerHelper.createTempCameraCaptureUri(context, "camera_photo")
            pendingDirectCameraPhotoPath = file.absolutePath
            takePhotoJpegLauncher.launch(uri)
        }.onFailure {
            runCatching {
                takePhotoPreviewFallbackLauncher.launch(null)
            }.onFailure {
                onShowToast(
                    if (isHebrew) "לא ניתן לפתוח את המצלמה במכשיר זה"
                    else "Could not launch camera on this device"
                )
            }
        }
    }

    val directCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchDirectCameraPhotoCapture()
        } else {
            onShowToast(
                if (isHebrew) "נדרשת הרשאת מצלמה לצילום תמונה"
                else "Camera permission is required to take a photo"
            )
        }
    }

    fun triggerTakePhotoAction() {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            launchDirectCameraPhotoCapture()
        } else {
            directCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // --- 1C. Upload Existing File (העלאת קובץ) -> Preserves original format, extension & MIME type ---
    val openDeviceFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val targetFolder = selectedFolderId.orEmpty()
            val payloadResult = FamilyFileManagerHelper.readPickedUriPayload(context, uri)
            payloadResult.fold(
                onSuccess = { payload ->
                    if (onUploadBinaryFile != null) {
                        onUploadBinaryFile(
                            targetFolder,
                            payload.sanitizedName,
                            payload.extension,
                            payload.bytes
                        )
                    } else {
                        onUploadFile(
                            targetFolder,
                            payload.sanitizedName,
                            payload.extension,
                            payload.sizeFormatted
                        )
                    }
                },
                onFailure = { err ->
                    onShowToast(err.localizedMessage ?: if (isHebrew) "שגיאה בקריאת הקובץ" else "Failed to read file")
                }
            )
        }
    }

    val layoutDir = if (isHebrew) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAddActionBottomSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 6.dp,
                        pressedElevation = 10.dp
                    ),
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .size(60.dp)
                        .testTag("files_fab_upload")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = if (isHebrew) "הוספה" else "Add",
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        ) { innerPadding ->
            SoftGlassScreenBackground(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // --- Clean Soft Glass Top Header Card ---
                FamilyGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    cornerRadius = 22.dp,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
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
                                            .testTag("breadcrumb_back_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = if (isHebrew) "חזרה" else "Back"
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.FolderShared,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentFolder?.getDisplayName(isHebrew)
                                            ?: if (isHebrew) "קבצי המשפחה" else "Family Files",
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        val isConnected = familyDriveConfig.connectionStatus == DriveConnectionStatus.CONNECTED ||
                                            familyDriveConfig.isConfigured
                                        Icon(
                                            imageVector = if (isConnected) Icons.Default.CloudDone else Icons.Default.CloudSync,
                                            contentDescription = null,
                                            tint = if (isConnected) Color(0xFF059669) else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (selectedFolderId != null && breadcrumbTrail.isNotEmpty()) {
                                                "FamilyTasks / " + breadcrumbTrail.joinToString(" / ") { it.getDisplayName(isHebrew) }
                                            } else if (isHebrew) {
                                                "Google Drive • תיקיית FamilyTasks משותפת"
                                            } else {
                                                "Google Drive • Shared FamilyTasks"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            // Compact utility icons: Search, List/Grid toggle, Refresh/Sync, and (if needed) Drive settings
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        showSearchBar = !showSearchBar
                                        if (!showSearchBar && searchQuery.isNotEmpty()) {
                                            onSearchQueryChange("")
                                        }
                                    },
                                    modifier = Modifier.testTag("toggle_search_button")
                                ) {
                                    Icon(
                                        imageVector = if (showSearchBar || searchQuery.isNotEmpty()) Icons.Default.SearchOff else Icons.Default.Search,
                                        contentDescription = if (isHebrew) "חיפוש" else "Search"
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val nextMode = if (viewMode == FilesViewMode.GRID) FilesViewMode.LIST else FilesViewMode.GRID
                                        onViewModeChange(nextMode)
                                    },
                                    modifier = Modifier.testTag(
                                        if (viewMode == FilesViewMode.GRID) "view_mode_list" else "view_mode_grid"
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (viewMode == FilesViewMode.GRID) {
                                            Icons.AutoMirrored.Filled.ViewList
                                        } else {
                                            Icons.Default.GridView
                                        },
                                        contentDescription = if (isHebrew) "שינוי תצוגה" else "Toggle view mode"
                                    )
                                }

                                IconButton(
                                    onClick = onConnectFamilyDrive,
                                    modifier = Modifier.testTag("sync_family_drive_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = if (isHebrew) "רענון מ-Google Drive" else "Sync Google Drive"
                                    )
                                }

                                if (currentMember.isParent) {
                                    IconButton(
                                        onClick = { showDriveSharingDialog = true },
                                        modifier = Modifier.testTag("open_drive_sharing_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudQueue,
                                            contentDescription = if (isHebrew) "הגדרות שיתוף Google Drive" else "Google Drive Sharing Settings"
                                        )
                                    }
                                }
                            }
                        }

                        // Expandable Search Bar
                        AnimatedVisibility(visible = showSearchBar || searchQuery.isNotEmpty()) {
                            Column {
                                Spacer(modifier = Modifier.height(10.dp))
                                VelvetSearchContainer(
                                    cornerRadius = 16.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = onSearchQueryChange,
                                        placeholder = {
                                            Text(
                                                text = if (isHebrew) "חיפוש קובץ או תיקייה..." else "Search files or folders...",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                                        },
                                        trailingIcon = {
                                            if (searchQuery.isNotEmpty()) {
                                                IconButton(
                                                    onClick = { onSearchQueryChange("") },
                                                    modifier = Modifier.testTag("clear_file_search_button")
                                                ) {
                                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        },
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                            unfocusedBorderColor = Color.Transparent
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("file_search_input")
                                    )
                                }
                            }
                        }

                        // Sort strip when files are present
                        if (sortedFilteredFiles.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (isHebrew) "מיון:" else "Sort:",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FileSortOption.entries.forEach { option ->
                                    val selected = sortOption == option
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSortOptionChange(option) },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = option.getDisplayName(isHebrew),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                )
                                                if (selected) {
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Icon(
                                                        imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("sort_chip_${option.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Connection / Picker Authorization Alert Banner (shown ONLY when action is required) ---
                val needsPickerAuth = familyDriveConfig.connectionStatus == DriveConnectionStatus.NEEDS_SHARED_FOLDER_PICKER ||
                    pendingDrivePickerRequest != null
                val needsDriveConnect = !familyDriveConfig.isConfigured &&
                    familyDriveConfig.connectionStatus != DriveConnectionStatus.CONNECTED

                if (needsPickerAuth || needsDriveConnect || !familyDriveConfig.errorMessageHe.isNullOrBlank()) {
                    DriveActionRequiredBanner(
                        config = familyDriveConfig,
                        isParent = currentMember.isParent,
                        isHebrew = isHebrew,
                        needsPickerAuth = needsPickerAuth,
                        onConnectDrive = onConnectFamilyDrive,
                        onOpenPicker = onRequestSharedFolderPicker
                    )
                }

                // --- Active Upload Progress / Retry Strip ---
                if (activeUploads.isNotEmpty()) {
                    ActiveUploadTasksStrip(
                        uploads = activeUploads,
                        isHebrew = isHebrew,
                        onCancel = onCancelUpload,
                        onRetry = onRetryUpload,
                        onDismiss = onDismissUpload
                    )
                }

                // --- Main Folder & File Browser Content ---
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val isEmptyAtCurrentLevel = currentLevelFolders.isEmpty() && sortedFilteredFiles.isEmpty()

                    if (isEmptyAtCurrentLevel) {
                        EmptyFilesWorkspaceView(
                            isSearchActive = searchQuery.isNotBlank(),
                            isSubfolder = selectedFolderId != null,
                            folderName = currentFolder?.getDisplayName(isHebrew),
                            isHebrew = isHebrew,
                            onOpenAddMenu = { showAddActionBottomSheet = true }
                        )
                    } else if (viewMode == FilesViewMode.GRID) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 156.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .testTag("files_grid_container"),
                            contentPadding = PaddingValues(top = 12.dp, bottom = 108.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (currentLevelFolders.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeaderLabel(
                                        text = if (isHebrew) "תיקיות (${currentLevelFolders.size})" else "Folders (${currentLevelFolders.size})"
                                    )
                                }
                                items(currentLevelFolders, key = { "folder_${it.id}" }) { folder ->
                                    val subCount = files.count { it.folderId == folder.id } +
                                        folders.count { it.parentFolderId == folder.id }
                                    FolderGridCard(
                                        folder = folder,
                                        itemCount = subCount,
                                        isHebrew = isHebrew,
                                        onClick = { onSelectFolder(folder.id) },
                                        onRename = { folderToRename = folder },
                                        onDelete = { folderToDeleteConfirm = folder }
                                    )
                                }
                            }

                            if (sortedFilteredFiles.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeaderLabel(
                                        text = if (isHebrew) "קבצים (${sortedFilteredFiles.size})" else "Files (${sortedFilteredFiles.size})"
                                    )
                                }
                                items(sortedFilteredFiles, key = { "file_${it.id}" }) { file ->
                                    val uploader = members.find {
                                        it.id == file.uploadedByUid || it.id == file.uploadedByMemberId
                                    }
                                    FileGridCard(
                                        file = file,
                                        uploader = uploader,
                                        isHebrew = isHebrew,
                                        onClick = { previewFile = file },
                                        onMove = { fileToMove = file },
                                        onRename = { fileToRename = file },
                                        onDelete = { fileToDeleteConfirm = file }
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .testTag("files_list_container"),
                            contentPadding = PaddingValues(top = 12.dp, bottom = 108.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (currentLevelFolders.isNotEmpty()) {
                                item {
                                    SectionHeaderLabel(
                                        text = if (isHebrew) "תיקיות (${currentLevelFolders.size})" else "Folders (${currentLevelFolders.size})"
                                    )
                                }
                                items(currentLevelFolders, key = { "folder_${it.id}" }) { folder ->
                                    val subCount = files.count { it.folderId == folder.id } +
                                        folders.count { it.parentFolderId == folder.id }
                                    FolderListRowCard(
                                        folder = folder,
                                        itemCount = subCount,
                                        isHebrew = isHebrew,
                                        onClick = { onSelectFolder(folder.id) },
                                        onRename = { folderToRename = folder },
                                        onDelete = { folderToDeleteConfirm = folder }
                                    )
                                }
                            }

                            if (sortedFilteredFiles.isNotEmpty()) {
                                item {
                                    SectionHeaderLabel(
                                        text = if (isHebrew) "קבצים (${sortedFilteredFiles.size})" else "Files (${sortedFilteredFiles.size})"
                                    )
                                }
                                items(sortedFilteredFiles, key = { "file_${it.id}" }) { file ->
                                    val uploader = members.find {
                                        it.id == file.uploadedByUid || it.id == file.uploadedByMemberId
                                    }
                                    FileListRowCard(
                                        file = file,
                                        uploader = uploader,
                                        isHebrew = isHebrew,
                                        onClick = { previewFile = file },
                                        onMove = { fileToMove = file },
                                        onRename = { fileToRename = file },
                                        onDelete = { fileToDeleteConfirm = file }
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

    // --- 2. Compact Material 3 Modal Bottom Sheet for the (+) Add Button ---
    // Contains strictly the 4 file/folder creation actions in Hebrew RTL layout.
    if (showAddActionBottomSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAddActionBottomSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.testTag("files_add_action_sheet")
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isHebrew) {
                            if (currentFolder != null) {
                                "הוספה אל \"${currentFolder.getDisplayName(true)}\""
                            } else {
                                "הוספה לתיקיית המשפחה"
                            }
                        } else {
                            if (currentFolder != null) {
                                "Add to \"${currentFolder.getDisplayName(false)}\""
                            } else {
                                "Add to Family Folder"
                            }
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Action 1: 📷 צילום תמונה — Take Photo (JPG)
                    AddActionSheetRow(
                        icon = Icons.Default.PhotoCamera,
                        emoji = "📷",
                        title = if (isHebrew) "צילום תמונה" else "Take Photo",
                        subtitle = if (isHebrew) "צילום במצלמה ושמירה כתמונת JPG" else "Capture camera photo as JPG",
                        accentColor = Color(0xFF059669),
                        testTag = "add_action_take_photo",
                        onClick = {
                            showAddActionBottomSheet = false
                            triggerTakePhotoAction()
                        }
                    )

                    // Action 2: 📄 סריקת מסמך — Scan Document (PDF)
                    AddActionSheetRow(
                        icon = Icons.Default.DocumentScanner,
                        emoji = "📄",
                        title = if (isHebrew) "סריקת מסמך" else "Scan Document",
                        subtitle = if (isHebrew) "סריקת עמוד אחד או יותר לקובץ PDF" else "Scan single or multi-page document to PDF",
                        accentColor = Color(0xFFDC2626),
                        testTag = "add_action_scan_document",
                        onClick = {
                            showAddActionBottomSheet = false
                            showDocumentScannerDialog = true
                        }
                    )

                    // Action 3: 📤 העלאת קובץ — Upload Existing File
                    AddActionSheetRow(
                        icon = Icons.Default.UploadFile,
                        emoji = "📤",
                        title = if (isHebrew) "העלאת קובץ" else "Upload Existing File",
                        subtitle = if (isHebrew) "בחירת קובץ קיים מהמכשיר בשמירה על הפורמט המקורי" else "Pick an existing file from device",
                        accentColor = Color(0xFF2563EB),
                        testTag = "add_action_upload_file",
                        onClick = {
                            showAddActionBottomSheet = false
                            openDeviceFileLauncher.launch(FamilyFileManagerHelper.SAF_MIME_TYPES)
                        }
                    )

                    // Action 4: 📁 תיקייה חדשה — Create New Folder
                    AddActionSheetRow(
                        icon = Icons.Default.CreateNewFolder,
                        emoji = "📁",
                        title = if (isHebrew) "תיקייה חדשה" else "Create New Folder",
                        subtitle = if (isHebrew) "יצירת תיקייה חדשה לארגון קבצים" else "Create a new folder",
                        accentColor = Color(0xFFD97706),
                        testTag = "add_action_new_folder",
                        onClick = {
                            showAddActionBottomSheet = false
                            onAddFolder()
                        }
                    )
                }
            }
        }
    }

    // --- Dedicated Multi-Page Document Scanner Dialog (PDF only) ---
    if (showDocumentScannerDialog) {
        CameraScannerDialog(
            folders = folders,
            initialFolderId = selectedFolderId,
            isHebrew = isHebrew,
            onDismiss = { showDocumentScannerDialog = false },
            onUploadBinaryFile = onUploadBinaryFile,
            onConfirmScannedDocument = { folderId, fileName, _ ->
                onUploadFile(folderId, fileName, "PDF", "1.4 MB")
            }
        )
    }

    // --- Google Drive Family Sharing Dialog (Parent management, separated from Add menu) ---
    if (showDriveSharingDialog) {
        FamilyDriveSharingDialog(
            familyDriveConfig = familyDriveConfig,
            members = members,
            currentMember = currentMember,
            isHebrew = isHebrew,
            onConnectOrSetupDrive = onConnectFamilyDrive,
            onShareWithMember = onShareDriveWithMember,
            onShareWithAllMembers = onShareDriveWithAllMembers,
            onUpdateMemberEmail = onUpdateMemberGoogleEmail,
            onDismiss = { showDriveSharingDialog = false }
        )
    }

    // --- Google Picker Shared Folder Authorization Dialog ---
    pendingDrivePickerRequest?.let { pickerReq ->
        GoogleDrivePickerDialog(
            request = pickerReq,
            accessToken = GoogleDriveFamilyService.getCurrentAccessToken(),
            verificationError = drivePickerVerificationError,
            isHebrew = isHebrew,
            onFolderPickedAndVerify = { pickedId, pickedName ->
                onVerifyPickedSharedFolder(pickedId, pickedName)
            },
            onDismiss = onDismissDrivePicker
        )
    }

    // --- File Preview Dialog ---
    previewFile?.let { file ->
        val folder = folders.find { it.id == file.folderId }
        val uploader = members.find {
            it.id == file.uploadedByUid || it.id == file.uploadedByMemberId
        }
        FilePreviewDialog(
            file = file,
            folder = folder,
            uploader = uploader,
            isHebrew = isHebrew,
            fileBytesProvider = fileBytesProvider,
            onDismiss = { previewFile = null },
            onMove = {
                val target = file
                previewFile = null
                fileToMove = target
            },
            onRename = {
                val target = file
                previewFile = null
                fileToRename = target
            },
            onDelete = {
                val targetId = file.id
                previewFile = null
                onDeleteFile(targetId)
            },
            onRequestDriveAccess = {
                onRequestSharedFolderPicker()
            },
            onShowToast = onShowToast
        )
    }

    // --- Move File to Folder Dialog ---
    fileToMove?.let { target ->
        MoveFileToFolderDialog(
            file = target,
            folders = folders,
            isHebrew = isHebrew,
            onDismiss = { fileToMove = null },
            onConfirmMove = { destinationFolderId ->
                onMoveFile(target.id, destinationFolderId)
                fileToMove = null
            }
        )
    }

    // --- Rename File Dialog ---
    fileToRename?.let { target ->
        RenameFileDialog(
            file = target,
            isHebrew = isHebrew,
            onDismiss = { fileToRename = null },
            onConfirmRename = { fileId, newName ->
                onRenameFile(fileId, newName)
                fileToRename = null
            }
        )
    }

    // --- Rename Folder Dialog ---
    folderToRename?.let { targetFolder ->
        RenameFolderDialog(
            folder = targetFolder,
            isHebrew = isHebrew,
            onDismiss = { folderToRename = null },
            onConfirmRename = { folderId, updatedName ->
                onRenameFolder(folderId, updatedName)
                folderToRename = null
            }
        )
    }

    // --- Delete File Confirmation Dialog ---
    fileToDeleteConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { fileToDeleteConfirm = null },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = if (isHebrew) "מחיקת קובץ" else "Delete File",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isHebrew) {
                        "האם למחוק את \"${target.name}\" מתיקיית המשפחה?"
                    } else {
                        "Delete \"${target.name}\" from the shared family folder?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFile(target.id)
                        fileToDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_file_button")
                ) {
                    Text(if (isHebrew) "מחק" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDeleteConfirm = null }) {
                    Text(if (isHebrew) "ביטול" else "Cancel")
                }
            }
        )
    }

    // --- Delete Folder Confirmation Dialog ---
    folderToDeleteConfirm?.let { targetFolder ->
        AlertDialog(
            onDismissRequest = { folderToDeleteConfirm = null },
            icon = {
                Icon(
                    Icons.Default.FolderDelete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = if (isHebrew) "מחיקת תיקייה" else "Delete Folder",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isHebrew) {
                        "האם למחוק את התיקייה \"${targetFolder.getDisplayName(true)}\"?"
                    } else {
                        "Delete folder \"${targetFolder.getDisplayName(false)}\"?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFolder(targetFolder.id)
                        folderToDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_folder_button")
                ) {
                    Text(if (isHebrew) "מחק תיקייה" else "Delete Folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDeleteConfirm = null }) {
                    Text(if (isHebrew) "ביטול" else "Cancel")
                }
            }
        )
    }
}

@Composable
private fun SectionHeaderLabel(text: String) {
    FamilySectionHeader(
        title = text,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun AddActionSheetRow(
    icon: ImageVector,
    emoji: String,
    title: String,
    subtitle: String,
    accentColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accentColor.copy(alpha = 0.14f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$emoji  $title",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DriveActionRequiredBanner(
    config: FamilyDriveConfig,
    isParent: Boolean,
    isHebrew: Boolean,
    needsPickerAuth: Boolean,
    onConnectDrive: () -> Unit,
    onOpenPicker: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("family_drive_workspace_banner"),
        shape = RoundedCornerShape(16.dp),
        color = if (needsPickerAuth) {
            Color(0xFFFEF3C7)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        }
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
                    text = when {
                        needsPickerAuth -> if (isHebrew) {
                            "נדרש אישור גישה לתיקיית המשפחה ב-Google Drive"
                        } else {
                            "Shared Folder Authorization Required"
                        }
                        !config.errorMessageHe.isNullOrBlank() -> config.errorMessageHe
                        else -> if (isHebrew) {
                            "חיבור תיקיית FamilyTasks ב-Google Drive"
                        } else {
                            "Connect FamilyTasks Google Drive Folder"
                        }
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (needsPickerAuth) Color(0xFF92400E) else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (needsPickerAuth) {
                Button(
                    onClick = onOpenPicker,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("open_shared_folder_picker_button")
                ) {
                    Text(
                        text = if (isHebrew) "אשר תיקייה" else "Authorize",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (isParent || config.driveRootFolderId.isNotBlank()) {
                FilledTonalButton(
                    onClick = onConnectDrive,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isHebrew) "חבר Drive" else "Connect Drive",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveUploadTasksStrip(
    uploads: List<FileUploadTaskState>,
    isHebrew: Boolean,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        uploads.take(3).forEach { task ->
            val isUploading = task.status == FileUploadStatus.UPLOADING
            val isFailed = task.status == FileUploadStatus.FAILED
            val isSuccess = task.status == FileUploadStatus.SUCCESS

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = when {
                    isFailed -> MaterialTheme.colorScheme.errorContainer
                    isSuccess -> Color(0xFF10B981).copy(alpha = 0.14f)
                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("upload_task_${task.uploadId}")
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                                imageVector = when {
                                    isFailed -> Icons.Default.ErrorOutline
                                    isSuccess -> Icons.Default.CheckCircle
                                    else -> Icons.Default.CloudUpload
                                },
                                contentDescription = null,
                                tint = when {
                                    isFailed -> MaterialTheme.colorScheme.error
                                    isSuccess -> Color(0xFF059669)
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = task.fileName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = when {
                                        isUploading -> if (isHebrew) {
                                            "מעלה (${task.extension})... ${(task.progress * 100).toInt()}%"
                                        } else {
                                            "Uploading (${task.extension})... ${(task.progress * 100).toInt()}%"
                                        }
                                        isFailed -> task.getErrorMessage(isHebrew)
                                            ?: (if (isHebrew) "העלאה נכשלה" else "Upload failed")
                                        isSuccess -> if (isHebrew) "הועלה בהצלחה (${task.extension}) ✓" else "Uploaded (${task.extension}) ✓"
                                        else -> if (isHebrew) "בוטל" else "Cancelled"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isUploading) {
                                IconButton(
                                    onClick = { onCancel(task.uploadId) },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("cancel_upload_${task.uploadId}")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                                }
                            } else if (isFailed) {
                                TextButton(
                                    onClick = { onRetry(task.uploadId) },
                                    modifier = Modifier.testTag("retry_upload_${task.uploadId}")
                                ) {
                                    Text(if (isHebrew) "נסה שוב" else "Retry", fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = { onDismiss(task.uploadId) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            } else {
                                IconButton(
                                    onClick = { onDismiss(task.uploadId) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    if (isUploading) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { task.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFilesWorkspaceView(
    isSearchActive: Boolean,
    isSubfolder: Boolean,
    folderName: String?,
    isHebrew: Boolean,
    onOpenAddMenu: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("empty_files_workspace_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.size(84.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = when {
                isSearchActive -> if (isHebrew) "לא נמצאו תוצאות חיפוש" else "No matching files or folders"
                else -> if (isHebrew) "עדיין אין כאן קבצים או תיקיות" else "No files or folders here yet"
            },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isSearchActive) {
                if (isHebrew) "נסו לחפש מילה אחרת או נקו את תיבת החיפוש." else "Try a different search term."
            } else {
                if (isHebrew) {
                    "לחצו על + כדי להוסיף קובץ או ליצור תיקייה."
                } else {
                    "Tap + to add a file or create a folder."
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!isSearchActive) {
            Spacer(modifier = Modifier.height(20.dp))
            FilledTonalButton(
                onClick = onOpenAddMenu,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("empty_state_add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isHebrew) "הוספת קובץ או תיקייה" else "Add File or Folder",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun FolderListRowCard(
    folder: DocumentFolder,
    itemCount: Int,
    isHebrew: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    FamilyGlassCard(
        onClick = onClick,
        cornerRadius = 18.dp,
        accentColor = Color(folder.colorHex),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("folder_row_${folder.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(folder.colorHex).copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = folder.iconEmoji.ifBlank { "📁" }, fontSize = 22.sp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.getDisplayName(isHebrew),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isHebrew) "$itemCount פריטים" else "$itemCount items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = if (isHebrew) "אפשרויות תיקייה" else "Folder options",
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isHebrew) "שינוי שם" else "Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isHebrew) "מחיקה" else "Delete",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderGridCard(
    folder: DocumentFolder,
    itemCount: Int,
    isHebrew: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    FamilyGlassCard(
        onClick = onClick,
        cornerRadius = 20.dp,
        accentColor = Color(folder.colorHex),
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("folder_grid_${folder.id}")
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(folder.colorHex).copy(alpha = 0.14f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = folder.iconEmoji.ifBlank { "📁" }, fontSize = 20.sp)
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isHebrew) "שינוי שם" else "Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isHebrew) "מחיקה" else "Delete",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = folder.getDisplayName(isHebrew),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isHebrew) "$itemCount פריטים" else "$itemCount items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRowCard(
    file: FamilyDocument,
    uploader: FamilyMember?,
    isHebrew: Boolean,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val ext = file.extension.uppercase()
    val typeColor = fileExtensionColor(ext)

    val thumbBitmap = remember(file.thumbnailBase64) {
        file.thumbnailBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    val uploaderLabel = uploader?.getDisplayName(isHebrew)?.takeIf { it.isNotBlank() }
        ?: file.uploadedByName.ifBlank { if (isHebrew) "חבר משפחה" else "Family Member" }

    FamilyGlassCard(
        cornerRadius = 18.dp,
        accentColor = typeColor,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            )
            .testTag("file_row_${file.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = file.name,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = typeColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = fileExtensionIcon(ext),
                            contentDescription = ext,
                            tint = typeColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$ext • ${file.sizeFormatted} • $uploaderLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("file_more_${file.id}")
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = if (isHebrew) "אפשרויות קובץ" else "File options",
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isHebrew) "תצוגה מקדימה" else "Preview") },
                        leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isHebrew) "פתח באפליקציה חיצונית" else "Open Externally") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            FamilyFileManagerHelper.openFileWithExternalApp(context, file, null, isHebrew)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isHebrew) "העבר לתיקייה" else "Move to Folder") },
                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onMove()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isHebrew) "שינוי שם" else "Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isHebrew) "מחיקה" else "Delete",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCard(
    file: FamilyDocument,
    uploader: FamilyMember?,
    isHebrew: Boolean,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val ext = file.extension.uppercase()
    val typeColor = fileExtensionColor(ext)

    val thumbBitmap = remember(file.thumbnailBase64) {
        file.thumbnailBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    FamilyGlassCard(
        cornerRadius = 20.dp,
        accentColor = typeColor,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            )
            .testTag("file_grid_${file.id}")
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(typeColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap.asImageBitmap(),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = fileExtensionIcon(ext),
                        contentDescription = ext,
                        tint = typeColor,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = typeColor,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = ext,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isHebrew) "העבר לתיקייה" else "Move to Folder") },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onMove()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isHebrew) "שינוי שם" else "Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isHebrew) "מחיקה" else "Delete",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = file.name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${file.sizeFormatted} • ${uploader?.getDisplayName(isHebrew) ?: file.uploadedByName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun MoveFileToFolderDialog(
    file: FamilyDocument,
    folders: List<DocumentFolder>,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onConfirmMove: (targetFolderId: String) -> Unit
) {
    var selectedTargetId by remember { mutableStateOf(file.folderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.DriveFileMove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = if (isHebrew) "העברת קובץ לתיקייה" else "Move File to Folder",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isHebrew) "בחרו תיקיית יעד עבור \"${file.name}\":" else "Select destination folder for \"${file.name}\":",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Root FamilyTasks option
                val isRootSelected = selectedTargetId.isBlank()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isRootSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selectedTargetId = "" }
                        .testTag("move_target_folder_root")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🏠", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isHebrew) "תיקיית FamilyTasks הראשית" else "Main FamilyTasks Folder",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.weight(1f)
                        )
                        if (isRootSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                folders.forEach { folder ->
                    val isSelected = folder.id == selectedTargetId
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) Color(folder.colorHex).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedTargetId = folder.id }
                            .testTag("move_target_folder_${folder.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = folder.iconEmoji.ifBlank { "📁" }, fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = folder.getDisplayName(isHebrew),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(folder.colorHex), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmMove(selectedTargetId) },
                modifier = Modifier.testTag("confirm_move_file_button")
            ) {
                Text(if (isHebrew) "העבר לכאן" else "Move Here")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isHebrew) "ביטול" else "Cancel")
            }
        }
    )
}

private fun fileExtensionColor(ext: String): Color {
    return when (ext.uppercase()) {
        "PDF" -> Color(0xFFEF4444)
        "JPG", "JPEG", "PNG", "WEBP" -> Color(0xFF10B981)
        "DOC", "DOCX", "TXT" -> Color(0xFF3B82F6)
        "XLS", "XLSX" -> Color(0xFF059669)
        "PPT", "PPTX" -> Color(0xFFD97706)
        "ZIP" -> Color(0xFF7C3AED)
        else -> Color(0xFF6366F1)
    }
}

private fun fileExtensionIcon(ext: String): ImageVector {
    return when (ext.uppercase()) {
        "PDF" -> Icons.Default.PictureAsPdf
        "JPG", "JPEG", "PNG", "WEBP" -> Icons.Default.Image
        else -> Icons.Default.Description
    }
}
