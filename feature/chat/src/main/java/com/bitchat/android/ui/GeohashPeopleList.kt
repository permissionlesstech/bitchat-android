package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.colorForPeer
import com.bitchat.android.feature.chat.R
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Geohash people list — card groups matching location / settings sheet rows (P26-P33).
 */
@Composable
internal fun GeohashPeopleSection(
    people: GeohashPeopleUi,
    onAction: (MeshPeerListAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_people,
            title = stringResource(R.string.people_count_title, people.count)
        )

        if (people.count == 0) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AboutHorizontalPadding)
                    .padding(top = 10.dp),
                color = colorScheme.surface,
                shape = AboutCardShape
            ) {
                Text(
                    text = stringResource(R.string.nobody_around),
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    color = palette.textTertiary,
                    modifier = Modifier.padding(
                        horizontal = SheetRowHorizontal,
                        vertical = SheetRowVertical
                    )
                )
            }
        } else {
            @Composable
            fun personRow(row: GeoPersonRowUi) {
                GeohashPersonItem(
                    row = row,
                    onTap = {
                        if (!row.isMe) {
                            onAction(MeshPeerListAction.GeohashPersonClicked(row.id))
                        }
                    }
                )
            }

            if (people.onLocation.isNotEmpty()) {
                AboutSectionLabel(text = stringResource(R.string.section_on_location))
                PeopleCard(
                    people = people.onLocation,
                    row = { personRow(it) }
                )
            }

            if (people.teleportedIn.isNotEmpty()) {
                AboutSectionLabel(text = stringResource(R.string.section_teleported_in))
                PeopleCard(
                    people = people.teleportedIn,
                    row = { personRow(it) }
                )
            }
        }
    }
}

/** One uncapped card of people. The enclosing sheet owns scrolling. */
@Composable
private fun PeopleCard(
    people: List<GeoPersonRowUi>,
    row: @Composable (GeoPersonRowUi) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .padding(top = 10.dp),
        color = colorScheme.surface,
        shape = AboutCardShape
    ) {
        AnimatedRowColumn(items = people, key = { it.id }) { index, person ->
            Column {
                if (index > 0) SheetCardDivider()
                row(person)
            }
        }
    }
}

@Composable
private fun GeohashPersonItem(
    row: GeoPersonRowUi,
    onTap: () -> Unit
) {
    val isMe = row.isMe
    val hasUnreadDM = row.hasUnreadDM
    val isTeleported = row.isTeleported
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    val statusIconRes =
        if (isTeleported) R.drawable.ic_spec_teleport
        else R.drawable.ic_spec_on_location_person

    val baseNameRaw = row.baseName
    val baseName = truncateNickname(baseNameRaw)
    val suffix = row.suffix
    val assignedColor = colorForPeer(row.colorKey, palette)
    val baseColor = if (isMe) palette.accentOrange else assignedColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = SheetRowHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            name = baseNameRaw,
            color = baseColor,
            badge = {
                Icon(
                    painter = painterResource(statusIconRes),
                    contentDescription = if (isTeleported) {
                        stringResource(R.string.cd_teleported)
                    } else {
                        stringResource(R.string.section_on_location)
                    },
                    modifier = Modifier.size(13.dp),
                    tint = if (isTeleported) palette.accentPurple else colorScheme.primary
                )
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
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
                    fontWeight = FontWeight.Normal,
                    color = baseColor.copy(alpha = SUFFIX_ALPHA)
                )
            }

            if (isMe) {
                Text(
                    text = stringResource(R.string.you_suffix),
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    color = baseColor
                )
            }
        }

        if (hasUnreadDM) {
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(R.string.cd_unread_message),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
                tint = palette.accentOrange
            )
        }
    }
}
