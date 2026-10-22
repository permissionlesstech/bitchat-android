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
     * Long-range discovery (Coded advertising set + extended scanning). Links switch between
     * 1M and Coded S=8 from the client side based on smoothed RSSI.
     */
    ADAPTIVE,

    /** Long-range discovery and Coded S=8 requested on every link where both ends support it. */
    MAX_RANGE;

    companion object {
        val DEFAULT = MAX_RANGE

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
 * Client-side PHY switching for [BleLongRangeMode.ADAPTIVE]: exponential smoothing plus
 * hysteresis and a minimum dwell, so a link near the threshold does not flap between PHYs.
 */
class PhyHysteresis(
    private val enterCodedBelowDbm: Int = ENTER_CODED_BELOW_DBM,
    private val leaveCodedAboveDbm: Int = LEAVE_CODED_ABOVE_DBM,
    private val minDwellMs: Long = 10_000L,
    private val smoothing: Double = 0.3
) {
    enum class Target { ONE_M, CODED }

    companion object {
        const val ENTER_CODED_BELOW_DBM = -80
        const val LEAVE_CODED_ABOVE_DBM = -66
    }

    private var smoothed: Double? = null
    private var lastSwitchAt: Long = Long.MIN_VALUE / 2

    val smoothedRssi: Int? get() = smoothed?.toInt()

    /** Returns the PHY to request, or null when the current PHY should be kept. */
    fun onRssi(rssi: Int, currentlyCoded: Boolean, nowMs: Long): Target? {
        val next = smoothed?.let { it + smoothing * (rssi - it) } ?: rssi.toDouble()
        smoothed = next
        if (nowMs - lastSwitchAt < minDwellMs) return null
        val target = when {
            !currentlyCoded && next < enterCodedBelowDbm -> Target.CODED
            currentlyCoded && next > leaveCodedAboveDbm -> Target.ONE_M
            else -> null
        }
        if (target != null) lastSwitchAt = nowMs
        return target
    }
}
