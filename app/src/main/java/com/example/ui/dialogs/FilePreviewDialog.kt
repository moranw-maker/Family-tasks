package com.example.ui.dialogs

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.files.FamilyFileManagerHelper
import com.example.model.DocumentFolder
import com.example.model.FamilyDocument
import com.example.model.FamilyMember

@Composable
fun FilePreviewDialog(
    file: FamilyDocument,
    folder: DocumentFolder? = null,
    uploader: FamilyMember? = null,
    isHebrew: Boolean = true,
    fileBytesProvider: (suspend (FamilyDocument) -> ByteArray?)? = null,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val ext = file.extension.uppercase()
    val isImage = ext in setOf("JPG", "JPEG", "PNG", "WEBP")
    val isPdf = ext == "PDF"

    var loadedBytes by remember(file.id) { mutableStateOf<ByteArray?>(null) }
    var renderedBitmap by remember(file.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoadingContent by remember(file.id) { mutableStateOf(true) }

    LaunchedEffect(file.id) {
        isLoadingContent = true
        val fetched = runCatching { fileBytesProvider?.invoke(file) }.getOrNull()
        loadedBytes = fetched
        val cachedFile = runCatching {
            FamilyFileManagerHelper.ensureLocalCacheFileForDocument(context, file, fetched)
        }.getOrNull()

        renderedBitmap = when {
            isImage -> {
                val raw = fetched ?: cachedFile?.readBytes()
                raw?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                } ?: file.thumbnailBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
                    runCatching {
                        val decoded = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
                    }.getOrNull()
                }
            }
            isPdf && cachedFile != null -> {
                FamilyFileManagerHelper.renderPdfFirstPageBitmap(cachedFile)
            }
            else -> null
        }
        isLoadingContent = false
    }

    val typeColor = when (ext) {
        "PDF" -> Color(0xFFEF4444)
        "JPG", "PNG", "JPEG", "WEBP" -> Color(0xFF10B981)
        "DOCX", "DOC", "TXT" -> Color(0xFF3B82F6)
        "XLSX", "XLS" -> Color(0xFF059669)
        "PPTX", "PPT" -> Color(0xFFD97706)
        "ZIP" -> Color(0xFF7C3AED)
        else -> Color(0xFF6366F1)
    }

    val uploaderDisplayName = uploader?.getDisplayName(isHebrew)?.takeIf { it.isNotBlank() }
        ?: file.uploadedByName.ifBlank { if (isHebrew) "חבר משפחה" else "Family Member" }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .testTag("file_preview_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(typeColor.copy(alpha = 0.1f))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(typeColor.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when {
                                    isPdf -> Icons.Default.PictureAsPdf
                                    isImage -> Icons.Default.Image
                                    else -> Icons.Default.Description
                                },
                                contentDescription = ext,
                                tint = typeColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${folder?.getDisplayName(isHebrew) ?: (if (isHebrew) "תיקייה משותפת" else "Shared Folder")} • ${file.sizeFormatted} • $uploaderDisplayName",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onMove != null) {
                            IconButton(
                                onClick = onMove,
                                modifier = Modifier.testTag("preview_move_button")
                            ) {
                                Icon(
                                    Icons.Default.DriveFileMove,
                                    contentDescription = if (isHebrew) "העבר לתיקייה" else "Move to Folder"
                                )
                            }
                        }
                        if (onRename != null) {
                            IconButton(
                                onClick = onRename,
                                modifier = Modifier.testTag("preview_rename_button")
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = if (isHebrew) "שינוי שם" else "Rename"
                                )
                            }
                        }
                        if (onDelete != null) {
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.testTag("preview_delete_button")
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = if (isHebrew) "מחיקה" else "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("close_preview_button")
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = if (isHebrew) "סגור" else "Close"
                            )
                        }
                    }
                }

                // Main Document / Image Preview Area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(20.dp)
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Shared Google Drive FamilyTasks Badge
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF10B981).copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudDone,
                                contentDescription = null,
                                tint = Color(0xFF059669),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isHebrew) "Google Drive • תיקיית FamilyTasks משותפת" else "Google Drive • Shared FamilyTasks Folder",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF059669)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isLoadingContent) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(strokeWidth = 3.dp)
                        }
                    } else if (isImage && renderedBitmap != null) {
                        // Full-screen style crisp Image Viewer
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 240.dp, max = 420.dp)
                                .testTag("image_fullscreen_preview"),
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 4.dp,
                            color = Color.Black.copy(alpha = 0.9f)
                        ) {
                            Image(
                                bitmap = renderedBitmap!!.asImageBitmap(),
                                contentDescription = file.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    } else if (isPdf && renderedBitmap != null) {
                        // Rendered PDF Document Viewer
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 280.dp, max = 460.dp)
                                .testTag("pdf_document_preview"),
                            shape = RoundedCornerShape(12.dp),
                            shadowElevation = 4.dp,
                            color = Color.White
                        ) {
                            Image(
                                bitmap = renderedBitmap!!.asImageBitmap(),
                                contentDescription = file.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    } else if (isImage) {
                        // Fallback vector photo canvas
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF0EA5E9), Color(0xFF6366F1), Color(0xFFA855F7))
                                    )
                                )
                                .testTag("image_fullscreen_preview"),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.25f),
                                    radius = size.minDimension * 0.18f,
                                    center = Offset(size.width * 0.75f, size.height * 0.28f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        // Document / Office / Archive Sheet Preview
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("document_sheet_preview"),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 2.dp
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = typeColor
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                                Text(
                                    text = if (isHebrew) {
                                        "פרטי קובץ משותף:\n" +
                                            "• תיקייה: ${folder?.getDisplayName(true) ?: "כללי"}\n" +
                                            "• הועלה על ידי: $uploaderDisplayName\n" +
                                            "• תאריך העלאה: ${file.uploadDateFormatted.ifBlank { file.uploadedDateFormatted }}\n" +
                                            "• סוג קובץ: $ext (${file.sizeFormatted})\n\n" +
                                            "ניתן לפתוח קובץ זה בלחיצה אחת באמצעות אפליקציה תומכת במכשיר או לשתף אותו באופן מאובטח."
                                    } else {
                                        "Shared Family File Details:\n" +
                                            "• Folder: ${folder?.getDisplayName(false) ?: "General"}\n" +
                                            "• Uploaded by: $uploaderDisplayName\n" +
                                            "• Date: ${file.uploadDateFormatted.ifBlank { file.uploadedDateFormatted }}\n" +
                                            "• Format: $ext (${file.sizeFormatted})\n\n" +
                                            "Open with an installed viewer app or share securely using Android Sharesheet."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Uploader & Date Metadata Pill
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = uploader?.avatarEmoji ?: "👤", fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (isHebrew) "הועלה ע\"י $uploaderDisplayName" else "Uploaded by $uploaderDisplayName",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = if (isHebrew) "תאריך: ${file.uploadDateFormatted.ifBlank { file.uploadedDateFormatted }}" else "Date: ${file.uploadDateFormatted.ifBlank { file.uploadedDateFormatted }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = typeColor.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "$ext • ${file.sizeFormatted}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = typeColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom Action Bar: Download, Share (Sharesheet), Open External (ACTION_VIEW)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    FamilyFileManagerHelper.ensureLocalCacheFileForDocument(context, file, loadedBytes)
                                }
                                onShowToast(
                                    if (isHebrew) "הקובץ \"${file.name}\" נשמר במכשיר בהצלחה 📥"
                                    else "Saved \"${file.name}\" to device 📥"
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("preview_download_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isHebrew) "הורדה" else "Download", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val res = FamilyFileManagerHelper.shareFileViaSharesheet(
                                    context = context,
                                    doc = file,
                                    rawBytes = loadedBytes,
                                    isHebrew = isHebrew
                                )
                                res.onFailure { err ->
                                    onShowToast(err.localizedMessage ?: if (isHebrew) "שיתוף הקובץ נכשל" else "Share failed")
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("preview_share_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isHebrew) "שיתוף" else "Share", fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val res = FamilyFileManagerHelper.openFileWithCompatibleEditor(
                                    context = context,
                                    doc = file,
                                    rawBytes = loadedBytes,
                                    isHebrew = isHebrew
                                )
                                res.onFailure { err ->
                                    onShowToast(err.localizedMessage ?: if (isHebrew) "לא נמצאה אפליקציית עריכה תואמת" else "No compatible editor found")
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("preview_edit_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isHebrew) "עריכה" else "Edit", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val res = FamilyFileManagerHelper.openFileWithExternalApp(
                                    context = context,
                                    doc = file,
                                    rawBytes = loadedBytes,
                                    isHebrew = isHebrew
                                )
                                res.onFailure { err ->
                                    onShowToast(err.localizedMessage ?: if (isHebrew) "לא נמצאה אפליקציה מתאימה לפתיחת הקובץ" else "No compatible app found")
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("preview_open_external_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isHebrew) "פתיחה בחוץ" else "Open", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("preview_done_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isHebrew) "סגור" else "Done", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
