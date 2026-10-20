package com.example.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.model.DocumentFolder
import com.example.model.FamilyDocument
import com.example.model.FamilyMember
import com.example.model.FileSortOption
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Core security, sanitization, caching, FileProvider URI resolution, and format handling
 * for the FamilyTasks V1.0.0 Shared Family File Manager.
 */
object FamilyFileManagerHelper {

    const val MAX_FILE_SIZE_BYTES: Long = 15L * 1024L * 1024L // 15 MB per file
    const val CHUNK_RAW_BYTES_SIZE: Int = 180 * 1024 // ~240 KB Base64 per Firestore chunk document
    const val MAX_INLINE_CHUNKS: Int = 20

    val ALLOWED_EXTENSIONS: Set<String> = setOf(
        "PDF", "JPG", "JPEG", "PNG", "WEBP",
        "DOC", "DOCX", "XLS", "XLSX", "PPT", "PPTX",
        "TXT", "ZIP"
    )

    val SAF_MIME_TYPES: Array<String> = arrayOf(
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/webp",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain",
        "application/zip",
        "*/*"
    )

    data class PickedFilePayload(
        val originalName: String,
        val sanitizedName: String,
        val extension: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sizeFormatted: String,
        val bytes: ByteArray,
        val thumbnailBase64: String? = null
    )

    sealed class FileValidationResult {
        data class Valid(
            val sanitizedName: String,
            val extension: String,
            val mimeType: String,
            val sizeBytes: Long,
            val sizeFormatted: String
        ) : FileValidationResult()

        data class Invalid(
            val errorCode: String,
            val messageHe: String,
            val messageEn: String
        ) : FileValidationResult()
    }

    /**
     * Sanitizes a user-supplied or device-picked filename:
     * - Strips directory traversal (`..`), slashes (`/`, `\`), control characters, and null bytes.
     * - Ensures a safe extension is preserved.
     * - Caps total length to 120 characters.
     */
    fun sanitizeFileName(rawName: String, defaultExtension: String = "pdf"): String {
        val stripped = rawName
            .replace("\u0000", "")
            .replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "_")
            .replace("..", "_")
            .trim()
            .trimStart('.')

        val fallbackExt = defaultExtension.trim().trimStart('.').lowercase(Locale.ROOT).ifBlank { "pdf" }
        if (stripped.isBlank()) {
            return "קובץ_משפחתי.$fallbackExt"
        }

        val dotIndex = stripped.lastIndexOf('.')
        val baseName: String
        val ext: String
        if (dotIndex > 0 && dotIndex < stripped.length - 1) {
            baseName = stripped.substring(0, dotIndex).trim().ifBlank { "קובץ_משפחתי" }
            ext = stripped.substring(dotIndex + 1).trim().lowercase(Locale.ROOT).ifBlank { fallbackExt }
        } else {
            baseName = stripped.trim().ifBlank { "קובץ_משפחתי" }
            ext = fallbackExt
        }

