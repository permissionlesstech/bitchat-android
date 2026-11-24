package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.meshgraph.MeshGraphService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@Suppress("DEPRECATION")
class BluetoothPacketBroadcasterTest {

    private var nowMs: Long = 100_000L
    private lateinit var scope: CoroutineScope
    private lateinit var graph: MeshGraphService
    private lateinit var tracker: BluetoothConnectionTracker
    private lateinit var broadcaster: BluetoothPacketBroadcaster
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var serverChar: BluetoothGattCharacteristic

    private val myPeerID = "1111111111111111"
    private val originPeerID = "2222222222222222"
    private val prevHopPeerID = "3333333333333333"
    private val originNeighborPeerID = "4444444444444444"
    private val prevHopNeighborPeerID = "5555555555555555"
    private val farPeerID = "6666666666666666"

    @Before
    fun setUp() {
        nowMs = 100_000L
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        graph = MeshGraphService { nowMs }
        val powerManager: PowerManager = mock()
        tracker = BluetoothConnectionTracker(scope, powerManager)
        broadcaster = BluetoothPacketBroadcaster(
            connectionScope = scope,
            connectionTracker = tracker,
            fragmentManager = null,
            myPeerID = myPeerID,
            meshGraphProvider = { graph },
            edgeMaxAgeMs = 30_000L
        )
        gattServer = mock()
        serverChar = mock()
        whenever(gattServer.notifyCharacteristicChanged(any(), any(), eq(false))).thenReturn(true)
    }

    @After
    fun tearDown() {
        broadcaster.shutdown()
        scope.cancel()
    }

    @Test
    fun `relayed broadcasts prune origin and previous-hop fresh confirmed neighbors on server and client links`() {
        runBlocking {
        seedConfirmedEdges(
            originPeerID to originNeighborPeerID,
            prevHopPeerID to prevHopNeighborPeerID,
            myPeerID to prevHopPeerID,
            myPeerID to farPeerID
        )

        // Server connections (subscribed devices):
        // - devPrevHop: exact incoming relayAddress
        // - devPrevHopAlt: same previousHopPeerID on another MAC (must NOT bounce back)
        // - devOriginNeighbor: fresh confirmed neighbor of origin (must be pruned)
        // - devFarServer: far peer (MUST receive)
        // - devUnidentified: newly connected device with no addressPeerMap entry (MUST receive)
        val devPrevHop = addServerDevice("AA:00:00:00:00:01", prevHopPeerID)
        val devPrevHopAlt = addServerDevice("AA:00:00:00:00:02", prevHopPeerID)
        val devOriginNeighbor = addServerDevice("AA:00:00:00:00:03", originNeighborPeerID)
        val devFarServer = addServerDevice("AA:00:00:00:00:04", farPeerID)
        val devUnidentified = addServerDevice("AA:00:00:00:00:05", peerID = null)

        // Client connections:
        // - gattOrigin: origin peer (must NOT bounce back)
        // - gattPrevHopNeighbor: fresh confirmed neighbor of previous hop (must be pruned)
        // - gattFarClient: another far peer (MUST receive)
        val gattOrigin = addClientDevice("BB:00:00:00:00:01", originPeerID)
        val gattPrevHopNeighbor = addClientDevice("BB:00:00:00:00:02", prevHopNeighborPeerID)
        val gattFarClient = addClientDevice("BB:00:00:00:00:03", "7777777777777777")

        for (type in listOf(MessageType.MESSAGE, MessageType.VOICE_FRAME, MessageType.FRAGMENT, MessageType.FILE_TRANSFER)) {
            val routed = RoutedPacket(
                packet = broadcastPacket(type, originPeerID),
                peerID = originPeerID,
                relayAddress = devPrevHop.address,
                previousHopPeerID = prevHopPeerID
            )
            assertTrue(broadcaster.broadcastSinglePacketInternal(routed, gattServer, serverChar))
        }

        // Skipped server targets
        verify(gattServer, never()).notifyCharacteristicChanged(eq(devPrevHop), any(), eq(false))
        verify(gattServer, never()).notifyCharacteristicChanged(eq(devPrevHopAlt), any(), eq(false))
        verify(gattServer, never()).notifyCharacteristicChanged(eq(devOriginNeighbor), any(), eq(false))

        // Delivered server targets (1 per type -> 4 total; queue drains only head without callback,
        // so at least startHead runs on the 1st call for each link)
        verify(gattServer).notifyCharacteristicChanged(eq(devFarServer), any(), eq(false))
        verify(gattServer).notifyCharacteristicChanged(eq(devUnidentified), any(), eq(false))

        // Skipped client targets
        verify(gattOrigin, never()).writeCharacteristic(any())
        verify(gattPrevHopNeighbor, never()).writeCharacteristic(any())

        // Delivered client target
        verify(gattFarClient).writeCharacteristic(any())
        }
    }

