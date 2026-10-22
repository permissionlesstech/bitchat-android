package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.R
import com.bitchat.android.util.ApkDownloader
import com.bitchat.android.util.AppVersion
import com.bitchat.android.util.GitHubReleaseClient
import com.bitchat.android.util.LatestReleaseProvider
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import com.bitchat.android.util.WorkManagerApkDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
internal fun Context.resolveApkFailureMessage(failure: ApkFailureMessage): String {
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
)

// --- Events (UI → ViewModel) ---

sealed class ApkUiEvent {
    object CheckStatus : ApkUiEvent()
    object PrepareRowClicked : ApkUiEvent()
    object DownloadUniversalClicked : ApkUiEvent()
    object ConfirmDownload : ApkUiEvent()
    object DismissPrepareDialog : ApkUiEvent()
    object DeleteClicked : ApkUiEvent()
    object ConfirmDelete : ApkUiEvent()
    object DismissDeleteDialog : ApkUiEvent()
    object HotspotShareClicked : ApkUiEvent()
    object AppShareClicked : ApkUiEvent()
    object ConfirmAppShare : ApkUiEvent()
    object DismissShareDialog : ApkUiEvent()
    object CancelDownload : ApkUiEvent()
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

// --- Effects (ViewModel → UI, one-shot) ---

sealed class ApkUiEffect {
    data class NavigateToHotspot(val apkPath: String) : ApkUiEffect()
    data class ShareApk(val apkUri: android.net.Uri, val chooserTitle: String) : ApkUiEffect()
    data class ShowToast(val message: String) : ApkUiEffect()
}

/**
 * ViewModel for APK download/status/share logic following MVI pattern.
 * UI sends [ApkUiEvent], observes [ApkUiState], and collects [ApkUiEffect].
 */
class ApkDownloadViewModel internal constructor(
    application: Application,
    private val apkManager: UniversalApkManager,
    private val downloader: ApkDownloader,
    private val latestReleaseProvider: LatestReleaseProvider
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        apkManager = UniversalApkManager(application),
        downloader = WorkManagerApkDownloader(application),
        latestReleaseProvider = GitHubReleaseClient(application)
    )

    companion object {
        private const val TAG = "ApkDownloadVM"
    }

    private val _state = MutableStateFlow(ApkUiState())
    val state: StateFlow<ApkUiState> = _state.asStateFlow()

    private val _effect = Channel<ApkUiEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private var metadataRefreshJob: Job? = null

    init {
        observeDownloader()
    }

    fun onEvent(event: ApkUiEvent) {
        when (event) {
            is ApkUiEvent.CheckStatus -> checkStatus()
            is ApkUiEvent.PrepareRowClicked -> onPrepareRowClicked()
            is ApkUiEvent.DownloadUniversalClicked -> onDownloadUniversalClicked()
            is ApkUiEvent.ConfirmDownload -> onConfirmDownload()
            is ApkUiEvent.DismissPrepareDialog -> _state.update { it.copy(showPrepareDialog = false) }
            is ApkUiEvent.DeleteClicked -> _state.update { it.copy(showDeleteDialog = true) }
            is ApkUiEvent.ConfirmDelete -> onConfirmDelete()
            is ApkUiEvent.DismissDeleteDialog -> _state.update { it.copy(showDeleteDialog = false) }
            is ApkUiEvent.HotspotShareClicked -> onHotspotShareClicked()
            is ApkUiEvent.AppShareClicked -> _state.update { it.copy(showShareApkDialog = true) }
            is ApkUiEvent.ConfirmAppShare -> onConfirmAppShare()
            is ApkUiEvent.DismissShareDialog -> _state.update { it.copy(showShareApkDialog = false) }
            is ApkUiEvent.CancelDownload -> onCancelDownload()
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
        apkManager.deleteCachedApk()
        checkStatus()
    }

    private fun onHotspotShareClicked() {
        val apkFile = apkManager.getCachedApk()
        if (apkFile != null) {
            viewModelScope.launch {
                _effect.send(ApkUiEffect.NavigateToHotspot(apkFile.absolutePath))
            }
        } else {
            sendToast(getString(R.string.apk_not_ready_please_prepare_it_first))
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
                _effect.send(
                    ApkUiEffect.ShareApk(
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
                        val info = apkManager.getCachedApkInfo()
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
                            ?: apkManager.getCachedApkInfo()?.toReady()
                        if (fallback != null) {
                            _state.update {
                                it.copy(
                                    apkStatus = fallback,
                                    releaseStatus = releaseStatusFor(fallback, it.releaseStatus),
                                    downloadProgress = 0
                                )
                            }
                            _effect.send(
                                ApkUiEffect.ShowToast(
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
            _effect.send(ApkUiEffect.ShowToast(message))
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
