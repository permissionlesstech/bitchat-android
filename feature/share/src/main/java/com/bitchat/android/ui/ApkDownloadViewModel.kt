package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.feature.share.R
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.share.AppBuildInfo
import com.bitchat.android.util.ApkDownloader
import com.bitchat.android.util.AppVersion
import com.bitchat.android.util.GitHubReleaseClient
import com.bitchat.android.util.LatestReleaseProvider
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import com.bitchat.android.util.WorkManagerApkDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- State ---

sealed class ApkPreparationStatus {
    object Loading : ApkPreparationStatus()
    object NotDownloaded : ApkPreparationStatus()
    data class Ready(
        val version: String,
        val sizeMB: Int,
        val source: UniversalApkManager.ApkSource,
        val variant: ShareableApkVariant
    ) : ApkPreparationStatus()
    /** [phase] is what the operation is actually doing; only a transfer has a real percentage. */
    data class Downloading(
        val phase: ApkDownloader.DownloadPhase = ApkDownloader.DownloadPhase.SelectingSource,
        val shareableFallback: Ready? = null
    ) : ApkPreparationStatus()
    data class Resumable(
        val progressPercent: Int,
        val failure: ApkFailureMessage
    ) : ApkPreparationStatus()
    data class Error(val failure: ApkFailureMessage) : ApkPreparationStatus()
}

/** A localizable failure kept as data until the UI or a one-shot effect renders it. */
data class ApkFailureMessage(
    @StringRes val messageRes: Int,
    val messageArgs: List<String> = emptyList()
)

/**
 * Resolves a failure defensively. The reason and its arguments cross the WorkManager boundary
 * independently, so an argument list that does not match the format string is possible; a row
 * showing generic text beats one that throws while formatting.
 */
internal fun Context.resolveApkFailureMessage(failure: ApkFailureMessage): String =
    resources.resolveApkFailureMessage(failure)

internal fun Resources.resolveApkFailureMessage(failure: ApkFailureMessage): String {
    return runCatching {
        getString(
            failure.messageRes,
            *failure.messageArgs.toTypedArray()
        )
    }.getOrElse {
        getString(R.string.prepare_apk_error_generic)
    }
}

sealed class ApkReleaseStatus {
    object Unknown : ApkReleaseStatus()
    object Checking : ApkReleaseStatus()
    data class Known(
        val version: String,
        val sizeMB: Int,
        val isNewerThanSharedApk: Boolean,
        val fromStaleCache: Boolean
    ) : ApkReleaseStatus()
}

data class ApkUiState(
    val apkStatus: ApkPreparationStatus = ApkPreparationStatus.Loading,
    val releaseStatus: ApkReleaseStatus = ApkReleaseStatus.Unknown,
    val downloadProgress: Int = 0,
    val showPrepareDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showShareApkDialog: Boolean = false
) {
    /** The APK that can be shared now: a ready one, or the one a running download replaces. */
    val shareableApk: ApkPreparationStatus.Ready?
        get() = when (apkStatus) {
            is ApkPreparationStatus.Ready -> apkStatus
            is ApkPreparationStatus.Downloading -> apkStatus.shareableFallback
            else -> null
        }

    /** A GitHub release newer than [shareableApk], or null. */
    val availableUpdate: ApkReleaseStatus.Known?
        get() = (releaseStatus as? ApkReleaseStatus.Known)?.takeIf { it.isNewerThanSharedApk }

    /**
     * Whether the share rows show. A GitHub update is optional, so sharing stays visible while
     * the replacement downloads or while metadata refreshes.
     */
    val canShare: Boolean
        get() = shareableApk != null

    /**
     * Enabled by the same mapping that decides what a tap does, so the prepare row can never
     * look tappable and do nothing.
     */
    val prepareRowEnabled: Boolean
        get() = prepareRowTapAction(apkStatus, releaseStatus) != null
}

// --- Actions (UI → ViewModel) ---

