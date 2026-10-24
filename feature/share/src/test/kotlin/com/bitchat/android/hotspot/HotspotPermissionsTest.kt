package com.bitchat.android.hotspot

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotPermissionsTest {

    @Test
    fun `Android 17 requires nearby Wi-Fi and local network permissions`() {
        assertEquals(
            listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK
            ),
            HotspotPermissions.requiredForSdk(37)
        )
    }

    @Test
    fun `Android 13 through 16 require nearby Wi-Fi permission`() {
        val expected = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)

        assertEquals(expected, HotspotPermissions.requiredForSdk(33))
        assertEquals(expected, HotspotPermissions.requiredForSdk(36))
    }

    @Test
    fun `Android 10 through 12 require fine location permission`() {
        val expected = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        assertEquals(expected, HotspotPermissions.requiredForSdk(29))
        assertEquals(expected, HotspotPermissions.requiredForSdk(32))
    }

    @Test
    fun `Android 9 and earlier require no hotspot runtime permission`() {
        assertEquals(emptyList<String>(), HotspotPermissions.requiredForSdk(28))
    }
}
