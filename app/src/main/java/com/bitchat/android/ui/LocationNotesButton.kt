package com.bitchat.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.geohash.ChannelID

/**
 * Unified notices button for both mesh and geohash timelines.
 */
@Composable
fun LocationNotesButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val boardPosts by viewModel.boardManager.posts.collectAsStateWithLifecycle()
    val unseenScopes by viewModel.boardManager.unseenScopes.collectAsStateWithLifecycle()
    val currentScope = when (val selected = selectedLocationChannel) {
        is ChannelID.Location -> selected.channel.geohash
        else -> ""
    }
    val hasNotices = boardPosts.any { it.geohash == currentScope }
    val hasUnseen = currentScope in unseenScopes

    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
    ) {
        Icon(
            imageVector = if (hasUnseen) Icons.Filled.PushPin else Icons.Outlined.PushPin,
            contentDescription = stringResource(R.string.cd_notices),
            modifier = Modifier.size(19.dp),
            tint = when {
                hasUnseen -> Color(0xFFFF9500)
                hasNotices -> Color(0xFFFF9500)
                else -> Color.Gray
            }
        )
    }
}
