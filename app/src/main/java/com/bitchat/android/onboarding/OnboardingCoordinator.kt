package com.bitchat.android.onboarding

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Owns the two runtime-permission launchers and the app-settings shortcut.
 * What to request and what a result means is [OnboardingFlow]'s.
 */
class OnboardingCoordinator(
    private val activity: ComponentActivity,
    private val onPermissionResults: (Map<String, Boolean>) -> Unit,
    private val onBackgroundLocationResult: (Boolean) -> Unit
) {

    companion object {
        private const val TAG = "OnboardingCoordinator"
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var backgroundLocationLauncher: ActivityResultLauncher<String>? = null

    init {
        setupPermissionLauncher()
        setupBackgroundLocationLauncher()
    }

    /**
     * Setup the permission request launcher
     */
    private fun setupPermissionLauncher() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            onPermissionResults(permissions)
        }
    }

    private fun setupBackgroundLocationLauncher() {
        backgroundLocationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            onBackgroundLocationResult(granted)
        }
    }

    fun launchPermissions(permissions: List<String>) {
        permissionLauncher?.launch(permissions.toTypedArray())
    }

    fun launchBackgroundLocation(permission: String) {
        backgroundLocationLauncher?.launch(permission)
    }

    /**
     * Open app settings for manual permission management
     */
    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            Log.d(TAG, "Opened app settings for manual permission management")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }
}
