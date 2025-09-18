package com.bitchat.android.features.file

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * Save a file from URI to app's file directory with unique filename
     */
    fun saveFileFromUri(
        context: Context,
        uri: Uri,
        originalName: String? = null
    ): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "❌ Failed to open input stream for URI: $uri")
                return null
            }
            Log.d(TAG, "📂 Opened input stream successfully")

            // Determine file extension
            val extension = originalName?.substringAfterLast(".") ?: "bin"
            val fileName = "file_${System.currentTimeMillis()}.$extension"

            // Create incoming dir if needed
            val incomingDir = File(context.filesDir, "files/incoming").apply {
                if (!exists()) mkdirs()
            }

            val file = File(incomingDir, fileName)

            inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Saved file to: ${file.absolutePath}")
            file.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file from URI", e)
            null
        }
    }

    /**
     * Copy file to app's outgoing directory for sending
     */
    fun copyFileForSending(context: Context, uri: Uri, originalName: String? = null): String? {
        Log.d(TAG, "🔄 Starting file copy from URI: $uri")
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "❌ Failed to open input stream for URI: $uri")
                return null
            }
            Log.d(TAG, "📂 Opened input stream successfully")

            // Determine original filename and extension if available
            val displayName = originalName ?: run {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                    }
                } catch (_: Exception) { null }
            }
            val extension = displayName?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
                ?: run {
                    // Try mime type to extension
                    val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
                    android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                }
            // Preserve original filename (without artificial prefixes), ensure uniqueness
            val baseName = displayName?.substringBeforeLast('.')?.take(64)?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?: "file"
            var fileName = if (extension.isNotBlank()) "$baseName.$extension" else baseName

            // Create outgoing dir if needed
            val outgoingDir = File(context.filesDir, "files/outgoing").apply {
                if (!exists()) mkdirs()
            }

            var target = File(outgoingDir, fileName)
            if (target.exists()) {
                var idx = 1
                val pureBase = baseName
                val dotExt = if (extension.isNotBlank()) ".${extension}" else ""
                while (target.exists() && idx < 1000) {
                    fileName = "$pureBase ($idx)$dotExt"
                    target = File(outgoingDir, fileName)
                    idx++
                }
            }

            inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "✅ Successfully copied file for sending: ${target.absolutePath}")
            Log.d(TAG, "📊 Final file size: ${target.length()} bytes")
            target.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Failed to copy file for sending", e)
            Log.e(TAG, "❌ Source URI: $uri")
            Log.e(TAG, "❌ Original name: $originalName")
            Log.e(TAG, "❌ Error type: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Get MIME type for a file based on extension
     */
    fun getMimeTypeFromExtension(fileName: String): String {
        return when (fileName.substringAfterLast(".", "").lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            else -> "application/octet-stream"
        }
    }

    /**
     * Format file size for display
     */
    fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024.0
            unitIndex++
        }
        return "%.1f %s".format(size, units[unitIndex])
    }

    /**
     * Check if file is viewable in system viewer
     */
    fun isFileViewable(fileName: String): Boolean {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return extension in listOf(
            "pdf", "txt", "json", "xml", "html", "htm", "csv",
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg"
        )
    }

    /**
     * Save an incoming file packet to app storage and return absolute path.
     * Mirrors existing behavior used in MessageHandler (preserves names and folders).
     */
    fun saveIncomingFile(
        context: Context,
        file: com.bitchat.android.model.BitchatFilePacket
    ): String {
        val lowerMime = file.mimeType.lowercase()
        val isImage = lowerMime.startsWith("image/")
        val baseDir = context.filesDir
        val subdir = if (isImage) "images/incoming" else "files/incoming"
        val dir = java.io.File(baseDir, subdir).apply { mkdirs() }

        fun extFromMime(m: String): String = when (m.lowercase()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "application/pdf" -> ".pdf"
            "text/plain" -> ".txt"
            else -> if (isImage) ".jpg" else ".bin"
        }

        // Prefer transmitted original name; ensure uniqueness to avoid overwrites
        val baseName = (file.fileName.takeIf { it.isNotBlank() }
            ?: (if (isImage) "img" else "file"))
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ext = extFromMime(lowerMime)
        var safeName = if (baseName.contains('.')) baseName else baseName + ext
        var idx = 1
        while (java.io.File(dir, safeName).exists() && idx < 1000) {
            val dot = safeName.lastIndexOf('.')
            safeName = if (dot > 0) {
                val b = safeName.substring(0, dot)
                val e = safeName.substring(dot)
                "$b ($idx)$e"
            } else {
                "$safeName ($idx)"
            }
            idx++
        }

        return try {
            val out = java.io.File(dir, safeName)
            out.outputStream().use { it.write(file.content) }
            out.absolutePath
        } catch (_: Exception) {
            // Fallback to cache dir with uniqueness
            try {
                var fallback = safeName
                var idx2 = 1
                while (java.io.File(context.cacheDir, fallback).exists() && idx2 < 1000) {
                    val dot = fallback.lastIndexOf('.')
                    fallback = if (dot > 0) {
                        val b = fallback.substring(0, dot)
                        val e = fallback.substring(dot)
                        "$b ($idx2)$e"
                    } else {
                        "$fallback ($idx2)"
                    }
                    idx2++
                }
                val out = java.io.File(context.cacheDir, fallback)
                out.outputStream().use { it.write(file.content) }
                out.absolutePath
            } catch (_: Exception) {
                val tmp = java.io.File.createTempFile(if (isImage) "img_" else "file_", if (isImage) ".jpg" else ".bin")
                tmp.writeBytes(file.content)
                tmp.absolutePath
            }
        }
    }

    /**
     * Classify BitchatMessageType from MIME string used in file messages.
     */
    fun messageTypeForMime(mime: String): com.bitchat.android.model.BitchatMessageType {
        val lower = mime.lowercase()
        return when {
            lower.startsWith("image/") -> com.bitchat.android.model.BitchatMessageType.Image
            lower.startsWith("audio/") -> com.bitchat.android.model.BitchatMessageType.Audio
            else -> com.bitchat.android.model.BitchatMessageType.File
        }
    }
}