sealed interface ApkAction {
    /** The section was composed; sent every time About shows it, to re-check the local APK. */
    data object SectionShown : ApkAction
    data object PrepareRowClicked : ApkAction
    data object DownloadUniversalClicked : ApkAction
    data object ConfirmDownload : ApkAction
    data object DismissPrepareDialog : ApkAction
    data object DeleteClicked : ApkAction
    data object ConfirmDelete : ApkAction
    data object DismissDeleteDialog : ApkAction
    data object HotspotShareClicked : ApkAction
    data object AppShareClicked : ApkAction
    data object ConfirmAppShare : ApkAction
    data object DismissShareDialog : ApkAction
    data object CancelDownload : ApkAction
}

// --- Row tap ---

/** What tapping the body of the prepare row does. */
internal enum class PrepareRowTapAction {
    OpenPrepareDialog,
    StartDownload
}

/**
 * What a tap on the prepare row means for [status], or null when the row has nothing to offer.
 *
 * The trailing controls are icon-only, so the row body is the discoverable half of every action
 * and has to stay in step with them. Deriving both the tap handler and the row's `enabled` flag
 * from this one function keeps the row from looking clickable while doing nothing.
 */
internal fun prepareRowTapAction(
    status: ApkPreparationStatus,
    releaseStatus: ApkReleaseStatus = ApkReleaseStatus.Unknown
): PrepareRowTapAction? = when {
    status is ApkPreparationStatus.NotDownloaded -> PrepareRowTapAction.OpenPrepareDialog
    // Consent was already given for these; resuming straight away avoids a redundant prompt.
    status is ApkPreparationStatus.Resumable -> PrepareRowTapAction.StartDownload
    status is ApkPreparationStatus.Error -> PrepareRowTapAction.StartDownload
    status is ApkPreparationStatus.Ready &&
        (status.source == UniversalApkManager.ApkSource.INSTALLED ||
            (releaseStatus as? ApkReleaseStatus.Known)?.isNewerThanSharedApk == true) ->
        PrepareRowTapAction.OpenPrepareDialog
    else -> null
}

// --- Events (ViewModel → UI, one-shot) ---

sealed interface ApkEvent {
    data class NavigateToHotspot(val apkPath: String) : ApkEvent
    data class ShareApk(val apkUri: Uri, val chooserTitle: String) : ApkEvent
    data class ShowToast(val message: String) : ApkEvent
}

/**
 * ViewModel for APK download/status/share logic following MVI pattern.
 * UI sends [ApkAction], observes [ApkUiState], and collects [ApkEvent].
 */
