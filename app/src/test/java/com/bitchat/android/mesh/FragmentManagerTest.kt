package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.FragmentPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class FragmentManagerTest {

    private lateinit var fragmentManager: FragmentManager
    private val senderID = "1122334455667788"
    private val recipientID = "8877665544332211"

    @Before
    fun setup() {
        fragmentManager = FragmentManager()
    }

    @Test
    fun `test fragmentation without route`() {
        // Create a large payload (e.g., 1000 bytes)
        val payload = ByteArray(1000)
        Random().nextBytes(payload)

        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)
        
        // Verify each fragment fits in MTU (512)
        for (fragment in fragments) {
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
            
            // Inspect the payload data size
            val fragmentPayload = FragmentPayload.decode(fragment.payload)
            assertNotNull(fragmentPayload)
        }
    }

    @Test
    fun `test fragmentation with route`() {
        // Create a large payload
        val payload = ByteArray(1000)
        Random().nextBytes(payload)
        
        // Create a fake route (3 hops)
        val route = listOf(
            hexStringToByteArray("AABBCCDDEEFF0011"),
            hexStringToByteArray("1100FFEEDDCCBBAA"),
            hexStringToByteArray("1234567890ABCDEF")
        )

        val packet = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )

        val fragments = fragmentManager.createFragments(packet)

        assertTrue("Should create multiple fragments", fragments.size > 1)

        // Verify fragments retain the route and version 2
        for (fragment in fragments) {
            assertEquals("Fragment version should be 2", 2u.toUByte(), fragment.version)
            assertEquals("Fragment should have the route", route.size, fragment.route?.size)
            
            val encodedSize = fragment.toBinaryData()?.size ?: 0
            assertTrue("Fragment encoded size should be <= 512, was $encodedSize", encodedSize <= 512)
        }
    }
    
    @Test
    fun `test fragmentation size difference with and without route`() {
        // This test specifically checks if the dynamic calculation logic works 
        // by observing that fragments with routes carry less data payload per fragment
        
        val payload = ByteArray(2000) // Large enough to ensure full fragments
        Random().nextBytes(payload)
        
        // 1. Without route
        val packetNoRoute = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = null
        )
        val fragmentsNoRoute = fragmentManager.createFragments(packetNoRoute)
        val firstFragPayloadNoRoute = FragmentPayload.decode(fragmentsNoRoute[0].payload)
        val dataSizeNoRoute = firstFragPayloadNoRoute?.data?.size ?: 0
        
        // 2. With large route (e.g., 5 hops)
        val route = List(5) { hexStringToByteArray("000000000000000$it") }
        val packetWithRoute = BitchatPacket(
            version = 2u,
            type = MessageType.MESSAGE.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            ttl = 7u,
            route = route
        )
        val fragmentsWithRoute = fragmentManager.createFragments(packetWithRoute)
        val firstFragPayloadWithRoute = FragmentPayload.decode(fragmentsWithRoute[0].payload)
        val dataSizeWithRoute = firstFragPayloadWithRoute?.data?.size ?: 0
        
        println("Data size without route: $dataSizeNoRoute")
        println("Data size with route: $dataSizeWithRoute")
        
        assertTrue("Data payload should be smaller with route", dataSizeWithRoute < dataSizeNoRoute)
        
        // Rough verification of the math:
        // 5 hops * 8 bytes = 40 bytes extra.
        // Plus v2 header overhead differences.
        // The difference should be roughly 40+ bytes.
        assertTrue("Difference should be significant", (dataSizeNoRoute - dataSizeWithRoute) >= 40)
    }

    @Test
    fun `test reassembly`() {
        val originalPayload = ByteArray(1500)
        Random().nextBytes(originalPayload)
        
        val originalPacket = BitchatPacket(
            version = 1u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = hexStringToByteArray(senderID),
            recipientID = hexStringToByteArray(recipientID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = originalPayload,
            ttl = 7u
        )
        
        val fragments = fragmentManager.createFragments(originalPacket)
        
        var reassembledPacket: BitchatPacket? = null
        
        // Feed fragments back into FragmentManager
        // Note: FragmentManager stores state in incomingFragments
        
        for (fragment in fragments) {
            val result = fragmentManager.handleFragment(fragment)
            if (result != null) {
                reassembledPacket = result
            }
        }
        
        assertNotNull("Should have reassembled packet", reassembledPacket)
        assertEquals("Type should match", originalPacket.type, reassembledPacket!!.type)
        assertEquals("Payload size should match", originalPacket.payload.size, reassembledPacket.payload.size)
        assertTrue("Payload content should match", originalPacket.payload.contentEquals(reassembledPacket.payload))
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8)
        for (i in 0 until 8) {
            val byteStr = hexString.substring(i * 2, i * 2 + 2)
            result[i] = byteStr.toInt(16).toByte()
        }
        return result
    }
}
