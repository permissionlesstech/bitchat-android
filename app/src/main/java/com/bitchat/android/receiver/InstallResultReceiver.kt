package com.bitchat.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.bitchat.android.R
import com.bitchat.android.util.ApkInstaller

/**
 * Receives installation results from PackageInstaller.
 * Shows toast messages to inform user of success/failure.
 */
class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "InstallResultReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_INSTALL_COMPLETE) {
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System is asking for user confirmation
                Log.d(TAG, "Installation pending user action")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start confirmation intent", e)
                        Toast.makeText(
                            context,
                            "Installation failed: Could not show confirmation dialog",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Installation succeeded")
                Toast.makeText(
                    context,
                    "BitChat installed successfully!",
                    Toast.LENGTH_LONG
                ).show()
            }

            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Installation failed with status $status: $message")
                val errorMsg = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by system policy"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Package conflicts with existing installation. Try uninstalling first."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Package is incompatible with this device"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Package is invalid or corrupted"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space"
                    else -> "Installation failed: ${message ?: "Unknown error"}"
                }
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
