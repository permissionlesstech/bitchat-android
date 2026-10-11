package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.CourierEnvelope
import com.bitchat.android.services.ConversationStorageCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class CourierStoreTest {
    private val now = 2_000_000L
    private val recipient = ByteArray(32) { 7 }
    private val favorite = ByteArray(32) { 8 }
    private val verified = ByteArray(32) { 9 }
    private val cipher = TestCipher(0x5a)

    @Test
    fun `direct pickup remains retryable until explicitly removed`() {
        val store = newStore()
        val envelope = envelope(1)
        assertTrue(store.deposit(envelope, favorite, CourierDepositTier.FAVORITE))

        assertEquals(1, store.copiesForRecipient(recipient).size)
        assertEquals(1, store.copiesForRecipient(recipient).size)
        assertTrue(store.remove(envelope))
        assertTrue(store.copiesForRecipient(recipient).isEmpty())
    }

    @Test
    fun `verified pool evicts oldest verified and never favorites`() {
        val store = newStore()
        repeat(20) { index ->
            assertTrue(store.deposit(envelope(index), ByteArray(32) { index.toByte() }, CourierDepositTier.VERIFIED))
        }
        val favoriteEnvelope = envelope(100)
        assertTrue(store.deposit(favoriteEnvelope, favorite, CourierDepositTier.FAVORITE))
        assertTrue(store.deposit(envelope(200), ByteArray(32) { 100 }, CourierDepositTier.VERIFIED))

        val copies = reloaded().copiesForRecipient(recipient)
        assertEquals(21, copies.size)
        assertTrue(copies.any { it.ciphertext.contentEquals(favoriteEnvelope.ciphertext) })
    }

    @Test
    fun `spray history and copy budget survive restart`() {
        val store = newStore()
        assertTrue(store.deposit(envelope(1, copies = 4u), verified, CourierDepositTier.VERIFIED))
        val courier = ByteArray(32) { 11 }

        val spray = store.sprayCopiesFor(courier).single()
        assertEquals(2u.toUByte(), spray.copies)
        assertTrue(store.commitSpray(spray, courier))
        assertTrue(reloaded().sprayCopiesFor(courier).isEmpty())
    }

    @Test
    fun `failed spray preview does not consume custody`() {
        val store = newStore()
        assertTrue(store.deposit(envelope(1, copies = 4u), verified, CourierDepositTier.VERIFIED))
        val courier = ByteArray(32) { 12 }

        assertEquals(2u.toUByte(), store.sprayCopiesFor(courier).single().copies)
        assertEquals(2u.toUByte(), reloaded().sprayCopiesFor(courier).single().copies)
    }

    @Test
    fun `concurrent spray reservations cannot over allocate custody`() {
        val store = newStore()
        assertTrue(store.deposit(envelope(1, copies = 4u), verified, CourierDepositTier.VERIFIED))
        val firstCourier = ByteArray(32) { 12 }
        val secondCourier = ByteArray(32) { 13 }
        val thirdCourier = ByteArray(32) { 14 }

        val first = store.sprayCopiesFor(firstCourier).single()
        val second = store.sprayCopiesFor(secondCourier).single()

        assertEquals(2u.toUByte(), first.copies)
        assertEquals(1u.toUByte(), second.copies)
        assertTrue(store.sprayCopiesFor(firstCourier).isEmpty())
        assertTrue(store.sprayCopiesFor(thirdCourier).isEmpty())
        assertTrue(store.commitSpray(second, secondCourier))
        assertTrue(store.commitSpray(first, firstCourier))
        assertTrue(reloaded().sprayCopiesFor(thirdCourier).isEmpty())
    }

    @Test
    fun `cancelled spray reservation makes its copies eligible again`() {
        val store = newStore()
        assertTrue(store.deposit(envelope(1, copies = 4u), verified, CourierDepositTier.VERIFIED))
        val courier = ByteArray(32) { 15 }

        val first = store.sprayCopiesFor(courier).single()

        assertTrue(store.cancelSpray(first, courier))
        assertEquals(2u.toUByte(), store.sprayCopiesFor(courier).single().copies)
    }

    @Test
    fun `stored prekey envelope retains its prekey id through spray`() {
        val store = newStore()
        val envelope = envelope(1, copies = 4u).copy(prekeyID = 0x11223344u)
        assertTrue(store.deposit(envelope, verified, CourierDepositTier.VERIFIED))

        val spray = store.sprayCopiesFor(ByteArray(32) { 16 }).single()

        assertEquals(0x11223344u, spray.prekeyID)
    }

    @Test
    fun `wipe deletes sealed custody and destroys key`() {
        val store = newStore()
        assertTrue(store.deposit(envelope(1), favorite, CourierDepositTier.FAVORITE))
        store.wipe()

        assertFalse(File(RuntimeEnvironment.getApplication().filesDir, "courier-store.sealed").exists())
        assertTrue(cipher.destroyed)
    }

    private fun envelope(id: Int, copies: UByte = 1u) = CourierEnvelope(
        recipientTag = CourierEnvelope.recipientTag(recipient, CourierEnvelope.epochDay(now)),
        expiry = (now + 60_000).toULong(),
        ciphertext = byteArrayOf((id ushr 8).toByte(), id.toByte()),
        copies = copies
    )

    private fun newStore(): CourierStore {
        File(RuntimeEnvironment.getApplication().filesDir, "courier-store.sealed").delete()
        return reloaded()
    }

    private fun reloaded() = CourierStore(RuntimeEnvironment.getApplication(), cipher) { now }

    private class TestCipher(private val mask: Int) : ConversationStorageCipher {
        var destroyed = false
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) =
            plaintext.map { (it.toInt() xor mask).toByte() }.toByteArray()
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray) = encrypt(envelope, associatedData)
        override fun destroyKey() { destroyed = true }
    }
}