    @Test
    fun `stale confirmed neighbors are not pruned after freshness window expires`() {
        runBlocking {
            seedConfirmedEdges(prevHopPeerID to prevHopNeighborPeerID)
            val devPrevHop = addServerDevice("AA:00:00:00:00:01", prevHopPeerID)
            val devStaleNeighbor = addServerDevice("AA:00:00:00:00:02", prevHopNeighborPeerID)

            // Expire freshness window (> 30s)
            nowMs += 31_000L

            val routed = RoutedPacket(
                packet = broadcastPacket(MessageType.MESSAGE, originPeerID),
                peerID = originPeerID,
                relayAddress = devPrevHop.address,
                previousHopPeerID = prevHopPeerID
            )
            assertTrue(broadcaster.broadcastSinglePacketInternal(routed, gattServer, serverChar))

            verify(gattServer, never()).notifyCharacteristicChanged(eq(devPrevHop), any(), eq(false))
            verify(gattServer).notifyCharacteristicChanged(eq(devStaleNeighbor), any(), eq(false))
        }
    }

    @Test
    fun `conservative relays skip incoming peer and origin without pruning confirmed neighbors`() {
        runBlocking {
            seedConfirmedEdges(prevHopPeerID to prevHopNeighborPeerID)
            val devPrevHop = addServerDevice("AA:00:00:00:00:01", prevHopPeerID)
            val devPrevHopNeighbor = addServerDevice("AA:00:00:00:00:02", prevHopNeighborPeerID)

            val conservative = RoutedPacket(
                packet = broadcastPacket(MessageType.ANNOUNCE, originPeerID),
                peerID = originPeerID,
                relayAddress = devPrevHop.address,
                previousHopPeerID = prevHopPeerID,
                skipNeighborPruning = true
            )
            assertTrue(broadcaster.broadcastSinglePacketInternal(conservative, gattServer, serverChar))

            verify(gattServer, never()).notifyCharacteristicChanged(eq(devPrevHop), any(), eq(false))
            verify(gattServer).notifyCharacteristicChanged(eq(devPrevHopNeighbor), any(), eq(false))
        }
    }

    private fun addServerDevice(address: String, peerID: String?): BluetoothDevice {
        val device = mock<BluetoothDevice> {
            on { getAddress() } doReturn address
        }
        val conn = BluetoothConnectionTracker.DeviceConnection(
            device = device,
            isClient = false,
            linkID = "link-$address"
        )
        tracker.addDeviceConnection(address, conn)
        tracker.addSubscribedDevice(device)
        if (peerID != null) {
            tracker.addressPeerMap[address] = peerID
        }
        return device
    }

    private fun addClientDevice(address: String, peerID: String): BluetoothGatt {
        val device = mock<BluetoothDevice> {
            on { getAddress() } doReturn address
        }
        val gatt = mock<BluetoothGatt>()
        val char = mock<BluetoothGattCharacteristic>()
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        val conn = BluetoothConnectionTracker.DeviceConnection(
            device = device,
            gatt = gatt,
            characteristic = char,
            isClient = true,
            linkID = "link-$address"
        )
        tracker.addDeviceConnection(address, conn)
        tracker.addressPeerMap[address] = peerID
        return gatt
    }

    private fun seedConfirmedEdges(vararg edges: Pair<String, String>) {
        val adj = mutableMapOf<String, MutableList<String>>()
        for ((a, b) in edges) {
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
        }
        adj.forEach { (peer, neighbors) ->
            graph.updateFromAnnouncement(peer, peer, neighbors, 1_000UL)
        }
    }

    private fun broadcastPacket(type: MessageType, senderHex: String): BitchatPacket =
        BitchatPacket(
            version = 1u,
            type = type.value,
            senderID = senderHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1_000UL,
            payload = "hello-ble".toByteArray(),
            ttl = 5u
        )
}
