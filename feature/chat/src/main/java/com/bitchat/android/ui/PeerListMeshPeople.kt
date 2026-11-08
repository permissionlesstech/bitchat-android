package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.feature.chat.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.colorForPeer

/** The mesh "People" card: connected peers, then offline favourites (P8, P12-P25). */
@Composable
internal fun MeshPeopleSection(
    people: PeopleUi.Mesh,
    onAction: (MeshPeerListAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Column(modifier = modifier) {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_people,
            title = stringResource(R.string.people_count_title, people.count)
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding)
                .padding(top = 10.dp),
            color = colorScheme.surface,
            shape = AboutCardShape
        ) {
            Column {
                if (people.noneConnected) {
                    Text(
                        text = stringResource(id = R.string.no_one_connected),
                        fontFamily = BitchatFontFamily,
                        fontSize = 12.sp,
                        color = palette.textTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SheetRowHorizontal, vertical = SheetRowVertical)
                    )
                }

                // Keyed on identity, so the animated list animates reordering.
                AnimatedRowColumn(items = people.rows, key = { it.key }) { rowIndex, row ->
                    Column {
                        if (rowIndex > 0) SheetCardDivider()
                        PeerItem(
                            row = row,
                            colorScheme = colorScheme,
                            onItemClick = {
                                onAction(MeshPeerListAction.PersonClicked(row.openPeerID))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerItem(
    row: MeshPersonRowUi,
    colorScheme: ColorScheme,
    onItemClick: () -> Unit,
) {
    val isDirect = row.isDirect
    val isWifiAware = row.isWifiAware
    val isMe = row.isMe
    val connectionDescription = meshConnectionDescription(
        meshConnectionKind(isWifiAware = isWifiAware, isDirect = isDirect)
    )
    // Split display name for hashtag suffix support (iOS-compatible)
    val baseNameRaw = row.baseName
    val baseName = truncateNickname(baseNameRaw)
    val suffix = row.suffix

    // Get consistent peer color (iOS-compatible)
    val palette = LocalBitchatPalette.current
    val assignedColor = colorForPeer(row.colorKey, palette)
    val baseColor = if (isMe) palette.accentOrange else assignedColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .padding(horizontal = SheetRowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            name = baseNameRaw,
            color = baseColor,
            isFavorite = row.isFavorite,
            theyFavoritedUs = row.theyFavoritedUs,
            isVerified = row.isVerified,
            badge = {
                when {
                    row.isConnected -> Icon(
                        painter = painterResource(
                            conversationTransportIcon(
                                isReachedOverInternet = false,
                                isWifiAware = isWifiAware,
                                isDirect = isDirect
                            )
                        ),
                        contentDescription = connectionDescription,
                        modifier = Modifier.size(13.dp),
                        tint = colorScheme.primary
                    )

                    row.showNostrGlobe -> Icon(
                        painter = painterResource(R.drawable.ic_spec_globe),
                        contentDescription = stringResource(R.string.cd_reachable_via_nostr),
                        modifier = Modifier.size(13.dp),
                        tint = palette.accentPurple
                    )

                    else -> Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(R.string.cd_offline_favorite),
                        modifier = Modifier.size(11.dp),
                        tint = palette.textTertiary
                    )
                }
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = baseName,
                fontFamily = BitchatFontFamily,
                fontSize = 14.sp,
                fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    color = baseColor.copy(alpha = SUFFIX_ALPHA)
                )
            }
        }

        UnreadBadge(
            count = row.unreadCount,
            colorScheme = colorScheme,
            modifier = Modifier.padding(start = 4.dp)
        )

        if (row.isSelected) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(SheetRowSelectedDot)
                    .background(colorScheme.primary, CircleShape)
            )
        }
    }
}
