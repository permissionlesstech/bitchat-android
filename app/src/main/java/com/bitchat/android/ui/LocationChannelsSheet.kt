package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.model.FestivalChannelInfo
import com.bitchat.android.model.FestivalChannels
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationChannelsSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onLocationNotesClick: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val activeChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val currentChannelName = activeChannel ?: FestivalChannels.GENERAL

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            val animatedDismiss = LocalSheetDismiss.current
            val selectChannel: (String) -> Unit = { name ->
                viewModel.joinChannel(name)
                coroutineScope.launch {
                    delay(150L)
                    animatedDismiss?.invoke() ?: onDismiss()
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                BitchatSheetTopBar(
                    onClose = onDismiss,
                    title = {
                        BitchatSheetTitle(text = "Festival Channels")
                    }
                )

                Text(
                    text = "Select a channel to view and send messages",
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(FestivalChannels.CHANNELS) { channelInfo ->
                        val isSelected = channelInfo.name.equals(currentChannelName, ignoreCase = true)
                        FestivalChannelRow(
                            channelInfo = channelInfo,
                            isSelected = isSelected,
                            onClick = { selectChannel(channelInfo.name) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FestivalChannelRow(
    channelInfo: FestivalChannelInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(12.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, colorScheme.primary, cardShape)
                } else {
                    Modifier
                }
            ),
        shape = cardShape,
        color = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.4f) else colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                tint = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channelInfo.name,
                    fontFamily = BitchatFontFamily,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = channelInfo.description,
                    fontFamily = BitchatFontFamily,
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

internal fun selectedLocationChannelOutsideNearby(
    selectedChannel: com.bitchat.android.geohash.ChannelID?,
    nearbyChannels: List<com.bitchat.android.geohash.GeohashChannel>
): com.bitchat.android.geohash.GeohashChannel? {
    if (selectedChannel !is com.bitchat.android.geohash.ChannelID.Location) return null
    val isNearby = nearbyChannels.any { it.geohash.equals(selectedChannel.channel.geohash, ignoreCase = true) }
    return if (!isNearby) selectedChannel.channel else null
}

internal fun channelForManualGeohash(input: String): com.bitchat.android.geohash.GeohashChannel? {
    val cleaned = input.trim().removePrefix("#").trim()
    val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz"
    if (cleaned.isEmpty() || !cleaned.lowercase().all { it in base32Chars }) return null
    val level = com.bitchat.android.geohash.GeohashChannelLevel.values().firstOrNull { it.precision == cleaned.length } ?: return null
    return com.bitchat.android.geohash.GeohashChannel(level = level, geohash = cleaned.lowercase())
}
