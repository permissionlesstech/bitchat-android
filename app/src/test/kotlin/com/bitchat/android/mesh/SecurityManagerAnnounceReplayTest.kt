package com.bitchat.android.mesh

import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.mock

class SecurityManagerAnnounceReplayTest {
    @Test
    fun `stale announce beyond 5-minute replay window is rejected`() {
        val encryptionService = mock<EncryptionService>()
        val manager = SecurityManager(encryptionService, "1111111111111111")
        val staleTimestamp = (System.currentTimeMillis() - AppConstants.Security.MESSAGE_TIMEOUT_MS - 60_000L).toULong()
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            senderID = ByteArray(8) { 0x22 },
            timestamp = staleTimestamp,
            payload = byteArrayOf(0x01),
            ttl = AppConstants.MESSAGE_TTL_HOPS
        )

        assertFalse(manager.validatePacket(packet, "2222222222222222"))
    }

    @Test
    fun `future-dated announce beyond 5-minute replay window is rejected`() {
        val encryptionService = mock<EncryptionService>()
        val manager = SecurityManager(encryptionService, "1111111111111111")
        val futureTimestamp = (System.currentTimeMillis() + AppConstants.Security.MESSAGE_TIMEOUT_MS + 60_000L).toULong()
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            senderID = ByteArray(8) { 0x22 },
            timestamp = futureTimestamp,
            payload = byteArrayOf(0x01),
            ttl = AppConstants.MESSAGE_TTL_HOPS
        )

        assertFalse(manager.validatePacket(packet, "2222222222222222"))
    }
}
