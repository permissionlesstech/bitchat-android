package com.bitchat.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.feature.share.R
import com.bitchat.android.hotspot.HotspotActivity
import com.bitchat.android.share.ShareAppSection
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import com.bitchat.android.util.downloadPhaseLabel
import javax.inject.Inject

/**
 * The APK-sharing rows of About's Settings card, moved verbatim from AboutSheet:
 * preparing the APK, sharing it over a hotspot or through another app, and
 * their dialogs. The rows stack in a plain Column, as they did in the card.
 */
internal class ShareAppSectionImpl @Inject constructor() : ShareAppSection {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content(modifier: Modifier) {
        val context = LocalContext.current
        val colorScheme = MaterialTheme.colorScheme

        Column(modifier = modifier) {
            // === Prepare App for Sharing Section ===
            // Deliberately Activity-scoped, not entry-scoped. Under a
            // route the entry decorator would clear this on pop and kill
            // an in-flight download. Do not "simplify" this back to a
            // bare hiltViewModel().
            val activity = LocalActivity.current as ComponentActivity
            val apkViewModel: ApkDownloadViewModel = hiltViewModel(activity)
            val apkUiState by apkViewModel.state.collectAsStateWithLifecycle()
            val apkStatus = apkUiState.apkStatus
            val releaseStatus = apkUiState.releaseStatus
            val downloadProgress = apkUiState.downloadProgress
            val shareableApk = when (apkStatus) {
                is ApkPreparationStatus.Ready -> apkStatus
                is ApkPreparationStatus.Downloading ->
                    apkStatus.shareableFallback
                else -> null
            }
            val availableUpdate = (releaseStatus as? ApkReleaseStatus.Known)
                ?.takeIf { it.isNewerThanSharedApk }

            // Handle one-shot effects (navigation, toasts, share intents)
            LaunchedEffect(Unit) {
                apkViewModel.onEvent(ApkUiEvent.CheckStatus)
                apkViewModel.effect.collect { effect ->
                    when (effect) {
                        is ApkUiEffect.NavigateToHotspot -> {
                            val intent = Intent(context, HotspotActivity::class.java)
                            intent.putExtra(HotspotActivity.EXTRA_APK_PATH, effect.apkPath)
                            context.startActivity(intent)
                        }
                        is ApkUiEffect.ShareApk -> {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/vnd.android.package-archive"
                                putExtra(Intent.EXTRA_STREAM, effect.apkUri)
                                clipData = android.content.ClipData.newRawUri("", effect.apkUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = Intent.createChooser(intent, effect.chooserTitle).apply {
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(chooser)
                        }
                        is ApkUiEffect.ShowToast -> {
                            Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // Prepare App for Sharing Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Enabled by the same mapping that decides what the tap
                    // does, so the row can never look tappable and do
                    // nothing.
                    .clickable(
                        enabled = prepareRowTapAction(
                            apkStatus,
                            releaseStatus
                        ) != null
                    ) {
                        apkViewModel.onEvent(ApkUiEvent.PrepareRowClicked)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (shareableApk != null) {
                        Icons.Default.Share
                    } else {
                        Icons.Default.CloudDownload
                    },
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (shareableApk != null) {
                                stringResource(R.string.prepare_apk_ready_title)
                            } else {
                                stringResource(R.string.prepare_apk_title)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface
                        )
                        if (availableUpdate != null) {
                            TooltipBox(
                                positionProvider = TooltipDefaults
                                    .rememberTooltipPositionProvider(),
                                tooltip = {
                                    PlainTooltip {
                                        Text(
                                            stringResource(
                                                R.string.prepare_apk_update_available,
                                                availableUpdate.version
                                            )
                                        )
                                    }
                                },
                                state = rememberTooltipState()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = stringResource(
                                        R.string.prepare_apk_update_warning
                                    ),
                                    tint = colorScheme.tertiary,
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .size(18.dp)
                                        .clickable {
                                            apkViewModel.onEvent(
                                                ApkUiEvent.DownloadUniversalClicked
                                            )
                                        }
                                )
                            }
                        }
                    }
                    Text(
                        text = when (val status = apkStatus) {
                            is ApkPreparationStatus.Loading -> stringResource(R.string.checking)
                            is ApkPreparationStatus.NotDownloaded ->
                                stringResource(
                                    R.string.prepare_apk_status_not_downloaded
                                )
                            is ApkPreparationStatus.Ready -> {
                                val source = when {
                                    status.source == UniversalApkManager.ApkSource.DOWNLOADED ->
                                        stringResource(R.string.prepare_apk_source_downloaded)
                                    status.variant == ShareableApkVariant.ARM64 ->
                                        stringResource(R.string.prepare_apk_source_installed_arm64)
                                    else ->
                                        stringResource(R.string.prepare_apk_source_installed)
                                }
                                stringResource(
                                    R.string.prepare_apk_ready_detail,
                                    status.version,
                                    status.sizeMB,
                                    source
                                )
                            }
                            is ApkPreparationStatus.Downloading ->
                                // Only the transfer has a percentage worth
                                // showing; the other phases are named
                                // instead of pretending to be at 0%.
                                if (status.phase.hasMeasurableProgress) {
                                    stringResource(R.string.prepare_apk_status_downloading, downloadProgress)
                                } else {
                                    stringResource(downloadPhaseLabel(status.phase))
                                }
                            is ApkPreparationStatus.Resumable ->
                                stringResource(
                                    R.string.prepare_apk_status_resumable,
                                    context.resolveApkFailureMessage(
                                        status.failure
                                    ),
                                    status.progressPercent
                                )
                            is ApkPreparationStatus.Error ->
                                context.resolveApkFailureMessage(
                                    status.failure
                                )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (apkStatus) {
                            is ApkPreparationStatus.Error -> colorScheme.error
                            is ApkPreparationStatus.Resumable -> colorScheme.primary
                            else -> colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                        lineHeight = 16.sp
                    )

                    // Progress lives in the column, not the trailing slot,
                    // which leaves that slot free for a single control.
                    ApkDownloadProgressBar(
                        status = apkStatus,
                        progressPercent = downloadProgress
                    )
                }

                // One control, one width, in every state. The progress
                // readout moved into the column above, so nothing else
                // competes for this slot.
                when (apkStatus) {
                    is ApkPreparationStatus.Downloading ->
                        ApkPrepareRowIconButton(
                            icon = Icons.Default.Close,
                            description = stringResource(
                                R.string.prepare_apk_stop
                            ),
                            onClick = {
                                apkViewModel.onEvent(
                                    ApkUiEvent.CancelDownload
                                )
                            }
                        )
                    is ApkPreparationStatus.Ready -> {
                        if (
                            apkStatus.source ==
                            UniversalApkManager.ApkSource.INSTALLED
                        ) {
                            ApkPrepareRowIconButton(
                                icon = Icons.Default.CloudDownload,
                                description = stringResource(
                                    R.string.prepare_apk_get_universal
                                ),
                                onClick = {
                                    apkViewModel.onEvent(
                                        ApkUiEvent.DownloadUniversalClicked
                                    )
                                },
                                tint = colorScheme.primary
                            )
                        } else if (
                            apkStatus.source ==
                            UniversalApkManager.ApkSource.DOWNLOADED
                        ) {
                            ApkPrepareRowIconButton(
                                icon = Icons.Default.Delete,
                                description = stringResource(
                                    R.string.prepare_apk_button_delete
                                ),
                                onClick = {
                                    apkViewModel.onEvent(
                                        ApkUiEvent.DeleteClicked
                                    )
                                },
                                tint = colorScheme.error
                            )
                        }
                    }
                    is ApkPreparationStatus.Resumable,
                    is ApkPreparationStatus.Error ->
                        ApkPrepareRowIconButton(
                            icon = Icons.Default.Refresh,
                            description = stringResource(
                                R.string.prepare_apk_retry
                            ),
                            onClick = {
                                apkViewModel.onEvent(
                                    ApkUiEvent.PrepareRowClicked
                                )
                            },
                            tint = colorScheme.primary
                        )
                    else -> {}
                }
            }

            // Prepare Dialog
            if (apkUiState.showPrepareDialog) {
                AlertDialog(
                    onDismissRequest = { apkViewModel.onEvent(ApkUiEvent.DismissPrepareDialog) },
                    title = {
                        Text(
                            text = stringResource(
                                if (availableUpdate != null) {
                                    R.string.prepare_apk_update_dialog_title
                                } else {
                                    R.string.prepare_apk_dialog_title
                                }
                            ),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    text = {
                        Text(
                            text = if (availableUpdate != null) {
                                stringResource(
                                    R.string.prepare_apk_update_dialog_message,
                                    availableUpdate.version,
                                    availableUpdate.sizeMB
                                )
                            } else {
                                stringResource(
                                    R.string.prepare_apk_dialog_message_unknown_size
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            apkViewModel.onEvent(ApkUiEvent.ConfirmDownload)
                        }) {
                            Text(stringResource(R.string.prepare_apk_dialog_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { apkViewModel.onEvent(ApkUiEvent.DismissPrepareDialog) }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                    containerColor = colorScheme.surface
                )
            }

            // Delete Dialog
            if (apkUiState.showDeleteDialog) {
                val sizeMB = (apkStatus as? ApkPreparationStatus.Ready)?.sizeMB ?: 0
                AlertDialog(
                    onDismissRequest = { apkViewModel.onEvent(ApkUiEvent.DismissDeleteDialog) },
                    title = {
                        Text(
                            text = stringResource(R.string.prepare_apk_delete_confirm),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.prepare_apk_delete_message, sizeMB),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                apkViewModel.onEvent(ApkUiEvent.ConfirmDelete)
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = colorScheme.error
                            )
                        ) {
                            Text(
                                stringResource(
                                    R.string.prepare_apk_button_delete
                                )
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { apkViewModel.onEvent(ApkUiEvent.DismissDeleteDialog) }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                    containerColor = colorScheme.surface
                )
            }

            // A GitHub update is optional. Keep sharing visible while the
            // replacement downloads or while metadata refreshes.
            val canShareAPK = shareableApk != null

            AnimatedVisibility(
                visible = canShareAPK,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = colorScheme.outline.copy(alpha = 0.12f)
                    )

                    // === Share via Hotspot Row ===
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                apkViewModel.onEvent(ApkUiEvent.HotspotShareClicked)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.hotspot_share_via),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.hotspot_share_via_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurface.copy(alpha = 0.6f),
                        lineHeight = 16.sp
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
                    }

                    HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp),
                color = colorScheme.outline.copy(alpha = 0.12f)
            )

            // === Share via Bluetooth/Email Row (Fallback) ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { apkViewModel.onEvent(ApkUiEvent.AppShareClicked) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.hotspot_share_other),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.hotspot_share_other_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurface.copy(alpha = 0.6f),
                        lineHeight = 16.sp
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp)
                )
            }

                    // APK Share Dialog
                    ApkShareExplanationDialog(
                        show = apkUiState.showShareApkDialog,
                        onConfirm = {
                            apkViewModel.onEvent(ApkUiEvent.ConfirmAppShare)
                        },
                        onDismiss = { apkViewModel.onEvent(ApkUiEvent.DismissShareDialog) }
                    )
                }
            }
        }
    }
}

/**
 * Dialog explaining APK sharing feature before sharing
 */
@Composable
private fun ApkShareExplanationDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        val colorScheme = MaterialTheme.colorScheme

        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.share_apk_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.share_apk_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )

                    // Info box with receiver instructions
                    Surface(
                        color = colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.share_apk_receiver_instructions),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurface.copy(alpha = 0.8f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onConfirm) {
                    Text(
                        text = stringResource(R.string.share_apk_confirm),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
