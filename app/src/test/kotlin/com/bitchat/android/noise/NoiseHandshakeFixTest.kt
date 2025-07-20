package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Comprehensive test to fix the Noise handshake implementation
 * 
 * Based on the error logs:
 * - ShortBufferException occurs in writeMessage() call
 * - The issue is in line 249 of NoiseSession.kt (processHandshakeMessage method)
 * 
 * This test implements the exact same XX handshake pattern step by step
 * to identify and fix the buffer/parameter issues.
 */
class NoiseHandshakeFixTest {
    
    companion object {
        private const val TAG = "NoiseHandshakeFixTest"
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        
        // XX Pattern Message Sizes
        private const val XX_MESSAGE_1_SIZE = 32      // -> e (ephemeral key only)
        private const val XX_MESSAGE_2_SIZE = 80      // <- e, ee, s, es (32 + 48)  
        private const val XX_MESSAGE_3_SIZE = 48      // -> s, se (encrypted static key)
    }
    
    /**
     * Test the exact XX pattern handshake as described in Noise specification
     * This replicates the exact scenario from the error logs
     */
    @Test
    fun testXXPatternHandshakeStepByStep() {
        println("=== Testing XX Pattern Handshake Step by Step ===")
        
        // Step 1: Create initiator and responder HandshakeState objects
        val initiator = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        
        // Step 2: Generate static keypairs for both sides  
        val initiatorStatic = initiator.localKeyPair
        val responderStatic = responder.localKeyPair
        
        assertNotNull("Initiator needs static keypair for XX", initiatorStatic)
        assertNotNull("Responder needs static keypair for XX", responderStatic)
        
        initiatorStatic!!.generateKeyPair()
        responderStatic!!.generateKeyPair()
        
        println("Generated static keypairs")
        println("Initiator static key: ${initiatorStatic.hasPrivateKey()} / ${initiatorStatic.hasPublicKey()}")
        println("Responder static key: ${responderStatic.hasPrivateKey()} / ${responderStatic.hasPublicKey()}")
        
        // Step 3: Start handshakes
        initiator.start()
        responder.start()
        
        assertEquals("Initiator should write first message", HandshakeState.WRITE_MESSAGE, initiator.action)
        assertEquals("Responder should read first message", HandshakeState.READ_MESSAGE, responder.action)
        
        // Step 4: XX Message 1 (Initiator -> Responder)
        // Message: -> e
        println("\n--- XX Message 1: -> e ---")
        
        val message1Buffer = ByteArray(200) // Generous buffer size
        var payload = ByteArray(0) // Empty payload for message 1
        
        val message1Length = initiator.writeMessage(
            message1Buffer, 0,      // message buffer 
            payload, 0, 0           // empty payload
        )
        
        val message1 = message1Buffer.copyOf(message1Length)
        println("Message 1 generated: ${message1.size} bytes (expected ~$XX_MESSAGE_1_SIZE)")
        assertEquals("XX Message 1 should be $XX_MESSAGE_1_SIZE bytes", XX_MESSAGE_1_SIZE, message1.size)
        assertEquals("After writing, initiator should read", HandshakeState.READ_MESSAGE, initiator.action)
        
        // Process message 1 at responder
        val payload1Buffer = ByteArray(200)
        val payload1Length = responder.readMessage(
            message1, 0, message1.size,
            payload1Buffer, 0
        )
        
        println("Message 1 processed at responder, payload length: $payload1Length")
        assertEquals("After reading, responder should write", HandshakeState.WRITE_MESSAGE, responder.action)
        
        // Step 5: XX Message 2 (Responder -> Initiator)  
        // Message: <- e, ee, s, es
        println("\n--- XX Message 2: <- e, ee, s, es ---")
        
        val message2Buffer = ByteArray(200) // Generous buffer
        payload = ByteArray(0) // Empty payload for message 2
        
        val message2Length = responder.writeMessage(
            message2Buffer, 0,      // message buffer
            payload, 0, 0           // empty payload  
        )
        
        val message2 = message2Buffer.copyOf(message2Length)
        println("Message 2 generated: ${message2.size} bytes (expected ~$XX_MESSAGE_2_SIZE)")
        assertEquals("XX Message 2 should be $XX_MESSAGE_2_SIZE bytes", XX_MESSAGE_2_SIZE, message2.size)
        assertEquals("After writing, responder should read", HandshakeState.READ_MESSAGE, responder.action)
        
        // Process message 2 at initiator
        val payload2Buffer = ByteArray(200)
        val payload2Length = initiator.readMessage(
            message2, 0, message2.size,
            payload2Buffer, 0
        )
        
        println("Message 2 processed at initiator, payload length: $payload2Length")
        assertEquals("After reading, initiator should write", HandshakeState.WRITE_MESSAGE, initiator.action)
        
        // Step 6: XX Message 3 (Initiator -> Responder)
        // Message: -> s, se  
        println("\n--- XX Message 3: -> s, se ---")
        
        val message3Buffer = ByteArray(200) // Generous buffer
        payload = ByteArray(0) // Empty payload for message 3
        
        val message3Length = initiator.writeMessage(
            message3Buffer, 0,      // message buffer
            payload, 0, 0           // empty payload
        )
        
        val message3 = message3Buffer.copyOf(message3Length)
        println("Message 3 generated: ${message3.size} bytes (expected ~$XX_MESSAGE_3_SIZE)")
        assertEquals("XX Message 3 should be $XX_MESSAGE_3_SIZE bytes", XX_MESSAGE_3_SIZE, message3.size)
        assertEquals("After writing, initiator should split", HandshakeState.SPLIT, initiator.action)
        
        // Process message 3 at responder
        val payload3Buffer = ByteArray(200)
        val payload3Length = responder.readMessage(
            message3, 0, message3.size,
            payload3Buffer, 0
        )
        
        println("Message 3 processed at responder, payload length: $payload3Length")
        assertEquals("After reading, responder should split", HandshakeState.SPLIT, responder.action)
        
        // Step 7: Split transport keys
        println("\n--- Splitting Transport Keys ---")
        
        val initiatorCiphers = initiator.split()
        val responderCiphers = responder.split()
        
        assertNotNull("Initiator ciphers should be created", initiatorCiphers)
        assertNotNull("Responder ciphers should be created", responderCiphers)
        
        assertEquals("Initiator should be complete", HandshakeState.COMPLETE, initiator.action)
        assertEquals("Responder should be complete", HandshakeState.COMPLETE, responder.action)
        
        // Step 8: Test transport encryption
        println("\n--- Testing Transport Encryption ---")
        
        val testMessage = "Hello from Noise Protocol!".toByteArray()
        println("Original message: ${String(testMessage)}")
        
        // Encrypt initiator -> responder
        val ciphertext1 = ByteArray(testMessage.size + 16) // Add MAC space
        val cipherLength1 = initiatorCiphers.sender.encryptWithAd(
            null, testMessage, 0,
            ciphertext1, 0, testMessage.size
        )
        val encrypted1 = ciphertext1.copyOf(cipherLength1)
        println("Encrypted by initiator: ${encrypted1.size} bytes")
        
        // Decrypt at responder
        val plaintext1 = ByteArray(testMessage.size + 16)
        val plainLength1 = responderCiphers.receiver.decryptWithAd(
            null, encrypted1, 0,
            plaintext1, 0, encrypted1.size
        )
        val decrypted1 = plaintext1.copyOf(plainLength1)
        
        println("Decrypted by responder: ${String(decrypted1)}")
        assertArrayEquals("Message should decrypt correctly", testMessage, decrypted1)
        
        // Encrypt responder -> initiator
        val ciphertext2 = ByteArray(testMessage.size + 16)
        val cipherLength2 = responderCiphers.sender.encryptWithAd(
            null, testMessage, 0,
            ciphertext2, 0, testMessage.size
        )
        val encrypted2 = ciphertext2.copyOf(cipherLength2)
        
        // Decrypt at initiator  
        val plaintext2 = ByteArray(testMessage.size + 16)
        val plainLength2 = initiatorCiphers.receiver.decryptWithAd(
            null, encrypted2, 0,
            plaintext2, 0, encrypted2.size
        )
        val decrypted2 = plaintext2.copyOf(plainLength2)
        
        assertArrayEquals("Reverse message should decrypt correctly", testMessage, decrypted2)
        
        // Clean up
        initiatorCiphers.destroy()
        responderCiphers.destroy()
        initiator.destroy()
        responder.destroy()
        
        println("\n=== XX Pattern Handshake Test PASSED ===")
    }
    
