package com.bitchat.android.nostr

import com.bitchat.android.mesh.NdrMeshRoute
import com.bitchat.android.mesh.NdrTransportTarget
import com.bitchat.android.noise.AuthenticatedNoiseSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NdrInviteRetryCoordinatorTest {
    @Test
    fun `retries rejected admission four times with bounded backoff`() = runTest {
        val attemptTimes = mutableListOf<Long>()
        val admitted = mutableListOf<NdrInviteRetryRequest>()
        val request = request(generation = "generation-1")
        val coordinator = NdrInviteRetryCoordinator(
            scope = this,
            isStillValid = { true },
            send = { _, completion ->
                attemptTimes += testScheduler.currentTime
                completion(false)
            },
            onAdmitted = admitted::add
        )

        coordinator.start(request)
        advanceUntilIdle()
        coordinator.start(request.copy())
        advanceUntilIdle()

        assertEquals(listOf(0L, 250L, 750L, 1_750L, 3_750L), attemptTimes)
        assertTrue(admitted.isEmpty())
    }

    @Test
    fun `same token cannot reset retry budget`() = runTest {
        var attempts = 0
        val coordinator = NdrInviteRetryCoordinator(
            scope = this,
            isStillValid = { true },
            send = { _, completion ->
                attempts += 1
                completion(false)
            },
            onAdmitted = {}
        )
        val first = request(generation = "generation-1")
        val duplicate = first.copy()

        coordinator.start(first)
        runCurrent()
        coordinator.start(duplicate)
        advanceUntilIdle()

        assertEquals(5, attempts)
    }

    @Test
    fun `stale generation invite or favorite cancels before delayed retry`() = runTest {
        var attempts = 0
        var stillValid = true
        val coordinator = NdrInviteRetryCoordinator(
            scope = this,
            isStillValid = { stillValid },
            send = { _, completion ->
                attempts += 1
                completion(false)
            },
            onAdmitted = {}
        )

        coordinator.start(request(generation = "generation-1"))
        runCurrent()
        stillValid = false
        advanceUntilIdle()

        assertEquals(1, attempts)
    }

    @Test
    fun `replacement generation gets a fresh token while old retry stays cancelled`() = runTest {
        val attemptedGenerations = mutableListOf<Any>()
        val coordinator = NdrInviteRetryCoordinator(
            scope = this,
            isStillValid = { true },
            send = { request, completion ->
                attemptedGenerations +=
                    request.token.route.transportTarget.generationToken
                completion(false)
            },
            onAdmitted = {}
        )

        coordinator.start(request(generation = "generation-1"))
        runCurrent()
        coordinator.start(request(generation = "generation-2"))
        advanceUntilIdle()

        assertEquals(1, attemptedGenerations.count { it == "generation-1" })
        assertEquals(5, attemptedGenerations.count { it == "generation-2" })
    }

    @Test
    fun `successful admission stops retrying`() = runTest {
        var attempts = 0
        val admitted = mutableListOf<NdrInviteRetryRequest>()
        val coordinator = NdrInviteRetryCoordinator(
            scope = this,
            isStillValid = { true },
            send = { _, completion ->
                attempts += 1
                completion(attempts == 2)
            },
            onAdmitted = admitted::add
        )

        coordinator.start(request(generation = "generation-1"))
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(1, admitted.size)
    }

    private fun request(generation: String): NdrInviteRetryRequest =
        NdrInviteRetryRequest(
            token = NdrInviteRetryToken(
                peerID = "peer-a",
                peerPubkeyHex = "ab".repeat(32),
                inviteEventId = "cd".repeat(32),
                route = NdrMeshRoute(
                    transportId = "BLE",
                    peerID = "peer-a",
                    authenticatedSession = AuthenticatedNoiseSession(
                        remoteStaticKey = ByteArray(32) { 1 },
                        sessionToken = ByteArray(32) { 2 }
                    ),
                    transportTarget = NdrTransportTarget(
                        endpointId = "endpoint-a",
                        generationToken = generation
                    )
                )
            ),
            eventJson = """{"id":"${"cd".repeat(32)}"}"""
        )
}
