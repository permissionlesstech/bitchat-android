package com.bitchat.android.net

import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorCircuitHealthPolicyTest {

    private val policy = TorCircuitHealthPolicy()

    // Verbatim from the report in issue #610, trimmed at the scrubbed detail.
    private val obtainExitCircuit =
        "Arti: ERROR: Failed to connect through Tor: Error { detail: ObtainExitCircuit " +
            "{ exit_ports: [scrubbed], cause: RequestFailed(RetryError { doing: " +
            "\"find or build a tunnel\", errors: [...] }) } }"

    private val socksError =
        "Arti: ERROR: SOCKS connection error: tor: error connecting to Tor: " +
            "Failed to obtain exit circuit for ports [scrubbed]"

    @Test
    fun `recognises the failures arti actually logs`() {
        assertTrue(policy.isCircuitFailure(obtainExitCircuit))
        assertTrue(policy.isCircuitFailure(socksError))
    }

    @Test
    fun `does not treat bootstrap progress as a failure`() {
        assertFalse(policy.isCircuitFailure("Sufficiently bootstrapped; system SOCKS now functional"))
        assertFalse(policy.isCircuitFailure("We have found that guard [scrubbed] is usable."))
        assertFalse(policy.isCircuitFailure("AMEx: state changed to Running"))
    }

    @Test
    fun `does not treat a local SOCKS protocol fault as a circuit failure`() {
        // handle_socks_connection wraps every one of its errors in "SOCKS connection error",
        // including these, which are raised before a circuit is ever attempted. Anything on the
        // device that speaks SOCKS badly to the local port must not read as a dead Tor.
        listOf(
            "Arti: ERROR: SOCKS connection error: Invalid SOCKS handshake",
            "Arti: ERROR: SOCKS connection error: Invalid SOCKS request",
            "Arti: ERROR: SOCKS connection error: Unsupported SOCKS version: 4",
            "Arti: ERROR: SOCKS connection error: Unsupported SOCKS command: 2"
        ).forEach { line ->
            assertFalse(line, policy.isCircuitFailure(line))
        }
    }

    @Test
    fun `a burst of simultaneous failures does not downgrade`() {
        // The report shows three threads failing inside the same millisecond. That is an
        // ordinary circuit loss, not a dead connection.
        val t = 500_000L
        repeat(20) {
            assertFalse(
                "a same-instant burst must not move the indicator",
                policy.onCircuitFailure(t)
            )
        }
        assertFalse(policy.onCircuitFailure(t + 1_000L))
    }

    @Test
    fun `failures that persist past the minimum span downgrade`() {
        val start = 500_000L
        assertFalse(policy.onCircuitFailure(start))
        assertFalse(policy.onCircuitFailure(start + 5_000L))
        assertFalse(policy.onCircuitFailure(start + 10_000L))

        // Fourth failure, and by now the outage has lasted past the minimum span.
        assertTrue(
            policy.onCircuitFailure(start + AppConstants.Tor.CIRCUIT_FAILURE_MIN_SPAN_MS)
        )
    }

    @Test
    fun `enough elapsed time alone is not enough`() {
        val start = 500_000L
        assertFalse(policy.onCircuitFailure(start))
        // Long span, but only two failures in it.
        assertFalse(policy.onCircuitFailure(start + AppConstants.Tor.CIRCUIT_FAILURE_WINDOW_MS))
    }

    @Test
    fun `failures spread beyond the window start a fresh tally`() {
        var t = 500_000L
        repeat(10) {
            assertFalse(
                "occasional failures far apart are normal Tor behaviour",
                policy.onCircuitFailure(t)
            )
            t += AppConstants.Tor.CIRCUIT_FAILURE_WINDOW_MS + 1_000L
        }
    }

    @Test
    fun `reset clears an in-progress tally`() {
        val start = 500_000L
        policy.onCircuitFailure(start)
        policy.onCircuitFailure(start + 5_000L)
        policy.onCircuitFailure(start + 10_000L)

        policy.reset()

        // Without the reset this next one would have been the fourth and would downgrade.
        assertFalse(
            policy.onCircuitFailure(start + AppConstants.Tor.CIRCUIT_FAILURE_MIN_SPAN_MS)
        )
    }

    @Test
    fun `a backwards clock jump restarts the window instead of downgrading`() {
        val start = 500_000L
        policy.onCircuitFailure(start)
        policy.onCircuitFailure(start + 5_000L)
        policy.onCircuitFailure(start + 10_000L)

        assertFalse(policy.onCircuitFailure(start - 60_000L))
    }

    @Test
    fun `a sustained outage downgrades exactly once per tally`() {
        val start = 500_000L
        var downgrades = 0
        var t = start
        repeat(4) {
            if (policy.onCircuitFailure(t)) {
                downgrades++
                policy.reset()
            }
            t += 7_000L
        }
        assertTrue("a real outage must downgrade", downgrades == 1)
    }
}