@HiltViewModel
class ApkDownloadViewModel internal constructor(
    application: Application,
    private val apkManager: UniversalApkManager,
    private val downloader: ApkDownloader,
    private val latestReleaseProvider: LatestReleaseProvider
) : AndroidViewModel(application) {

    // Hilt injects through this constructor rather than the primary one:
    // UniversalApkManager's collaborators are internal, and routing internal
    // Kotlin types through Dagger's generated Java buys nothing here. Tests
    // keep using the primary constructor to substitute fakes.
    @Inject
    constructor(
        application: Application,
        buildInfo: AppBuildInfo,
        torManager: ArtiTorManager,
        okHttpProvider: OkHttpProvider,
    ) : this(
        application = application,
        apkManager = UniversalApkManager(application, buildInfo, torManager, okHttpProvider),
        downloader = WorkManagerApkDownloader(application, buildInfo, torManager, okHttpProvider),
        latestReleaseProvider = GitHubReleaseClient(
            context = application,
            routedClient = okHttpProvider::routedHttpClient,
            awaitRoute = { torManager.awaitSelectedRoute(GitHubReleaseClient.ROUTE_READY_TIMEOUT_MILLIS) },
        )
    )

    companion object {
        private const val TAG = "ApkDownloadVM"
    }

    private val _state = MutableStateFlow(ApkUiState())
    val uiState: StateFlow<ApkUiState> = _state.asStateFlow()

    // Buffered on an Activity-scoped ViewModel: an event raised while About is closed waits
    // here and is delivered once, the next time the section collects.
    private val _events = Channel<ApkEvent>(Channel.BUFFERED)
    val events: Flow<ApkEvent> = _events.receiveAsFlow()

    private var metadataRefreshJob: Job? = null

    init {
        observeDownloader()
    }

    fun onAction(action: ApkAction) {
        when (action) {
            ApkAction.SectionShown -> checkStatus()
            ApkAction.PrepareRowClicked -> onPrepareRowClicked()
            ApkAction.DownloadUniversalClicked -> onDownloadUniversalClicked()
            ApkAction.ConfirmDownload -> onConfirmDownload()
            ApkAction.DismissPrepareDialog -> _state.update { it.copy(showPrepareDialog = false) }
            ApkAction.DeleteClicked -> _state.update { it.copy(showDeleteDialog = true) }
            ApkAction.ConfirmDelete -> onConfirmDelete()
            ApkAction.DismissDeleteDialog -> _state.update { it.copy(showDeleteDialog = false) }
            ApkAction.HotspotShareClicked -> onHotspotShareClicked()
            ApkAction.AppShareClicked -> _state.update { it.copy(showShareApkDialog = true) }
            ApkAction.ConfirmAppShare -> onConfirmAppShare()
            ApkAction.DismissShareDialog -> _state.update { it.copy(showShareApkDialog = false) }
            ApkAction.CancelDownload -> onCancelDownload()
        }
    }

    private fun onPrepareRowClicked() {
        when (
            prepareRowTapAction(
                _state.value.apkStatus,
                _state.value.releaseStatus
            )
        ) {
            PrepareRowTapAction.OpenPrepareDialog ->
                _state.update { it.copy(showPrepareDialog = true) }
            PrepareRowTapAction.StartDownload -> startDownload()
            null -> {}
        }
    }

    private fun onConfirmDownload() {
        _state.update { it.copy(showPrepareDialog = false) }
        startDownload()
    }

    private fun onDownloadUniversalClicked() {
        val status = _state.value.apkStatus
        val hasUpdate = (_state.value.releaseStatus as? ApkReleaseStatus.Known)
            ?.isNewerThanSharedApk == true
        if (status is ApkPreparationStatus.Ready &&
            (status.source == UniversalApkManager.ApkSource.INSTALLED || hasUpdate)
        ) {
            _state.update { it.copy(showPrepareDialog = true) }
        }
    }

    private fun onConfirmDelete() {
        _state.update { it.copy(showDeleteDialog = false) }
        downloader.cancelDownload()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { apkManager.deleteCachedApk() }
            checkStatus()
        }
    }

    private fun onHotspotShareClicked() {
        viewModelScope.launch {
            val apkFile = withContext(Dispatchers.IO) { apkManager.getCachedApk() }
            if (apkFile != null) {
                _events.send(ApkEvent.NavigateToHotspot(apkFile.absolutePath))
            } else {
                _events.send(
                    ApkEvent.ShowToast(getString(R.string.apk_not_ready_please_prepare_it_first))
                )
            }
        }
    }

    private fun onConfirmAppShare() {
        _state.update { it.copy(showShareApkDialog = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apkFile = apkManager.getCachedApk()
                if (apkFile == null || !apkFile.exists()) {
                    sendToast(getString(R.string.apk_not_ready_please_prepare_it_first))
                    return@launch
                }

                val context = getApplication<Application>()
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                _events.send(
                    ApkEvent.ShareApk(
                        apkUri = uri,
                        chooserTitle = getString(R.string.share_apk_chooser_title)
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing APK share", e)
                sendToast(getString(R.string.share_apk_error))
            }
        }
    }

    private fun onCancelDownload() {
        downloader.cancelDownload()

        val fallback = (_state.value.apkStatus as? ApkPreparationStatus.Downloading)
            ?.shareableFallback
        _state.update {
            it.copy(
                apkStatus = fallback ?: ApkPreparationStatus.Loading,
                downloadProgress = 0
            )
        }
        if (fallback == null) checkStatus()
    }

    private fun startDownload() {
        val current = _state.value.apkStatus
        val fallback = when (current) {
            is ApkPreparationStatus.Ready -> current
            is ApkPreparationStatus.Downloading -> current.shareableFallback
            else -> null
        }
        val partial = apkManager.getPartialDownloadProgress()
        _state.update {
            it.copy(
                apkStatus = ApkPreparationStatus.Downloading(
                    shareableFallback = fallback
                ),
                downloadProgress = partial ?: 0
            )
        }
        downloader.startDownload()
    }

    private fun checkStatus() {
        viewModelScope.launch {
            val resolvedStatus = resolveApkStatus()
            _state.update { current ->
                // WorkManager is the source of truth for active work. A queued or newly started
                // job legitimately has no partial file yet, so never infer that it is orphaned
                // from cache contents, and never let a resolved status overwrite it - the user
                // may have started a download while the local artifact was being inspected.
                when (val active = current.apkStatus) {
                    is ApkPreparationStatus.Downloading ->
                        // Active work still adopts a local artifact it was created without. A
                        // ViewModel restored onto a running download starts from Loading, so the
                        // observer had no Ready to carry into shareableFallback, and an installed
                        // APK - with both sharing actions - would stay hidden for the whole
                        // transfer. Deciding here covers the observer arriving before this runs
                        // and during the resolve above, which are different orderings.
                        if (active.shareableFallback == null) {
                            current.copy(
                                apkStatus = active.copy(
                                    shareableFallback = shareableReady(resolvedStatus)
                                )
                            )
                        } else {
                            current
                        }
                    else -> current.copy(
                        apkStatus = resolvedStatus,
                        downloadProgress = 0
                    )
                }
            }
            // Metadata is skipped while work is active, as it was before: an in-flight download
            // has no use for a freshness check and the API budget is scarce.
            if (_state.value.apkStatus is ApkPreparationStatus.Downloading) return@launch
            // Local availability is resolved and published before this independent network task
            // starts. Metadata can add a freshness warning, but can never hide sharing.
            refreshReleaseMetadata()
        }
    }

    private fun refreshReleaseMetadata() {
        if (metadataRefreshJob?.isActive == true) return
        metadataRefreshJob = viewModelScope.launch {
            _state.update { it.copy(releaseStatus = ApkReleaseStatus.Checking) }
            latestReleaseProvider.latestRelease()
                .onSuccess { snapshot ->
                    val shared = shareableReady(_state.value.apkStatus)
                    _state.update {
                        it.copy(
                            releaseStatus = ApkReleaseStatus.Known(
                                version = snapshot.release.versionName,
                                sizeMB = (snapshot.release.universalApkSize / 1024 / 1024).toInt(),
                                isNewerThanSharedApk = shared?.let { ready ->
                                    AppVersion.isNewer(ready.version, snapshot.release.versionName)
                                } ?: false,
                                fromStaleCache = snapshot.isStale
                            )
                        )
                    }
                }
                .onFailure {
                    // Metadata is an optional enhancement. Keep the locally resolved APK state.
                    _state.update { it.copy(releaseStatus = ApkReleaseStatus.Unknown) }
                }
        }
    }

    private fun observeDownloader() {
        viewModelScope.launch {
            downloader.downloadState.collect { downloadState ->
                when (downloadState) {
                    is ApkDownloader.DownloadState.Idle -> {
                        val downloading = _state.value.apkStatus as? ApkPreparationStatus.Downloading
                        if (downloading != null) {
                            val fallback = downloading.shareableFallback
                            _state.update {
                                it.copy(
                                    apkStatus = fallback ?: ApkPreparationStatus.Loading,
                                    downloadProgress = 0
                                )
                            }
                            if (fallback == null) checkStatus()
                        }
                    }
                    is ApkDownloader.DownloadState.Downloading -> {
                        _state.update {
                            val fallback = (it.apkStatus as? ApkPreparationStatus.Downloading)
                                ?.shareableFallback
                                ?: (it.apkStatus as? ApkPreparationStatus.Ready)
                            it.copy(
                                apkStatus = ApkPreparationStatus.Downloading(
                                    phase = downloadState.phase,
                                    shareableFallback = fallback
                                ),
                                downloadProgress = downloadState.progressPercent
                            )
                        }
                    }
                    is ApkDownloader.DownloadState.Success -> {
                        val info = withContext(Dispatchers.IO) { apkManager.getCachedApkInfo() }
                        _state.update {
                            val ready = ApkPreparationStatus.Ready(
                                version = info?.version ?: downloadState.version,
                                sizeMB = info?.let { cached ->
                                    (cached.size / 1024 / 1024).toInt()
                                } ?: downloadState.sizeMB,
                                source = info?.source ?: UniversalApkManager.ApkSource.DOWNLOADED,
                                variant = info?.variant ?: ShareableApkVariant.UNIVERSAL
                            )
                            it.copy(
                                apkStatus = ready,
                                releaseStatus = releaseStatusFor(ready, it.releaseStatus),
                                downloadProgress = 100
                            )
                        }
                    }
                    is ApkDownloader.DownloadState.Failed -> {
                        val failure = downloadState.toFailureMessage()
                        val fallback = (_state.value.apkStatus as? ApkPreparationStatus.Downloading)
                            ?.shareableFallback
                            ?: withContext(Dispatchers.IO) { apkManager.getCachedApkInfo() }
                                ?.toReady()
                        if (fallback != null) {
                            _state.update {
                                it.copy(
                                    apkStatus = fallback,
                                    releaseStatus = releaseStatusFor(fallback, it.releaseStatus),
                                    downloadProgress = 0
                                )
                            }
                            _events.send(
                                ApkEvent.ShowToast(
                                    getApplication<Application>().resolveApkFailureMessage(
                                        failure
                                    )
                                )
                            )
                        } else {
                            _state.update {
                                if (downloadState.resumablePercent != null) {
                                    it.copy(
                                        apkStatus = ApkPreparationStatus.Resumable(
                                            progressPercent = downloadState.resumablePercent,
                                            failure = failure
                                        ),
                                        downloadProgress = downloadState.resumablePercent
                                    )
                                } else {
                                    it.copy(apkStatus = ApkPreparationStatus.Error(failure))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendToast(message: String) {
        viewModelScope.launch {
            _events.send(ApkEvent.ShowToast(message))
        }
    }

    private fun getString(resId: Int): String {
        return getApplication<Application>().getString(resId)
    }

    private fun ApkDownloader.DownloadState.Failed.toFailureMessage() = ApkFailureMessage(
        messageRes = reason.messageRes,
        messageArgs = messageArgs
    )

    private fun shareableReady(status: ApkPreparationStatus): ApkPreparationStatus.Ready? =
        when (status) {
            is ApkPreparationStatus.Ready -> status
            is ApkPreparationStatus.Downloading -> status.shareableFallback
            else -> null
        }

    private fun releaseStatusFor(
        ready: ApkPreparationStatus.Ready,
        releaseStatus: ApkReleaseStatus
    ): ApkReleaseStatus = (releaseStatus as? ApkReleaseStatus.Known)?.let {
        it.copy(isNewerThanSharedApk = AppVersion.isNewer(ready.version, it.version))
    } ?: releaseStatus

    private fun UniversalApkManager.ApkInfo.toReady() = ApkPreparationStatus.Ready(
        version = version,
        sizeMB = (size / 1024 / 1024).toInt(),
        source = source,
        variant = variant
    )

    private suspend fun resolveApkStatus(): ApkPreparationStatus = withContext(Dispatchers.IO) {
        try {
            val info = apkManager.prepareLocalApkInfo()
            if (info != null) {
                info.toReady()
            } else {
                val partial = apkManager.getPartialDownloadProgress()
                if (partial != null) {
                    ApkPreparationStatus.Resumable(
                        progressPercent = partial,
                        failure = ApkFailureMessage(
                            messageRes = R.string.prepare_apk_download_interrupted
                        )
                    )
                } else {
                    ApkPreparationStatus.NotDownloaded
                }
            }
        } catch (e: Exception) {
            // The exception text is English and often internal; log it, show a translated line.
            Log.e(TAG, "Error reading APK status", e)
            ApkPreparationStatus.Error(
                ApkFailureMessage(messageRes = R.string.share_apk_error)
            )
        }
    }
}
