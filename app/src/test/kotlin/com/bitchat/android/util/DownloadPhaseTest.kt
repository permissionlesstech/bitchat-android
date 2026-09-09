package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase crosses a WorkManager `Data` boundary as a plain string, so it has to survive a
 * round trip and degrade sensibly when it does not.
 */
class DownloadPhaseTest {

    @Test
    fun `every phase survives the round trip through its key`() {
        ApkDownloader.DownloadPhase.entries.forEach { phase ->
            assertEquals(phase, ApkDownloader.DownloadPhase.fromKey(phase.name))
        }
    }

    @Test
    fun `an absent or unrecognised key falls back to the transfer`() {
        // Work enqueued by an older build, or progress read before the first phase is published.
        assertEquals(
            ApkDownloader.DownloadPhase.Transferring,
            ApkDownloader.DownloadPhase.fromKey(null)
        )
        assertEquals(
            ApkDownloader.DownloadPhase.Transferring,
            ApkDownloader.DownloadPhase.fromKey("SomePhaseFromAFutureBuild")
        )
    }

    @Test
    fun `phase keys from queued work created by the old downloader still map correctly`() {
        assertEquals(
            ApkDownloader.DownloadPhase.SelectingSource,
            ApkDownloader.DownloadPhase.fromKey("ResolvingRelease")
        )
        assertEquals(
            ApkDownloader.DownloadPhase.VerifyingSignature,
            ApkDownloader.DownloadPhase.fromKey("VerifyingChecksum")
        )
    }

    @Test
    fun `a queued retry is not reported as a connectivity wait`() {
        // WorkManager returns a retry to ENQUEUED for the backoff even while the device is online,
        // so attempt count is the only thing separating the two waits.
        assertEquals(
            ApkDownloader.DownloadPhase.AwaitingConnectivity,
            queuedPhase(runAttemptCount = 0)
        )
        assertEquals(ApkDownloader.DownloadPhase.Retrying, queuedPhase(runAttemptCount = 1))
        assertEquals(ApkDownloader.DownloadPhase.Retrying, queuedPhase(runAttemptCount = 2))
    }

    @Test
    fun `only the transfer claims measurable progress`() {
        assertTrue(ApkDownloader.DownloadPhase.Transferring.hasMeasurableProgress)

        val unmeasurable = ApkDownloader.DownloadPhase.entries
            .filterNot { it == ApkDownloader.DownloadPhase.Transferring }
        assertFalse(unmeasurable.isEmpty())
        unmeasurable.forEach {
            assertFalse("$it has no percentage to report", it.hasMeasurableProgress)
        }
    }
}
