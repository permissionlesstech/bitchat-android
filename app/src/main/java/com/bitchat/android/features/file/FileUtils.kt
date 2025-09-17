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
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

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
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

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

            Log.d(TAG, "Copied file for sending: ${target.absolutePath}")
            target.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file for sending", e)
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
}
