package com.bitchat.android.ui

import com.bitchat.android.R
import com.bitchat.android.util.ApkDownloader
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The row body and its trailing icon button are two doors into the same actions, and the trailing
 * buttons no longer carry visible labels. If this mapping is wrong the affordance simply vanishes,
 * so each status is pinned down here rather than left to the composable.
 */
class PrepareRowTapActionTest {

    private fun ready(
        variant: ShareableApkVariant,
        source: UniversalApkManager.ApkSource = UniversalApkManager.ApkSource.INSTALLED
    ) = ApkPreparationStatus.Ready(
        version = "1.7.5",
        sizeMB = 12,
        source = source,
        variant = variant
    )

    private fun error() = ApkPreparationStatus.Error(
        ApkFailureMessage(messageRes = R.string.prepare_apk_error_generic)
    )

    @Test
    fun `an arm64-only build offers the universal download`() {
        // The only entry point besides the trailing icon, which has no label to explain itself.
        assertEquals(
            PrepareRowTapAction.OpenPrepareDialog,
            prepareRowTapAction(ready(ShareableApkVariant.ARM64))
        )
    }

    @Test
    fun `a standalone installed universal apk can optionally be replaced from github`() {
        assertEquals(
            PrepareRowTapAction.OpenPrepareDialog,
            prepareRowTapAction(ready(ShareableApkVariant.UNIVERSAL))
        )
    }

    @Test
    fun `a current downloaded universal apk leaves the row inert`() {
        assertNull(
            prepareRowTapAction(
                ready(ShareableApkVariant.UNIVERSAL, UniversalApkManager.ApkSource.DOWNLOADED)
            )
        )
    }

    @Test
    fun `a stale downloaded apk opens the update dialog without blocking sharing`() {
        assertEquals(
            PrepareRowTapAction.OpenPrepareDialog,
            prepareRowTapAction(
                ready(ShareableApkVariant.UNIVERSAL, UniversalApkManager.ApkSource.DOWNLOADED),
                ApkReleaseStatus.Known(
                    version = "1.7.6",
                    sizeMB = 24,
                    isNewerThanSharedApk = true,
                    fromStaleCache = false
                )
            )
        )
    }

    @Test
    fun `a missing apk asks before spending the bytes`() {
        assertEquals(
            PrepareRowTapAction.OpenPrepareDialog,
            prepareRowTapAction(ApkPreparationStatus.NotDownloaded)
        )
    }

    @Test
    fun `an interrupted or failed download resumes without asking again`() {
        // The user already consented to the download; re-prompting would be noise.
        assertEquals(
            PrepareRowTapAction.StartDownload,
            prepareRowTapAction(
                ApkPreparationStatus.Resumable(
                    43,
                    ApkFailureMessage(R.string.prepare_apk_download_interrupted)
                )
            )
        )
        assertEquals(
            PrepareRowTapAction.StartDownload,
            prepareRowTapAction(error())
        )
    }

    @Test
    fun `a download in flight is not restartable by tapping the row`() {
        // Otherwise a stray tap behind the stop button would queue a second download.
        ApkDownloader.DownloadPhase.entries.forEach { phase ->
            assertNull(prepareRowTapAction(ApkPreparationStatus.Downloading(phase)))
        }
        assertNull(prepareRowTapAction(ApkPreparationStatus.Loading))
    }

}
