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
