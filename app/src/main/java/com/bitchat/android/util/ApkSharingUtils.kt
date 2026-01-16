package com.bitchat.android.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Utility object for sharing the installed APK with other users.
 * Supports both single APK installations (direct downloads) and split APK installations (Play Store AAB).
 */
object ApkSharingUtils {
    private const val TAG = "ApkSharingUtils"
    private const val APK_SHARE_DIR = "apk_share"

    /**
     * Detects if the app is installed as split APKs (from AAB) or single APK.
     *
     * @param context Application context
     * @return Pair<Boolean, List<File>> where:
     *         - First: true if split APKs, false if single APK
     *         - Second: List of APK files to share
     */
    fun detectAndCollectApks(context: Context): Pair<Boolean, List<File>> {
        val applicationInfo = context.applicationInfo
        val sourceDir = applicationInfo.sourceDir // Base APK path
        val splitSourceDirs = applicationInfo.splitSourceDirs // Split APKs (null if single)

        return if (splitSourceDirs.isNullOrEmpty()) {
            // Single APK installation (direct download/sideload)
            Log.d(TAG, "Detected single APK installation: $sourceDir")
            Pair(false, listOf(File(sourceDir)))
        } else {
            // Split APK installation (Play Store AAB)
            val apkFiles = mutableListOf(File(sourceDir)) // Base APK
            splitSourceDirs.forEach { splitPath ->
                apkFiles.add(File(splitPath))
            }
            Log.d(TAG, "Detected split APK installation with ${apkFiles.size} files")
            Pair(true, apkFiles)
        }
    }

    /**
     * Prepares APKs for sharing by copying them to the cache directory with friendly names.
     * This is necessary because APKs in /data/app/ are not directly accessible for sharing.
     *
     * @param context Application context
     * @return List<File> of cached APK files ready for sharing, or null on error
     */
    fun prepareApksForSharing(context: Context): List<File>? {
        return try {
            val (isSplit, apkFiles) = detectAndCollectApks(context)

            // Create cache subdirectory for APKs
            val cacheDir = File(context.cacheDir, APK_SHARE_DIR).apply {
                if (exists()) {
                    deleteRecursively() // Clean old files
                    Log.d(TAG, "Cleaned existing APK share cache")
                }
                mkdirs()
            }

            val cachedFiles = mutableListOf<File>()

            apkFiles.forEachIndexed { index, sourceFile ->
                if (!sourceFile.exists()) {
                    Log.e(TAG, "Source APK not found: ${sourceFile.path}")
                    return null
                }

                // Generate friendly names
                val fileName = when {
                    isSplit && index == 0 -> "bitchat-base.apk"
                    isSplit -> {
                        // Extract split name (e.g., config.arm64_v8a, config.xxhdpi)
                        val splitName = sourceFile.name
                            .replace("split_config.", "")
                            .replace("split_", "")
                            .replace(".apk", "")
                        "bitchat-$splitName.apk"
                    }
                    else -> "bitchat.apk"
                }

                val destFile = File(cacheDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                cachedFiles.add(destFile)
                Log.d(TAG, "Copied ${sourceFile.name} -> ${destFile.name} (${destFile.length()} bytes)")
            }

            Log.d(TAG, "Prepared ${cachedFiles.size} APK(s) for sharing, total size: ${cachedFiles.sumOf { it.length() }} bytes")

            // TEMPORARY DEBUG: Also copy to Downloads for testing
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                cachedFiles.forEach { cachedFile ->
                    val testFile = File(downloadsDir, "debug-${cachedFile.name}")
                    cachedFile.copyTo(testFile, overwrite = true)
                    Log.d(TAG, "DEBUG: Copied to Downloads for testing: ${testFile.absolutePath} (${testFile.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "DEBUG: Could not copy to Downloads (this is OK)", e)
            }

            cachedFiles

        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare APKs for sharing", e)
            null
        }
    }

    /**
     * Cleans up the cached APKs after sharing.
     * This prevents accumulation of APK copies in the cache directory.
     *
     * @param context Application context
     */
    fun cleanupSharedApks(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, APK_SHARE_DIR)
            if (cacheDir.exists()) {
                val deletedFiles = cacheDir.listFiles()?.size ?: 0
                cacheDir.deleteRecursively()
                Log.d(TAG, "Cleaned up $deletedFiles shared APK file(s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup shared APKs", e)
        }
    }
}
