package com.bitchat.android.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertisingSetParameters

/**
 * How aggressively the BLE transport trades throughput for range.
 *
 * Every mode keeps the legacy 1M advertisement and legacy scan reception, so clients without
 * LE Coded support (older Android, iOS) keep discovering and connecting exactly as before.
 */
enum class BleLongRangeMode {
    /** Stock behavior: legacy advertising, legacy scanning, no PHY requests. */
    OFF,

    /**
     * Long-range discovery (Coded advertising set + extended scanning). Each link moves between
     * 1M and Coded via [AdaptivePhyController]: link RSSI on outgoing connections (incoming ones
     * follow the outgoing connection to the same peer) and send failures.
     */
    ADAPTIVE,

    /** Long-range discovery and Coded S=8 requested on every link where both ends support it. */
    MAX_RANGE;

    companion object {
        /** Adaptive keeps long-range discovery and 1M throughput at short range. */
        val DEFAULT = ADAPTIVE

        fun fromName(name: String?): BleLongRangeMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * LE Coded coding scheme requested for established links (`setPreferredPhy` phy options).
 *
 * It is a preference sent to the controller: the remote may pick a different coding for its own
 * transmissions, and Android reports a Coded link only as `PHY_LE_CODED`, never which coding is
 * in use. Advertising coding is chosen by the controller; Android exposes no API for it.
 */
enum class BleCodedCoding(
    val label: String,
    val dataRateKbps: Int?,
    /** Approximate distance multiple versus 1M in free space; null when the controller decides. */
    val rangeFactor: String?
) {
    S8("S=8", 125, "~4×"),
    S2("S=2", 500, "~2×"),
    NO_PREFERENCE("no preference", null, null);

    companion object {
        val DEFAULT = S8

        fun fromName(name: String?): BleCodedCoding = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Controller features relevant to long-range operation, read from the public adapter API at
 * runtime. Nothing here is assumed: a device only uses Coded PHY when the stack reports it.
 */
data class BleRadioCapabilities(
    val leCodedPhy: Boolean,
    val le2MPhy: Boolean,
    val leExtendedAdvertising: Boolean,
    val lePeriodicAdvertising: Boolean,
    val multipleAdvertisement: Boolean,
    val maxAdvertisingDataLength: Int
) {
    companion object {
        val NONE = BleRadioCapabilities(
            leCodedPhy = false,
            le2MPhy = false,
            leExtendedAdvertising = false,
            lePeriodicAdvertising = false,
            multipleAdvertisement = false,
            maxAdvertisingDataLength = 31
        )

        fun detect(adapter: BluetoothAdapter?): BleRadioCapabilities {
            if (adapter == null) return NONE
            fun probe(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { false }
            return BleRadioCapabilities(
                leCodedPhy = probe { adapter.isLeCodedPhySupported },
                le2MPhy = probe { adapter.isLe2MPhySupported },
                leExtendedAdvertising = probe { adapter.isLeExtendedAdvertisingSupported },
                lePeriodicAdvertising = probe { adapter.isLePeriodicAdvertisingSupported },
                multipleAdvertisement = probe { adapter.isMultipleAdvertisementSupported },
                maxAdvertisingDataLength = try { adapter.leMaximumAdvertisingDataLength } catch (_: Exception) { 31 }
            )
        }
    }
}

/**
 * Pure decisions for long-range operation so the fallback matrix is unit-testable without radios.
 */
object BleLongRangePolicy {
    /**
     * Bytes needed by the Coded advertising set: flags (3) + 128-bit service UUID list (18) +
     * 128-bit service data carrying the 8-byte peer ID (26). Extended connectable advertising
     * cannot use a scan response, so the peer ID must live in the advertisement itself.
     */
    const val CODED_ADVERTISEMENT_BYTES = 47

    /** Long-range discovery needs both a Coded-capable controller and extended advertising. */
    fun supportsLongRangeDiscovery(caps: BleRadioCapabilities): Boolean =
        caps.leCodedPhy && caps.leExtendedAdvertising

    fun shouldAdvertiseCoded(mode: BleLongRangeMode, caps: BleRadioCapabilities): Boolean =
        mode != BleLongRangeMode.OFF &&
            supportsLongRangeDiscovery(caps) &&
            caps.maxAdvertisingDataLength >= CODED_ADVERTISEMENT_BYTES

    /**
     * Extended scanning on all supported PHYs receives both legacy 1M and Coded advertisements.
     * [extendedScanRejected] latches when the stack refused an extended scan at runtime.
     */
    fun shouldUseExtendedScan(
        mode: BleLongRangeMode,
        caps: BleRadioCapabilities,
        extendedScanRejected: Boolean
    ): Boolean = mode != BleLongRangeMode.OFF && supportsLongRangeDiscovery(caps) && !extendedScanRejected

    /**
     * Initiating PHYs for connectGatt. Connect on the PHY the advertisement was heard on; when it
     * is unknown (manual connect) let the controller initiate on both.
     */
    fun connectPhyMask(mode: BleLongRangeMode, caps: BleRadioCapabilities, advertisedPrimaryPhy: Int?): Int {
        if (mode == BleLongRangeMode.OFF || !caps.leCodedPhy) return BluetoothDevice.PHY_LE_1M_MASK
        return when (advertisedPrimaryPhy) {
            BluetoothDevice.PHY_LE_CODED -> BluetoothDevice.PHY_LE_CODED_MASK
            BluetoothDevice.PHY_LE_1M -> BluetoothDevice.PHY_LE_1M_MASK
            else -> BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK
        }
    }

    fun shouldRequestCodedOnConnect(mode: BleLongRangeMode, caps: BleRadioCapabilities): Boolean =
        mode == BleLongRangeMode.MAX_RANGE && caps.leCodedPhy

    /**
     * Minimum scan RSSI worth a connection attempt. The stock thresholds (down to -65 dBm) reject
     * exactly the weak advertisements long range exists for; a Coded S=8 receiver works to roughly
     * -103 dBm, so Coded results are accepted down to sensitivity in foreground modes.
     */
    fun connectRssiThreshold(
        mode: BleLongRangeMode,
        powerMode: PowerManager.PowerMode,
        heardOnCoded: Boolean
    ): Int {
        val stock = when (powerMode) {
            PowerManager.PowerMode.PERFORMANCE -> -95
            PowerManager.PowerMode.BALANCED -> -85
            PowerManager.PowerMode.POWER_SAVER -> -75
            PowerManager.PowerMode.ULTRA_LOW_POWER -> -65
        }
        if (mode == BleLongRangeMode.OFF) return stock
        if (heardOnCoded) {
            return when (powerMode) {
                PowerManager.PowerMode.PERFORMANCE,
                PowerManager.PowerMode.BALANCED -> -110
                PowerManager.PowerMode.POWER_SAVER -> -100
                PowerManager.PowerMode.ULTRA_LOW_POWER -> -90
            }
        }
        // Legacy 1M results: relax by 5 dB, still above 1M sensitivity (~ -97 dBm).
        return stock - 5
    }

    data class CodedAdvertisingParams(val interval: Int, val txPowerLevel: Int)

    /**
     * Coded advertising cadence per power mode. Transmit power is a requested level; the controller
     * clamps it to the device's certified maximum, so the app never exceeds regulatory limits.
     */
    fun codedAdvertisingParams(powerMode: PowerManager.PowerMode): CodedAdvertisingParams = when (powerMode) {
        PowerManager.PowerMode.PERFORMANCE ->
            CodedAdvertisingParams(AdvertisingSetParameters.INTERVAL_LOW, AdvertisingSetParameters.TX_POWER_HIGH)
        PowerManager.PowerMode.BALANCED ->
            CodedAdvertisingParams(AdvertisingSetParameters.INTERVAL_MEDIUM, AdvertisingSetParameters.TX_POWER_HIGH)
        PowerManager.PowerMode.POWER_SAVER ->
            CodedAdvertisingParams(AdvertisingSetParameters.INTERVAL_HIGH, AdvertisingSetParameters.TX_POWER_HIGH)
        PowerManager.PowerMode.ULTRA_LOW_POWER ->
            CodedAdvertisingParams(AdvertisingSetParameters.INTERVAL_HIGH, AdvertisingSetParameters.TX_POWER_MEDIUM)
    }

    fun phyOption(coding: BleCodedCoding): Int = when (coding) {
        BleCodedCoding.S8 -> BluetoothDevice.PHY_OPTION_S8
        BleCodedCoding.S2 -> BluetoothDevice.PHY_OPTION_S2
        BleCodedCoding.NO_PREFERENCE -> BluetoothDevice.PHY_OPTION_NO_PREFERRED
    }

    /** Readable name for the HCI/GATT status codes seen in PHY updates and disconnects. */
    fun statusName(status: Int): String = when (status) {
        0x00 -> "success"
        0x08 -> "connection timeout"
        0x11 -> "unsupported feature"
        0x13 -> "remote closed"
        0x16 -> "closed locally"
        0x1A -> "remote lacks feature"
        0x1E -> "invalid LL parameters"
        0x22 -> "LL response timeout"
        0x23 -> "LL procedure collision"
        0x2A -> "transaction collision"
        0x3B -> "unacceptable parameters"
        0x3E -> "failed to establish"
        0x85 -> "GATT error"
        0x93 -> "GATT timeout"
        else -> "0x%02X".format(status)
    }

    fun phyName(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "Coded"
        0 -> "-"
        else -> "phy$phy"
    }
}

/**
 * User-facing summary of the BLE range state, derived from [BleLinkDiagnostics.Snapshot].
 */
data class BleRangeIndicator(
    val kind: Kind,
    /** PHY state across current BLE links whose PHY is known. */
    val links: Links,
    /** Rough phone-to-phone estimate in meters for the current method. */
    val estimatedRangeMeters: IntRange
) {
    /**
     * Link PHY summary. Deliberately not a device count: one phone is reached through several
     * addresses and links (legacy set, Coded set, its own initiator address, Android's server-side
     * echo of our client connection), so per-link counts don't map to people.
     */
    enum class Links { NONE, ALL_CODED, SOME_CODED, STANDARD }

    enum class Kind {
        /** Capabilities not read yet (BLE not started). */
        UNKNOWN,
        /** Long range switched off by the user. */
        STANDARD_OFF,
        /** Long range requested but this phone's controller can't do it. */
        STANDARD_UNSUPPORTED,
        /** Coded advertising/scanning active; peers can be reached at long range. */
        LONG_RANGE
    }

    companion object {
        /**
         * Phone-to-phone estimates. Standard 1M BLE between phones typically reaches 10-50 m;
         * LE Coded S=8 adds roughly 12 dB of link budget, about 4x the distance in free space.
         * Walls, bodies and antenna orientation dominate, so these are deliberately broad.
         */
        val STANDARD_RANGE_M = 10..50
        val LONG_RANGE_M = 40..200

        fun from(snapshot: BleLinkDiagnostics.Snapshot): BleRangeIndicator {
            val known = snapshot.links.filter { it.txPhy != 0 && it.rxPhy != 0 }
            val coded = known.count {
                it.txPhy == BluetoothDevice.PHY_LE_CODED && it.rxPhy == BluetoothDevice.PHY_LE_CODED
            }
            val links = when {
                known.isEmpty() -> Links.NONE
                coded == known.size -> Links.ALL_CODED
                coded > 0 -> Links.SOME_CODED
                else -> Links.STANDARD
            }
            val kind = when {
                snapshot.mode == BleLongRangeMode.OFF -> Kind.STANDARD_OFF
                !snapshot.capabilitiesKnown -> Kind.UNKNOWN
                !BleLongRangePolicy.supportsLongRangeDiscovery(snapshot.capabilities) -> Kind.STANDARD_UNSUPPORTED
                snapshot.codedAdvertiser == BleLinkDiagnostics.AdvertiserState.UNSUPPORTED &&
                    snapshot.extendedScanRejected -> Kind.STANDARD_UNSUPPORTED
                else -> Kind.LONG_RANGE
            }
            return BleRangeIndicator(
                kind = kind,
                links = links,
                estimatedRangeMeters = if (kind == Kind.LONG_RANGE || coded > 0) LONG_RANGE_M else STANDARD_RANGE_M
            )
        }
    }
}

/**
 * Groups diagnostic link entries into peers and real radio connections.
 *
 * Android reports our own outgoing (client) connection to the app's GATT server as well, so it
 * appears a second time as a server link on the same address. That mirror is the same radio
 * link, not another connection. Bitchat also connects in both directions, so a fully connected
 * peer normally shows two radio connections (outgoing + incoming) and three entries.
 */
object BleLinkGrouping {
    data class Connection(
        val link: BleLinkDiagnostics.Link,
        /** Android's server-side report of this same outgoing connection, if present. */
        val mirror: BleLinkDiagnostics.Link? = null
    ) {
        val outgoing: Boolean get() = link.isClient
        val sendOk: Long get() = link.sendOk + (mirror?.sendOk ?: 0)
        val sendFailed: Long get() = link.sendFailed + (mirror?.sendFailed ?: 0)
        val received: Long get() = link.received + (mirror?.received ?: 0)
    }

    data class Peer(
        /** Mesh peer ID when known from a validated announce, else null. */
        val peerID: String?,
        /** All addresses this peer is seen on (for name lookup). */
        val addresses: Set<String>,
        /** Outgoing connection first, then incoming ones. */
        val connections: List<Connection>
    )

    fun group(links: List<BleLinkDiagnostics.Link>): List<Peer> {
        val clientAddresses = links.filter { it.isClient }.map { it.address }.toSet()
        val mirrors = links.filter { !it.isClient && it.address in clientAddresses }
        val mains = links.filter { it !in mirrors }
        val peerByAddress = links.mapNotNull { link -> link.peerID?.let { link.address to it } }.toMap()
        fun peerOf(link: BleLinkDiagnostics.Link) = link.peerID ?: peerByAddress[link.address]

        return mains
            .groupBy { peerOf(it) ?: "address:${it.address}" }
            .map { (_, groupLinks) ->
                val connections = groupLinks
                    .map { main ->
                        Connection(main, if (main.isClient) mirrors.firstOrNull { it.address == main.address } else null)
                    }
                    .sortedWith(compareByDescending<Connection> { it.outgoing }.thenBy { it.link.connectedAt })
                Peer(
                    peerID = groupLinks.firstNotNullOfOrNull { peerOf(it) },
                    addresses = connections.flatMap { listOfNotNull(it.link.address, it.mirror?.address) }.toSet(),
                    connections = connections
                )
            }
            .sortedBy { it.connections.minOf { c -> c.link.connectedAt } }
    }
}

/**
 * Thresholds for [BleLongRangeMode.ADAPTIVE]. Asymmetric on purpose: moving to Coded must be fast
 * (a 1M link that fades past its edge drops before it can switch), moving back to 1M must be slow
 * and only on a clearly strong, stable signal.
 */
data class AdaptivePhyTuning(
    /** Smoothed RSSI below this, held for [enterConfirmMs], moves the link to Coded. */
    val enterCodedBelowDbm: Int = DEFAULT_ENTER_CODED_BELOW_DBM,
    /** Smoothed RSSI above this, held for [leaveStableMs], moves the link back to 1M. */
    val leaveCodedAboveDbm: Int = DEFAULT_LEAVE_CODED_ABOVE_DBM,
    val enterConfirmMs: Long = 3_000L,
    val leaveStableMs: Long = 30_000L,
    /** Margin below [enterCodedBelowDbm]: a single raw sample this weak switches at once. */
    val emergencyMarginDb: Int = 10,
    /** Consecutive failed sends on a 1M link that switch it to Coded. */
    val failureBurst: Int = 3,
    val smoothing: Double = 0.4,
    /** Within this many dB of the relevant threshold, sample RSSI faster. */
    val nearThresholdMarginDb: Int = 8,
    val fastPollMs: Long = 2_000L,
    val slowPollMs: Long = 10_000L
) {
    val emergencyBelowDbm: Int get() = enterCodedBelowDbm - emergencyMarginDb

    /** Keeps at least [MIN_GAP_DB] between the thresholds so a link cannot oscillate. */
    fun sanitized(): AdaptivePhyTuning {
        val enter = enterCodedBelowDbm.coerceIn(MIN_THRESHOLD_DBM, MAX_THRESHOLD_DBM - MIN_GAP_DB)
        val leave = leaveCodedAboveDbm.coerceIn(enter + MIN_GAP_DB, MAX_THRESHOLD_DBM)
        return copy(enterCodedBelowDbm = enter, leaveCodedAboveDbm = leave)
    }

    companion object {
        const val DEFAULT_ENTER_CODED_BELOW_DBM = -78
        const val DEFAULT_LEAVE_CODED_ABOVE_DBM = -65
        const val MIN_GAP_DB = 6
        const val MIN_THRESHOLD_DBM = -100
        const val MAX_THRESHOLD_DBM = -30
    }
}

/**
 * Per-link PHY decisions for [BleLongRangeMode.ADAPTIVE]: when to move a link between 1M and Coded.
 *
 * Fed by link RSSI (incoming connections follow our outgoing connection to the same peer, since
 * Android exposes no RSSI read on the GATT server side), plus send results. Not thread-safe:
 * callers serialize access per link.
 */
class AdaptivePhyController(private val tuning: () -> AdaptivePhyTuning = { AdaptivePhyTuning() }) {

    enum class Target { ONE_M, CODED }

    enum class Reason { WEAK_SIGNAL, SIGNAL_DROP, SEND_FAILURES, WEAK_AT_CONNECT, STRONG_SIGNAL }

    data class Decision(val target: Target, val reason: Reason)

    var smoothedRssi: Double? = null
        private set
    private var weakSince: Long? = null
    private var strongSince: Long? = null
    private var consecutiveFailures = 0

    /** A connection that starts already weak goes straight to Coded. */
    fun onConnected(initialRssi: Int?, currentlyCoded: Boolean): Decision? {
        if (initialRssi == null || currentlyCoded) return null
        smoothedRssi = initialRssi.toDouble()
        return if (initialRssi < tuning().sanitized().enterCodedBelowDbm) {
            Decision(Target.CODED, Reason.WEAK_AT_CONNECT)
        } else null
    }

    fun onRssi(rssi: Int, currentlyCoded: Boolean, nowMs: Long): Decision? {
        val t = tuning().sanitized()
        val smoothed = smoothedRssi?.let { it + t.smoothing * (rssi - it) } ?: rssi.toDouble()
        smoothedRssi = smoothed

        if (!currentlyCoded) {
            strongSince = null
            if (rssi < t.emergencyBelowDbm) return switched(Decision(Target.CODED, Reason.SIGNAL_DROP))
            if (smoothed < t.enterCodedBelowDbm) {
                val since = weakSince ?: nowMs.also { weakSince = it }
                if (nowMs - since >= t.enterConfirmMs) return switched(Decision(Target.CODED, Reason.WEAK_SIGNAL))
            } else {
                weakSince = null
            }
        } else {
            weakSince = null
            if (smoothed > t.leaveCodedAboveDbm) {
                val since = strongSince ?: nowMs.also { strongSince = it }
                if (nowMs - since >= t.leaveStableMs) return switched(Decision(Target.ONE_M, Reason.STRONG_SIGNAL))
            } else {
                strongSince = null
            }
        }
        return null
    }

    /** Repeated send failures on a non-Coded link are the most direct sign it is failing. */
    fun onSendResult(success: Boolean, currentlyCoded: Boolean): Decision? {
        if (success) {
            consecutiveFailures = 0
            return null
        }
        consecutiveFailures++
        if (currentlyCoded || consecutiveFailures < tuning().failureBurst) return null
        return switched(Decision(Target.CODED, Reason.SEND_FAILURES))
    }

    /** Sample faster while the signal sits near the threshold that could trigger a switch. */
    fun pollIntervalMs(currentlyCoded: Boolean): Long {
        val t = tuning().sanitized()
        val smoothed = smoothedRssi ?: return t.fastPollMs
        val distance = if (currentlyCoded) t.leaveCodedAboveDbm - smoothed else smoothed - t.enterCodedBelowDbm
        return if (distance <= t.nearThresholdMarginDb) t.fastPollMs else t.slowPollMs
    }

    /** Human-readable pending state for diagnostics, e.g. "to Coded in 2s". */
    fun pendingNote(currentlyCoded: Boolean, nowMs: Long): String? {
        val t = tuning().sanitized()
        weakSince?.takeIf { !currentlyCoded }?.let { since ->
            return "to Coded in ${((t.enterConfirmMs - (nowMs - since)).coerceAtLeast(0) + 999) / 1000}s if still weak"
        }
        strongSince?.takeIf { currentlyCoded }?.let { since ->
            return "to 1M in ${((t.leaveStableMs - (nowMs - since)).coerceAtLeast(0) + 999) / 1000}s if still strong"
        }
        return null
    }

    private fun switched(decision: Decision): Decision {
        weakSince = null
        strongSince = null
        consecutiveFailures = 0
        return decision
    }
}

/** Current adaptive thresholds from debug settings; defaults when settings are unavailable. */
object BleAdaptiveSettings {
    fun current(): AdaptivePhyTuning =
        try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleAdaptiveTuning.value
        } catch (_: Exception) {
            AdaptivePhyTuning()
        }
}

/**
 * Exponential back-off for PHY requests on one link. A remote can refuse a PHY change silently
 * (no callback), so without this a controller that keeps deciding "Coded" would re-send the
 * request on every evaluation. Resets when the link reaches the target or the target changes.
 * After [maxAttempts] refusals it pauses for [cooldownMs], then tries a new round: the remote's
 * reason to refuse (its own PHY preference, for example) may have gone away.
 */
class PhyRequestBackoff(
    private val baseMs: Long = 5_000L,
    private val maxMs: Long = 40_000L,
    private val maxAttempts: Int = 5,
    private val cooldownMs: Long = 300_000L
) {
    private var target: AdaptivePhyController.Target? = null
    private var attempts = 0
    private var lastAt = 0L

    /** True if a request for [target] may be sent now; records the attempt when it may. */
    fun allow(target: AdaptivePhyController.Target, nowMs: Long): Boolean {
        if (target != this.target) {
            this.target = target
            attempts = 0
        }
        if (attempts >= maxAttempts) {
            if (nowMs - lastAt < cooldownMs) return false
            attempts = 0
        }
        val wait = if (attempts == 0) 0L else (baseMs shl (attempts - 1)).coerceAtMost(maxMs)
        if (attempts > 0 && nowMs - lastAt < wait) return false
        attempts++
        lastAt = nowMs
        return true
    }

    /**
     * Diagnostics for a link that is not on [target] although it was requested, e.g.
     * "1M requested 2x, peer hasn't switched"; null when nothing is outstanding.
     */
    fun refusalNote(target: AdaptivePhyController.Target, nowMs: Long): String? {
        if (target != this.target || attempts == 0) return null
        val phy = if (target == AdaptivePhyController.Target.CODED) "Coded" else "1M"
        if (attempts >= maxAttempts) {
            val retryIn = ((cooldownMs - (nowMs - lastAt)).coerceAtLeast(0) + 999) / 1000
            return "peer kept its PHY after $attempts requests for $phy; asking again in ${retryIn}s"
        }
        return "$phy requested ${attempts}x, peer hasn't switched yet"
    }

    /** The link is on the requested PHY (or a new situation began): start over. */
    fun reset() {
        target = null
        attempts = 0
    }

    /** Report the link's current PHY; resets once the requested target is reached. */
    fun onPhy(isCoded: Boolean) {
        val reached = when (target) {
            AdaptivePhyController.Target.CODED -> isCoded
            AdaptivePhyController.Target.ONE_M -> !isCoded
            null -> false
        }
        if (reached) reset()
    }
}
