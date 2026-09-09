package com.bitchat.android.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

/**
 * Wear variant of the phone's BluetoothPermissionManager.
 *
 * The phone version additionally requires ACCESS_FINE/COARSE_LOCATION (legacy BLE scanning
 * behavior on older phones). The watch app deliberately declares no location permissions —
 * on Wear OS, BLUETOOTH_SCAN with the `neverForLocation` flag is sufficient — so only the
 * Bluetooth runtime permissions are checked here.
 *
 * Same fully-qualified name as the phone class, which is excluded from the wear shared-source
 * sync (see wear/build.gradle.kts), so there is exactly one definition in this compilation.
 */
class BluetoothPermissionManager(private val context: Context) {

    fun hasBluetoothPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
                )
            )
        }

        return permissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