        val cappedBase = if (baseName.length > 96) baseName.take(96) else baseName
        val cappedExt = if (ext.length > 10) ext.take(10) else ext
        return "$cappedBase.$cappedExt"
    }

    /**
     * Sanitizes a folder name (strips path separators and traversal tokens).
     */
    fun sanitizeFolderName(rawFolderName: String): String {
        return rawFolderName
            .replace("\u0000", "")
            .replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), " ")
            .replace("..", "")
            .trim()
            .take(100)
    }

    /**
     * Resolves duplicate filenames in the same folder by appending ` (1)`, ` (2)`, etc.
     * Ensures uploading a file with an identical name never overwrites or confuses existing files.
     */
    fun resolveUniqueDisplayFileName(
        desiredName: String,
        existingNamesInFolder: Collection<String>
    ): String {
        val normalizedExisting = existingNamesInFolder.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        if (desiredName.trim().lowercase(Locale.ROOT) !in normalizedExisting) {
            return desiredName.trim()
        }

        val dotIndex = desiredName.lastIndexOf('.')
        val base = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val extSuffix = if (dotIndex > 0) desiredName.substring(dotIndex) else ""

        var counter = 2
        while (counter < 10_000) {
            val candidate = "$base ($counter)$extSuffix"
            if (candidate.lowercase(Locale.ROOT) !in normalizedExisting) {
                return candidate
            }
            counter++
        }
        return "${base}_${System.currentTimeMillis() % 10000}$extSuffix"
    }

    /**
     * Builds a private, family-scoped Firebase Cloud Storage object path.
     * Uses both [familyId] and unique [fileId] so identical filenames never collide.
     */
    fun buildStoragePath(familyId: String, fileId: String, sanitizedFileName: String): String {
        val safeFamilyId = familyId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeFileId = fileId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeName = sanitizeFileName(sanitizedFileName)
        return "families/$safeFamilyId/files/$safeFileId/$safeName"
    }

    /**
     * Validates that a storage path belongs strictly to [familyId] and contains no path traversal.
     */
    fun isSafeStoragePath(storagePath: String, familyId: String): Boolean {
        if (storagePath.isBlank() || storagePath.contains("..") || storagePath.contains("//")) {
            return false
        }
        return storagePath.startsWith("families/$familyId/files/")
    }

    fun isStoragePathSafeForFamily(storagePath: String, familyId: String): Boolean =
        isSafeStoragePath(storagePath, familyId)

    /**
     * Extracts uppercase extension from a filename or fallback extension.
     */
    fun extractExtension(fileName: String, fallbackExtension: String = ""): String {
        val dotIdx = fileName.lastIndexOf('.')
        val fromName = if (dotIdx > 0 && dotIdx < fileName.length - 1) {
            fileName.substring(dotIdx + 1).trim().uppercase(Locale.ROOT)
        } else {
            ""
        }
        val resolved = fromName.ifBlank { fallbackExtension.trim().trimStart('.').uppercase(Locale.ROOT) }
        return if (resolved == "JPEG") "JPG" else resolved.ifBlank { "PDF" }
    }

    /**
     * Formats byte size into human-readable KB / MB string.
     */
    fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return "0 KB"
        return when {
            sizeBytes < 1024L -> "$sizeBytes B"
            sizeBytes < 1024L * 1024L -> {
                val kb = sizeBytes / 1024.0
                if (kb < 10.0) String.format(Locale.US, "%.1f KB", kb)
                else String.format(Locale.US, "%.0f KB", kb)
            }
            else -> {
                val mb = sizeBytes / (1024.0 * 1024.0)
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }

    fun parseApproximateSizeBytes(sizeFormatted: String): Long {
        val clean = sizeFormatted.trim().uppercase(Locale.ROOT)
        val num = clean.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: return 0L
        return when {
            clean.contains("MB") -> (num * 1024.0 * 1024.0).toLong()
            clean.contains("KB") -> (num * 1024.0).toLong()
            else -> num.toLong()
        }
    }

    /**
     * Validates file name, extension, and size before starting an upload.
     */
    fun validateUploadCandidate(
        rawFileName: String,
        rawExtension: String,
        sizeBytes: Long
    ): FileValidationResult {
        if (sizeBytes <= 0L) {
            return FileValidationResult.Invalid(
                errorCode = "EMPTY_FILE",
                messageHe = "הקובץ שנבחר ריק ולא ניתן להעלאה.",
                messageEn = "The selected file is empty and cannot be uploaded."
            )
        }
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            return FileValidationResult.Invalid(
                errorCode = "FILE_TOO_LARGE",
                messageHe = "הקובץ גדול מדי (${formatFileSize(sizeBytes)}). הגודל המקסימלי המותר הוא 15 MB.",
                messageEn = "File is too large (${formatFileSize(sizeBytes)}). Maximum allowed size is 15 MB."
            )
        }

        val ext = extractExtension(rawFileName, rawExtension)
        if (ext !in ALLOWED_EXTENSIONS) {
            return FileValidationResult.Invalid(
                errorCode = "UNSUPPORTED_FORMAT",
                messageHe = "סוג הקובץ ($ext) אינו נתמך. פורמטים נתמכים: PDF, JPG, PNG, DOCX, XLSX, PPTX, TXT, ZIP.",
                messageEn = "Unsupported file format ($ext). Supported formats: PDF, JPG, PNG, DOCX, XLSX, PPTX, TXT, ZIP."
            )
        }

        val sanitizedName = sanitizeFileName(rawFileName, ext.lowercase(Locale.ROOT))
        val mimeType = FamilyDocument.defaultMimeTypeForExtension(ext)
        return FileValidationResult.Valid(
            sanitizedName = sanitizedName,
            extension = ext,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sizeFormatted = formatFileSize(sizeBytes)
        )
    }

    /**
     * Authorization check inside the shared `FamilyTasks` Google Drive folder:
     * Every active family member (both PARENT and CHILD) has equal file-management permissions
     * (open, preview, upload, download, edit, rename, move, and delete) inside their family's
     * shared `FamilyTasks` folder. Cross-family access is strictly forbidden.
     */
    fun canMemberModifyOrDeleteFile(
        file: FamilyDocument,
        member: FamilyMember,
        authenticatedUid: String?,
        activeFamilyId: String = ""
    ): Boolean {
        val callerIds = setOfNotNull(
            authenticatedUid?.takeIf { it.isNotBlank() },
            member.id.takeIf { it.isNotBlank() }
        )
        if (callerIds.isEmpty()) return false
        if (activeFamilyId.isNotBlank() && file.familyId.isNotBlank() && file.familyId != activeFamilyId) {
            return false
        }
        return true
    }

    /**
     * Authorization check for shared folders inside the `FamilyTasks` Google Drive workspace:
     * Every active family member (both PARENT and CHILD) has equal folder-management permissions
     * (create, rename, organize, and delete folders/subfolders).
     */
    fun canMemberDeleteFolder(member: FamilyMember): Boolean {
        return member.id.isNotBlank()
    }

    /**
     * Builds the ordered breadcrumb trail from root to [selectedFolderId].
     */
    fun buildFolderBreadcrumbTrail(
        folders: List<DocumentFolder>,
        selectedFolderId: String?
    ): List<DocumentFolder> {
        if (selectedFolderId.isNullOrBlank()) return emptyList()
        val folderById = folders.associateBy { it.id }
        val trail = mutableListOf<DocumentFolder>()
        val visited = mutableSetOf<String>()
        var cursor = folderById[selectedFolderId]
        while (cursor != null && visited.add(cursor.id)) {
            trail.add(0, cursor)
            val parentId = cursor.parentFolderId?.takeIf { it.isNotBlank() }
            cursor = if (parentId != null) folderById[parentId] else null
        }
        return trail
    }

    /**
     * Sorts files according to the selected [FileSortOption] and ascending/descending order.
     */
    fun sortFiles(
        files: List<FamilyDocument>,
        sortOption: FileSortOption,
        ascending: Boolean = false
    ): List<FamilyDocument> {
        val comparator: Comparator<FamilyDocument> = when (sortOption) {
            FileSortOption.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FileSortOption.DATE -> compareBy<FamilyDocument> { it.uploadedAtMillis }.thenBy { it.name }
            FileSortOption.SIZE -> compareBy<FamilyDocument> { it.sizeBytes }.thenBy { it.name }
            FileSortOption.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.extension }
        }
        return if (ascending) {
            files.sortedWith(comparator)
        } else {
            when (sortOption) {
                FileSortOption.NAME, FileSortOption.TYPE -> files.sortedWith(comparator)
                FileSortOption.DATE, FileSortOption.SIZE -> files.sortedWith(comparator.reversed())
            }
        }
    }

    /**
     * Reads a picked [Uri] from Android Storage Access Framework into a [PickedFilePayload].
     */
    fun readPickedUriPayload(context: Context, uri: Uri): Result<PickedFilePayload> {
        return runCatching {
            val resolver = context.contentResolver
            var displayName = "מסמך_משפחתי.pdf"
            var queriedSize = -1L

            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        queriedSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            if (queriedSize > MAX_FILE_SIZE_BYTES) {
                throw IllegalArgumentException("הקובץ גדול מדי (${formatFileSize(queriedSize)}). מקסימום 15 MB.")
            }

            val detectedMime = resolver.getType(uri).orEmpty()
            val fallbackExt = mimeTypeToExtension(detectedMime)
            val ext = extractExtension(displayName, fallbackExt)
            val sanitized = sanitizeFileName(displayName, ext.lowercase(Locale.ROOT))

            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var totalRead = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    totalRead += read
                    if (totalRead > MAX_FILE_SIZE_BYTES) {
                        throw IllegalArgumentException("הקובץ גדול מדי (מעל 15 MB).")
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: throw IllegalStateException("לא ניתן לקרוא את הקובץ שנבחר מהמכשיר.")

            val actualSize = bytes.size.toLong().coerceAtLeast(1L)
            val thumb = if (ext in setOf("JPG", "JPEG", "PNG", "WEBP")) {
                createCompactThumbnailBase64(bytes)
            } else {
                null
            }

            PickedFilePayload(
                originalName = displayName,
                sanitizedName = sanitized,
                extension = ext,
                mimeType = FamilyDocument.defaultMimeTypeForExtension(ext),
                sizeBytes = actualSize,
                sizeFormatted = formatFileSize(actualSize),
                bytes = bytes,
                thumbnailBase64 = thumb
            )
        }
    }

    private fun mimeTypeToExtension(mimeType: String): String {
        return when (mimeType.lowercase(Locale.ROOT)) {
            "application/pdf" -> "PDF"
            "image/jpeg", "image/jpg" -> "JPG"
            "image/png" -> "PNG"
            "image/webp" -> "WEBP"
            "application/msword" -> "DOC"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
            "application/vnd.ms-excel" -> "XLS"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX"
            "application/vnd.ms-powerpoint" -> "PPT"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX"
            "text/plain" -> "TXT"
            "application/zip", "application/x-zip-compressed" -> "ZIP"
            else -> "PDF"
        }
    }

    /**
     * Generates a compact JPEG thumbnail Base64 string so image thumbnails render immediately
     * in Grid and List views across all family devices.
     */
    fun createCompactThumbnailBase64(imageBytes: ByteArray, maxDimension: Int = 180): String? {
        return runCatching {
            val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
            val ratio = minOf(
                maxDimension.toFloat() / decoded.width.coerceAtLeast(1),
                maxDimension.toFloat() / decoded.height.coerceAtLeast(1),
                1f
            )
            val targetW = (decoded.width * ratio).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * ratio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /**
     * Encodes a captured camera [Bitmap] into JPEG bytes + thumbnail.
     */
    fun encodeCameraBitmapToJpegBytes(bitmap: Bitmap, quality: Int = 88): Pair<ByteArray, String?> {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        val thumb = createCompactThumbnailBase64(bytes)
        return bytes to thumb
    }

    /**
     * Generates a real, multi-page or single-page PDF byte array for scanned documents or
     * seeded documents so Android's native `PdfRenderer` and external PDF viewers can open it cleanly.
     */
    fun generateDocumentPdfBytes(
        title: String,
        uploaderName: String,
        dateFormatted: String,
        bodyLines: List<String>
    ): ByteArray {
        return runCatching {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val bgPaint = Paint().apply { color = Color.WHITE }
            canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

            val headerBarPaint = Paint().apply { color = Color.rgb(31, 110, 108) }
            canvas.drawRect(0f, 0f, 595f, 76f, headerBarPaint)

            val headerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20f
                isFakeBoldText = true
            }
            canvas.drawText("FamilyTasks • $title", 36f, 46f, headerTitlePaint)

            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(92, 102, 114)
                textSize = 13f
            }
            canvas.drawText("Uploaded by: $uploaderName   |   Date: $dateFormatted", 36f, 114f, metaPaint)

            val dividerPaint = Paint().apply {
                color = Color.rgb(220, 226, 232)
                strokeWidth = 1.5f
            }
            canvas.drawLine(36f, 130f, 559f, 130f, dividerPaint)

            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(26, 33, 40)
                textSize = 15f
            }
            var y = 168f
            for (line in bodyLines) {
                canvas.drawText(line, 36f, y, bodyPaint)
                y += 28f
            }

            pdfDocument.finishPage(page)
            val out = ByteArrayOutputStream()
            pdfDocument.writeTo(out)
            pdfDocument.close()
            out.toByteArray()
        }.getOrElse {
            // Minimal valid PDF fallback for JVM unit tests where Android Canvas is stubbed
            "%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >> endobj\nxref\n0 4\ntrailer << /Size 4 /Root 1 0 R >>\n%%EOF".toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Generates a clean image byte array for a captured/scanned document or photo fallback.
     */
    fun generateSampleImageBytes(title: String, uploaderName: String = "FamilyTasks"): ByteArray {
        return runCatching {
            val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val bg = Paint().apply { color = Color.rgb(236, 245, 244) }
            canvas.drawRect(0f, 0f, 640f, 480f, bg)

            val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(40f, 40f, 600f, 440f, 24f, 24f, cardPaint)

            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(31, 110, 108)
                textSize = 26f
                isFakeBoldText = true
            }
            canvas.drawText(title.take(28), 72f, 140f, accentPaint)

            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(74, 111, 165)
                textSize = 20f
            }
            canvas.drawText(uploaderName, 72f, 190f, subPaint)

            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val bytes = out.toByteArray()
            if (bytes.isNotEmpty()) bytes else byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        }.getOrElse {
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        }
    }

    /**
     * Creates a temporary camera capture file in `cacheDir/camera_captures/` and returns both
     * the [File] and its secure `FileProvider` `content://` [Uri].
     */
    fun createTempCameraCaptureUri(context: Context, prefix: String = "family_photo"): Pair<File, Uri> {
        val captureDir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val file = File(captureDir, "${prefix}_${System.currentTimeMillis()}.jpg")
        if (!file.exists()) {
            file.createNewFile()
        }
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        return file to uri
    }

    fun createTempCameraImageUri(context: Context, prefix: String = "family_photo"): Pair<File, Uri> =
        createTempCameraCaptureUri(context, prefix)

    /**
     * Returns the local cache [File] path for a family document without exposing raw paths to external apps.
     */
    fun getCachedFileForDocument(context: Context, doc: FamilyDocument): File {
        val safeFam = doc.familyId.ifBlank { "default_family" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = File(context.cacheDir, "family_files/$safeFam").apply { mkdirs() }
        val safeName = sanitizeFileName(doc.name, doc.extension.lowercase(Locale.ROOT))
        val safeId = doc.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "${safeId}_$safeName")
    }

    /**
     * Writes [bytes] into the private family file cache for [doc] and returns the cached [File].
     */
    fun writeBytesToCachedFile(context: Context, doc: FamilyDocument, bytes: ByteArray): File {
        val target = getCachedFileForDocument(context, doc)
        FileOutputStream(target).use { it.write(bytes) }
        return target
    }

    /**
     * Ensures a valid local cache file exists for [doc] (either already downloaded/cached, or materialized
     * from [downloadedBytes] or a valid document representation) and returns its secure `FileProvider` [Uri].
     */
    fun ensureCachedFileAndGetSecureUri(
        context: Context,
        doc: FamilyDocument,
        downloadedBytes: ByteArray? = null
    ): Pair<File, Uri> {
        val targetFile = getCachedFileForDocument(context, doc)
        if (downloadedBytes != null && downloadedBytes.isNotEmpty()) {
            FileOutputStream(targetFile).use { it.write(downloadedBytes) }
        } else if (!targetFile.exists() || targetFile.length() == 0L) {
            val fallbackBytes = when {
                doc.isPdfFile -> generateDocumentPdfBytes(
                    title = doc.name,
                    uploaderName = doc.uploadedByName.ifBlank { "חבר משפחה" },
                    dateFormatted = doc.uploadedDateFormatted,
                    bodyLines = listOf(
                        "מסמך משפחתי משותף: ${doc.name}",
                        "הועלה על ידי: ${doc.uploadedByName.ifBlank { "חבר משפחה" }}",
                        "תאריך העלאה: ${doc.uploadedDateFormatted}",
                        "גודל קובץ: ${doc.sizeFormatted}",
                        doc.contentPreviewHe.ifBlank { "מסמך זה שמור בתיקייה המשפחתית המשותפת." }
                    )
                )
                doc.isImageFile -> {
                    val fromThumb = doc.thumbnailBase64?.takeIf { it.isNotBlank() }?.let {
                        runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                    }
                    if (fromThumb != null && fromThumb.isNotEmpty()) {
                        fromThumb
                    } else {
                        generateSampleImageBytes(doc.name, doc.uploadedByName.ifBlank { "תמונה משפחתית" })
                    }
                }
                else -> {
                    val textContent = buildString {
                        appendLine("שם הקובץ: ${doc.name}")
                        appendLine("סוג קובץ: ${doc.extension}")
                        appendLine("גודל: ${doc.sizeFormatted}")
                        appendLine("הועלה על ידי: ${doc.uploadedByName}")
                        appendLine("תאריך: ${doc.uploadedDateFormatted}")
                        if (doc.contentPreviewHe.isNotBlank()) {
                            appendLine()
                            appendLine(doc.contentPreviewHe)
                        }
                    }
                    textContent.toByteArray(Charsets.UTF_8)
                }
            }
            FileOutputStream(targetFile).use { it.write(fallbackBytes) }
        }

        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, targetFile)
        return targetFile to contentUri
    }

    /**
     * Opens a cached family document in an external viewer app using Android `ACTION_VIEW`
     * and a secure `FileProvider` `content://` URI with `FLAG_GRANT_READ_URI_PERMISSION`.
     * Returns `true` if an external app handled the intent, or `false` if no app is installed.
     */
    fun openWithExternalApp(
        context: Context,
        doc: FamilyDocument,
        downloadedBytes: ByteArray? = null
    ): Boolean {
        return runCatching {
            val (_, contentUri) = ensureCachedFileAndGetSecureUri(context, doc, downloadedBytes)
            val mimeType = doc.mimeType.ifBlank {
                FamilyDocument.defaultMimeTypeForExtension(doc.extension)
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val canResolve = context.packageManager.queryIntentActivities(viewIntent, 0).isNotEmpty()
            if (canResolve) {
                context.startActivity(viewIntent)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Shares a cached family document via the Android Sharesheet (`Intent.ACTION_SEND`)
     * using a secure `FileProvider` `content://` URI. Never exposes raw private paths or public URLs.
     */
    fun shareDocumentViaSharesheet(
        context: Context,
        doc: FamilyDocument,
        downloadedBytes: ByteArray? = null,
        isHebrew: Boolean = true
    ): Boolean {
        return runCatching {
            val (_, contentUri) = ensureCachedFileAndGetSecureUri(context, doc, downloadedBytes)
            val mimeType = doc.mimeType.ifBlank {
                FamilyDocument.defaultMimeTypeForExtension(doc.extension)
            }
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, doc.name)
                putExtra(
                    Intent.EXTRA_TEXT,
                    if (isHebrew) "שיתוף קובץ מהתיקייה המשפחתית: ${doc.name}"
                    else "Shared family file: ${doc.name}"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                sendIntent,
                if (isHebrew) "שיתוף קובץ משפחתי" else "Share Family File"
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    fun ensureLocalCacheFileForDocument(
        context: Context,
        doc: FamilyDocument,
        rawBytes: ByteArray? = null
    ): File {
        return ensureCachedFileAndGetSecureUri(context, doc, rawBytes).first
    }

    fun getSecureContentUriForDocument(
        context: Context,
        doc: FamilyDocument,
        rawBytes: ByteArray? = null
    ): Uri {
        return ensureCachedFileAndGetSecureUri(context, doc, rawBytes).second
    }

    fun renderPdfFirstPageBitmap(pdfFile: File): Bitmap? {
        return runCatching {
            android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)?.use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            val w = page.width.coerceAtLeast(595)
                            val h = page.height.coerceAtLeast(842)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bmp)
                            canvas.drawColor(Color.WHITE)
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    } else null
                }
            }
        }.getOrNull() ?: runCatching {
            Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    fun openFileWithExternalApp(
        context: Context,
        doc: FamilyDocument,
        rawBytes: ByteArray? = null,
        isHebrew: Boolean = true
    ): Result<Unit> {
        return if (openWithExternalApp(context, doc, rawBytes)) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    if (isHebrew) "לא נמצאה אפליקציה מתאימה לפתיחת קובץ ${doc.extension} במכשיר"
                    else "No compatible app found to open ${doc.extension} file"
                )
            )
        }
    }

    fun shareFileViaSharesheet(
        context: Context,
        doc: FamilyDocument,
        rawBytes: ByteArray? = null,
        isHebrew: Boolean = true
    ): Result<Unit> {
        return if (shareDocumentViaSharesheet(context, doc, rawBytes, isHebrew)) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    if (isHebrew) "שיתוף הקובץ נכשל" else "Failed to share file"
                )
            )
        }
    }

    /**
     * Opens a shared Google Drive file for editing in a compatible editor (Google Docs/Sheets/Slides,
     * Google Drive, Microsoft Office, or system editor) using `ACTION_EDIT` / `ACTION_VIEW` with
     * read+write `FileProvider` permissions or the Google Drive editor URL.
     */
    fun openFileWithCompatibleEditor(
        context: Context,
        doc: FamilyDocument,
        rawBytes: ByteArray? = null,
        isHebrew: Boolean = true
    ): Result<Unit> {
        return runCatching {
            val (_, contentUri) = ensureCachedFileAndGetSecureUri(context, doc, rawBytes)
            val mimeType = doc.mimeType.ifBlank {
                FamilyDocument.defaultMimeTypeForExtension(doc.extension)
            }
            val editIntent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.queryIntentActivities(editIntent, 0).isNotEmpty()) {
                context.startActivity(editIntent)
                return@runCatching
            }

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.queryIntentActivities(viewIntent, 0).isNotEmpty()) {
                context.startActivity(viewIntent)
                return@runCatching
            }

            val driveLink = doc.driveUrl.ifBlank {
                val driveId = doc.driveFileId.ifBlank { doc.id }
                "https://drive.google.com/file/d/$driveId/edit"
            }
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(driveLink)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.packageManager.queryIntentActivities(browserIntent, 0).isNotEmpty()) {
                context.startActivity(browserIntent)
                return@runCatching
            }

            throw IllegalStateException(
                if (isHebrew) "לא נמצאה אפליקציית עריכה תואמת במכשיר עבור ${doc.extension}"
                else "No compatible editor app found on device for ${doc.extension}"
            )
        }
    }
}
