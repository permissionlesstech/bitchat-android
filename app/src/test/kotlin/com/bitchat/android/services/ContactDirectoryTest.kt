package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class ContactDirectoryTest {

    private lateinit var identityManager: SecureIdentityStateManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            "contact-directory-test-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        ContactDirectory.initialize(context) { null }
        ContactDirectory.identityManagerProvider = { identityManager }
    }

    @After
    fun tearDown() {
        ContactDirectory.identityManagerProvider = { SecureIdentityStateManager(it) }
    }

    @Test
    fun `an explicit connected peer resolves without scanning the whole mesh`() {
        val peerID = "1111222233334444"
        val mesh = mock<MeshService>()
        whenever(mesh.getPeerInfo(peerID)).thenReturn(PeerInfo(
            id = peerID, nickname = "fixture-name", isConnected = true,
            isDirectConnection = true, noisePublicKey = ByteArray(32) { 1 },
            signingPublicKey = null, isVerifiedNickname = false, lastSeen = 0L,
        ))
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "fixture-name"))
        ContactDirectory.initialize(RuntimeEnvironment.getApplication()) { mesh }

        val resolution = ContactDirectory.resolve(peerID)

        assertEquals(peerID, resolution.meshPeerID)
        assertEquals("fixture-name", resolution.displayName)
        verify(mesh, never()).getPeerNicknames()
    }

    @Test
    fun `offline contact resolves display name from cached fingerprint nickname`() {
        val fingerprint = "ab".repeat(32)
        identityManager.cacheFingerprintNickname(fingerprint, "Alice")

        val resolution = ContactDirectory.resolve("contact_$fingerprint")

        assertEquals("Alice", resolution.displayName)
        assertNull(resolution.meshPeerID)
    }

    @Test
    fun `offline contact without cached nickname has no display name`() {
        val fingerprint = "cd".repeat(32)

        val resolution = ContactDirectory.resolve("contact_$fingerprint")

        assertNull(resolution.displayName)
    }
}
