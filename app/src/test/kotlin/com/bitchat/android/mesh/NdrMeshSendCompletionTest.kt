package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.NdrFeatureGate
import com.bitchat.android.noise.AuthenticatedNoiseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class NdrMeshSendCompletionTest {
    @Before
    fun enableNdr() = NdrFeatureGate.setEnabledForTests(true)

    @After
    fun disableNdr() = NdrFeatureGate.setEnabledForTests(false)

    @Test
    fun `cancelled send scope completes once without transport admission`() = runTest {
        for (bluetooth in listOf(true, false)) {
            val job = Job().apply { cancel() }
            var attempted = false
            val sender = sender(CoroutineScope(StandardTestDispatcher(testScheduler) + job), bluetooth) {
                attempted = true
            }
            val results = mutableListOf<Boolean>()

            sender(results::add)
            runCurrent()

            assertEquals(listOf(false), results)
            assertFalse(attempted)
        }
    }

    @Test
    fun `cancellation before scheduled send starts completes once`() = runTest {
        for (bluetooth in listOf(true, false)) {
            val job = Job()
            var attempted = false
            val sender = sender(CoroutineScope(StandardTestDispatcher(testScheduler) + job), bluetooth) {
                attempted = true
            }
            val results = mutableListOf<Boolean>()

            sender(results::add)
            job.cancel()
            runCurrent()

            assertEquals(listOf(false), results)
            assertFalse(attempted)
        }
    }

    @Test
    fun `synchronous transport failure releases pending admission`() = runTest {
        for (bluetooth in listOf(true, false)) {
            var attempted = false
            val sender = sender(this, bluetooth) {
                attempted = true
                throw IllegalStateException("Synthetic transport failure")
            }
            val results = mutableListOf<Boolean>()

            sender(results::add)
            runCurrent()

            assertTrue(attempted)
            assertEquals(listOf(false), results)
        }
    }

    @Test
    fun `normal coroutine completion waits for asynchronous transport result`() = runTest {
        for (bluetooth in listOf(true, false)) {
            var transportCompletion: ((Boolean) -> Unit)? = null
            val sender = sender(this, bluetooth) { transportCompletion = it }
            val results = mutableListOf<Boolean>()

            sender(results::add)
            runCurrent()
            assertTrue(results.isEmpty())

            requireNotNull(transportCompletion)(true)
            requireNotNull(transportCompletion)(false)
            assertEquals(listOf(true), results)
        }
    }

    private fun sender(
        scope: CoroutineScope,
        bluetooth: Boolean,
        handoff: ((Boolean) -> Unit) -> Unit
    ): ((Boolean) -> Unit) -> Unit {
        val route = NdrMeshRoute(
            transportId = if (bluetooth) "BLE" else "test-wifi",
            peerID = "22".repeat(8),
            authenticatedSession = AuthenticatedNoiseSession(ByteArray(32) { 2 }, ByteArray(32) { 3 }),
            transportTarget = NdrTransportTarget("synthetic-endpoint", "generation-1")
        )
        val encryption = mock<EncryptionService>()
        whenever(encryption.encryptForSession(any(), any(), any())).thenReturn(byteArrayOf(1))
        whenever(encryption.signData(any())).thenReturn(ByteArray(64) { 4 })

        // Exercise the real send methods without constructing radios or identity stores.
        if (bluetooth) {
            val service = mock<BluetoothMeshService>(defaultAnswer = CALLS_REAL_METHODS)
            val transport = mock<BluetoothConnectionManager>()
            setField(service, "serviceScope", scope)
            setField(service, "connectionManager", transport)
            setField(service, "encryptionService", encryption)
            setField(service, "myPeerID", "11".repeat(8))
            doReturn(route).whenever(service).currentNdrRoute(route.peerID, route.transportId)
            doAnswer { invocation ->
                handoff(invocation.getArgument(3))
                null
            }.whenever(transport).sendPacketToNdrTargetConfirmed(any(), any(), any(), any())
            return { completion -> service.sendNdrEvent(route, "synthetic invite", { true }, completion) }
        }

        val core = mock<MeshCore>(defaultAnswer = CALLS_REAL_METHODS)
        val transport = mock<MeshTransport>()
        whenever(transport.id).thenReturn(route.transportId)
        setField(core, "scope", scope)
        setField(core, "transport", transport)
        setField(core, "encryptionService", encryption)
        setField(core, "myPeerID", "11".repeat(8))
        doReturn(route).whenever(core).currentNdrRoute(route.peerID, route.transportId)
        doAnswer { invocation ->
            handoff(invocation.getArgument(4))
            null
        }.whenever(transport).sendPacketToNdrTargetConfirmed(any(), any(), any(), any(), any())
        return { completion -> core.sendNdrEvent(route, "synthetic invite", { true }, completion) }
    }

    private fun setField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
