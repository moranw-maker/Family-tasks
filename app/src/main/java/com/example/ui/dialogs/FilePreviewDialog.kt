package com.example.ui.dialogs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.FamilyDocument
import com.example.ui.theme.*

@Composable
fun FilePreviewDialog(
    file: FamilyDocument,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onRename: (FamilyDocument) -> Unit,
    onDelete: (FamilyDocument) -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableIntStateOf(1) }
    val totalPages = 3
    var isZoomed by remember { mutableStateOf(false) }

    val (badgeColor, bgColor) = when (file.extension.uppercase()) {
        "PDF" -> GoogleRed to GoogleRedLight
        "DOC", "DOCX" -> GoogleBlue to GoogleBlueLight
        "PNG", "JPG", "JPEG" -> GoogleGreen to GoogleGreenLight
        else -> GoogleYellowDark to GoogleYellowLight
    }

    val isImage = file.extension.uppercase() in listOf("JPG", "JPEG", "PNG", "WEBP")
    val isPdf = file.extension.uppercase() == "PDF"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .padding(vertical = 12.dp)
                .testTag("file_preview_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar
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
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(bgColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = file.extension,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                fontSize = 11.sp
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1
                            )
                            Text(
                                text = "${file.sizeFormatted} • ${file.uploadDateFormatted} • Google Drive",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Document / Image Viewer Canvas Box
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isImage) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (isImage) {
                        // High Quality Image Document Viewer
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF1E293B),
                                    shadowElevation = 6.dp,
                                    border = androidx.compose.foundation.BorderStroke(2.dp, GoogleBlue.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth(if (isZoomed) 0.98f else 0.88f)
                                        .height(if (isZoomed) 290.dp else 230.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                                    listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(16.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = GoogleBlueLight,
                                                modifier = Modifier.size(64.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(text = "📸", fontSize = 32.sp)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                text = file.name,
                                                color = Color.White,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                textAlign = TextAlign.Center,
                                                maxLines = 2
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = GoogleGreen.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = "✓ תמונה סרוקה • Google Drive • ${file.sizeFormatted}",
                                                    color = GoogleGreenLight,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { isZoomed = !isZoomed },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isZoomed) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = if (isZoomed) "הקטן תצוגה" else "הגדל זום", fontSize = 11.sp)
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            onShowToast(if (isHebrew) "תמונה נפתחה בתצוגה מלאה באיכות גבוהה! 🔍" else "Opened full screen image preview! 🔍")
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = if (isHebrew) "מסך מלא" else "Fullscreen", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    } else if (isPdf) {
                        // PDF Document Viewer Mockup
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                shadowElevation = 3.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "📄 ${file.name}",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = "עמוד $currentPage מתוך $totalPages",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = Color.LightGray)
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Text(
                                        text = when (currentPage) {
                                            1 -> "פרק 1: הנחיות ואישורים רשמיים\n\nמסמך זה מאוחסן ומגובה בענן המשפחתי המאובטח.\nכל השינויים מסונכרנים ישירות עם Google Drive ומשותפים עם מנהלי הבית."
                                            2 -> "פרק 2: פרטי התלמיד / בן המשפחה\n\nהועלה על ידי: ${file.uploadedByName}\nתאריך רישום: ${file.uploadDateFormatted}\nגודל קובץ: ${file.sizeFormatted}"
                                            else -> "פרק 3: חתימת אישור ומעקב\n\nהמסמך מאומת ומוכן להדפסה, שיתוף והורדה בכל עת."
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFF334155),
                                            lineHeight = 20.sp
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Page controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { if (currentPage > 1) currentPage-- },
                                    enabled = currentPage > 1
                                ) {
                                    Text("◀ עמוד קודם", fontSize = 12.sp)
                                }

                                Text(
                                    text = "$currentPage / $totalPages",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )

                                TextButton(
                                    onClick = { if (currentPage < totalPages) currentPage++ },
                                    enabled = currentPage < totalPages
                                ) {
                                    Text("עמוד הבא ▶", fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        // Word or other document
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "📑", fontSize = 54.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "קובץ מסמך Google Drive • ${file.sizeFormatted}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Buttons: Share, Download, Rename, Delete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Share Button
                    Button(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "קובץ משפחתי מ-Family Hub: ${file.name}\nקישור דרייב: ${file.driveFileUrl}")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "שתף קובץ"))
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("preview_share_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isHebrew) "שתף" else "Share", fontSize = 12.sp)
                    }

                    // Download Button
                    OutlinedButton(
                        onClick = {
                            onShowToast(if (isHebrew) "הקובץ ${file.name} נשמר בהצלחה למכשיר! 📥" else "File downloaded! 📥")
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("preview_download_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isHebrew) "הורד" else "Save", fontSize = 12.sp)
                    }

                    // Rename Button
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onRename(file)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("preview_rename_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isHebrew) "שנה שם" else "Rename", fontSize = 12.sp)
                    }

                    // Delete Button
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onDelete(file)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleRed),
                        modifier = Modifier
                            .weight(0.7f)
                            .testTag("preview_delete_btn")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
