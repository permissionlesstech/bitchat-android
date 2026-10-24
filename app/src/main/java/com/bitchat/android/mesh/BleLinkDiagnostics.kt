package com.bitchat.android.mesh

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide BLE radio diagnostics: capabilities, advertiser/scanner state, per-link PHY and
 * RSSI, and link-level delivery counters. Writers are radio callbacks; readers poll [snapshot],
 * so high-rate scan callbacks never emit UI updates.
 */
object BleLinkDiagnostics {
    private const val TAG = "BleLongRange"
    private const val MAX_EVENTS = 60

    enum class AdvertiserState { STOPPED, STARTING, ACTIVE, FAILED, UNSUPPORTED, DISABLED }
    enum class ScanMode { STOPPED, LEGACY_1M, EXTENDED_ALL_PHYS }

    data class Link(
        val linkID: String,
        val address: String,
        val isClient: Boolean,
        val connectedAt: Long,
        /** Mesh peer observed on this link (validated announce); one device can hold several links. */
        val peerID: String? = null,
        val discoveredPhy: Int? = null,
        val txPhy: Int = 0,
        val rxPhy: Int = 0,
        val rssi: Int? = null,
        /** Where [rssi] comes from: "link" (controller read) or "outgoing link" (incoming links). */
        val rssiSource: String? = null,
        /** Adaptive controller view: smoothed RSSI and a pending switch, if any. */
        val smoothedRssi: Int? = null,
        val adaptiveNote: String? = null,
        val state: String = "connecting",
        val phyRequests: Int = 0,
        /** Last PHY this side asked for, e.g. "Coded S=8" or "1M"; null if none was requested. */
        val lastPhyRequest: String? = null,
        val lastPhyStatus: Int? = null,
        val sendOk: Long = 0,
        val sendFailed: Long = 0,
        val received: Long = 0
    )

    data class Event(val timestamp: Long, val text: String)

    data class Snapshot(
        val capabilities: BleRadioCapabilities,
        val capabilitiesKnown: Boolean,
        val mode: BleLongRangeMode,
        val coding: BleCodedCoding,
        val legacyAdvertiser: AdvertiserState,
        val legacyAdvertiserError: Int?,
        val codedAdvertiser: AdvertiserState,
        val codedAdvertiserError: Int?,
        val codedAdvertiserTxPowerDbm: Int?,
        val codedAdvertiserIntervalMs: Int?,
        val scanMode: ScanMode,
        val extendedScanRejected: Boolean,
        val scanResultsLegacy: Long,
        val scanResultsCoded: Long,
        val scanResultsOther: Long,
        val lastCodedRssi: Int?,
        val lastCodedAt: Long?,
        val links: List<Link>,
        val events: List<Event>
    )

    @Volatile var capabilities: BleRadioCapabilities = BleRadioCapabilities.NONE
        private set
    @Volatile private var capabilitiesKnown = false
    @Volatile var mode: BleLongRangeMode = BleLongRangeMode.DEFAULT
        private set
    @Volatile var coding: BleCodedCoding = BleCodedCoding.DEFAULT
        private set
    @Volatile private var codedIntervalMs: Int? = null
    @Volatile private var legacyAdvertiser = AdvertiserState.STOPPED
    @Volatile private var legacyAdvertiserError: Int? = null
    @Volatile private var codedAdvertiser = AdvertiserState.STOPPED
    @Volatile private var codedAdvertiserError: Int? = null
    @Volatile private var codedTxPower: Int? = null
    @Volatile private var scanMode = ScanMode.STOPPED
    @Volatile private var lastActiveScanMode = ScanMode.STOPPED
    @Volatile private var extendedScanRejected = false
    private val legacyResults = AtomicLong()
    private val codedResults = AtomicLong()
    private val otherResults = AtomicLong()
    @Volatile private var lastCodedRssi: Int? = null
    @Volatile private var lastCodedAt: Long? = null
    private val links = ConcurrentHashMap<String, Link>()
    private val events = ArrayDeque<Event>()

