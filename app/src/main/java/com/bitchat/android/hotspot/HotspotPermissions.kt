package com.bitchat.android.hotspot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object HotspotPermissions {
    const val ANDROID_17_API_LEVEL = 37

    @SuppressLint("InlinedApi")
    fun requiredForSdk(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        return when {
            sdkInt >= ANDROID_17_API_LEVEL -> listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
            sdkInt >= Build.VERSION_CODES.Q -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            else -> emptyList()
        }
    }

    fun missingFrom(context: Context): List<String> {
        return requiredForSdk().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}
