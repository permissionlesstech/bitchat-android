package com.bitchat.android.ui

import android.content.res.Configuration.UI_MODE_NIGHT_YES
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.feature.share.R
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.util.ApkDownloader
import com.bitchat.android.util.ShareableApkVariant
import com.bitchat.android.util.UniversalApkManager
import com.bitchat.android.util.downloadPhaseLabel

/**
 * The APK-sharing rows of About's Settings card: preparing the APK, sharing it over a hotspot
 * or through another app, and their dialogs. The rows stack in a plain Column, as they did in
 * the card. Stateless: [ShareAppSectionImpl] feeds it and handles what it sends back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareAppSectionContent(
    uiState: ApkUiState,
    onAction: (ApkAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val resources = LocalResources.current
    val colorScheme = MaterialTheme.colorScheme
    val apkStatus = uiState.apkStatus
    val downloadProgress = uiState.downloadProgress
    val shareableApk = uiState.shareableApk
    val availableUpdate = uiState.availableUpdate

    Column(modifier = modifier) {
        // Prepare App for Sharing Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = uiState.prepareRowEnabled) {
                    onAction(ApkAction.PrepareRowClicked)
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
                                        onAction(ApkAction.DownloadUniversalClicked)
                                    }
                            )
                        }
                    }
                }
                Text(
                    text = when (val status = apkStatus) {
                        is ApkPreparationStatus.Loading -> stringResource(R.string.checking)
                        is ApkPreparationStatus.NotDownloaded ->
                            stringResource(R.string.prepare_apk_status_not_downloaded)
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
                            // Only the transfer has a percentage worth showing; the other
                            // phases are named instead of pretending to be at 0%.
                            if (status.phase.hasMeasurableProgress) {
                                stringResource(R.string.prepare_apk_status_downloading, downloadProgress)
                            } else {
                                stringResource(downloadPhaseLabel(status.phase))
                            }
                        is ApkPreparationStatus.Resumable ->
                            stringResource(
                                R.string.prepare_apk_status_resumable,
                                resources.resolveApkFailureMessage(status.failure),
                                status.progressPercent
                            )
                        is ApkPreparationStatus.Error ->
                            resources.resolveApkFailureMessage(status.failure)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (apkStatus) {
                        is ApkPreparationStatus.Error -> colorScheme.error
                        is ApkPreparationStatus.Resumable -> colorScheme.primary
                        else -> colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    lineHeight = 16.sp
                )

                // Progress lives in the column, not the trailing slot, which leaves that slot
                // free for a single control.
                ApkDownloadProgressBar(
                    status = apkStatus,
                    progressPercent = downloadProgress
                )
            }

            // One control, one width, in every state. The progress readout moved into the
            // column above, so nothing else competes for this slot.
            when (apkStatus) {
                is ApkPreparationStatus.Downloading ->
                    ApkPrepareRowIconButton(
                        icon = Icons.Default.Close,
                        description = stringResource(R.string.prepare_apk_stop),
                        onClick = { onAction(ApkAction.CancelDownload) }
                    )
                is ApkPreparationStatus.Ready -> {
                    if (apkStatus.source == UniversalApkManager.ApkSource.INSTALLED) {
                        ApkPrepareRowIconButton(
                            icon = Icons.Default.CloudDownload,
                            description = stringResource(R.string.prepare_apk_get_universal),
                            onClick = { onAction(ApkAction.DownloadUniversalClicked) },
                            tint = colorScheme.primary
                        )
                    } else if (apkStatus.source == UniversalApkManager.ApkSource.DOWNLOADED) {
                        ApkPrepareRowIconButton(
                            icon = Icons.Default.Delete,
                            description = stringResource(R.string.prepare_apk_button_delete),
                            onClick = { onAction(ApkAction.DeleteClicked) },
                            tint = colorScheme.error
                        )
                    }
                }
                is ApkPreparationStatus.Resumable,
                is ApkPreparationStatus.Error ->
                    ApkPrepareRowIconButton(
                        icon = Icons.Default.Refresh,
                        description = stringResource(R.string.prepare_apk_retry),
                        onClick = { onAction(ApkAction.PrepareRowClicked) },
                        tint = colorScheme.primary
                    )
                else -> {}
            }
        }

        // Prepare Dialog
        if (uiState.showPrepareDialog) {
            AlertDialog(
                onDismissRequest = { onAction(ApkAction.DismissPrepareDialog) },
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
                            stringResource(R.string.prepare_apk_dialog_message_unknown_size)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(onClick = { onAction(ApkAction.ConfirmDownload) }) {
                        Text(stringResource(R.string.prepare_apk_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onAction(ApkAction.DismissPrepareDialog) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                containerColor = colorScheme.surface
            )
        }

        // Delete Dialog
        if (uiState.showDeleteDialog) {
            val sizeMB = (apkStatus as? ApkPreparationStatus.Ready)?.sizeMB ?: 0
            AlertDialog(
                onDismissRequest = { onAction(ApkAction.DismissDeleteDialog) },
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
                        onClick = { onAction(ApkAction.ConfirmDelete) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.prepare_apk_button_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onAction(ApkAction.DismissDeleteDialog) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                containerColor = colorScheme.surface
            )
        }

        AnimatedVisibility(
            visible = uiState.canShare,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colorScheme.outline.copy(alpha = 0.12f)
                )

                // === Share via Hotspot Row ===
                ShareRow(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.hotspot_share_via),
                    subtitle = stringResource(R.string.hotspot_share_via_subtitle),
                    onClick = { onAction(ApkAction.HotspotShareClicked) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    color = colorScheme.outline.copy(alpha = 0.12f)
                )

                // === Share via Bluetooth/Email Row (Fallback) ===
                ShareRow(
                    icon = Icons.Default.Bluetooth,
                    title = stringResource(R.string.hotspot_share_other),
                    subtitle = stringResource(R.string.hotspot_share_other_subtitle),
                    onClick = { onAction(ApkAction.AppShareClicked) }
                )

                // APK Share Dialog. It sits inside the visibility block, so it hides with the
                // rows while its flag stays set (Q-K3).
                ApkShareExplanationDialog(
                    show = uiState.showShareApkDialog,
                    onConfirm = { onAction(ApkAction.ConfirmAppShare) },
                    onDismiss = { onAction(ApkAction.DismissShareDialog) }
                )
            }
        }
    }
}

/** One of the two share rows: an icon, a title over a subtitle, and a chevron. */
@Composable
private fun ShareRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
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
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
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