    fun setCapabilities(caps: BleRadioCapabilities) {
        val firstDetection = !capabilitiesKnown
        capabilitiesKnown = true
        if (caps == capabilities && !firstDetection) return
        capabilities = caps
        event(
            "Controller: coded=${caps.leCodedPhy} 2M=${caps.le2MPhy} extAdv=${caps.leExtendedAdvertising} " +
                "periodicAdv=${caps.lePeriodicAdvertising} multiAdv=${caps.multipleAdvertisement} " +
                "maxAdvData=${caps.maxAdvertisingDataLength}"
        )
    }

    fun setMode(next: BleLongRangeMode) {
        if (next == mode) return
        mode = next
        event("Long-range mode: $next")
    }

    fun setCoding(next: BleCodedCoding) {
        if (next == coding) return
        coding = next
        event("Coded coding preference: ${next.label}")
    }

    /** [interval] in AdvertisingSetParameters units of 0.625 ms. */
    fun onCodedAdvertisingInterval(interval: Int) {
        codedIntervalMs = interval * 5 / 8
    }

    fun onLegacyAdvertiser(state: AdvertiserState, error: Int? = null) {
        legacyAdvertiser = state
        legacyAdvertiserError = error
        if (state == AdvertiserState.FAILED) event("Legacy advertising failed: ${advertiseErrorName(error)}")
    }

    fun onCodedAdvertiser(state: AdvertiserState, error: Int? = null, txPowerDbm: Int? = null) {
        val changed = state != codedAdvertiser || error != codedAdvertiserError
        codedAdvertiser = state
        codedAdvertiserError = error
        if (txPowerDbm != null || state != AdvertiserState.ACTIVE) codedTxPower = txPowerDbm
        if (!changed) return
        when (state) {
            AdvertiserState.ACTIVE -> event("Coded advertising active (tx ${txPowerDbm ?: "?"} dBm)")
            AdvertiserState.FAILED -> event("Coded advertising failed: ${advertiseErrorName(error)}")
            AdvertiserState.UNSUPPORTED -> event("Coded advertising unsupported; legacy advertising only")
            else -> Unit
        }
    }

    fun onScanMode(next: ScanMode) {
        if (next != ScanMode.STOPPED && next != lastActiveScanMode) {
            lastActiveScanMode = next
            event("Scanning: ${if (next == ScanMode.EXTENDED_ALL_PHYS) "extended (1M + Coded)" else "legacy 1M"}")
        }
        scanMode = next
    }

    fun onExtendedScanRejected(errorCode: Int) {
        if (!extendedScanRejected) event("Extended scan rejected (error $errorCode); falling back to legacy scan")
        extendedScanRejected = true
    }

    fun isExtendedScanRejected(): Boolean = extendedScanRejected

    fun resetExtendedScanRejection() {
        extendedScanRejected = false
    }

    fun onScanResult(isLegacy: Boolean, primaryPhy: Int, rssi: Int) {
        when {
            isLegacy -> legacyResults.incrementAndGet()
            primaryPhy == android.bluetooth.BluetoothDevice.PHY_LE_CODED -> {
                codedResults.incrementAndGet()
                lastCodedRssi = rssi
                lastCodedAt = System.currentTimeMillis()
            }
            else -> otherResults.incrementAndGet()
        }
    }

    fun onLinkOpened(linkID: String, address: String, isClient: Boolean, discoveredPhy: Int?, rssi: Int?) {
        links[linkID] = Link(
            linkID = linkID,
            address = address,
            isClient = isClient,
            connectedAt = System.currentTimeMillis(),
            discoveredPhy = discoveredPhy,
            rssi = rssi
        )
    }

    fun onLinkState(linkID: String, state: String) = update(linkID) { it.copy(state = state) }

    fun onLinkPeer(linkID: String, peerID: String) = update(linkID) { it.copy(peerID = peerID) }

    fun onLinkRssi(linkID: String, rssi: Int, source: String = "link") =
        update(linkID) { it.copy(rssi = rssi, rssiSource = source) }

