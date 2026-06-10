package com.bitchat.android.ui.debug

import android.os.Build
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class DebugSettingsManagerTest {

    private lateinit var manager: DebugSettingsManager

    @Before
    fun setup() {
        manager = DebugSettingsManager.getInstance()
        manager.resetForTesting()
        manager.setVerboseLoggingEnabled(true)
    }

    @After
    fun tearDown() {
        manager.resetForTesting()
    }

    @Test
    fun `logIncoming emits one typed packet trace`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 7u,
            senderID = "aaaabbbbccccdddd",
            payload = byteArrayOf(1, 2, 3)
        )

        manager.logIncoming(
            packet = packet,
            fromPeerID = "aaaabbbbccccdddd",
            fromNickname = "alice",
            fromDeviceAddress = "AA:BB:CC:DD:EE:FF",
            myPeerID = "1111222233334444"
        )

        val packetMessages = manager.debugMessages.value.filterIsInstance<DebugMessage.PacketEvent>()

        assertEquals("Incoming packet should create one packet event", 1, packetMessages.size)
        assertTrue(packetMessages.single().content.contains("Incoming"))
        assertTrue(packetMessages.single().content.contains("MESSAGE"))
    }

    @Test
    fun `logOutgoing relay emits one relay event without packet duplicate`() {
        manager.logOutgoing(
            packetType = "MESSAGE",
            toPeerID = "bbbbccccddddeeee",
            toNickname = "bob",
            toDeviceAddress = "11:22:33:44:55:66",
            previousHopPeerID = "aaaabbbbccccdddd",
            packetVersion = 1u,
            routeInfo = "routed: 2 hops",
            ttl = 6u
        )

        val packetMessages = manager.debugMessages.value.filterIsInstance<DebugMessage.PacketEvent>()
        val relayMessages = manager.debugMessages.value.filterIsInstance<DebugMessage.RelayEvent>()

        assertEquals("Relay send should not also create a packet event", 0, packetMessages.size)
        assertEquals("Relay send should create one relay event", 1, relayMessages.size)
        assertTrue(relayMessages.single().content.contains("Relay"))
        assertTrue(relayMessages.single().content.contains("MESSAGE"))
    }

    @Test
    fun `validation failures are hidden when verbose logging is disabled`() {
        manager.setVerboseLoggingEnabled(false)
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 7u,
            senderID = "aaaabbbbccccdddd",
            payload = byteArrayOf(1)
        )

        manager.logPacketValidationFailure(packet, "aaaabbbbccccdddd", "invalid signature")

        val validationMessages = manager.debugMessages.value.filterIsInstance<DebugMessage.ValidationEvent>()
        assertEquals(0, validationMessages.size)
    }
}
