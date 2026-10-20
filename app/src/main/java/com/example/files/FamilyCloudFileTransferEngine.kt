package com.example.files

import android.content.Context
import android.util.Base64
import com.example.model.DocumentFolder
import com.example.model.FamilyDocument
import com.example.model.FamilyDriveConfig
import com.example.model.GOOGLE_DRIVE_ROOT_FOLDER_NAME
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Coordinates Google Drive file/folder transfers with non-binary metadata synchronization
 * in the configured named Firestore database (`ai-studio-android-familyta-a054dbd1-9c2d-4a83-ad6d-dcfe6fb0d2ca`).
 *
 * Exclusive Google Drive Storage Invariants:
 * - Actual binary file contents and folder structures are stored ONLY in Google Drive inside
 *   the family's shared `FamilyTasks` folder (`driveRootFolderId`).
 * - Does NOT use Firebase Cloud Storage for file contents.
 * - Does NOT use Firestore Base64 binary chunks (`/chunks`) or Firestore as a document/image storage substitute.
 * - Uses temporary local device cache (`context.cacheDir`) strictly for previews, downloads, and FileProvider opening.
 */
object FamilyCloudFileTransferEngine {

    fun parseDriveConfigFromFamilyDoc(
        familyId: String,
        data: Map<String, Any?>
    ): FamilyDriveConfig {
        val rootId = (data["driveRootFolderId"] as? String)?.trim().orEmpty()
        val rootName = (data["driveRootFolderName"] as? String)?.trim()?.ifBlank { GOOGLE_DRIVE_ROOT_FOLDER_NAME }
            ?: GOOGLE_DRIVE_ROOT_FOLDER_NAME
        val ownerUid = (data["driveOwnerUid"] as? String)?.trim()
            ?: (data["ownerUid"] as? String)?.trim()
            ?: (data["createdBy"] as? String)?.trim()
            ?: ""
        val ownerEmail = (data["driveOwnerEmail"] as? String)?.trim().orEmpty()
        val ownerName = (data["driveOwnerName"] as? String)?.trim().orEmpty()
        val webViewLink = (data["driveWebViewLink"] as? String)?.trim().orEmpty()
        val sharedEmails = (data["driveSharedMemberEmails"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.lowercase(Locale.ROOT)?.takeIf { e -> e.isNotBlank() } }
            ?.distinct()
            ?: emptyList()
        val rawStatuses = data["driveMemberAccessStatus"] as? Map<*, *>
        val memberStatuses = rawStatuses?.entries?.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = (v as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to value
        }?.toMap() ?: emptyMap()
        val connectedAt = (data["driveConnectedAtMillis"] as? Number)?.toLong() ?: 0L
        val updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: connectedAt

        return FamilyDriveConfig(
            familyId = familyId,
            driveRootFolderId = rootId,
            driveRootFolderName = rootName,
            ownerUid = ownerUid,
            ownerEmail = ownerEmail,
            ownerName = ownerName,
            isConnected = rootId.isNotBlank(),
            webViewLink = webViewLink.ifBlank {
                if (rootId.isNotBlank()) "https://drive.google.com/drive/folders/$rootId" else ""
            },
            sharedEmails = sharedEmails,
            memberStatuses = memberStatuses,
            connectedAtMillis = connectedAt,
            updatedAtMillis = updatedAt
        )
    }

    suspend fun persistDriveConfigToFirestore(
        db: FirebaseFirestore?,
        config: FamilyDriveConfig
    ): Result<FamilyDriveConfig> {
        if (db == null || config.familyId.isBlank() || config.driveRootFolderId.isBlank()) {
            return Result.success(config)
        }
        return runCatching {
            val familyRef = db.collection("families").document(config.familyId)
            val now = System.currentTimeMillis()
            // Use a transaction so concurrent setups never overwrite an already-registered FamilyTasks root folder ID
            val committedRootId = db.runTransaction { tx ->
                val snap = tx.get(familyRef)
                val existingRootId = snap.getString("driveRootFolderId")?.trim().orEmpty()
                val authoritativeRootId = existingRootId.ifBlank { config.driveRootFolderId.trim() }
                val payload = mapOf(
                    "driveRootFolderId" to authoritativeRootId,
                    "driveRootFolderName" to GOOGLE_DRIVE_ROOT_FOLDER_NAME,
                    "driveConnected" to true,
                    "driveOwnerUid" to (snap.getString("driveOwnerUid")?.takeIf { it.isNotBlank() } ?: config.ownerUid),
                    "driveOwnerEmail" to (snap.getString("driveOwnerEmail")?.takeIf { it.isNotBlank() } ?: config.ownerEmail),
                    "driveOwnerName" to (snap.getString("driveOwnerName")?.takeIf { it.isNotBlank() } ?: config.ownerName),
                    "driveWebViewLink" to "https://drive.google.com/drive/folders/$authoritativeRootId",
                    "driveSharedMemberEmails" to config.sharedEmails,
                    "driveMemberAccessStatus" to config.memberStatuses,
                    "driveConnectedAtMillis" to (snap.getLong("driveConnectedAtMillis") ?: now),
                    "updatedAt" to now
                )
                tx.set(familyRef, payload, SetOptions.merge())
                authoritativeRootId
            }.await()

            config.copy(
                driveRootFolderId = committedRootId,
                driveRootFolderName = GOOGLE_DRIVE_ROOT_FOLDER_NAME,
                isConnected = true,
                webViewLink = "https://drive.google.com/drive/folders/$committedRootId",
                updatedAtMillis = now
            )
        }
    }

    fun parseFolderDocument(
        docId: String,
        data: Map<String, Any?>,
        familyId: String
    ): DocumentFolder? {
        val nameHe = (data["nameHe"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: (data["name"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val nameEn = (data["nameEn"] as? String)?.trim()?.ifBlank { nameHe } ?: nameHe
        val emoji = (data["iconEmoji"] as? String)?.trim()?.ifBlank { "📁" } ?: "📁"
        val colorHex = (data["colorHex"] as? Number)?.toLong() ?: 0xFF1F6E6CL
        val parentFolderId = (data["parentFolderId"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val createdByUid = (data["createdByUid"] as? String)?.trim().orEmpty()
        val createdByName = (data["createdByName"] as? String)?.trim().orEmpty()
        val createdAtMillis = (data["createdAtMillis"] as? Number)?.toLong()
            ?: (data["createdAt"] as? Number)?.toLong()
            ?: System.currentTimeMillis()
        val driveFolderId = (data["driveFolderId"] as? String)?.trim()?.ifBlank { docId } ?: docId
        val webViewLink = (data["webViewLink"] as? String)?.trim()
            ?.ifBlank { "https://drive.google.com/drive/folders/$driveFolderId" }
            ?: "https://drive.google.com/drive/folders/$driveFolderId"

        return DocumentFolder(
            id = docId,
            nameHe = nameHe,
            nameEn = nameEn,
            iconEmoji = emoji,
            colorHex = colorHex,
            familyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() } ?: familyId,
            parentFolderId = parentFolderId,
            createdByUid = createdByUid,
            createdByName = createdByName,
            createdAtMillis = createdAtMillis,
            driveFolderId = driveFolderId,
            webViewLink = webViewLink
        )
    }

    fun buildFolderFirestoreMap(
        folder: DocumentFolder,
        familyId: String,
        callerUid: String,
        callerName: String
    ): Map<String, Any?> {
        val safeNameHe = FamilyFileManagerHelper.sanitizeFolderName(folder.nameHe).ifBlank { "תיקייה משותפת" }
        val safeNameEn = FamilyFileManagerHelper.sanitizeFolderName(folder.nameEn).ifBlank { safeNameHe }
        val now = folder.createdAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val driveFolderId = folder.driveFolderId.ifBlank { folder.id }
        val map = mutableMapOf<String, Any?>(
            "id" to folder.id,
            "driveFolderId" to driveFolderId,
            "familyId" to familyId,
            "nameHe" to safeNameHe,
            "nameEn" to safeNameEn,
            "iconEmoji" to folder.iconEmoji.ifBlank { "📁" },
            "colorHex" to folder.colorHex,
            "createdByUid" to callerUid,
            "createdByName" to callerName.ifBlank { "חבר משפחה" },
            "createdAtMillis" to now,
            "updatedAt" to now,
            "webViewLink" to folder.webViewLink.ifBlank { "https://drive.google.com/drive/folders/$driveFolderId" }
        )
        if (!folder.parentFolderId.isNullOrBlank()) {
            map["parentFolderId"] = folder.parentFolderId
        }
        return map
    }

    fun parseFileDocument(
        docId: String,
        data: Map<String, Any?>,
        familyId: String
    ): FamilyDocument? {
        val rawName = (data["name"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val ext = ((data["extension"] as? String)?.trim()?.uppercase(Locale.ROOT)).orEmpty().ifBlank {
            FamilyFileManagerHelper.extractExtension(rawName, "PDF")
        }
        val sizeBytes = ((data["sizeBytes"] as? Number)?.toLong() ?: 0L).coerceAtLeast(1L)
        val sizeFormatted = (data["sizeFormatted"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: FamilyFileManagerHelper.formatFileSize(sizeBytes)
        val uploadedByUid = (data["uploadedByUid"] as? String)?.trim().orEmpty()
        val uploadedByMemberId = (data["uploadedByMemberId"] as? String)?.trim()?.ifBlank { uploadedByUid }
            ?: uploadedByUid
        val uploadedByName = (data["uploadedByName"] as? String)?.trim()?.ifBlank { uploadedByMemberId }
            ?: uploadedByMemberId
        val uploadedDateFormatted = (data["uploadedDateFormatted"] as? String)?.trim()
            ?: (data["uploadDateFormatted"] as? String)?.trim()
            ?: "היום"
        val uploadedAtMillis = (data["uploadedAtMillis"] as? Number)?.toLong()
            ?: (data["createdAt"] as? Number)?.toLong()
            ?: System.currentTimeMillis()
        val folderId = (data["folderId"] as? String)?.trim()?.ifBlank { "f_school" } ?: "f_school"
        val mimeType = (data["mimeType"] as? String)?.trim()?.ifBlank {
            FamilyDocument.defaultMimeTypeForExtension(ext)
        } ?: FamilyDocument.defaultMimeTypeForExtension(ext)
        val driveFileId = (data["driveFileId"] as? String)?.trim()?.ifBlank { docId } ?: docId
        val driveParentFolderId = (data["driveParentFolderId"] as? String)?.trim()?.ifBlank { folderId } ?: folderId
        val webViewLink = (data["driveUrl"] as? String)?.trim()
            ?.ifBlank { "https://drive.google.com/file/d/$driveFileId/view" }
            ?: "https://drive.google.com/file/d/$driveFileId/view"
        val storagePath = (data["storagePath"] as? String)?.trim().orEmpty()
        val contentPreviewHe = (data["contentPreviewHe"] as? String).orEmpty()
        val contentPreviewEn = (data["contentPreviewEn"] as? String).orEmpty()
        val thumbnailBase64 = (data["thumbnailBase64"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        return FamilyDocument(
            id = docId,
            folderId = folderId,
            name = rawName,
            extension = ext,
            sizeFormatted = sizeFormatted,
            uploadedByMemberId = uploadedByMemberId,
            uploadedDateFormatted = uploadedDateFormatted,
            isDriveSynced = true,
            driveUrl = webViewLink,
            contentPreviewHe = contentPreviewHe,
            contentPreviewEn = contentPreviewEn,
            uploadedByName = uploadedByName,
            uploadDateFormatted = uploadedDateFormatted,
            driveFileUrl = webViewLink,
            familyId = (data["familyId"] as? String)?.takeIf { it.isNotBlank() } ?: familyId,
            uploadedByUid = uploadedByUid.ifBlank { uploadedByMemberId },
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            storagePath = storagePath,
            uploadedAtMillis = uploadedAtMillis,
            thumbnailBase64 = thumbnailBase64,
            chunkCount = 0,
            driveFileId = driveFileId,
            driveParentFolderId = driveParentFolderId
        )
    }

    /**
     * Builds the Firestore index payload for a Google Drive file, including `driveFileId`, `mimeType`,
     * and optional `thumbnailBase64` preview so cross-account family members can preview shared images.
     */
    fun buildFileFirestoreMap(
        doc: FamilyDocument,
        familyId: String,
        callerUid: String,
        callerName: String,
        callerRole: String
    ): Map<String, Any?> {
        val ext = FamilyFileManagerHelper.extractExtension(doc.name, doc.extension)
        val safeName = FamilyFileManagerHelper.sanitizeFileName(doc.name, ext.lowercase(Locale.ROOT))
        val validSizeBytes = doc.sizeBytes.coerceIn(1L, FamilyFileManagerHelper.MAX_FILE_SIZE_BYTES)
        val storagePath = if (doc.storagePath.isNotBlank() && FamilyFileManagerHelper.isSafeStoragePath(doc.storagePath, familyId)) {
            doc.storagePath
        } else {
            FamilyFileManagerHelper.buildStoragePath(familyId, doc.id, safeName)
        }
        val driveFileId = doc.driveFileId.ifBlank { doc.id }
        val webViewLink = doc.driveUrl.ifBlank { "https://drive.google.com/file/d/$driveFileId/view" }
        val now = doc.uploadedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dateStr = doc.uploadedDateFormatted.ifBlank {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        }

        val map = mutableMapOf<String, Any?>(
            "id" to doc.id,
            "driveFileId" to driveFileId,
            "driveParentFolderId" to doc.driveParentFolderId.ifBlank { doc.folderId },
            "familyId" to familyId,
            "folderId" to doc.folderId,
            "name" to safeName,
            "extension" to ext,
            "mimeType" to doc.mimeType.ifBlank { FamilyDocument.defaultMimeTypeForExtension(ext) },
            "sizeBytes" to validSizeBytes,
            "sizeFormatted" to doc.sizeFormatted.ifBlank { FamilyFileManagerHelper.formatFileSize(validSizeBytes) },
            "uploadedByUid" to callerUid,
            "uploadedByMemberId" to callerUid,
            "uploadedByName" to callerName.ifBlank { "חבר משפחה" },
            "uploaderRole" to callerRole,
            "uploadedDateFormatted" to dateStr,
            "uploadDateFormatted" to dateStr,
            "uploadedAtMillis" to now,
            "updatedAt" to now,
            "isDriveSynced" to true,
            "driveUrl" to webViewLink,
            "storagePath" to storagePath
        )
        val thumb = doc.thumbnailBase64?.trim()
        if (!thumb.isNullOrEmpty() && thumb.length <= 350_000) {
            map["thumbnailBase64"] = thumb
        }
        return map
    }

    /**
     * Uploads [fileBytes] to the family's shared `FamilyTasks` folder in Google Drive
     * using the uploader's authorized Google account ([context] + [callerEmail]), and records
     * metadata (including the real `driveFileId` and EXIF-corrected `thumbnailBase64` preview for images)
     * in `/families/{familyId}/files/{doc.id}`.
     */
    suspend fun uploadBinaryAndPersistMetadata(
        context: Context?,
        db: FirebaseFirestore?,
        familyId: String,
        driveRootFolderId: String = "",
        callerUid: String,
        callerName: String,
        callerRole: String,
        callerEmail: String? = null,
        doc: FamilyDocument,
        fileBytes: ByteArray,
        rootDriveFolderId: String = driveRootFolderId,
        targetDriveFolderId: String = "",
        authorizedMemberUids: List<String> = emptyList(),
        parentUids: List<String> = emptyList(),
        simulatedStepDelayMs: Long = 0L,
        onProgress: (Float) -> Unit
    ): Result<FamilyDocument> {
        var uploadedDriveFileId: String? = null
        val safeStoragePath = FamilyFileManagerHelper.buildStoragePath(familyId, doc.id, doc.name)

        return try {
            currentCoroutineContext().ensureActive()
            onProgress(0.10f)

            // 1. Upload actual binary bytes to Google Drive inside the shared FamilyTasks folder
            // CRITICAL: Pass context and callerEmail so the real Google Drive REST API is invoked!
            val effectiveRootId = rootDriveFolderId.trim()
                .ifBlank { driveRootFolderId.trim() }
                .ifBlank { GoogleDriveFamilyService.getRegisteredRootFolderIdForFamily(familyId).orEmpty() }
                .ifBlank { "gdrive_root_$familyId" }
            val effectiveTargetFolderId = targetDriveFolderId.trim()
                .takeIf { it.isNotBlank() && !it.startsWith("f_") }
                ?: effectiveRootId
            val driveUploadRes = GoogleDriveFamilyService.uploadFileToDrive(
                familyId = familyId,
                driveRootFolderId = effectiveRootId,
                targetFolderId = effectiveTargetFolderId,
                fileIdHint = doc.id,
                fileName = doc.name,
                extension = doc.extension,
                mimeType = doc.mimeType,
                fileBytes = fileBytes,
                callerUid = callerUid,
                callerName = callerName,
                context = context,
                accountEmail = callerEmail,
                simulatedStepDelayMs = simulatedStepDelayMs,
                onProgress = onProgress
            )
            val driveDoc = driveUploadRes.getOrThrow()
            uploadedDriveFileId = driveDoc.driveFileId

            // 2. Ensure a high-clarity EXIF-oriented preview is generated for JPG/PNG/WEBP images
            currentCoroutineContext().ensureActive()
            val extUpper = FamilyFileManagerHelper.extractExtension(doc.name, doc.extension)
            val resolvedThumbnail = doc.thumbnailBase64?.takeIf { it.isNotBlank() }
                ?: driveDoc.thumbnailBase64?.takeIf { it.isNotBlank() }
                ?: if (extUpper in setOf("JPG", "JPEG", "PNG", "WEBP") && fileBytes.isNotEmpty()) {
                    FamilyFileManagerHelper.createCompactThumbnailBase64(fileBytes, maxDimension = 720)
                } else {
                    null
                }

            val preparedDoc = doc.copy(
                id = doc.id,
                familyId = familyId,
                uploadedByUid = callerUid,
                uploadedByMemberId = callerUid,
                uploadedByName = callerName,
                storagePath = safeStoragePath,
                driveFileId = driveDoc.driveFileId,
                driveParentFolderId = driveDoc.driveParentFolderId,
                driveUrl = driveDoc.driveUrl,
                driveFileUrl = driveDoc.driveFileUrl,
                webViewLink = driveDoc.driveUrl,
                isDriveSynced = true,
                thumbnailBase64 = resolvedThumbnail,
                chunkCount = 0
            )
            if (context != null && fileBytes.isNotEmpty()) {
                FamilyFileManagerHelper.writeBytesToCachedFile(context, preparedDoc, fileBytes)
            }

            // 3. Commit index metadata to /families/{familyId}/files/{fileId}
            currentCoroutineContext().ensureActive()
            if (db != null && familyId.isNotBlank()) {
                val payload = buildFileFirestoreMap(
                    doc = preparedDoc,
                    familyId = familyId,
                    callerUid = callerUid,
                    callerName = callerName,
                    callerRole = callerRole
                )
                db.collection("families").document(familyId)
                    .collection("files").document(preparedDoc.id)
                    .set(payload)
                    .await()
            }

            onProgress(1.0f)
            Result.success(preparedDoc)
        } catch (ce: CancellationException) {
            if (!uploadedDriveFileId.isNullOrBlank()) {
                GoogleDriveFamilyService.deleteItemInDrive(
                    driveItemId = uploadedDriveFileId,
                    context = context,
                    accountEmail = callerEmail
                )
            }
            throw ce
        } catch (e: Exception) {
            if (!uploadedDriveFileId.isNullOrBlank()) {
                runCatching {
                    GoogleDriveFamilyService.deleteItemInDrive(
                        driveItemId = uploadedDriveFileId,
                        context = context,
                        accountEmail = callerEmail
                    )
                }
            }
            Result.failure(e)
        }
    }

    suspend fun deleteFileFromGoogleDrive(
        context: Context?,
        doc: FamilyDocument
    ) {
        val driveId = doc.driveFileId.ifBlank { doc.id }
        runCatching {
            GoogleDriveFamilyService.deleteItemInDrive(driveId, context = context)
        }
        if (context != null) {
            runCatching {
                val cached = FamilyFileManagerHelper.getCachedFileForDocument(context, doc)
                if (cached.exists()) cached.delete()
            }
        }
    }

    suspend fun deleteBinaryAndChunks(
        context: Context?,
        db: FirebaseFirestore?,
        familyId: String,
        fileId: String,
        driveFileId: String = fileId,
        callerEmail: String? = null,
        storagePath: String = ""
    ) {
        val effectiveDriveId = driveFileId.ifBlank { fileId }
        runCatching {
            GoogleDriveFamilyService.deleteItemInDrive(
                driveItemId = effectiveDriveId,
                context = context,
                accountEmail = callerEmail
            )
        }
    }

    /**
     * Downloads the binary bytes for [doc] from Google Drive using the current viewer's authorized
     * Google account ([context] + [callerEmail]), or returns from local cache / shared preview
     * so every authorized family member (PARENT or CHILD) can view, download, open, or share the file.
     */
    suspend fun fetchDocumentBytes(
        context: Context?,
        db: FirebaseFirestore?,
        familyId: String,
        doc: FamilyDocument,
        callerEmail: String? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        // 1. Check temporary local cache first (only if non-empty)
        if (context != null) {
            val cachedFile = FamilyFileManagerHelper.getCachedFileForDocument(context, doc)
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                val cachedBytes = runCatching { cachedFile.readBytes() }.getOrNull()
                if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                    return@withContext cachedBytes
                }
            }
        }

        // 2. Download directly from Google Drive using the viewer's authorized Google account!
        // Also extract the Drive file ID from driveUrl if driveFileId was stored as a doc_ key in older uploads.
        val extractedUrlDriveId = extractDriveIdFromUrl(doc.driveUrl)
            ?: extractDriveIdFromUrl(doc.driveFileUrl)
            ?: extractDriveIdFromUrl(doc.webViewLink)
        val driveFileId = when {
            doc.driveFileId.isNotBlank() && !doc.driveFileId.startsWith("doc_") -> doc.driveFileId.trim()
            !extractedUrlDriveId.isNullOrBlank() -> extractedUrlDriveId
            doc.driveFileId.isNotBlank() -> doc.driveFileId.trim()
            else -> doc.id
        }

        val fromDrive = GoogleDriveFamilyService.downloadFileBytesFromDrive(
            driveFileId = driveFileId,
            context = context,
            accountEmail = callerEmail
        ).getOrNull()

        if (fromDrive != null && fromDrive.isNotEmpty()) {
            if (context != null) {
                runCatching {
                    FamilyFileManagerHelper.writeBytesToCachedFile(context, doc, fromDrive)
                }
            }
            return@withContext fromDrive
        }

        // 3. If the file is an image and has a shared preview in Firestore (`thumbnailBase64`),
        // decode and cache those JPEG bytes so cross-device family members see the actual image
        // immediately even if `drive.file` per-file consent was created by the other member.
        if (doc.isImageFile) {
            val b64 = doc.thumbnailBase64?.takeIf { it.isNotBlank() } ?: run {
                // Also check live Firestore doc in case thumbnailBase64 was present there
                if (db != null && familyId.isNotBlank() && doc.id.isNotBlank()) {
                    runCatching {
                        val snap = db.collection("families").document(familyId)
                            .collection("files").document(doc.id)
                            .get()
                            .await()
                        snap.getString("thumbnailBase64")?.takeIf { it.isNotBlank() }
                    }.getOrNull()
                } else null
            }
            if (!b64.isNullOrBlank()) {
                val thumbBytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                    if (context != null) {
                        runCatching {
                            FamilyFileManagerHelper.writeBytesToCachedFile(context, doc, thumbBytes)
                        }
                    }
                    return@withContext thumbBytes
                }
            }
        }

        null
    }

    private fun extractDriveIdFromUrl(url: String): String? {
        if (url.isBlank()) return null
        val match = Regex("/d/([a-zA-Z0-9_-]{10,})").find(url)
            ?: Regex("[?&]id=([a-zA-Z0-9_-]{10,})").find(url)
        return match?.groupValues?.getOrNull(1)
    }
}
