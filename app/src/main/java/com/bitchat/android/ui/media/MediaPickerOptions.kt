package com.bitchat.android.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R

/**
 * Media picker that offers image and file options
 * Clicking opens a quick selection menu
 */
@Composable
fun MediaPickerOptions(
    modifier: Modifier = Modifier,
    onImagePick: (() -> Unit)? = null,
    onFilePick: (() -> Unit)? = null
) {
    var showOptions by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Main button
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color = Color.Gray.copy(alpha = 0.5f))
                .clickable {
                    showOptions = true
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.cd_pick_media),
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }

        // Options menu (shown when clicked)
        if (showOptions) {
            Column(
                modifier = Modifier
                    .graphicsLayer {
                        translationY = -120f // Position above the button
                        scaleX = 0.8f
                        scaleY = 0.8f
                    }
                    .zIndex(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color = MaterialTheme.colorScheme.surface)
                    .clickable {
                        showOptions = false
                    }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Image option
                onImagePick?.let { imagePick ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(color = MaterialTheme.colorScheme.primaryContainer)
                            .clickable {
                                showOptions = false
                                imagePick()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.media_type_image),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // File option
                onFilePick?.let { filePick ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(color = MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                showOptions = false
                                filePick()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.media_type_file),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        // Clickable overlay to dismiss options
        if (showOptions) {
            Box(
                modifier = Modifier
                    .size(400.dp)
                    .clickable {
                        showOptions = false
                    }
            )
        }
    }
}
