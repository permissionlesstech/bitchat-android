package com.bitchat.android.mesh

import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BLEPacketPaddingPolicyTest {
    private val senderID = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x00)
    private val timestamp = 1_709_600_000_000uL

    @Test
    fun `BLE padding policy matches iOS packet type table`() {
        val expected = mapOf(
            MessageType.ANNOUNCE to false,
            MessageType.MESSAGE to false,
            MessageType.LEAVE to false,
            MessageType.REQUEST_SYNC to false,
            MessageType.FRAGMENT to false,
            MessageType.FILE_TRANSFER to false,
            MessageType.NOISE_ENCRYPTED to true,
            MessageType.NOISE_HANDSHAKE to true
        )

        expected.forEach { (type, shouldPad) ->
            assertEquals(
                "${type.name} BLE padding policy must match iOS",
                shouldPad,
                BLEPacketPaddingPolicy.shouldPadForBLE(type.value)
            )
        }

        assertFalse(
            "Unknown packet types should be sent unpadded, matching iOS default",
            BLEPacketPaddingPolicy.shouldPadForBLE(0x7Fu)
        )
    }

    @Test
    fun `public BLE packet types are encoded without PKCS7 padding tails`() {
        val publicTypes = listOf(
            MessageType.ANNOUNCE,
            MessageType.MESSAGE,
            MessageType.LEAVE,
            MessageType.REQUEST_SYNC,
            MessageType.FRAGMENT,
            MessageType.FILE_TRANSFER
        )

        publicTypes.forEach { type ->
            val packet = packet(type.value, payload = payloadEndingWithoutPaddingShape(type))
            val raw = packet.toBinaryData(padding = false)!!
            val encodedForBLE = packet.toBinaryData(
                padding = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)
            )!!

            assertArrayEquals(
                "${type.name} BLE encoding should be the unpadded frame",
                raw,
                encodedForBLE
            )
            assertFalse(
                "${type.name} BLE encoding must not leave iOS-visible PKCS#7 tail bytes",
                hasPkcs7PaddingTail(encodedForBLE)
            )
            assertNotNull(
                "${type.name} unpadded BLE frame must remain decodable",
                BinaryProtocol.decode(encodedForBLE)
            )
        }
    }

    @Test
    fun `Noise BLE packet types remain padded and decodable`() {
        val noiseTypes = listOf(MessageType.NOISE_HANDSHAKE, MessageType.NOISE_ENCRYPTED)

        noiseTypes.forEach { type ->
            val packet = packet(type.value, payload = "noise-${type.name}".toByteArray())
            val raw = packet.toBinaryData(padding = false)!!
            val encodedForBLE = packet.toBinaryData(
                padding = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)
            )!!

            assertTrue("${type.name} BLE frame should be padded", encodedForBLE.size > raw.size)
            assertTrue(
                "${type.name} BLE frame should end with valid PKCS#7 padding",
                hasPkcs7PaddingTail(encodedForBLE)
            )
            assertArrayEquals(
                "${type.name} padding should strip back to the raw frame",
                raw,
                MessagePadding.unpad(encodedForBLE)
            )
            assertNotNull(
                "${type.name} padded BLE frame must remain decodable",
                BinaryProtocol.decode(encodedForBLE)
            )
        }
    }

    private fun packet(type: UByte, payload: ByteArray): BitchatPacket {
        val version = if (type == MessageType.FILE_TRANSFER.value) 2u.toUByte() else 1u.toUByte()
        return BitchatPacket(
            version = version,
            type = type,
            senderID = senderID,
            recipientID = null,
            timestamp = timestamp,
            payload = payload,
            signature = null,
            ttl = 7u,
            route = null
        )
    }

    private fun payloadEndingWithoutPaddingShape(type: MessageType): ByteArray {
        return "ios-ble-policy-${type.name}-z".toByteArray()
    }

    private fun hasPkcs7PaddingTail(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val paddingLength = data.last().toInt() and 0xFF
        if (paddingLength <= 0 || paddingLength > data.size) return false
        val start = data.size - paddingLength
        return data.copyOfRange(start, data.size).all { (it.toInt() and 0xFF) == paddingLength }
    }
}
