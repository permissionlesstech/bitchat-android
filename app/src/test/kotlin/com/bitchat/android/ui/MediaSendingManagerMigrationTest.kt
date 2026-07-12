package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PreparedPrivateMediaTransfer
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.mesh.PrivateMediaWireMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class MediaSendingManagerMigrationTest {
    private val peerID = "8877665544332211"
    private lateinit var state: ChatState
    private lateinit var mesh: MeshService
    private lateinit var manager: MediaSendingManager
    private lateinit var file: File

    @Before
    fun setup() {
        state = ChatState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        state.setNickname("me")
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("0011223344556677")
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "old peer"))
        manager = MediaSendingManager(
            state,
            MessageManager(state),
            mock(),
            getMeshService = { mesh }
        )
        file = kotlin.io.path.createTempFile("private-media", ".jpg").toFile().apply {
            writeBytes(ByteArray(128) { it.toByte() })
        }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `preflight rejection creates no local echo mapping or send`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.Rejected("too many fragments"))

        manager.sendImageNote(peerID, null, file.absolutePath)

        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        assertEquals(null, manager.legacyPrivateMediaConsent.value)
        verify(mesh, never()).sendFilePrivate(any(), any())
    }

    @Test
    fun `missing Noise session initiates one handshake without echo prompt or media send`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.NeedsHandshake)

        manager.sendImageNote(peerID, null, file.absolutePath)

        verify(mesh, times(1)).initiateNoiseHandshake(peerID)
        verify(mesh, never()).sendFilePrivate(any(), any())
        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        assertEquals(null, manager.legacyPrivateMediaConsent.value)
    }

    @Test
    fun `legacy consent is one shot rechecks policy and echoes only after approval`() {
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.RequiresLegacyConsent("relay-visible warning"))
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(true)))
            .thenAnswer { invocation ->
                val transferId = invocation.getArgument<String>(2)
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = transferId,
                        // Simulate the capability becoming authenticated while
                        // the consent dialog was open: recheck must upgrade.
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            }

        manager.sendImageNote(peerID, null, file.absolutePath)

        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        val request = manager.legacyPrivateMediaConsent.value
        assertNotNull(request)

        manager.approveLegacyPrivateMedia(request!!.requestId)
        manager.approveLegacyPrivateMedia(request.requestId)

        assertEquals(1, commits.get())
        assertEquals(1, state.privateChats.value[peerID]?.size)
        assertEquals(null, manager.legacyPrivateMediaConsent.value)
        verify(mesh, times(1)).prepareFilePrivate(eq(peerID), any(), any(), eq(false))
        verify(mesh, times(1)).prepareFilePrivate(eq(peerID), any(), any(), eq(true))
    }

    @Test
    fun `prepared transfer ID mismatch aborts before local echo or commit`() {
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = "wrong-transfer-id",
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            )

        manager.sendImageNote(peerID, null, file.absolutePath)

        assertEquals(0, commits.get())
        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
    }

    @Test
    fun `cancelled consent cannot later send or echo`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.RequiresLegacyConsent("relay-visible warning"))

        manager.sendImageNote(peerID, null, file.absolutePath)
        val request = manager.legacyPrivateMediaConsent.value!!
        manager.cancelLegacyPrivateMedia(request.requestId)
        manager.approveLegacyPrivateMedia(request.requestId)

        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        verify(mesh, never()).prepareFilePrivate(eq(peerID), any(), any(), eq(true))
    }
}
