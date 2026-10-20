package com.example.files

import android.content.Context
import com.example.model.DocumentFolder
import com.example.model.FamilyDocument
import com.example.model.FamilyDriveConfig
import com.example.model.GOOGLE_DRIVE_ROOT_FOLDER_NAME
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
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
            thumbnailBase64 = null,
            chunkCount = 0,
            driveFileId = driveFileId,
            driveParentFolderId = driveParentFolderId
        )
    }

    /**
     * Builds the non-binary Firestore index payload for a Google Drive file.
     * Never writes Base64 image blobs or binary chunks to Firestore.
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

        return mapOf(
            "id" to doc.id,
            "driveFileId" to driveFileId,
            "driveParentFolderId" to doc.driveParentFolderId.ifBlank { doc.folderId },
            "familyId" to familyId,
            "folderId" to doc.folderId.ifBlank { "f_school" },
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
    }

    /**
     * Uploads [fileBytes] exclusively to the family's shared `FamilyTasks` folder in Google Drive
     * and records non-binary index metadata in `/families/{familyId}/files/{doc.id}`.
     *
     * Reliability invariants:
     * - Reports progress via [onProgress] (`0.0f..1.0f`).
     * - Never writes Firestore metadata until the Google Drive upload succeeds.
     * - If cancelled or if metadata persistence fails, deletes the partially uploaded Google Drive file.
     * - Never uses Firebase Cloud Storage or Firestore binary chunks.
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
                simulatedStepDelayMs = simulatedStepDelayMs,
                onProgress = onProgress
            )
            val driveDoc = driveUploadRes.getOrThrow()
            uploadedDriveFileId = driveDoc.driveFileId

            // 2. Write temporary local cache file for instant preview / FileProvider opening
            currentCoroutineContext().ensureActive()
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
                chunkCount = 0
            )
            if (context != null && fileBytes.isNotEmpty()) {
                FamilyFileManagerHelper.writeBytesToCachedFile(context, preparedDoc, fileBytes)
            }

            // 3. Commit non-binary index metadata to /families/{familyId}/files/{fileId}
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
                GoogleDriveFamilyService.deleteItemInDrive(uploadedDriveFileId)
            }
            throw ce
        } catch (e: Exception) {
            if (!uploadedDriveFileId.isNullOrBlank()) {
                runCatching { GoogleDriveFamilyService.deleteItemInDrive(uploadedDriveFileId) }
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
            GoogleDriveFamilyService.deleteItemInDrive(driveId)
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
     * Downloads the binary bytes for [doc] from Google Drive (or returns from temporary local cache)
     * so any family member (PARENT or CHILD) can open, preview, edit, download, or share the file.
     */
    suspend fun fetchDocumentBytes(
        context: Context?,
        db: FirebaseFirestore?,
        familyId: String,
        doc: FamilyDocument,
        callerEmail: String? = null
    ): ByteArray? {
        // 1. Check temporary local cache first
        if (context != null) {
            val cachedFile = FamilyFileManagerHelper.getCachedFileForDocument(context, doc)
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                return runCatching { cachedFile.readBytes() }.getOrNull()
            }
        }

        // 2. Download directly from Google Drive
        val driveFileId = doc.driveFileId.ifBlank { doc.id }
        val fromDrive = GoogleDriveFamilyService.downloadFileBytesFromDrive(driveFileId).getOrNull()
        if (fromDrive != null && fromDrive.isNotEmpty()) {
            if (context != null) {
                runCatching {
                    FamilyFileManagerHelper.writeBytesToCachedFile(context, doc, fromDrive)
                }
            }
            return fromDrive
        }

        return null
    }
}
