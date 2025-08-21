package com.bitchat.android.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the mesh foreground service on device boot if enabled and permissions are satisfied.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
        val persistentEnabled = prefs.getBoolean("persistent_mesh_enabled", false)
        val startOnBoot = prefs.getBoolean("start_on_boot_enabled", false)

        if (!persistentEnabled || !startOnBoot) {
            return
        }

        if (!hasRequiredPermissions(context)) {
            Log.w("BootCompletedReceiver", "Missing permissions; not starting mesh on boot")
            return
        }

        PersistentMeshService.start(context)
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val required = listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        return required.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}