    /**
     * Test the exact issue from the error logs:
     * ShortBufferException in writeMessage call
     */
    @Test
    fun testShortBufferExceptionIssue() {
        println("=== Testing ShortBuffer Exception Issue ===")
        
        // Create the exact scenario from the error logs:
        // iOS initiates handshake (sends 32-byte message 1)
        // Android responds as responder
        
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        val responderStatic = responder.localKeyPair!!
        responderStatic.generateKeyPair()
        responder.start()
        
        // Simulate the iOS message 1 (32 bytes of ephemeral key)
        val mockMessage1 = ByteArray(32) { it.toByte() } // Dummy ephemeral key
        
        // Process the incoming message (this succeeds according to logs)
        val payloadBuffer = ByteArray(256)
        val payloadLength = responder.readMessage(
            mockMessage1, 0, mockMessage1.size,
            payloadBuffer, 0
        )
        
        println("Processed mock message 1, payload length: $payloadLength")
        assertEquals("Responder should write after reading message 1", HandshakeState.WRITE_MESSAGE, responder.action)
        
        // THIS IS WHERE THE ERROR OCCURS: Generating response message 2
        println("Generating response message (this is where ShortBufferException occurs)...")
        
        // Test different buffer sizes to find the issue
        val bufferSizes = listOf(32, 64, 80, 96, 128, 200, 256)
        
        for (bufferSize in bufferSizes) {
            println("Testing buffer size: $bufferSize")
            
            try {
                val responseBuffer = ByteArray(bufferSize)
                val responseLength = responder.writeMessage(
                    responseBuffer, 0,     // message buffer
                    ByteArray(0), 0, 0     // empty payload
                )
                
                val response = responseBuffer.copyOf(responseLength)
                println("SUCCESS: Generated response with buffer size $bufferSize: ${response.size} bytes")
                
                // Validate the response size
                if (response.size == XX_MESSAGE_2_SIZE) {
                    println("✓ Response size matches expected XX message 2 size")
                } else {
                    println("⚠ Response size ${response.size} != expected $XX_MESSAGE_2_SIZE")
                }
                
                break // Stop on first success
                
            } catch (e: Exception) {
                println("FAILED with buffer size $bufferSize: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        
        responder.destroy()
    }
    
    /**
     * Test the exact parameter order used in NoiseSession.kt
     * to identify if the wrong parameter order is the issue
     */
    @Test
    fun testParameterOrderIssue() {
        println("=== Testing Parameter Order Issue ===")
        
        // First create a proper message 1 from an initiator
        val initiator = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        initiator.localKeyPair!!.generateKeyPair()
        initiator.start()
        
        // Generate real message 1
        val message1Buffer = ByteArray(200)
        val message1Length = initiator.writeMessage(
            message1Buffer, 0,
            ByteArray(0), 0, 0
        )
        val realMessage1 = message1Buffer.copyOf(message1Length)
        println("Generated real message 1: ${realMessage1.size} bytes")
        
        // Now test responder
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        responder.localKeyPair!!.generateKeyPair()
        responder.start()
        
        // Process real message 1
        val payloadBuffer = ByteArray(256)
        val payloadLength = responder.readMessage(realMessage1, 0, realMessage1.size, payloadBuffer, 0)
        println("Processed real message 1, payload length: $payloadLength")
        println("Responder action after read: ${responder.action}")
        
        // Test the WRONG parameter order from NoiseSession.kt line 249:
        // handshakeStateLocal.writeMessage(ByteArray(0), 0, responseBuffer, 0, 0)
        //                                  ^^^^^^^^^^^^^^^ WRONG! This should be the MESSAGE buffer
        //                                                   ^^^^^^^^^^^^^^ This should be the PAYLOAD buffer
        
        println("\nTesting WRONG parameter order (as in current code):")
        try {
            val responseBuffer = ByteArray(200)
            
            // WRONG ORDER (as in current NoiseSession.kt):
            val wrongLength = responder.writeMessage(
                ByteArray(0), 0,          // ❌ EMPTY ARRAY as message buffer!
                responseBuffer, 0, 0      // ❌ Response buffer as "payload"!
            )
            
            println("WRONG order UNEXPECTEDLY succeeded: $wrongLength bytes")
            
        } catch (e: Exception) {
            println("WRONG order failed as expected: ${e.javaClass.simpleName}: ${e.message}")
        }
        
        println("\nTesting CORRECT parameter order:")
        try {
            val responseBuffer = ByteArray(200)
            
            // CORRECT ORDER:
            val correctLength = responder.writeMessage(
                responseBuffer, 0,        // ✓ Response buffer as message buffer
                ByteArray(0), 0, 0        // ✓ Empty array as payload
            )
            
            val response = responseBuffer.copyOf(correctLength)
            println("CORRECT order succeeded: ${response.size} bytes")
            assertEquals("Response should be XX message 2 size", XX_MESSAGE_2_SIZE, response.size)
            
        } catch (e: Exception) {
            println("CORRECT order failed unexpectedly: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        
        initiator.destroy()
        responder.destroy()
    }
    
    /**
     * Test the exact buffer calculation used in NoiseSession.kt
     */
    @Test
    fun testBufferCalculationIssue() {
        println("=== Testing Buffer Calculation Issue ===")
        
        // From NoiseSession.kt:
        // val responseBuffer = ByteArray(expectedSize + MAX_PAYLOAD_SIZE)
        // where MAX_PAYLOAD_SIZE = 256
        // and expectedSize = XX_MESSAGE_2_SIZE = 80
        // So buffer size should be 80 + 256 = 336
        
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        responder.localKeyPair!!.generateKeyPair()
        responder.start()
        
        // Process dummy message 1
        val mockMessage1 = ByteArray(32) { it.toByte() }
        val payloadBuffer = ByteArray(256)
        responder.readMessage(mockMessage1, 0, mockMessage1.size, payloadBuffer, 0)
        
        // Test the exact buffer size from NoiseSession.kt
        val MAX_PAYLOAD_SIZE = 256
        val expectedSize = XX_MESSAGE_2_SIZE
        val bufferSize = expectedSize + MAX_PAYLOAD_SIZE
        
        println("Testing buffer size from NoiseSession.kt: $bufferSize bytes")
        println("Expected response size: $expectedSize bytes")
        println("MAX_PAYLOAD_SIZE: $MAX_PAYLOAD_SIZE bytes")
        
        try {
            val responseBuffer = ByteArray(bufferSize)
            
            val responseLength = responder.writeMessage(
                responseBuffer, 0,        // Correct: message buffer
                ByteArray(0), 0, 0        // Correct: empty payload
            )
            
            val response = responseBuffer.copyOf(responseLength)
            println("SUCCESS: Generated response ${response.size} bytes with buffer size $bufferSize")
            
            assertEquals("Response should match XX message 2 size", XX_MESSAGE_2_SIZE, response.size)
            
        } catch (e: Exception) {
            println("FAILED with NoiseSession.kt buffer calculation: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        
        responder.destroy()
    }
}
