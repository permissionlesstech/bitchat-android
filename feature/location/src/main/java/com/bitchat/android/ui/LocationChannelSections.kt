package com.bitchat.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.feature.location.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

/*
 * The cards of the location channels screen, one per lazy item, each drawn from its part of
 * LocationChannelsUiState. Every gesture goes to onAction; none of these takes the ViewModel.
 */

/** Mesh section: icon + title header, offline subtitle, then selection card (L5, b2). */
@Composable
internal fun MeshCard(
    row: MeshRowUi,
    onAction: (LocationChannelsAction) -> Unit,
) {
    Column {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_range,
            title = stringResource(R.string.mesh_title),
            subtitle = stringResource(R.string.mesh_section_subtitle),
            modifier = Modifier.padding(top = 8.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding)
                .padding(top = 10.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = AboutCardShape
        ) {
            ChannelOptionRow(
                title = stringResource(R.string.mesh_title),
                countText = peopleCountLabel(row.title),
                subtitle = stringResource(
                    if (row.wifiAwareEnabled) {
                        R.string.location_bluetooth_wifi_subtitle
                    } else {
                        R.string.location_bluetooth_subtitle
                    },
                    meshRangeString()
                ),
                isSelected = row.isSelected,
                participantCount = row.peopleCount,
                titleColor = MaterialTheme.colorScheme.secondary,
                titleBold = row.titleBold,
                onClick = { onAction(LocationChannelsAction.MeshClicked) }
            )
        }
    }
}

@Composable
internal fun LocationChannelsHeader() {
    SheetIconSectionHeader(
        iconRes = R.drawable.ic_spec_globe,
        title = stringResource(R.string.location_channels_heading),
        subtitle = stringResource(R.string.location_channels_desc),
        modifier = Modifier.padding(top = 20.dp)
    )
}

/** L6, b3, b4: the selected channel when it has no nearby row; always selected. */
@Composable
internal fun TeleportedCard(
    row: GeohashRowUi,
    onAction: (LocationChannelsAction) -> Unit,
) {
    Column {
        AboutSectionLabel(text = stringResource(R.string.cd_teleported))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding),
            color = MaterialTheme.colorScheme.surface,
            shape = AboutCardShape
        ) {
            ChannelOptionRow(
                title = "#${row.geohash}",
                countText = peopleCountLabel(row.title),
                subtitle = row.subtitle,
                isSelected = row.isSelected,
                participantCount = row.participantCount,
                titleColor = MaterialTheme.colorScheme.primary,
                titleBold = row.titleBold,
                trailingContent = {
                    ChannelBookmarkButton(
                        bookmarked = row.isBookmarked,
                        onClick = { onAction(LocationChannelsAction.BookmarkToggled(row.geohash)) }
                    )
                },
                onClick = {
                    onAction(LocationChannelsAction.ChannelClicked(ChannelRowId.Teleported(row.channel)))
                }
            )
        }
    }
}

/**
 * L7, b5, b6. Each row asks for its bookmark's name when it is composed, keyed by geohash, as the
 * row's own effect always did (Q-L15).
 */
@Composable
internal fun BookmarksCard(
    rows: List<GeohashRowUi>,
    onAction: (LocationChannelsAction) -> Unit,
) {
    Column {
        AboutSectionLabel(text = stringResource(R.string.bookmarked))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding),
            color = MaterialTheme.colorScheme.surface,
            shape = AboutCardShape
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) SheetCardDivider()
                    val gh = row.geohash

                    ChannelOptionRow(
                        title = "#$gh",
                        countText = peopleCountLabel(row.title),
                        subtitle = row.subtitle,
                        isSelected = row.isSelected,
                        participantCount = row.participantCount,
                        titleBold = row.titleBold,
                        trailingContent = {
                            ChannelBookmarkButton(
                                bookmarked = row.isBookmarked,
                                onClick = { onAction(LocationChannelsAction.BookmarkToggled(gh)) }
                            )
                        },
                        onClick = {
                            onAction(LocationChannelsAction.ChannelClicked(ChannelRowId.Bookmark(row.channel)))
                        }
                    )
                    LaunchedEffect(gh) {
                        onAction(LocationChannelsAction.BookmarkShown(gh))
                    }
                }
            }
        }
    }
}

/** b9: shown when effective location is on and the permission is denied (Q-L10). */
@Composable
internal fun PermissionDeniedNotice(onAction: (LocationChannelsAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.location_permission_denied),
            fontSize = 12.sp,
            fontFamily = BitchatFontFamily,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(
            onClick = { onAction(LocationChannelsAction.OpenSettingsClicked) },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                text = stringResource(R.string.open_settings),
                fontSize = 12.sp,
                fontFamily = BitchatFontFamily
            )
        }
    }
}

/**
 * Nearby location channels and the control for teleporting somewhere new (L3, L9, b7, b8, b10,
 * b12-b15). The typed geohash and its error belong to the Root (Q-L1).
 */
