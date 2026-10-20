package com.example.ui.dialogs

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.example.ui.theme.GoogleBlue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.files.FamilyFileManagerHelper
import com.example.model.DocumentFolder
import com.example.model.FamilyDocument
import com.example.model.FamilyMember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewDialog(
    file: FamilyDocument,
    folder: DocumentFolder? = null,
    uploader: FamilyMember? = null,
    isHebrew: Boolean,
    fileBytesProvider: (suspend (FamilyDocument) -> ByteArray?)? = null,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRename: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onRequestDriveAccess: (() -> Unit)? = null,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current

    var rawBytes by remember(file.id) { mutableStateOf<ByteArray?>(null) }
    var renderedBitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(file.id) { mutableStateOf(true) }
    var isShowingThumbnailFallback by remember(file.id) { mutableStateOf(false) }
    var loadErrorMessage by remember(file.id) { mutableStateOf<String?>(null) }
    var reloadTrigger by remember(file.id) { mutableIntStateOf(0) }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Pinch-to-zoom & pan state for image viewer
    var zoomScale by remember(file.id, reloadTrigger) { mutableFloatStateOf(1f) }
    var panOffset by remember(file.id, reloadTrigger) { mutableStateOf(Offset.Zero) }

    // Instant preview fallback from thumbnailBase64 while full bytes stream from Google Drive
    val fallbackThumbnailBitmap = remember(file.id, file.thumbnailBase64) {
        file.thumbnailBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val decoded = Base64.decode(b64, Base64.DEFAULT)
                FamilyFileManagerHelper.decodeImageBitmapWithCorrectOrientation(decoded, maxDimension = 1280)
            }.getOrNull()
        }
    }

    LaunchedEffect(file.id, file.name, reloadTrigger) {
        isLoading = true
        isShowingThumbnailFallback = false
        loadErrorMessage = null
        try {
            val fetched = fileBytesProvider?.invoke(file)
            rawBytes = fetched
            when {
                file.isImageFile -> {
                    val decoded = if (fetched != null && fetched.isNotEmpty()) {
                        FamilyFileManagerHelper.decodeImageBitmapWithCorrectOrientation(fetched, maxDimension = 2048)
                    } else {
                        null
                    }
                    renderedBitmap = decoded ?: fallbackThumbnailBitmap
                    isShowingThumbnailFallback = (decoded == null && fallbackThumbnailBitmap != null)
                    if (renderedBitmap == null) {
                        loadErrorMessage = if (isHebrew) {
                            "לא ניתן לטעון את התמונה מ-Google Drive. ייתכן שנדרש אישור גישה לחשבון הנוכחי או בדיקת חיבור."
                        } else {
                            "Unable to load image from Google Drive. Account authorization or connection check may be needed."
                        }
                    }
                }
                file.isPdfFile -> {
                    val localFile = FamilyFileManagerHelper.ensureLocalCacheFileForDocument(context, file, fetched)
                    renderedBitmap = FamilyFileManagerHelper.renderPdfFirstPageBitmap(localFile)
                }
                else -> {
                    renderedBitmap = null
                }
            }
        } catch (e: Exception) {
            if (file.isImageFile && fallbackThumbnailBitmap != null) {
                renderedBitmap = fallbackThumbnailBitmap
                isShowingThumbnailFallback = true
            } else {
                loadErrorMessage = e.localizedMessage ?: if (isHebrew) {
                    "שגיאה בטעינת הקובץ מ-Google Drive"
                } else {
                    "Error loading file from Google Drive"
                }
            }
        } finally {
            isLoading = false
        }
    }

    // System CreateDocument launcher for "הורדה" (Save to device Downloads/Documents)
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            file.mimeType.ifBlank { FamilyDocument.defaultMimeTypeForExtension(file.extension) }
        )
    ) { targetUri: Uri? ->
        if (targetUri != null) {
            runCatching {
                val bytesToSave = rawBytes ?: run {
                    val localFile = FamilyFileManagerHelper.ensureLocalCacheFileForDocument(context, file, null)
                    if (localFile.exists() && localFile.length() > 0L) localFile.readBytes() else ByteArray(0)
                }
                if (bytesToSave.isEmpty()) {
                    throw IllegalStateException(
                        if (isHebrew) "הקובץ טרם נטען מ-Google Drive" else "File not yet loaded from Google Drive"
                    )
                }
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    out.write(bytesToSave)
                    out.flush()
                } ?: throw IllegalStateException("Cannot open output stream")
            }.fold(
                onSuccess = {
                    onShowToast(if (isHebrew) "הקובץ נשמר במכשיר בהצלחה" else "File saved to device")
                },
                onFailure = { err ->
                    onShowToast(
                        err.localizedMessage ?: if (isHebrew) "שמירת הקובץ נכשלה" else "Failed to save file"
                    )
                }
            )
        }
    }

    val uploaderLabel = uploader?.getDisplayName(isHebrew)?.takeIf { it.isNotBlank() }
        ?: file.uploadedByName.ifBlank { if (isHebrew) "חבר משפחה" else "Family Member" }
    val subtitleText = buildString {
        append(uploaderLabel)
        if (file.uploadedDateFormatted.isNotBlank()) {
            append(" • ")
            append(file.uploadedDateFormatted)
        }
        if (file.sizeFormatted.isNotBlank()) {
            append(" • ")
            append(file.sizeFormatted)
        }
    }

    val layoutDir = if (isHebrew) LayoutDirection.Rtl else LayoutDirection.Ltr

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .testTag("file_preview_dialog"),
                containerColor = MaterialTheme.colorScheme.surface,
                topBar = {
                    // Clean Material 3 Top App Bar: Back/Close, readable filename, small uploader subtitle, one 3-dot overflow menu
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("preview_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = if (isHebrew) "סגירה" else "Close",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = 20.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("preview_file_title")
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("preview_overflow_menu_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = if (isHebrew) "אפשרויות נוספות" else "More options",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    if (onRename != null) {
                                        DropdownMenuItem(
                                            text = { Text(if (isHebrew) "שינוי שם" else "Rename") },
                                            leadingIcon = {
                                                Icon(Icons.Default.Edit, contentDescription = null)
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                onRename()
                                            },
                                            modifier = Modifier.testTag("preview_rename_button")
                                        )
                                    }
                                    if (onMove != null) {
                                        DropdownMenuItem(
                                            text = { Text(if (isHebrew) "העברה" else "Move") },
                                            leadingIcon = {
                                                Icon(Icons.Default.DriveFileMove, contentDescription = null)
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                onMove()
                                            },
                                            modifier = Modifier.testTag("preview_move_button")
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (isHebrew) "עריכה באפליקציה תואמת" else "Edit in App")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.EditNote, contentDescription = null)
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            FamilyFileManagerHelper.openFileWithCompatibleEditor(
                                                context,
                                                file,
                                                rawBytes,
                                                isHebrew
                                            ).onFailure { err ->
                                                onShowToast(
                                                    err.localizedMessage ?: if (isHebrew) "לא נמצאה אפליקציית עריכה" else "No editor found"
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("preview_edit_in_app_button")
                                    )
                                    if (file.driveUrl.isNotBlank() || file.driveFileId.isNotBlank()) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(if (isHebrew) "פתיחה ב-Google Drive" else "Open in Google Drive")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.CloudDone, contentDescription = null)
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                runCatching {
                                                    val url = file.driveUrl.ifBlank {
                                                        "https://drive.google.com/file/d/${file.driveFileId.ifBlank { file.id }}/view"
                                                    }
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                }.onFailure {
                                                    onShowToast(
                                                        if (isHebrew) "לא ניתן לפתוח את Google Drive במכשיר"
                                                        else "Cannot open Google Drive"
                                                    )
                                                }
                                            },
                                            modifier = Modifier.testTag("preview_open_drive_menu_item")
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (isHebrew) "מחיקה" else "Delete",
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            showDeleteConfirmDialog = true
                                        },
                                        modifier = Modifier.testTag("preview_delete_button")
                                    )
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    // Clean Bottom Action Bar with exactly 4 main actions: שיתוף, הורדה, פתיחה, עוד
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PreviewBottomActionItem(
                                icon = Icons.Default.Share,
                                label = if (isHebrew) "שיתוף" else "Share",
                                testTag = "preview_share_button",
                                onClick = {
                                    FamilyFileManagerHelper.shareFileViaSharesheet(
                                        context,
                                        file,
                                        rawBytes,
                                        isHebrew
                                    ).onFailure { err ->
                                        onShowToast(
                                            err.localizedMessage ?: if (isHebrew) "שיתוף נכשל" else "Share failed"
                                        )
                                    }
                                }
                            )

                            PreviewBottomActionItem(
                                icon = Icons.Default.Download,
                                label = if (isHebrew) "הורדה" else "Download",
                                testTag = "preview_save_device_button",
                                onClick = {
                                    runCatching {
                                        val suggested = FamilyFileManagerHelper.sanitizeFileName(
                                            file.name,
                                            file.extension.lowercase()
                                        )
                                        saveDocumentLauncher.launch(suggested)
                                    }.onFailure {
                                        onShowToast(
                                            if (isHebrew) "לא ניתן לפתוח חלון שמירה במכשיר"
                                            else "Could not open file saver"
                                        )
                                    }
                                }
                            )

                            PreviewBottomActionItem(
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                label = if (isHebrew) "פתיחה" else "Open",
                                testTag = "preview_open_external_button",
                                onClick = {
                                    FamilyFileManagerHelper.openFileWithExternalApp(
                                        context,
                                        file,
                                        rawBytes,
                                        isHebrew
                                    ).onFailure { err ->
                                        onShowToast(
                                            err.localizedMessage ?: if (isHebrew) "לא נמצאה אפליקציה תואמת" else "No compatible app found"
                                        )
                                    }
                                }
                            )

                            PreviewBottomActionItem(
                                icon = Icons.Default.MoreHoriz,
                                label = if (isHebrew) "עוד" else "More",
                                testTag = "preview_more_bottom_button",
                                onClick = {
                                    showOverflowMenu = true
                                }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                // Main Content / Image Area: maximizes available screen space without unnecessary black bars or metadata cards
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = renderedBitmap
                    when {
                        file.isImageFile -> {
                            if (bmp != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(file.id) {
                                            detectTapGestures(
                                                onDoubleTap = {
                                                    if (zoomScale > 1.05f) {
                                                        zoomScale = 1f
                                                        panOffset = Offset.Zero
                                                    } else {
                                                        zoomScale = 2.5f
                                                    }
                                                }
                                            )
                                        }
                                        .pointerInput(file.id) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
                                                zoomScale = newScale
                                                if (newScale > 1f) {
                                                    panOffset += pan
                                                } else {
                                                    panOffset = Offset.Zero
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = file.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 8.dp, vertical = 8.dp)
                                            .graphicsLayer(
                                                scaleX = zoomScale,
                                                scaleY = zoomScale,
                                                translationX = panOffset.x,
                                                translationY = panOffset.y
                                            )
                                            .testTag("preview_rendered_image")
                                    )

                                    if (isShowingThumbnailFallback && !isLoading) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                            tonalElevation = 4.dp,
                                            shadowElevation = 4.dp,
                                            border = BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                                .testTag("preview_thumbnail_fallback_banner")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CloudSync,
                                                    contentDescription = null,
                                                    tint = GoogleBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                                    Text(
                                                        text = if (isHebrew) "מוצגת תצוגה מקדימה מהירה" else "Showing fast preview thumbnail",
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    )
                                                    Text(
                                                        text = if (isHebrew) "אשרו גישה ב-Google Picker לטעינת התמונה המלאה" else "Authorize via Google Picker to load full-resolution image",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    )
                                                }
                                                if (onRequestDriveAccess != null) {
                                                    FilledTonalButton(
                                                        onClick = onRequestDriveAccess,
                                                        shape = RoundedCornerShape(10.dp),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                        modifier = Modifier.testTag("preview_authorize_full_res_btn")
                                                    ) {
                                                        Text(
                                                            text = if (isHebrew) "איכות מלאה" else "Load HD",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (zoomScale > 1.05f) {
                                        FilledTonalButton(
                                            onClick = {
                                                zoomScale = 1f
                                                panOffset = Offset.Zero
                                            },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(bottom = 12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ZoomOutMap,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isHebrew) "איפוס זום" else "Reset Zoom",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }

                                    // Subtle loading indicator at top corner if upgrading from preview to full Drive stream
                                    if (isLoading) {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .align(Alignment.TopCenter)
                                        )
                                    }
                                }
                            } else if (isLoading) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("preview_loading_indicator")
                                    )
                                    Text(
                                        text = if (isHebrew) "טוען תמונה מ-Google Drive..." else "Loading image from Google Drive...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                // Explicit retry & permission-repair flow when image cannot be loaded
                                ImageLoadErrorOrConsentView(
                                    file = file,
                                    errorMessage = loadErrorMessage,
                                    isHebrew = isHebrew,
                                    onRetry = { reloadTrigger++ },
                                    onRequestDriveAccess = onRequestDriveAccess,
                                    onOpenInDrive = {
                                        runCatching {
                                            val url = file.driveUrl.ifBlank {
                                                "https://drive.google.com/file/d/${file.driveFileId.ifBlank { file.id }}/view"
                                            }
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        }.onFailure {
                                            onShowToast(
                                                if (isHebrew) "לא ניתן לפתוח את Google Drive"
                                                else "Cannot open Google Drive"
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        file.isPdfFile -> {
                            if (isLoading && bmp == null) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(44.dp))
                                    Text(
                                        text = if (isHebrew) "טוען מסמך PDF..." else "Loading PDF document...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = file.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .testTag("preview_rendered_pdf")
                                )
                            } else {
                                ImageLoadErrorOrConsentView(
                                    file = file,
                                    errorMessage = loadErrorMessage,
                                    isHebrew = isHebrew,
                                    onRetry = { reloadTrigger++ },
                                    onRequestDriveAccess = onRequestDriveAccess,
                                    onOpenInDrive = {
                                        FamilyFileManagerHelper.openFileWithExternalApp(context, file, rawBytes, isHebrew)
                                    }
                                )
                            }
                        }

                        file.isTextFile && rawBytes != null -> {
                            val textContent = remember(rawBytes) {
                                rawBytes?.toString(Charsets.UTF_8).orEmpty()
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = textContent.ifBlank { file.contentPreviewHe },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }

                        else -> {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(44.dp))
                            } else {
                                NonImageDocumentSummaryView(
                                    file = file,
                                    folderName = folder?.getDisplayName(isHebrew)
                                        ?: if (isHebrew) "FamilyTasks" else "FamilyTasks",
                                    uploaderName = uploaderLabel,
                                    isHebrew = isHebrew,
                                    onOpenExternal = {
                                        FamilyFileManagerHelper.openFileWithExternalApp(
                                            context,
                                            file,
                                            rawBytes,
                                            isHebrew
                                        ).onFailure { err ->
                                            onShowToast(
                                                err.localizedMessage ?: if (isHebrew) "לא נמצאה אפליקציה תואמת" else "No compatible app found"
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Require confirmation before deleting
            if (showDeleteConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
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
                                "האם למחוק את \"${file.name}\" מתיקיית Google Drive המשפחתית?"
                            } else {
                                "Delete \"${file.name}\" from the shared family Google Drive folder?"
                            }
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteConfirmDialog = false
                                onDelete()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("confirm_preview_delete_button")
                        ) {
                            Text(if (isHebrew) "מחק" else "Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmDialog = false }) {
                            Text(if (isHebrew) "ביטול" else "Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PreviewBottomActionItem(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier
            .defaultMinSize(minWidth = 68.dp, minHeight = 52.dp)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ImageLoadErrorOrConsentView(
    file: FamilyDocument,
    errorMessage: String?,
    isHebrew: Boolean,
    onRetry: () -> Unit,
    onRequestDriveAccess: (() -> Unit)?,
    onOpenInDrive: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 20.dp)
            .testTag("preview_error_retry_container"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Text(
            text = if (isHebrew) "נדרש סנכרון או הרשאת Google Drive" else "Google Drive Sync or Permission Needed",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Text(
            text = errorMessage ?: if (isHebrew) {
                "התמונה הועלתה על ידי ${file.uploadedByName.ifBlank { "בן משפחה" }}. לחצו על 'נסה שוב' או אשרו גישה לקובץ ב-Google Drive."
            } else {
                "Uploaded by ${file.uploadedByName.ifBlank { "a family member" }}. Tap Retry or authorize access in Google Drive."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("preview_retry_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isHebrew) "נסה שוב" else "Retry")
            }

            if (onRequestDriveAccess != null) {
                FilledTonalButton(
                    onClick = onRequestDriveAccess,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("preview_authorize_drive_button")
                ) {
                    Icon(Icons.Default.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isHebrew) "אישור ב-Drive" else "Authorize Drive")
                }
            } else {
                OutlinedButton(
                    onClick = onOpenInDrive,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("preview_open_drive_fallback_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isHebrew) "פתח ב-Drive" else "Open in Drive")
                }
            }
        }
    }
}

@Composable
private fun NonImageDocumentSummaryView(
    file: FamilyDocument,
    folderName: String,
    uploaderName: String,
    isHebrew: Boolean,
    onOpenExternal: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(76.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Text(
            text = file.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Text(
            text = "${file.extension.uppercase()} • ${file.sizeFormatted} • $folderName • $uploaderName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        FilledTonalButton(
            onClick = onOpenExternal,
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isHebrew) "פתיחה באפליקציה חיצונית" else "Open in External App")
        }
    }
}