// --- Previews ---

/** A synthetic ready APK, the default preview and test state. */
internal fun previewApkUiState(
    apkStatus: ApkPreparationStatus = ApkPreparationStatus.Ready(
        version = "1.0.0",
        sizeMB = 12,
        source = UniversalApkManager.ApkSource.DOWNLOADED,
        variant = ShareableApkVariant.UNIVERSAL
    ),
    downloadProgress: Int = 0
): ApkUiState = ApkUiState(apkStatus = apkStatus, downloadProgress = downloadProgress)

@Preview(name = "Ready")
@Preview(name = "Ready (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun ShareAppSectionReadyPreview() {
    BitchatTheme {
        Surface { ShareAppSectionContent(uiState = previewApkUiState(), onAction = {}) }
    }
}

@Preview(name = "Downloading 40%")
@Preview(name = "Downloading 40% (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun ShareAppSectionDownloadingPreview() {
    BitchatTheme {
        Surface {
            ShareAppSectionContent(
                uiState = previewApkUiState(
                    apkStatus = ApkPreparationStatus.Downloading(
                        phase = ApkDownloader.DownloadPhase.Transferring
                    ),
                    downloadProgress = 40
                ),
                onAction = {}
            )
        }
    }
}

@Preview(name = "Error")
@Preview(name = "Error (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun ShareAppSectionErrorPreview() {
    BitchatTheme {
        Surface {
            ShareAppSectionContent(
                uiState = previewApkUiState(
                    apkStatus = ApkPreparationStatus.Error(
                        ApkFailureMessage(messageRes = R.string.prepare_apk_error_generic)
                    )
                ),
                onAction = {}
            )
        }
    }
}
