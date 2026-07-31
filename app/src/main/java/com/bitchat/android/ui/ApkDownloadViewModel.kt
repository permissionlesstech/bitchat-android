package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.R
import com.bitchat.android.util.ApkDownloader
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import com.bitchat.android.util.WorkManagerApkDownloader
import kotlinx.coroutines.Dispatchers
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
        val phase: ApkDownloader.DownloadPhase = ApkDownloader.DownloadPhase.SelectingSource
    ) : ApkPreparationStatus()
    data class Resumable(val progressPercent: Int, val message: String) : ApkPreparationStatus()
    data class Error(val message: String) : ApkPreparationStatus()
}

data class ApkUiState(
    val apkStatus: ApkPreparationStatus = ApkPreparationStatus.Loading,
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
internal fun prepareRowTapAction(status: ApkPreparationStatus): PrepareRowTapAction? = when {
    status is ApkPreparationStatus.NotDownloaded -> PrepareRowTapAction.OpenPrepareDialog
    // Consent was already given for these; resuming straight away avoids a redundant prompt.
    status is ApkPreparationStatus.Resumable -> PrepareRowTapAction.StartDownload
    status is ApkPreparationStatus.Error -> PrepareRowTapAction.StartDownload
    status is ApkPreparationStatus.Ready && status.variant == ShareableApkVariant.ARM64 ->
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
class ApkDownloadViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ApkDownloadVM"
    }

    private val apkManager = UniversalApkManager(application)
    private val downloader: ApkDownloader = WorkManagerApkDownloader(application)

    private val _state = MutableStateFlow(ApkUiState())
    val state: StateFlow<ApkUiState> = _state.asStateFlow()

    private val _effect = Channel<ApkUiEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

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
        when (prepareRowTapAction(_state.value.apkStatus)) {
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
        if (status is ApkPreparationStatus.Ready &&
            status.variant == ShareableApkVariant.ARM64
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

        // Leave Downloading immediately. The cancelled job maps to Idle, which
        // the observer intentionally ignores because local status is resolved below.
        _state.update {
            it.copy(apkStatus = ApkPreparationStatus.Loading, downloadProgress = 0)
        }

        checkStatus()
    }

    private fun startDownload() {
        val partial = apkManager.getPartialDownloadProgress()
        _state.update {
            it.copy(
                apkStatus = ApkPreparationStatus.Downloading(),
                downloadProgress = partial ?: 0
            )
        }
        downloader.startDownload()
    }

    private fun checkStatus() {
        viewModelScope.launch {
            // WorkManager is the source of truth for active work. A queued or
            // newly started job legitimately has no partial file yet, so never
            // infer that it is orphaned from cache contents.
            if (_state.value.apkStatus is ApkPreparationStatus.Downloading) {
                return@launch
            }

            val resolvedStatus = resolveApkStatus()
            _state.update { current ->
                // Re-check in case the user started a download while the local
                // artifact was being inspected or copied.
                if (current.apkStatus is ApkPreparationStatus.Downloading) {
                    current
                } else {
                    current.copy(apkStatus = resolvedStatus, downloadProgress = 0)
                }
            }
        }
    }

    private fun observeDownloader() {
        viewModelScope.launch {
            downloader.downloadState.collect { downloadState ->
                when (downloadState) {
                    is ApkDownloader.DownloadState.Idle -> {
                        // Don't overwrite — status set by checkStatus()
                    }
                    is ApkDownloader.DownloadState.Downloading -> {
                        _state.update {
                            it.copy(
                                apkStatus = ApkPreparationStatus.Downloading(downloadState.phase),
                                downloadProgress = downloadState.progressPercent
                            )
                        }
                    }
                    is ApkDownloader.DownloadState.Success -> {
                        val info = apkManager.getCachedApkInfo()
                        _state.update {
                            it.copy(
                                apkStatus = ApkPreparationStatus.Ready(
                                    version = info?.version ?: downloadState.version,
                                    sizeMB = info?.let { cached ->
                                        (cached.size / 1024 / 1024).toInt()
                                    } ?: downloadState.sizeMB,
                                    source = info?.source ?: UniversalApkManager.ApkSource.DOWNLOADED,
                                    variant = info?.variant ?: ShareableApkVariant.UNIVERSAL
                                ),
                                downloadProgress = 100
                            )
                        }
                    }
                    is ApkDownloader.DownloadState.Failed -> {
                        val localArm64 = apkManager.getCachedApkInfo()
                            ?.takeIf { it.variant == ShareableApkVariant.ARM64 }
                        if (localArm64 != null) {
                            _state.update {
                                it.copy(
                                    apkStatus = ApkPreparationStatus.Ready(
                                        version = localArm64.version,
                                        sizeMB = (localArm64.size / 1024 / 1024).toInt(),
                                        source = localArm64.source,
                                        variant = localArm64.variant
                                    )
                                )
                            }
                            _effect.send(ApkUiEffect.ShowToast(failureMessage(downloadState)))
                        } else {
                            val message = failureMessage(downloadState)
                            _state.update {
                                if (downloadState.resumablePercent != null) {
                                    it.copy(
                                        apkStatus = ApkPreparationStatus.Resumable(
                                            progressPercent = downloadState.resumablePercent,
                                            message = message
                                        ),
                                        downloadProgress = downloadState.resumablePercent
                                    )
                                } else {
                                    it.copy(
                                        apkStatus = ApkPreparationStatus.Error(message)
                                    )
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

    /**
     * The single place a download failure turns into words. The downloader names the failure and
     * this resolves it, so the message follows the device locale rather than the worker's.
     */
    private fun failureMessage(state: ApkDownloader.DownloadState.Failed): String =
        getApplication<Application>().getString(
            state.reason.messageRes,
            *state.messageArgs.toTypedArray()
        )

    private suspend fun resolveApkStatus(): ApkPreparationStatus = withContext(Dispatchers.IO) {
        try {
            val info = apkManager.prepareLocalApkInfo()
            if (info != null) {
                ApkPreparationStatus.Ready(
                    version = info.version,
                    sizeMB = (info.size / 1024 / 1024).toInt(),
                    source = info.source,
                    variant = info.variant
                )
            } else {
                val partial = apkManager.getPartialDownloadProgress()
                if (partial != null) {
                    ApkPreparationStatus.Resumable(
                        progressPercent = partial,
                        message = getString(R.string.prepare_apk_download_interrupted)
                    )
                } else {
                    ApkPreparationStatus.NotDownloaded
                }
            }
        } catch (e: Exception) {
            // The exception text is English and often internal; log it, show a translated line.
            Log.e(TAG, "Error reading APK status", e)
            ApkPreparationStatus.Error(getString(R.string.share_apk_error))
        }
    }
}