@Composable
internal fun NearbyChannelsCard(
    nearby: NearbyUi,
    appLocationEnabled: Boolean,
    effectiveLocationEnabled: Boolean,
    customGeohash: String,
    customError: String?,
    onCustomGeohashChange: (String) -> Unit,
    onAction: (LocationChannelsAction) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column {
        AboutSectionLabel(
            text = stringResource(R.string.location_channels_nearby)
        )
        if (!appLocationEnabled) {
            SheetDestructiveButton(
                text = stringResource(R.string.enable_location_services),
                isDestructive = false,
                onClick = { onAction(LocationChannelsAction.EnableLocationClicked) },
                modifier = Modifier.padding(
                    start = AboutHorizontalPadding,
                    end = AboutHorizontalPadding,
                    bottom = 10.dp
                )
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding),
            color = colorScheme.surface,
            shape = AboutCardShape
        ) {
            Column {
                if (effectiveLocationEnabled) {
                    if (nearby.rows.isNotEmpty()) {
                        nearby.rows.forEachIndexed { index, row ->
                            if (index > 0) SheetCardDivider()
                            val channel = row.channel

                            ChannelOptionRow(
                                title = channelLevelName(channel.level),
                                countText = peopleCountLabel(row.title),
                                subtitle = row.subtitle,
                                isSelected = row.isSelected,
                                participantCount = row.participantCount,
                                titleColor = colorScheme.primary,
                                titleBold = row.titleBold,
                                trailingContent = {
                                    ChannelBookmarkButton(
                                        bookmarked = row.isBookmarked,
                                        onClick = {
                                            onAction(LocationChannelsAction.BookmarkToggled(channel.geohash))
                                        }
                                    )
                                },
                                onClick = {
                                    onAction(LocationChannelsAction.ChannelClicked(ChannelRowId.Nearby(channel)))
                                }
                            )
                        }
                        SheetCardDivider()
                    } else if (nearby.showLoading) {
                        ChannelLoadingRow()
                        SheetCardDivider()
                    }
                }

                CustomGeohashRow(
                    customGeohash = customGeohash,
                    onGeohashChange = onCustomGeohashChange,
                    onOpenMap = { onAction(LocationChannelsAction.MapPickerClicked(customGeohash)) },
                    onTeleport = { onAction(LocationChannelsAction.TeleportClicked(customGeohash)) }
                )
            }
        }

        AnimatedVisibility(
            visible = customError != null,
            enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
                expandVertically(
                    tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
                ),
            exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
                shrinkVertically(
                    tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing)
                )
        ) {
            // Held across the exit animation: by the time it plays, the error
            // itself has already been cleared.
            val shownError = remember(customError) { customError ?: "" }
            Text(
                text = shownError,
                fontSize = 12.sp,
                fontFamily = BitchatFontFamily,
                color = colorScheme.error,
                modifier = Modifier.padding(
                    start = AboutHorizontalPadding + ChannelRowHorizontal,
                    top = 8.dp
                )
            )
        }
    }
}

/** L4, b16 */
@Composable
internal fun LocationNotesCard(
    subtitle: NotesSubtitle,
    onAction: (LocationChannelsAction) -> Unit,
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
        ChannelOptionRow(
            title = stringResource(R.string.cd_location_notes),
            countText = null,
            subtitle = notesSubtitleText(subtitle),
            isSelected = false,
            participantCount = 0,
            titleColor = colorScheme.primary,
            leadingIconRes = R.drawable.ic_spec_chat_bubbles,
            trailingContent = {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = { onAction(LocationChannelsAction.NotesClicked) }
        )
    }
}

@Composable
private fun notesSubtitleText(subtitle: NotesSubtitle): String = when (subtitle) {
    NotesSubtitle.Reveal -> stringResource(R.string.nearby_notes_reveal)
    NotesSubtitle.NoRelays -> stringResource(R.string.location_notes_relays_unavailable)
    NotesSubtitle.Loading -> stringResource(R.string.loading_location_notes)
    NotesSubtitle.One -> stringResource(R.string.nearby_notes_one)
    is NotesSubtitle.Many -> stringResource(R.string.nearby_notes_many, subtitle.n)
    NotesSubtitle.Empty -> stringResource(R.string.location_notes_empty_title)
}

/** L21, b17. The switch follows the stored mode (R2). */
@Composable
internal fun TorRoutingCard(
    tor: TorUi,
    onAction: (LocationChannelsAction) -> Unit,
) {
    Column {
        AboutSectionLabel(text = stringResource(R.string.about_network))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AboutHorizontalPadding),
            color = MaterialTheme.colorScheme.surface,
            shape = AboutCardShape
        ) {
            ChannelSettingsToggleRow(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.location_tor_routing_title),
                subtitle = stringResource(R.string.location_tor_routing_desc),
                checked = tor.enabled,
                enabled = tor.available,
                statusIndicator = {
                    BitchatBadge(text = stringResource(R.string.badge_recommended))
                },
                onCheckedChange = { enabled -> onAction(LocationChannelsAction.TorToggled(enabled)) }
            )
        }
        if (!tor.available) {
            Text(
                text = stringResource(R.string.tor_not_available_in_this_build),
                fontSize = 12.sp,
                fontFamily = BitchatFontFamily,
                color = LocalBitchatPalette.current.textTertiary,
                modifier = Modifier.padding(
                    start = AboutHorizontalPadding + ChannelRowHorizontal,
                    top = 8.dp
                )
            )
        }
    }
}

/** b11: shown while the app toggle is on. */
@Composable
internal fun LocationToggleButton(onAction: (LocationChannelsAction) -> Unit) {
    SheetDestructiveButton(
        text = stringResource(R.string.disable_location_services),
        isDestructive = true,
        onClick = { onAction(LocationChannelsAction.DisableLocationClicked) },
        modifier = Modifier.padding(
            start = AboutHorizontalPadding,
            end = AboutHorizontalPadding,
            top = 24.dp
        )
    )
}
