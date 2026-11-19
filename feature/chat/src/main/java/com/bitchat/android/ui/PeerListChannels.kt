package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.feature.chat.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette

/** The joined channels card (P10, a11, a12). */
@Composable
internal fun ChannelsSection(
    channels: List<ChannelRowUi>,
    hasConversations: Boolean,
    onAction: (MeshPeerListAction) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_chat_bubbles,
            title = stringResource(R.string.channels),
            modifier = Modifier.padding(
                top = if (hasConversations) 20.dp else 8.dp
            )
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding)
                .padding(top = 10.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = AboutCardShape
        ) {
            Column {
                channels.forEachIndexed { index, row ->
                    if (index > 0) SheetCardDivider()
                    ChannelRow(
                        channel = row.name,
                        isSelected = row.isSelected,
                        unreadCount = row.unread,
                        colorScheme = colorScheme,
                        onChannelClick = {
                            onAction(MeshPeerListAction.ChannelClicked(row.name))
                        },
                        onLeaveChannel = {
                            onAction(MeshPeerListAction.LeaveChannel(row.name))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: String,
    isSelected: Boolean,
    unreadCount: Int,
    colorScheme: ColorScheme,
    onChannelClick: () -> Unit,
    onLeaveChannel: () -> Unit,
) {
    val palette = LocalBitchatPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChannelClick)
            .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(SheetRowLeadingSlot),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(SheetRowSelectedDot)
                        .background(colorScheme.primary, CircleShape)
                )
            } else if (unreadCount > 0) {
                UnreadBadge(count = unreadCount, colorScheme = colorScheme)
            } else {
                Text(
                    text = "#",
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.width(SheetRowLeadingGutter))

        Text(
            text = channel,
            fontFamily = BitchatFontFamily,
            fontSize = 14.sp,
            color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        CloseButton(onClick = onLeaveChannel)
    }
}
