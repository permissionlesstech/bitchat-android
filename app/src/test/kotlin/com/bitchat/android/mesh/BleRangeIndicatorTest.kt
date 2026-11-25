package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class BleRangeIndicatorTest {

    private val coded = BleRadioCapabilities(
        leCodedPhy = true,
        le2MPhy = true,
        leExtendedAdvertising = true,
        lePeriodicAdvertising = false,
        multipleAdvertisement = true,
        maxAdvertisingDataLength = 251
    )

    private fun snapshot(
        caps: BleRadioCapabilities = coded,
        known: Boolean = true,
        mode: BleLongRangeMode = BleLongRangeMode.MAX_RANGE,
        codedAdvertiser: BleLinkDiagnostics.AdvertiserState = BleLinkDiagnostics.AdvertiserState.ACTIVE,
        extendedScanRejected: Boolean = false,
        links: List<BleLinkDiagnostics.Link> = emptyList()
    ) = BleLinkDiagnostics.Snapshot(
        capabilities = caps,
        capabilitiesKnown = known,
        mode = mode,
        coding = BleCodedCoding.S8,
        legacyAdvertiser = BleLinkDiagnostics.AdvertiserState.ACTIVE,
        legacyAdvertiserError = null,
        codedAdvertiser = codedAdvertiser,
        codedAdvertiserError = null,
        codedAdvertiserTxPowerDbm = null,
        codedAdvertiserIntervalMs = null,
        scanMode = BleLinkDiagnostics.ScanMode.EXTENDED_ALL_PHYS,
        extendedScanRejected = extendedScanRejected,
        scanResultsLegacy = 0,
        scanResultsCoded = 0,
        scanResultsOther = 0,
        lastCodedRssi = null,
        lastCodedAt = null,
        links = links,
        events = emptyList()
    )

    private fun link(id: String, phy: Int) = BleLinkDiagnostics.Link(
        linkID = id,
        address = "ADDR-$id",
        isClient = true,
        connectedAt = 0L,
        txPhy = phy,
        rxPhy = phy
    )

    @Test
    fun `capable phone in max range reports long range with long estimate`() {
        val indicator = BleRangeIndicator.from(snapshot())

        assertEquals(BleRangeIndicator.Kind.LONG_RANGE, indicator.kind)
        assertEquals(BleRangeIndicator.LONG_RANGE_M, indicator.estimatedRangeMeters)
        assertEquals(BleRangeIndicator.Links.NONE, indicator.links)
    }

    @Test
    fun `off mode and unsupported hardware report standard range`() {
        assertEquals(
            BleRangeIndicator.Kind.STANDARD_OFF,
            BleRangeIndicator.from(snapshot(mode = BleLongRangeMode.OFF)).kind
        )
        val unsupported = BleRangeIndicator.from(snapshot(caps = BleRadioCapabilities.NONE))
        assertEquals(BleRangeIndicator.Kind.STANDARD_UNSUPPORTED, unsupported.kind)
        assertEquals(BleRangeIndicator.STANDARD_RANGE_M, unsupported.estimatedRangeMeters)
    }

    @Test
    fun `runtime rejection of both coded advertising and extended scan reports unsupported`() {
        val indicator = BleRangeIndicator.from(
            snapshot(
                codedAdvertiser = BleLinkDiagnostics.AdvertiserState.UNSUPPORTED,
                extendedScanRejected = true
            )
        )
        assertEquals(BleRangeIndicator.Kind.STANDARD_UNSUPPORTED, indicator.kind)
    }

    @Test
    fun `undetected capabilities make no claim`() {
        assertEquals(BleRangeIndicator.Kind.UNKNOWN, BleRangeIndicator.from(snapshot(known = false)).kind)
    }

    @Test
    fun `link phy summary distinguishes all, some and no coded links`() {
        val coded = BluetoothDevice.PHY_LE_CODED
        val oneM = BluetoothDevice.PHY_LE_1M
        fun summary(vararg links: BleLinkDiagnostics.Link) =
            BleRangeIndicator.from(snapshot(links = links.toList())).links

        assertEquals(BleRangeIndicator.Links.ALL_CODED, summary(link("a", coded), link("b", coded)))
        assertEquals(BleRangeIndicator.Links.SOME_CODED, summary(link("a", coded), link("b", oneM)))
        assertEquals(BleRangeIndicator.Links.STANDARD, summary(link("a", oneM)))
        // Links whose PHY hasn't been read yet don't count either way.
        assertEquals(BleRangeIndicator.Links.ALL_CODED, summary(link("a", coded), link("b", 0)))
        assertEquals(BleRangeIndicator.Links.NONE, summary(link("a", 0)))
    }

    @Test
    fun `coded link in off mode still reports the long range estimate`() {
        val indicator = BleRangeIndicator.from(
            snapshot(mode = BleLongRangeMode.OFF, links = listOf(link("a", BluetoothDevice.PHY_LE_CODED)))
        )
        assertEquals(BleRangeIndicator.Kind.STANDARD_OFF, indicator.kind)
        assertEquals(BleRangeIndicator.LONG_RANGE_M, indicator.estimatedRangeMeters)
    }
}