    fun onAdaptiveState(linkID: String, smoothedRssi: Int?, note: String?) =
        update(linkID) { it.copy(smoothedRssi = smoothedRssi, adaptiveNote = note) }

    fun onPhyRequested(linkID: String, target: String) {
        update(linkID) { it.copy(phyRequests = it.phyRequests + 1, lastPhyRequest = target) }
        links[linkID]?.let { Log.i(TAG, "PHY request $target on ${role(it)} link ${it.address}") }
    }

    fun onPhy(linkID: String, txPhy: Int, rxPhy: Int, status: Int, fromUpdate: Boolean) {
        val before = links[linkID]
        update(linkID) {
            if (status == 0) it.copy(txPhy = txPhy, rxPhy = rxPhy, lastPhyStatus = status)
            else it.copy(lastPhyStatus = status)
        }
        val link = links[linkID] ?: return
        val label = "${role(link)} ${link.address}: tx ${BleLongRangePolicy.phyName(link.txPhy)} / " +
            "rx ${BleLongRangePolicy.phyName(link.rxPhy)}"
        if (status != 0) {
            event(
                "PHY ${if (fromUpdate) "update" else "read"} failed " +
                    "(${BleLongRangePolicy.statusName(status)}) on $label"
            )
        } else if (before == null || before.txPhy != txPhy || before.rxPhy != rxPhy) {
            event("PHY ${if (fromUpdate) "updated" else "read"} $label")
        }
    }

    fun onSendComplete(address: String, linkID: String?, success: Boolean) {
        val id = linkID ?: links.values.firstOrNull { it.address == address }?.linkID ?: return
        update(id) { if (success) it.copy(sendOk = it.sendOk + 1) else it.copy(sendFailed = it.sendFailed + 1) }
    }

    fun onReceived(linkID: String) = update(linkID) { it.copy(received = it.received + 1) }

    fun onLinkClosed(linkID: String?, address: String, status: Int?) {
        val link = linkID?.let { links.remove(it) }
            ?: links.values.firstOrNull { it.address == address }?.also { links.remove(it.linkID) }
        if (status != null && status != 0) {
            val phy = link?.let { " on ${BleLongRangePolicy.phyName(it.txPhy)}" } ?: ""
            event("Link ${link?.let(::role) ?: ""} $address closed: ${BleLongRangePolicy.statusName(status)}$phy")
        }
    }

    fun clearEvents() {
        synchronized(events) { events.clear() }
    }

    fun event(text: String) {
        Log.i(TAG, text)
        synchronized(events) {
            events.addLast(Event(System.currentTimeMillis(), text))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        capabilities = capabilities,
        capabilitiesKnown = capabilitiesKnown,
        mode = mode,
        coding = coding,
        legacyAdvertiser = legacyAdvertiser,
        legacyAdvertiserError = legacyAdvertiserError,
        codedAdvertiser = codedAdvertiser,
        codedAdvertiserError = codedAdvertiserError,
        codedAdvertiserTxPowerDbm = codedTxPower,
        codedAdvertiserIntervalMs = codedIntervalMs,
        scanMode = scanMode,
        extendedScanRejected = extendedScanRejected,
        scanResultsLegacy = legacyResults.get(),
        scanResultsCoded = codedResults.get(),
        scanResultsOther = otherResults.get(),
        lastCodedRssi = lastCodedRssi,
        lastCodedAt = lastCodedAt,
        links = links.values.sortedBy { it.connectedAt },
        events = synchronized(events) { events.toList() }
    )

    private fun update(linkID: String, transform: (Link) -> Link) {
        links.computeIfPresent(linkID) { _, link -> transform(link) }
    }

    private fun role(link: Link) = if (link.isClient) "client" else "server"

    private fun advertiseErrorName(code: Int?): String = when (code) {
        1 -> "DATA_TOO_LARGE"
        2 -> "TOO_MANY_ADVERTISERS"
        3 -> "ALREADY_STARTED"
        4 -> "INTERNAL_ERROR"
        5 -> "FEATURE_UNSUPPORTED"
        null -> "unknown"
        else -> "error $code"
    }
}
