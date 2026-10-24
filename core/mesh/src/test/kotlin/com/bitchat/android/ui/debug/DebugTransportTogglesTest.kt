package com.bitchat.android.ui.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugTransportTogglesTest {
    private val settings = DebugSettingsManager.getInstance()
    private val applied = mutableListOf<String>()

    private val recorder = object : TransportToggles {
        override fun setBleEnabled(enabled: Boolean) {
            applied += "ble=$enabled"
        }

        override fun setWifiAwareEnabled(enabled: Boolean) {
            applied += "wifiAware=$enabled"
        }
    }

    @After
    fun tearDown() {
        settings.transportToggles = null
        settings.setBleEnabled(true)
        settings.setWifiAwareEnabled(false)
    }

    @Test
    fun `turning a transport off applies it through the registered toggles`() {
        settings.transportToggles = recorder

        settings.setBleEnabled(false)
        settings.setWifiAwareEnabled(true)

        assertEquals(listOf("ble=false", "wifiAware=true"), applied)
    }

    @Test
    fun `without registered toggles the setting is still recorded`() {
        settings.setBleEnabled(false)

        assertFalse(settings.bleEnabled.value)
        assertEquals(emptyList<String>(), applied)
    }
}
