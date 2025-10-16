package com.bitchat.android.features.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

object MediaSizeLimiter {
    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024.0
            unit++
        }
        return "%.1f %s".format(size, units[unit])
    }

    private fun toastTooLarge(context: Context, label: String) {
        val maxLabel = formatBytes(com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES)
        Toast.makeText(context, "$label is too large to send (max $maxLabel)", Toast.LENGTH_SHORT).show()
    }

    fun queryContentLength(context: Context, uri: Uri): Long? {
        // Try OpenableColumns.SIZE first
        val sizeFromQuery = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else null
            }
        } catch (_: Exception) { null }

        if (sizeFromQuery != null && sizeFromQuery >= 0) return sizeFromQuery

        // Fallback to file descriptor statSize
        val sizeFromFd = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        } catch (_: Exception) { null }

        return sizeFromFd?.takeIf { it >= 0 }
    }

    // Returns false if too large and shows a toast
    fun enforceUriPrecheck(context: Context, uri: Uri, label: String): Boolean {
        val len = queryContentLength(context, uri)
        if (len != null && len > com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES) {
            toastTooLarge(context, label)
            return false
        }
        return true
    }

    // Returns false if too large. Optionally deletes the file if too large.
    fun enforcePathPostCheck(context: Context, path: String, label: String, deleteIfTooLarge: Boolean = true): Boolean {
        return try {
            val file = File(path)
            val len = file.length()
            if (len > com.bitchat.android.util.AppConstants.Media.MAX_FILE_SIZE_BYTES) {
                if (deleteIfTooLarge) runCatching { file.delete() }
                toastTooLarge(context, label)
                false
            } else true
        } catch (_: Exception) { true }
    }
}

