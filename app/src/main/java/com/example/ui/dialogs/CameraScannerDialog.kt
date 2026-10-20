package com.example.ui.dialogs

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.files.FamilyFileManagerHelper
import com.example.model.DocumentFolder
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Camera & Document Capture Dialog (V1.0.0):
 * - Supports both Photo Capture (`isDocumentMode = false` -> JPG) and Document Scanner (`isDocumentMode = true` -> PDF/JPG).
 * - Handles runtime `CAMERA` permission safely without requesting broad storage permissions.
 * - Uses temporary `FileProvider` URIs (`rememberSaveable`) to survive device rotation safely.
 * - Also supports immediate quick capture in emulator/streaming environments without physical camera hardware.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScannerDialog(
    folders: List<DocumentFolder>,
    selectedFolderId: String?,
    isHebrew: Boolean,
    initialDocumentMode: Boolean = true,
    onDismiss: () -> Unit,
    onUploadFile: (folderId: String, fileName: String, extension: String, sizeFormatted: String) -> Unit = { _, _, _, _ -> },
    onUploadBinaryFile: ((folderId: String, fileName: String, extension: String, bytes: ByteArray) -> Unit)? = null,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    var isDocumentMode by rememberSaveable { mutableStateOf(initialDocumentMode) }
    var docName by rememberSaveable { mutableStateOf("") }
    var targetFolderId by rememberSaveable {
        mutableStateOf(selectedFolderId ?: folders.firstOrNull()?.id ?: "f_school")
    }
    var selectedFormat by rememberSaveable {
        mutableStateOf(if (initialDocumentMode) "PDF" else "JPG")
    }
    var capturedStage by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var capturedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var permissionDeniedMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && !pendingCameraUriString.isNullOrBlank()) {
            val uri = Uri.parse(pendingCameraUriString)
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                capturedBytes = bytes
                capturedBitmap = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                capturedStage = true
            }
        }
    }

    val takePicturePreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            capturedBytes = out.toByteArray()
            capturedBitmap = bitmap
            capturedStage = true
        }
    }

    fun launchSystemCamera() {
        permissionDeniedMessage = null
        val tempPair = runCatching {
            FamilyFileManagerHelper.createTempCameraImageUri(context)
        }.getOrNull()
        if (tempPair != null) {
            pendingCameraUriString = tempPair.second.toString()
            runCatching {
                takePictureLauncher.launch(tempPair.second)
            }.onFailure {
                runCatching { takePicturePreviewLauncher.launch(null) }
            }
        } else {
            runCatching { takePicturePreviewLauncher.launch(null) }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSystemCamera()
        } else {
            permissionDeniedMessage = if (isHebrew) {
                "הרשאת מצלמה נדחתה. ניתן להשתמש בצילום מהיר או לאשר הרשאת מצלמה בהגדרות."
            } else {
                "Camera permission denied. You can still use quick capture or grant permission in Settings."
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "scanner_line")
    val scanLineProgress by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_line"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .testTag("camera_scanner_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isDocumentMode) Icons.Default.DocumentScanner else Icons.Default.PhotoCamera,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isDocumentMode) {
                                    if (isHebrew) "צילום וסריקת מסמך" else "Scan Document"
                                } else {
                                    if (isHebrew) "צילום תמונה למשפחה" else "Take Family Photo"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                            )
                            Text(
                                text = if (isHebrew) "העלאה ישירה לתיקייה המשפחתית המשותפת" else "Direct upload to shared family folder",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Mode Switcher: Photo vs Document
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isDocumentMode,
                        onClick = {
                            isDocumentMode = false
                            selectedFormat = "JPG"
                        },
                        label = { Text(if (isHebrew) "צילום תמונה (JPG)" else "Photo (JPG)") },
                        leadingIcon = {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("camera_mode_photo_chip")
                    )
                    FilterChip(
                        selected = isDocumentMode,
                        onClick = {
                            isDocumentMode = true
                            selectedFormat = "PDF"
                        },
                        label = { Text(if (isHebrew) "צילום מסמך (PDF)" else "Document (PDF)") },
                        leadingIcon = {
                            Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("camera_mode_document_chip")
                    )
                }

                permissionDeniedMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Viewfinder / Captured Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0F172A))
                        .border(
                            width = 2.dp,
                            color = if (capturedStage) Color(0xFF10B981) else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(20.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (capturedBitmap != null) {
                        Image(
                            bitmap = capturedBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val frameW = size.width * 0.72f
                            val frameH = size.height * 0.76f
                            val left = (size.width - frameW) / 2f
                            val top = (size.height - frameH) / 2f

                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.12f),
                                topLeft = Offset(left, top),
                                size = Size(frameW, frameH),
                                cornerRadius = CornerRadius(16f, 16f)
                            )

                            drawRoundRect(
                                color = if (capturedStage) Color(0xFF10B981) else Color(0xFF38BDF8),
                                topLeft = Offset(left, top),
                                size = Size(frameW, frameH),
                                cornerRadius = CornerRadius(16f, 16f),
                                style = Stroke(width = 4f)
                            )

                            if (!capturedStage && isDocumentMode) {
                                val scanY = top + frameH * scanLineProgress
                                drawLine(
                                    color = Color(0xFF38BDF8),
                                    start = Offset(left + 8f, scanY),
                                    end = Offset(left + frameW - 8f, scanY),
                                    strokeWidth = 5f
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = if (capturedStage) Icons.Default.CheckCircle else Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = if (capturedStage) Color(0xFF10B981) else Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (capturedStage) {
                                if (isHebrew) "הצילום נקלט בהצלחה ומוכן להעלאה! ✨" else "Captured & Ready to Upload! ✨"
                            } else {
                                if (isHebrew) "צלמו באמצעות מצלמת המכשיר או צילום מהיר" else "Capture with device camera or quick capture"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Launch Native System Camera Button + Quick Capture Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                launchSystemCamera()
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("launch_system_camera_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isHebrew) "מצלמת המכשיר" else "System Camera", fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = {
                            capturedStage = !capturedStage
                            if (capturedStage && capturedBytes == null) {
                                val title = docName.ifBlank {
                                    if (isDocumentMode) "מסמך_סרוק" else "תמונה_משפחתית"
                                }
                                capturedBytes = if (selectedFormat == "PDF") {
                                    FamilyFileManagerHelper.generateDocumentPdfBytes(
                                        title = title,
                                        uploaderName = "משפחה",
                                        dateFormatted = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                                        bodyLines = listOf("מסמך שנסרק והועלה ישירות לתיקייה המשפחתית.")
                                    )
                                } else {
                                    FamilyFileManagerHelper.generateSampleImageBytes(title, "משפחה")
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("capture_scan_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (capturedStage) Icons.Default.Refresh else Icons.Default.Camera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (capturedStage) {
                                if (isHebrew) "צילום מחדש" else "Retake"
                            } else {
                                if (isHebrew) "צילום מהיר" else "Quick Capture"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Document / Photo Name Input
                OutlinedTextField(
                    value = docName,
                    onValueChange = { docName = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scanned_doc_name_input"),
                    label = {
                        Text(
                            if (isDocumentMode) {
                                if (isHebrew) "שם המסמך (למשל: אישור רפואי / דף עבודה)" else "Document Name"
                            } else {
                                if (isHebrew) "שם התמונה (למשל: טיול שבת / יצירה)" else "Photo Name"
                            }
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Target Folder Picker
                Text(
                    text = if (isHebrew) "שמירה בתיקייה:" else "Save to folder:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders, key = { it.id }) { folder ->
                        FilterChip(
                            selected = targetFolderId == folder.id,
                            onClick = { targetFolderId = folder.id },
                            label = { Text("${folder.iconEmoji} ${folder.getDisplayName(isHebrew)}") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Save & Upload Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (isHebrew) "ביטול" else "Cancel")
                    }

                    Button(
                        onClick = {
                            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("ddMM_HHmm"))
                            val baseTitle = docName.trim().ifEmpty {
                                if (isDocumentMode) {
                                    if (isHebrew) "מסמך_סרוק_$timestamp" else "Scanned_Doc_$timestamp"
                                } else {
                                    if (isHebrew) "תמונה_$timestamp" else "Photo_$timestamp"
                                }
                            }
                            val ext = if (isDocumentMode) selectedFormat else "JPG"
                            val finalFileName = FamilyFileManagerHelper.sanitizeFileName(baseTitle, ext.lowercase())
                            val bytesToUpload = capturedBytes ?: if (ext == "PDF") {
                                FamilyFileManagerHelper.generateDocumentPdfBytes(
                                    title = finalFileName,
                                    uploaderName = "משפחה",
                                    dateFormatted = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                                    bodyLines = listOf("מסמך שנסרק והועלה לתיקייה המשפחתית.")
                                )
                            } else {
                                FamilyFileManagerHelper.generateSampleImageBytes(finalFileName, "משפחה")
                            }

                            if (onUploadBinaryFile != null) {
                                onUploadBinaryFile(targetFolderId, finalFileName, ext, bytesToUpload)
                            } else {
                                onUploadFile(
                                    targetFolderId,
                                    finalFileName,
                                    ext,
                                    FamilyFileManagerHelper.formatFileSize(bytesToUpload.size.toLong())
                                )
                            }
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("confirm_camera_upload_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isHebrew) "העלה לתיקייה" else "Upload Now",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
