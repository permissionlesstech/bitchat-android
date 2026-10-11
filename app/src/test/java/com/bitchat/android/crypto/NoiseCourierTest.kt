package com.bitchat.android.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.noise.NoiseEncryptionService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class NoiseCourierTest {
    @Test
    fun `Noise X seal authenticates sender and rejects tampering`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("noise-courier-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val service = NoiseEncryptionService(
            context,
            SecureIdentityStateManager(prefs, testOnly = true)
        )
        val payload = byteArrayOf(1, 0, 1, 65, 1, 1, 66)

        val sealed = service.sealCourierPayload(payload, service.getStaticPublicKeyData())
        val (senderKey, opened) = service.openCourierPayload(sealed)

        assertEquals(payload.size + 96, sealed.size)
        assertArrayEquals(service.getStaticPublicKeyData(), senderKey)
        assertArrayEquals(payload, opened)

        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { service.openCourierPayload(sealed) }
    }
}
