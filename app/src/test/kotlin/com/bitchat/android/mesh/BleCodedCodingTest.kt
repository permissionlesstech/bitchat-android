package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class BleCodedCodingTest {

    @Test
    fun `coding preference maps to the matching setPreferredPhy option`() {
        assertEquals(BluetoothDevice.PHY_OPTION_S8, BleLongRangePolicy.phyOption(BleCodedCoding.S8))
        assertEquals(BluetoothDevice.PHY_OPTION_S2, BleLongRangePolicy.phyOption(BleCodedCoding.S2))
        assertEquals(BluetoothDevice.PHY_OPTION_NO_PREFERRED, BleLongRangePolicy.phyOption(BleCodedCoding.NO_PREFERENCE))
    }

    @Test
    fun `default coding is S8 and unknown persisted values fall back to it`() {
        assertEquals(BleCodedCoding.S8, BleCodedCoding.DEFAULT)
        assertEquals(BleCodedCoding.S8, BleCodedCoding.fromName("bogus"))
        assertEquals(BleCodedCoding.S2, BleCodedCoding.fromName("S2"))
    }

    @Test
    fun `status codes are named and unknown ones shown in hex`() {
        assertEquals("success", BleLongRangePolicy.statusName(0))
        assertEquals("remote lacks feature", BleLongRangePolicy.statusName(0x1A))
        assertEquals("0x7F", BleLongRangePolicy.statusName(0x7F))
    }
}
