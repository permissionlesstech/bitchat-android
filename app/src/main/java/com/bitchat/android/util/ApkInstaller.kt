package com.bitchat.android.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Utility for installing APK files (single or split) using PackageInstaller API.
 * This enables BitChat to be self-distributing in offline mesh network scenarios.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"
    const val ACTION_INSTALL_COMPLETE = "com.bitchat.android.INSTALL_COMPLETE"

    /**
     * Install APK files using PackageInstaller API.
     * Handles both single APK and split APKs (from AAB).
     *
     * @param context Application context
     * @param apkFiles List of APK files to install (can be single file or multiple splits)
     * @return true if installation session was created successfully, false otherwise
     */
    fun installApks(context: Context, apkFiles: List<File>): Boolean {
        return try {
            Log.d(TAG, "Starting installation of ${apkFiles.size} APK file(s)")

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            // Create installation session
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            try {
                // Write each APK file to the session
                apkFiles.forEachIndexed { index, apkFile ->
                    if (!apkFile.exists()) {
                        Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
                        session.abandon()
                        return false
                    }

                    val name = if (apkFiles.size == 1) {
                        "base.apk"
                    } else {
                        "split_$index.apk"
                    }

                    session.openWrite(name, 0, apkFile.length()).use { output ->
                        apkFile.inputStream().use { input ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    Log.d(TAG, "Wrote ${apkFile.name} to session (${apkFile.length()} bytes)")
                }

                // Create pending intent for installation result
                val intent = Intent(ACTION_INSTALL_COMPLETE).apply {
                    setPackage(context.packageName)
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    flags
                )

                // Commit the session - this will show the system install dialog
                session.commit(pendingIntent.intentSender)
                Log.d(TAG, "Installation session committed (ID: $sessionId)")

                true
            } catch (e: Exception) {
                Log.e(TAG, "Error writing APKs to session", e)
                session.abandon()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating installation session", e)
            false
        }
    }

    /**
     * Install a single APK file.
     *
     * @param context Application context
     * @param apkFile APK file to install
     * @return true if installation session was created successfully, false otherwise
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return installApks(context, listOf(apkFile))
    }

    /**
     * Install APK from URI (e.g., content:// URI from FileProvider).
     * Copies the URI to a temporary file first, then installs.
     *
     * @param context Application context
     * @param apkUri URI pointing to the APK file
     * @return true if installation started successfully, false otherwise
     */
    fun installApkFromUri(context: Context, apkUri: Uri): Boolean {
        return try {
            // Copy URI to temporary file
            val tempFile = File(context.cacheDir, "temp_install.apk")
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.e(TAG, "Failed to copy APK from URI to temp file")
                return false
            }

            Log.d(TAG, "Copied APK from URI to temp file (${tempFile.length()} bytes)")
            installApk(context, tempFile)
        } catch (e: IOException) {
            Log.e(TAG, "Error installing APK from URI", e)
            false
        }
    }

    /**
     * Check if the app has permission to install packages.
     * On Android 8.0+, user must grant "Install unknown apps" permission.
     *
     * @param context Application context
     * @return true if permission is granted, false otherwise
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // No permission needed on older Android versions
        }
    }

    /**
     * Open system settings to allow installing from this app.
     *
     * @param context Application context
     */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
