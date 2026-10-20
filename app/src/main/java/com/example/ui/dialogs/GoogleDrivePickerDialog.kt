package com.example.ui.dialogs

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.files.GoogleDriveFamilyService
import com.example.model.DrivePickerAuthorizationRequest
import com.example.model.GOOGLE_DRIVE_ROOT_FOLDER_NAME

/**
 * Supported Google Picker authorization dialog for `drive.file` shared-folder access.
 *
 * Allows an invited family member (Parent or Child) to explicitly select and authorize access
 * to the parent's single shared `FamilyTasks` folder (`expectedDriveRootFolderId`) via Google Picker,
 * and strictly verifies that the selected folder ID matches the family's registered `driveRootFolderId`.
 */
@Composable
fun GoogleDrivePickerDialog(
    request: DrivePickerAuthorizationRequest,
    accessToken: String?,
    isHebrew: Boolean,
    verificationError: String?,
    onFolderPickedAndVerify: (pickedDriveId: String, pickedName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var manualFolderLinkOrId by rememberSaveable { mutableStateOf("") }
    var showWebViewPicker by rememberSaveable { mutableStateOf(!accessToken.isNullOrBlank()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("google_drive_picker_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Default.FolderShared,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isHebrew) {
                        "אישור גישה לתיקיית המשפחה המשותפת ($GOOGLE_DRIVE_ROOT_FOLDER_NAME)"
                    } else {
                        "Authorize Shared Family Folder ($GOOGLE_DRIVE_ROOT_FOLDER_NAME)"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isHebrew) {
                        "כל קבצי המשפחה נשמרים אך ורק בתיקיית $GOOGLE_DRIVE_ROOT_FOLDER_NAME המשותפת של ההורה. בחרו את התיקייה המשותפת בבורר של Google Drive."
                    } else {
                        "All family files are stored in the single shared $GOOGLE_DRIVE_ROOT_FOLDER_NAME folder. Select it in Google Picker to grant access."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Expected Folder ID Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (isHebrew) {
                                "תיקייה משותפת נדרשת: ${request.expectedItemName}"
                            } else {
                                "Required Shared Folder: ${request.expectedItemName}"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isHebrew) {
                                "מזהה תיקיית המשפחה (driveRootFolderId): ${request.expectedDriveRootFolderId}"
                            } else {
                                "Registered driveRootFolderId: ${request.expectedDriveRootFolderId}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("picker_expected_root_id_label")
                        )
                        if (request.ownerEmail.isNotBlank()) {
                            Text(
                                text = if (isHebrew) {
                                    "בעלים (הורה): ${request.ownerEmail}"
                                } else {
                                    "Owner (Parent): ${request.ownerEmail}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Error banner if user picked the wrong folder ID
                if (!verificationError.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("picker_verification_error_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = verificationError,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Embedded Google Picker WebView (when live OAuth accessToken is available)
                if (showWebViewPicker && !accessToken.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag("google_picker_webview_container")
                    ) {
                        GooglePickerWebView(
                            accessToken = accessToken,
                            projectNumber = GoogleDriveFamilyService.GOOGLE_CLOUD_PROJECT_NUMBER,
                            expectedItemName = request.expectedItemName,
                            onPicked = { pickedId, pickedName ->
                                onFolderPickedAndVerify(pickedId, pickedName)
                            }
                        )
                    }
                }

                // Manual / Direct Folder Selection & ID Verification (also supports pasting shared link or testing)
                OutlinedTextField(
                    value = manualFolderLinkOrId,
                    onValueChange = { manualFolderLinkOrId = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("picker_folder_id_input"),
                    label = {
                        Text(
                            if (isHebrew) "קישור או מזהה תיקיית FamilyTasks לאימות"
                            else "Shared FamilyTasks Folder Link or ID"
                        )
                    },
                    placeholder = {
                        Text(request.expectedItemDriveId)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            val extractedId = extractDriveFolderIdFromLinkOrRaw(
                                manualFolderLinkOrId.ifBlank { request.expectedItemDriveId }
                            )
                            onFolderPickedAndVerify(extractedId, request.expectedItemName)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("verify_picked_folder_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHebrew) "אמת וחבר תיקייה משותפת" else "Verify & Connect Folder",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dismiss_google_picker_button")
            ) {
                Text(if (isHebrew) "סגור" else "Close")
            }
        }
    )
}

/**
 * Extracts a clean Google Drive folder/file ID from either a full Drive URL
 * (`https://drive.google.com/drive/folders/<ID>`) or a raw ID string.
 */
fun extractDriveFolderIdFromLinkOrRaw(rawInput: String): String {
    val trimmed = rawInput.trim()
    val foldersMatch = Regex("/folders/([A-Za-z0-9_-]+)").find(trimmed)
    if (foldersMatch != null) return foldersMatch.groupValues[1]
    val fileMatch = Regex("/d/([A-Za-z0-9_-]+)").find(trimmed)
    if (fileMatch != null) return fileMatch.groupValues[1]
    val idParamMatch = Regex("[?&]id=([A-Za-z0-9_-]+)").find(trimmed)
    if (idParamMatch != null) return idParamMatch.groupValues[1]
    return trimmed
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GooglePickerWebView(
    accessToken: String,
    projectNumber: String,
    expectedItemName: String,
    onPicked: (pickedId: String, pickedName: String) -> Unit
) {
    val safeToken = accessToken.replace("'", "").replace("\"", "")
    val safeQuery = expectedItemName.replace("'", "").replace("\"", "")
    val pickerHtml = remember(safeToken, projectNumber, safeQuery) {
        """
        <!DOCTYPE html>
        <html dir="rtl" lang="he">
        <head>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <script type="text/javascript" src="https://apis.google.com/js/api.js"></script>
            <style>
                body { font-family: sans-serif; margin: 0; padding: 12px; background: #F8FAFC; text-align: center; }
                .btn { background: #1A73E8; color: white; border: none; border-radius: 10px; padding: 10px 18px; font-weight: bold; font-size: 14px; cursor: pointer; }
                .hint { color: #475569; font-size: 12px; margin-top: 8px; }
            </style>
        </head>
        <body>
            <button class="btn" onclick="openFamilyPicker()">פתח בורר תיקיות Google Drive (FamilyTasks)</button>
            <div class="hint">בחרו את התיקייה המשותפת "$safeQuery" מתוך "שותפו איתי" (Shared with me)</div>
            <script type="text/javascript">
                var pickerApiLoaded = false;
                function onApiLoad() {
                    gapi.load('picker', {'callback': function() {
                        pickerApiLoaded = true;
                        openFamilyPicker();
                    }});
                }
                function openFamilyPicker() {
                    if (!pickerApiLoaded) return;
                    var sharedFoldersView = new google.picker.DocsView(google.picker.ViewId.FOLDERS)
                        .setIncludeFolders(true)
                        .setSelectFolderEnabled(true)
                        .setOwnedByMe(false);
                    var searchView = new google.picker.DocsView()
                        .setQuery('$safeQuery')
                        .setIncludeFolders(true)
                        .setSelectFolderEnabled(true);
                    var picker = new google.picker.PickerBuilder()
                        .addView(sharedFoldersView)
                        .addView(searchView)
                        .setOAuthToken('$safeToken')
                        .setAppId('$projectNumber')
                        .setCallback(pickerCallback)
                        .build();
                    picker.setVisible(true);
                }
                function pickerCallback(data) {
                    if (data && data[google.picker.Response.ACTION] == google.picker.Action.PICKED) {
                        var doc = data[google.picker.Response.DOCUMENTS][0];
                        var id = doc[google.picker.Document.ID] || '';
                        var name = doc[google.picker.Document.NAME] || '';
                        if (window.FamilyTasksPickerBridge) {
                            window.FamilyTasksPickerBridge.onItemPicked(id, name);
                        }
                    }
                }
                window.onload = onApiLoad;
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onItemPicked(pickedId: String, pickedName: String) {
                            post {
                                onPicked(pickedId, pickedName)
                            }
                        }
                    },
                    "FamilyTasksPickerBridge"
                )
                loadDataWithBaseURL(
                    "https://drive.google.com",
                    pickerHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )
}
