package com.bitchat.android.ui

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.feature.share.R
import com.bitchat.android.util.ApkDownloader
import com.bitchat.android.util.ApkDownloadFailureReason
import com.bitchat.android.util.GitHubReleaseClient
import com.bitchat.android.util.LatestReleaseProvider
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections

private fun syntheticString(resId: Int) = "string:$resId"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ApkDownloadViewModelTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = SyntheticStringsApplication(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `local apk is shareable before release metadata starts`() = runTest {
        val manager = managerWithLocalApk()
        val downloader = FakeDownloader()
        lateinit var viewModel: ApkDownloadViewModel
        val statusWhenMetadataStarted = CompletableDeferred<ApkPreparationStatus>()
        val metadata = object : LatestReleaseProvider {
            override suspend fun latestRelease(): Result<GitHubReleaseClient.ReleaseSnapshot> {
                statusWhenMetadataStarted.complete(viewModel.state.value.apkStatus)
                return Result.failure(IllegalStateException("synthetic offline response"))
            }
        }
        viewModel = ApkDownloadViewModel(application, manager, downloader, metadata)

        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val ready = awaitReady(viewModel)

        assertEquals("1.7.5", ready.version)
        assertTrue(statusWhenMetadataStarted.await() is ApkPreparationStatus.Ready)
    }

    @Test
    fun `notification cancellation returning idle restores shareable fallback`() = runTest {
        val manager = managerWithLocalApk()
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            manager,
            downloader,
            offlineMetadata()
        )

        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val originalReady = awaitReady(viewModel)
        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)

        val downloading = viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading
        assertSame(originalReady, downloading.shareableFallback)
        assertEquals(1, downloader.startCount)

        downloader.emit(ApkDownloader.DownloadState.Idle)
        assertEquals(originalReady, awaitReady(viewModel))
    }

    @Test
    fun `rate limits remain stable failures without countdowns`() = runTest {
        val manager = mock<UniversalApkManager>()
        whenever(manager.getCachedApkInfo()).thenReturn(null)
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            manager,
            downloader,
            offlineMetadata()
        )

        downloader.emit(
            ApkDownloader.DownloadState.Failed(
                reason = ApkDownloadFailureReason.RateLimited,
                messageArgs = listOf("GitHub Releases"),
                resumablePercent = null
            )
        )

        val failure = awaitError(viewModel).failure
        assertEquals(R.string.prepare_apk_error_rate_limited, failure.messageRes)
        assertEquals(listOf("GitHub Releases"), failure.messageArgs)
    }

    @Test
    fun `non-rate failures keep their own message`() = runTest {
        val manager = mock<UniversalApkManager>()
        whenever(manager.getCachedApkInfo()).thenReturn(null)
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            manager,
            downloader,
            offlineMetadata()
        )

        downloader.emit(
            ApkDownloader.DownloadState.Failed(
                reason = ApkDownloadFailureReason.AllSourcesFailed,
                messageArgs = emptyList(),
                resumablePercent = null
            )
        )

        val failure = awaitError(viewModel).failure
        assertEquals(R.string.prepare_apk_error_all_sources, failure.messageRes)
        assertEquals(emptyList<String>(), failure.messageArgs)
    }

    @Test
    fun `a download already running when the ViewModel starts still exposes the local apk`() =
        runTest {
            // Process death during a transfer leaves WorkManager running and the ViewModel fresh,
            // so the observer builds Downloading out of Loading and has no Ready to carry. Without
            // a fallback the row and both sharing actions vanish for the rest of the download.
            val manager = managerWithLocalApk()
            val downloader = FakeDownloader(
                ApkDownloader.DownloadState.Downloading(
                    progressPercent = 30,
                    phase = ApkDownloader.DownloadPhase.Transferring
                )
            )

            val viewModel = ApkDownloadViewModel(
                application,
                manager,
                downloader,
                offlineMetadata()
            )

            val restored = viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading
            assertNull(restored.shareableFallback)

            viewModel.onEvent(ApkUiEvent.CheckStatus)

            val adopted = awaitFallback(viewModel)
            assertEquals("1.7.5", adopted.version)
            assertEquals(UniversalApkManager.ApkSource.INSTALLED, adopted.source)
        }

    @Test
    fun `adopting a local apk never displaces the fallback a download already carries`() = runTest {
        val manager = managerWithLocalApk()
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            manager,
            downloader,
            offlineMetadata()
        )

        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val originalReady = awaitReady(viewModel)
        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)

        viewModel.onEvent(ApkUiEvent.CheckStatus)

        val downloading = viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading
        assertSame(originalReady, downloading.shareableFallback)
    }

    // --- Characterization (inventory C.6, K5-K15) ---

    @Test
    fun downloadUniversalClicked_opensDialogOnlyForInstalledOrUpdate() = runTest {
        // K5: an installed APK may be replaced by the universal one.
        val installed = ApkDownloadViewModel(
            application,
            managerWithLocalApk(),
            FakeDownloader(),
            offlineMetadata()
        )
        installed.onEvent(ApkUiEvent.CheckStatus)
        awaitReady(installed)
        installed.onEvent(ApkUiEvent.DownloadUniversalClicked)
        assertTrue(installed.state.value.showPrepareDialog)

        // A current downloaded APK has nothing to replace it with.
        val downloaded = ApkDownloadViewModel(
            application,
            managerWithLocalApk(source = UniversalApkManager.ApkSource.DOWNLOADED),
            FakeDownloader(),
            offlineMetadata()
        )
        downloaded.onEvent(ApkUiEvent.CheckStatus)
        awaitReady(downloaded)
        downloaded.onEvent(ApkUiEvent.DownloadUniversalClicked)
        assertFalse(downloaded.state.value.showPrepareDialog)
    }

    @Test
    fun startDownload_carriesReadyFallbackAndPartialProgress() = runTest {
        // K6
        val manager = managerWithLocalApk()
        whenever(manager.getPartialDownloadProgress()).thenReturn(42)
        val downloader = FakeDownloader(emitOnStart = false)
        val viewModel = ApkDownloadViewModel(application, manager, downloader, offlineMetadata())
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val ready = awaitReady(viewModel)

        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)

        val downloading = viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading
        assertEquals(ApkDownloader.DownloadPhase.SelectingSource, downloading.phase)
        assertSame(ready, downloading.shareableFallback)
        assertEquals(42, viewModel.state.value.downloadProgress)
        assertEquals(1, downloader.startCount)
    }

    @Test
    fun dialogFlags_setByOpeners_clearedByDismiss_confirmDownloadClosesAndStarts() = runTest {
        // K7
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithLocalApk(),
            downloader,
            offlineMetadata()
        )
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        awaitReady(viewModel)

        viewModel.onEvent(ApkUiEvent.DeleteClicked)
        assertTrue(viewModel.state.value.showDeleteDialog)
        viewModel.onEvent(ApkUiEvent.DismissDeleteDialog)
        assertFalse(viewModel.state.value.showDeleteDialog)

        viewModel.onEvent(ApkUiEvent.AppShareClicked)
        assertTrue(viewModel.state.value.showShareApkDialog)
        viewModel.onEvent(ApkUiEvent.DismissShareDialog)
        assertFalse(viewModel.state.value.showShareApkDialog)

        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        assertTrue(viewModel.state.value.showPrepareDialog)
        viewModel.onEvent(ApkUiEvent.DismissPrepareDialog)
        assertFalse(viewModel.state.value.showPrepareDialog)
        assertEquals(0, downloader.startCount)

        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)
        assertFalse(viewModel.state.value.showPrepareDialog)
        assertEquals(1, downloader.startCount)
    }

    @Test
    fun confirmDelete_deletesAndReturnsToMissing() = runTest {
        // K8: close, cancel the work, delete the file, then re-resolve - in that order.
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val manager = mock<UniversalApkManager>()
        var deleted = false
        whenever(manager.prepareLocalApkInfo()).thenAnswer {
            calls += "resolve"
            if (deleted) null else syntheticApkInfo()
        }
        whenever(manager.getPartialDownloadProgress()).thenReturn(null)
        whenever(manager.deleteCachedApk()).thenAnswer {
            calls += "delete"
            deleted = true
            true
        }
        val downloader = FakeDownloader(onCancel = { calls += "cancel" })
        val viewModel = ApkDownloadViewModel(application, manager, downloader, offlineMetadata())
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        awaitReady(viewModel)
        viewModel.onEvent(ApkUiEvent.DeleteClicked)

        viewModel.onEvent(ApkUiEvent.ConfirmDelete)

        assertFalse(viewModel.state.value.showDeleteDialog)
        awaitStatus(viewModel) { it is ApkPreparationStatus.NotDownloaded }
        assertEquals(listOf("resolve", "cancel", "delete", "resolve"), calls.toList())
    }

    @Test
    fun hotspotShareWithCachedApk_emitsNavigateToHotspotWithPath() = runTest {
        // K9
        val manager = managerWithLocalApk()
        val apk = File(application.cacheDir, "synthetic-shareable.apk")
        whenever(manager.getCachedApk()).thenReturn(apk)
        val viewModel = ApkDownloadViewModel(application, manager, FakeDownloader(), offlineMetadata())

        viewModel.onEvent(ApkUiEvent.HotspotShareClicked)

        assertEquals(ApkUiEffect.NavigateToHotspot(apk.absolutePath), awaitEffect(viewModel))
    }

    @Test
    fun hotspotShareWithoutCachedApk_emitsNotReadyToast() = runTest {
        // K9
        val manager = managerWithLocalApk()
        whenever(manager.getCachedApk()).thenReturn(null)
        val viewModel = ApkDownloadViewModel(application, manager, FakeDownloader(), offlineMetadata())

        viewModel.onEvent(ApkUiEvent.HotspotShareClicked)

        assertEquals(
            ApkUiEffect.ShowToast(syntheticString(R.string.apk_not_ready_please_prepare_it_first)),
            awaitEffect(viewModel)
        )
    }

    @Test
    fun confirmAppShareWithoutFile_closesDialogAndEmitsNotReadyToast() = runTest {
        // K10
        val manager = managerWithLocalApk()
        whenever(manager.getCachedApk()).thenReturn(
            File(application.cacheDir, "synthetic-missing.apk")
        )
        val viewModel = ApkDownloadViewModel(application, manager, FakeDownloader(), offlineMetadata())
        viewModel.onEvent(ApkUiEvent.AppShareClicked)

        viewModel.onEvent(ApkUiEvent.ConfirmAppShare)

        assertFalse(viewModel.state.value.showShareApkDialog)
        assertEquals(
            ApkUiEffect.ShowToast(syntheticString(R.string.apk_not_ready_please_prepare_it_first)),
            awaitEffect(viewModel)
        )
    }

    @Test
    fun confirmAppShareWhenUriCannotBeBuilt_emitsShareErrorToast() = runTest {
        // K10: the test host declares no FileProvider, so building the URI throws.
        val manager = managerWithLocalApk()
        val apk = File(application.cacheDir, "synthetic-shareable.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4b))
        }
        whenever(manager.getCachedApk()).thenReturn(apk)
        val viewModel = ApkDownloadViewModel(application, manager, FakeDownloader(), offlineMetadata())

        viewModel.onEvent(ApkUiEvent.ConfirmAppShare)

        assertEquals(
            ApkUiEffect.ShowToast(syntheticString(R.string.share_apk_error)),
            awaitEffect(viewModel)
        )
    }

    @Test
    fun cancelDownloadWithFallback_restoresReady() = runTest {
        // K11
        val downloader = FakeDownloader(emitOnStart = false)
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithLocalApk(),
            downloader,
            offlineMetadata()
        )
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val ready = awaitReady(viewModel)
        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)

        viewModel.onEvent(ApkUiEvent.CancelDownload)

        assertEquals(1, downloader.cancelCount)
        assertSame(ready, viewModel.state.value.apkStatus)
        assertEquals(0, viewModel.state.value.downloadProgress)
    }

    @Test
    fun cancelDownloadWithoutFallback_reloadsStatus() = runTest {
        // K11
        val downloader = FakeDownloader(emitOnStart = false)
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithoutApk(),
            downloader,
            offlineMetadata()
        )
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        awaitStatus(viewModel) { it is ApkPreparationStatus.NotDownloaded }
        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)
        assertNull(
            (viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading).shareableFallback
        )

        viewModel.onEvent(ApkUiEvent.CancelDownload)

        assertEquals(1, downloader.cancelCount)
        awaitStatus(viewModel) { it is ApkPreparationStatus.NotDownloaded }
        assertEquals(0, viewModel.state.value.downloadProgress)
    }

    @Test
    fun idleWhileDownloadingWithoutFallback_reloadsWithoutCancelling() = runTest {
        // K12
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithoutApk(),
            downloader,
            offlineMetadata()
        )
        downloader.emit(ApkDownloader.DownloadState.Downloading(progressPercent = 10))
        assertTrue(viewModel.state.value.apkStatus is ApkPreparationStatus.Downloading)

        downloader.emit(ApkDownloader.DownloadState.Idle)

        awaitStatus(viewModel) { it is ApkPreparationStatus.NotDownloaded }
        assertEquals(0, downloader.cancelCount)
        assertEquals(0, viewModel.state.value.downloadProgress)
    }

    @Test
    fun idleWhileNotDownloading_isNoOp() = runTest {
        // K12
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithoutApk(),
            downloader,
            offlineMetadata()
        )
        downloader.emit(ApkDownloader.DownloadState.Success(version = "1.7.6", sizeMB = 20))
        val ready = awaitReady(viewModel)

        downloader.emit(ApkDownloader.DownloadState.Idle)

        assertSame(ready, viewModel.state.value.apkStatus)
        assertEquals(100, viewModel.state.value.downloadProgress)
    }

    @Test
    fun downloaderProgress_keepsPreviousFallback() = runTest {
        // K13
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithLocalApk(),
            downloader,
            offlineMetadata()
        )
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val ready = awaitReady(viewModel)

        downloader.emit(
            ApkDownloader.DownloadState.Downloading(
                progressPercent = 37,
                phase = ApkDownloader.DownloadPhase.Transferring
            )
        )
        val first = viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading
        assertEquals(ApkDownloader.DownloadPhase.Transferring, first.phase)
        assertSame(ready, first.shareableFallback)
        assertEquals(37, viewModel.state.value.downloadProgress)

        downloader.emit(ApkDownloader.DownloadState.Downloading(progressPercent = 55))
        val second = viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading
        assertSame(ready, second.shareableFallback)
        assertEquals(55, viewModel.state.value.downloadProgress)
    }

    @Test
    fun successWithCachedInfo_readyFromInfo_andReleaseRecomputed() = runTest {
        // K14: the release was newer than the installed 1.7.5; after the 1.7.6 download it is not.
        val manager = managerWithLocalApk()
        whenever(manager.getCachedApkInfo()).thenReturn(
            syntheticApkInfo(
                version = "1.7.6",
                sizeMiB = 20,
                source = UniversalApkManager.ApkSource.DOWNLOADED
            )
        )
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(application, manager, downloader, releaseMetadata("1.7.6"))
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        awaitReady(viewModel)
        val known = awaitKnownRelease(viewModel)
        assertTrue(known.isNewerThanSharedApk)

        downloader.emit(ApkDownloader.DownloadState.Success(version = "0.0.1", sizeMB = 1))

        val ready = awaitStatus(viewModel) {
            (it as? ApkPreparationStatus.Ready)?.version == "1.7.6"
        } as ApkPreparationStatus.Ready
        assertEquals(20, ready.sizeMB)
        assertEquals(UniversalApkManager.ApkSource.DOWNLOADED, ready.source)
        assertEquals(ShareableApkVariant.UNIVERSAL, ready.variant)
        assertEquals(100, viewModel.state.value.downloadProgress)
        assertFalse((viewModel.state.value.releaseStatus as ApkReleaseStatus.Known).isNewerThanSharedApk)
    }

    @Test
    fun successWithoutCachedInfo_readyFromDownloadState() = runTest {
        // K14
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithoutApk(),
            downloader,
            offlineMetadata()
        )

        downloader.emit(ApkDownloader.DownloadState.Success(version = "1.7.6", sizeMB = 20))

        assertEquals(
            ApkPreparationStatus.Ready(
                version = "1.7.6",
                sizeMB = 20,
                source = UniversalApkManager.ApkSource.DOWNLOADED,
                variant = ShareableApkVariant.UNIVERSAL
            ),
            awaitReady(viewModel)
        )
        assertEquals(100, viewModel.state.value.downloadProgress)
    }

    @Test
    fun failedWithFallback_emitsToastAndRestoresReady() = runTest {
        // K15
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithLocalApk(),
            downloader,
            offlineMetadata()
        )
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val ready = awaitReady(viewModel)
        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)

        downloader.emit(
            ApkDownloader.DownloadState.Failed(
                reason = ApkDownloadFailureReason.AllSourcesFailed,
                messageArgs = emptyList(),
                resumablePercent = 30
            )
        )

        assertEquals(
            ApkUiEffect.ShowToast(syntheticString(R.string.prepare_apk_error_all_sources)),
            awaitEffect(viewModel)
        )
        assertSame(ready, viewModel.state.value.apkStatus)
        assertEquals(0, viewModel.state.value.downloadProgress)
    }

    @Test
    fun failedWithoutFallbackButResumable_becomesResumable() = runTest {
        // K15
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithoutApk(),
            downloader,
            offlineMetadata()
        )

        downloader.emit(
            ApkDownloader.DownloadState.Failed(
                reason = ApkDownloadFailureReason.AllSourcesFailed,
                messageArgs = emptyList(),
                resumablePercent = 30
            )
        )

        val resumable = awaitStatus(viewModel) {
            it is ApkPreparationStatus.Resumable
        } as ApkPreparationStatus.Resumable
        assertEquals(30, resumable.progressPercent)
        assertEquals(R.string.prepare_apk_error_all_sources, resumable.failure.messageRes)
        assertEquals(30, viewModel.state.value.downloadProgress)
        assertNull(awaitNoEffect(viewModel))
    }

    @Test
    fun eventWhileNotCollected_deliveredOnceOnNextCollect() = runTest {
        // Review Focus 5 / Q-K1: a toast raised while About is closed waits for the next collector,
        // and reaches it exactly once.
        val downloader = FakeDownloader()
        val viewModel = ApkDownloadViewModel(
            application,
            managerWithLocalApk(),
            downloader,
            offlineMetadata()
        )
        viewModel.onEvent(ApkUiEvent.CheckStatus)
        awaitReady(viewModel)
        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)
        downloader.emit(
            ApkDownloader.DownloadState.Failed(
                reason = ApkDownloadFailureReason.AllSourcesFailed,
                messageArgs = emptyList(),
                resumablePercent = null
            )
        )
        awaitReady(viewModel)

        assertEquals(
            ApkUiEffect.ShowToast(syntheticString(R.string.prepare_apk_error_all_sources)),
            awaitEffect(viewModel)
        )
        assertNull(awaitNoEffect(viewModel))
    }

    // --- Helpers ---
    //
    // Waits run on Dispatchers.Default so their timeouts are real time: the ViewModel does its
    // disk work on Dispatchers.IO, which the test scheduler's virtual clock does not wait for.

    private suspend fun awaitStatus(
        viewModel: ApkDownloadViewModel,
        predicate: (ApkPreparationStatus) -> Boolean
    ): ApkPreparationStatus = withContext(Dispatchers.Default) {
        withTimeout(5_000L) {
            viewModel.state.map { it.apkStatus }.first(predicate)
        }
    }

    private suspend fun awaitFallback(
        viewModel: ApkDownloadViewModel
    ): ApkPreparationStatus.Ready =
        (awaitStatus(viewModel) {
            (it as? ApkPreparationStatus.Downloading)?.shareableFallback != null
        } as ApkPreparationStatus.Downloading).shareableFallback!!

    private suspend fun awaitReady(
        viewModel: ApkDownloadViewModel
    ): ApkPreparationStatus.Ready =
        awaitStatus(viewModel) { it is ApkPreparationStatus.Ready } as ApkPreparationStatus.Ready

    private suspend fun awaitError(
        viewModel: ApkDownloadViewModel
    ): ApkPreparationStatus.Error =
        awaitStatus(viewModel) { it is ApkPreparationStatus.Error } as ApkPreparationStatus.Error

    private suspend fun awaitKnownRelease(
        viewModel: ApkDownloadViewModel
    ): ApkReleaseStatus.Known = withContext(Dispatchers.Default) {
        withTimeout(5_000L) {
            viewModel.state.map { it.releaseStatus }.first { it is ApkReleaseStatus.Known }
                as ApkReleaseStatus.Known
        }
    }

    private suspend fun awaitEffect(viewModel: ApkDownloadViewModel): ApkUiEffect =
        withContext(Dispatchers.Default) {
            withTimeout(5_000L) { viewModel.effect.first() }
        }

    /** Null when no effect arrives within a short real-time window. */
    private suspend fun awaitNoEffect(viewModel: ApkDownloadViewModel): ApkUiEffect? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(300L) { viewModel.effect.first() }
        }

    /**
     * This module's JVM tests run without Android resources (see BackDispatchTest), so the
     * strings the ViewModel resolves come back as their resource ID instead.
     */
    private class SyntheticStringsApplication(base: Application) : Application() {
        private val strings: Resources = mock(Resources::class.java) { invocation ->
            if (invocation.method.name == "getString") {
                syntheticString(invocation.arguments[0] as Int)
            } else {
                RETURNS_DEFAULTS.answer(invocation)
            }
        }

        init {
            attachBaseContext(base)
        }

        override fun getResources(): Resources = strings
    }

    private fun offlineMetadata() = object : LatestReleaseProvider {
        override suspend fun latestRelease(): Result<GitHubReleaseClient.ReleaseSnapshot> =
            Result.failure(IllegalStateException("synthetic offline response"))
    }

    private fun releaseMetadata(version: String) = object : LatestReleaseProvider {
        override suspend fun latestRelease(): Result<GitHubReleaseClient.ReleaseSnapshot> =
            Result.success(
                GitHubReleaseClient.ReleaseSnapshot(
                    release = GitHubReleaseClient.Release(
                        versionName = version,
                        universalApkSize = 20L * 1024 * 1024,
                        universalApkUrl = "https://example.invalid/synthetic-universal.apk",
                        universalApkName = "synthetic-universal.apk"
                    ),
                    isStale = false
                )
            )
    }

    private fun syntheticApkInfo(
        version: String = "1.7.5",
        sizeMiB: Long = 12,
        source: UniversalApkManager.ApkSource = UniversalApkManager.ApkSource.INSTALLED
    ) = UniversalApkManager.ApkInfo(
        version = version,
        downloadDate = 1_700_000_000_000L,
        size = sizeMiB * 1024 * 1024,
        file = File(application.cacheDir, "synthetic-shareable.apk"),
        source = source,
        variant = ShareableApkVariant.UNIVERSAL,
        downloadSourceId = null
    )

    private suspend fun managerWithLocalApk(
        source: UniversalApkManager.ApkSource = UniversalApkManager.ApkSource.INSTALLED
    ): UniversalApkManager {
        val manager = mock<UniversalApkManager>()
        whenever(manager.prepareLocalApkInfo()).thenReturn(syntheticApkInfo(source = source))
        whenever(manager.getPartialDownloadProgress()).thenReturn(null)
        return manager
    }

    private suspend fun managerWithoutApk(): UniversalApkManager {
        val manager = mock<UniversalApkManager>()
        whenever(manager.prepareLocalApkInfo()).thenReturn(null)
        whenever(manager.getPartialDownloadProgress()).thenReturn(null)
        whenever(manager.getCachedApkInfo()).thenReturn(null)
        return manager
    }

    private class FakeDownloader(
        initial: ApkDownloader.DownloadState = ApkDownloader.DownloadState.Idle,
        private val emitOnStart: Boolean = true,
        private val onCancel: () -> Unit = {}
    ) : ApkDownloader {
        private val mutableState = MutableStateFlow(initial)
        override val downloadState = mutableState.asStateFlow()
        var startCount = 0
        var cancelCount = 0

        override fun startDownload() {
            startCount += 1
            if (emitOnStart) {
                mutableState.value = ApkDownloader.DownloadState.Downloading(
                    progressPercent = 0,
                    phase = ApkDownloader.DownloadPhase.SelectingSource
                )
            }
        }

        override fun cancelDownload() {
            cancelCount += 1
            onCancel()
        }

        fun emit(state: ApkDownloader.DownloadState) {
            mutableState.value = state
        }
    }
}
