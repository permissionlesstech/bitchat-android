package com.bitchat.android.nostr

import android.os.Build
import com.bitchat.android.model.ReadReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class NostrTransportAdmissionTest {
    @Test
    fun `cancelled transport scope completes admission exactly once as retryable`() {
        val job = Job().apply { cancel() }
        val transport = NostrTransport(
            context = RuntimeEnvironment.getApplication(),
            transportScope = CoroutineScope(Dispatchers.Unconfined + job)
        )
        val admissions = mutableListOf<NostrSendAdmission>()

        transport.sendPrivateMessage(
            content = "hello",
            to = "aa".repeat(32),
            recipientNickname = "peer",
            messageID = "cancelled",
            completion = admissions::add
        )

        assertEquals(listOf(NostrSendAdmission.RETRYABLE), admissions)
    }

    @Test
    fun `already expired payload is terminal before identity or relay work`() {
        val transport = NostrTransport(
            context = RuntimeEnvironment.getApplication(),
            transportScope = CoroutineScope(Dispatchers.Unconfined + Job())
        )
        val admissions = mutableListOf<NostrSendAdmission>()

        transport.sendPrivateMessage(
            content = "expired",
            to = "aa".repeat(32),
            recipientNickname = "peer",
            messageID = "expired",
            expiresAtSeconds = 0uL,
            completion = admissions::add
        )

        assertEquals(listOf(NostrSendAdmission.TERMINAL_FAILED), admissions)
    }

    @Test
    fun `invocation before relay reset cannot enqueue when coroutine resumes after reset`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val relayManager = NostrRelayManager(scope)
        val setupReset = relayManager.discardForAccountReset()
        relayManager.completeAccountReset(setupReset)
        val oldGeneration = relayManager.accountGenerationForTesting()
        val transport = NostrTransport(
            context = RuntimeEnvironment.getApplication(),
            transportScope = scope,
            relayManager = relayManager
        )

        transport.sendDeliveryAckGeohash(
            messageID = "old-account-message",
            toRecipientHex = "22".repeat(32),
            fromIdentity = NostrIdentity.fromPrivateKey("11".repeat(32))
        )
        val resetToken = relayManager.discardForAccountReset()
        scope.advanceUntilIdle()

        assertEquals(oldGeneration + 1, relayManager.accountGenerationForTesting())
        assertEquals(0, relayManager.queuedEventCountForTesting())
        assertEquals(0, relayManager.pendingGiftWrapCountForTesting())
        relayManager.completeAccountReset(resetToken)
    }

    @Test
    fun `account reset clears throttled reads and invalidates their delayed work`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val relayManager = NostrRelayManager(scope)
        val setupReset = relayManager.discardForAccountReset()
        relayManager.completeAccountReset(setupReset)
        val transport = NostrTransport(
            context = RuntimeEnvironment.getApplication(),
            transportScope = scope,
            relayManager = relayManager
        )

        transport.sendReadReceipt(ReadReceipt("first"), "aa".repeat(32))
        transport.sendReadReceipt(ReadReceipt("second"), "aa".repeat(32))
        assertEquals(1, transport.activeReadCountForTesting())
        assertEquals(1, transport.queuedReadCountForTesting())

        transport.discardForAccountReset()
        val resetToken = relayManager.discardForAccountReset()
        scope.advanceUntilIdle()

        assertEquals(0, transport.activeReadCountForTesting())
        assertEquals(0, transport.queuedReadCountForTesting())
        assertEquals(0, relayManager.queuedEventCountForTesting())
        relayManager.completeAccountReset(resetToken)
    }

    @Test
    fun `stale transport reset cannot reopen admission`() {
        val transport = NostrTransport(
            context = RuntimeEnvironment.getApplication(),
            transportScope = CoroutineScope(Dispatchers.Unconfined + Job())
        )
        val firstReset = transport.discardForAccountReset()
        val secondReset = transport.discardForAccountReset()
        val admissions = mutableListOf<NostrSendAdmission>()

        assertEquals(false, transport.completeAccountReset(firstReset))
        transport.sendPrivateMessage(
            content = "blocked",
            to = "aa".repeat(32),
            recipientNickname = "peer",
            messageID = "stale-reset",
            completion = admissions::add
        )
        assertEquals(listOf(NostrSendAdmission.RETRYABLE), admissions)

        assertEquals(true, transport.completeAccountReset(secondReset))
    }
}
