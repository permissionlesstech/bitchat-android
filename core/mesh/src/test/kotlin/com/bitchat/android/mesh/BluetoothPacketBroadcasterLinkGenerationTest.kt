package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.ui.debug.DebugSettingsManager
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothPacketBroadcasterLinkGenerationTest {

    @Suppress("DEPRECATION")
    @Test
    fun `link replacement at same address stops obsolete retries while replacement link drains`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val tracker = BluetoothConnectionTracker(scope, mock())
        val broadcaster = BluetoothPacketBroadcaster(
            connectionScope = scope,
            connectionTracker = tracker,
            fragmentManager = null,
            myPeerID = "0102030405060708",
            debugSettingsManager = Lazy { DebugSettingsManager(DebugPreferenceManager()) },
            transferProgressManager = TransferProgressManager(dispatcher, IdentityTeardown()),
        )

        val address = "AA:BB:CC:DD:EE:01"
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)

        val oldChar = mock<BluetoothGattCharacteristic>()
        val oldGatt = mock<BluetoothGatt>()
        val oldWriteAttempts = AtomicInteger(0)
        whenever(oldGatt.writeCharacteristic(any())).thenAnswer {
            oldWriteAttempts.incrementAndGet()
            false // Reject initial write so broadcaster schedules a retry
        }

        val newChar = mock<BluetoothGattCharacteristic>()
        val newGatt = mock<BluetoothGatt>()
        val newWriteAttempts = AtomicInteger(0)
        whenever(newGatt.writeCharacteristic(any())).thenAnswer {
            newWriteAttempts.incrementAndGet()
            true
        }

        try {
            tracker.addDeviceConnection(
                address,
                BluetoothConnectionTracker.DeviceConnection(
                    device = device,
                    gatt = oldGatt,
                    characteristic = oldChar,
                    isClient = true,
                    linkID = "link-gen-1"
                )
            )

            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = MeshPacketUtils.hexStringToByteArray("0102030405060708"),
                timestamp = 1_700_000_000_000uL,
                payload = "hello".toByteArray(),
                ttl = 5u
            )

            // Queue on link-gen-1 -> immediate write attempt fails and schedules 15ms retry
            assertTrue(
                broadcaster.sendPacketToLink(
                    routed = RoutedPacket(packet),
                    deviceAddress = address,
                    linkID = "link-gen-1",
                    gattServer = null,
                    characteristic = null
                )
            )
            assertEquals(1, oldWriteAttempts.get())

            // Replace connection at the SAME address with link-gen-2 before retry fires
            tracker.addDeviceConnection(
                address,
                BluetoothConnectionTracker.DeviceConnection(
                    device = device,
                    gatt = newGatt,
                    characteristic = newChar,
                    isClient = true,
                    linkID = "link-gen-2"
                )
            )

            // Advance well past multiple retry backoff windows: obsolete link-gen-1 must NOT retry
            scope.advanceTimeBy(1_000L)
            scope.runCurrent()
            assertEquals(
                "obsolete link generation must stop retrying once replaced at the same address",
                1,
                oldWriteAttempts.get()
            )

            // Sending on the replacement generation drains normally
            assertTrue(
                broadcaster.sendPacketToLink(
                    routed = RoutedPacket(packet),
                    deviceAddress = address,
                    linkID = "link-gen-2",
                    gattServer = null,
                    characteristic = null
                )
            )
            assertEquals(1, newWriteAttempts.get())
            broadcaster.onGattClientWriteComplete(address, "link-gen-2", BluetoothGatt.GATT_SUCCESS)
            scope.runCurrent()
            assertEquals(1, oldWriteAttempts.get())
        } finally {
            broadcaster.shutdown()
            tracker.stop()
        }
    }
}
