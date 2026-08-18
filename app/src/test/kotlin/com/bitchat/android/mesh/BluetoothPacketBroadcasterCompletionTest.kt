package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class BluetoothPacketBroadcasterCompletionTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tracker = BluetoothConnectionTracker(scope, mock())
    private val broadcaster = BluetoothPacketBroadcaster(scope, tracker, null, MY_PEER_ID)

    @After
    fun tearDown() {
        broadcaster.shutdown()
        scope.cancel()
    }

    @Test
    fun `queue admission waits for successful GATT completion`() = runBlocking {
        val connection = connectedPeer()

        val result = async(Dispatchers.Default) {
            broadcaster.sendPacketToPeerAndAwaitCompletion(packet(), PEER_ID, null, null)
        }

        verify(connection.gatt!!, timeout(1_000)).writeCharacteristic(connection.characteristic!!)
        assertFalse(result.isCompleted)

        broadcaster.onGattClientWriteComplete(DEVICE_ADDRESS, LINK_ID, BluetoothGatt.GATT_SUCCESS)
        assertTrue(result.await())
    }

    @Test
    fun `disconnect fails an admitted send before custody can be released`() = runBlocking {
        val connection = connectedPeer()

        val result = async(Dispatchers.Default) {
            broadcaster.sendPacketToPeerAndAwaitCompletion(packet(), PEER_ID, null, null)
        }

        verify(connection.gatt!!, timeout(1_000)).writeCharacteristic(connection.characteristic!!)
        assertFalse(result.isCompleted)

        broadcaster.onLinkDisconnected(DEVICE_ADDRESS, LINK_ID)
        assertFalse(result.await())
    }

    private fun connectedPeer(): BluetoothConnectionTracker.DeviceConnection {
        val device = mock<BluetoothDevice>()
        val gatt = mock<BluetoothGatt>()
        val characteristic = mock<BluetoothGattCharacteristic>()
        whenever(device.address).thenReturn(DEVICE_ADDRESS)
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        val connection = BluetoothConnectionTracker.DeviceConnection(
            device = device,
            gatt = gatt,
            characteristic = characteristic,
            isClient = true,
            linkID = LINK_ID
        )
        tracker.addDeviceConnection(DEVICE_ADDRESS, connection)
        tracker.observePeerIfCurrent(DEVICE_ADDRESS, LINK_ID, PEER_ID)
        return connection
    }

    private fun packet() = RoutedPacket(
        BitchatPacket(
            type = MessageType.COURIER_ENVELOPE.value,
            senderID = MY_PEER_ID.hexToBytes(),
            recipientID = PEER_ID.hexToBytes(),
            timestamp = System.currentTimeMillis().toULong(),
            payload = byteArrayOf(1, 2, 3),
            ttl = 7u
        )
    )

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
        const val PEER_ID = "aaaabbbbccccdddd"
        const val DEVICE_ADDRESS = "00:11:22:33:44:55"
        const val LINK_ID = "synthetic-link"
    }
}
