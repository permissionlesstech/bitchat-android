package com.bitchat.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.util.ApkDownloader
import com.bitchat.android.util.GitHubReleaseClient
import com.bitchat.android.util.LatestReleaseProvider
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ApkDownloadViewModelTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
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
        val metadata = object : LatestReleaseProvider {
            override suspend fun latestRelease(): Result<GitHubReleaseClient.ReleaseSnapshot> =
                Result.failure(IllegalStateException("synthetic offline response"))
        }
        val viewModel = ApkDownloadViewModel(application, manager, downloader, metadata)

        viewModel.onEvent(ApkUiEvent.CheckStatus)
        val originalReady = awaitReady(viewModel)
        viewModel.onEvent(ApkUiEvent.PrepareRowClicked)
        viewModel.onEvent(ApkUiEvent.ConfirmDownload)

        val downloading = viewModel.state.value.apkStatus as ApkPreparationStatus.Downloading
        assertSame(originalReady, downloading.shareableFallback)
        assertEquals(1, downloader.startCount)

        downloader.emit(ApkDownloader.DownloadState.Idle)
        val restored = awaitReady(viewModel)
        assertEquals(originalReady, restored)
    }

    private suspend fun managerWithLocalApk(): UniversalApkManager {
        val manager = mock<UniversalApkManager>()
        whenever(manager.prepareLocalApkInfo()).thenReturn(
            UniversalApkManager.ApkInfo(
                version = "1.7.5",
                downloadDate = 1_700_000_000_000L,
                size = 12L * 1024 * 1024,
                file = File(application.cacheDir, "synthetic-shareable.apk"),
                source = UniversalApkManager.ApkSource.INSTALLED,
                variant = ShareableApkVariant.UNIVERSAL,
                downloadSourceId = null
            )
        )
        whenever(manager.getPartialDownloadProgress()).thenReturn(null)
        return manager
    }

    private suspend fun awaitReady(
        viewModel: ApkDownloadViewModel
    ): ApkPreparationStatus.Ready = withTimeout(5_000L) {
        while (true) {
            (viewModel.state.value.apkStatus as? ApkPreparationStatus.Ready)?.let { return@withTimeout it }
            delay(1L)
        }
        error("unreachable")
    }

    private class FakeDownloader : ApkDownloader {
        private val mutableState = MutableStateFlow<ApkDownloader.DownloadState>(
            ApkDownloader.DownloadState.Idle
        )
        override val downloadState = mutableState.asStateFlow()
        var startCount = 0

        override fun startDownload() {
            startCount += 1
            mutableState.value = ApkDownloader.DownloadState.Downloading(
                progressPercent = 0,
                phase = ApkDownloader.DownloadPhase.SelectingSource
            )
        }

        override fun cancelDownload() = Unit

        fun emit(state: ApkDownloader.DownloadState) {
            mutableState.value = state
        }
    }
}
