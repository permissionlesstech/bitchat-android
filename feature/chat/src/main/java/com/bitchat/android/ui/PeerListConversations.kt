package com.bitchat.android.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.feature.chat.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.colorForPeer
import kotlinx.coroutines.delay

/**
 * The conversations section of the list: header, search, status, then the online and offline
 * groups (P6, P7, P9, a3-a8). Item keys are the ones the list always used.
 */
internal fun LazyListScope.conversationsSection(
    conversations: ConversationsUi,
    query: String,
    onAction: (MeshPeerListAction) -> Unit,
    onDeleteRequested: (ConversationRowUi) -> Unit,
) {
    item(key = "private_conversations_header") {
        SheetIconSectionHeader(
            iconRes = R.drawable.ic_spec_envelope,
            title = stringResource(R.string.conversations),
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (conversations.showSearch) {
        item(key = "private_conversations_search") {
            ConversationSearchField(
                query = query,
                onQueryChange = { onAction(MeshPeerListAction.QueryChanged(it)) },
            )
        }
    }

    when (conversations.status) {
        ConversationStatusKind.Loading -> {
            item(key = "private_conversations_loading") {
                ConversationSectionStatus(
                    icon = { CircularProgressIndicator(Modifier.size(20.dp)) },
                    text = stringResource(R.string.loading_conversations)
                )
            }
        }

        ConversationStatusKind.Error -> {
            item(key = "private_conversations_error") {
                ConversationSectionStatus(
                    icon = {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    text = stringResource(R.string.conversation_storage_error)
                )
            }
        }

        ConversationStatusKind.Empty -> {
            item(key = "private_conversations_empty") {
                ConversationSectionStatus(
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_spec_envelope),
                            contentDescription = null
                        )
                    },
                    text = stringResource(R.string.no_conversations_yet)
                )
            }
        }

        ConversationStatusKind.NoResults -> {
            item(key = "private_conversations_no_results") {
                ConversationSectionStatus(
                    icon = {
                        Icon(Icons.Outlined.SearchOff, contentDescription = null)
                    },
                    text = stringResource(R.string.no_conversation_results)
                )
            }
        }

        null -> Unit
    }

    conversationGroup(
        labelKey = "private_conversations_online_label",
        labelRes = R.string.online_conversations,
        rows = conversations.online,
        onAction = onAction,
        onDeleteRequested = onDeleteRequested,
    )
    conversationGroup(
        labelKey = "private_conversations_offline_label",
        labelRes = R.string.offline_conversations,
        rows = conversations.offline,
        onAction = onAction,
        onDeleteRequested = onDeleteRequested,
    )
}

private fun LazyListScope.conversationGroup(
    labelKey: String,
    labelRes: Int,
    rows: List<ConversationRowUi>,
    onAction: (MeshPeerListAction) -> Unit,
    onDeleteRequested: (ConversationRowUi) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = labelKey) {
        ConversationGroupLabel(text = stringResource(labelRes))
    }
    itemsIndexed(
        items = rows,
        key = { _, row -> "conversation:${row.conversationID}" }
    ) { index, row ->
        ConversationSwipeItem(
            row = row,
            isFirst = index == 0,
            isLast = index == rows.lastIndex,
            onAction = onAction,
            onDeleteRequested = onDeleteRequested,
            modifier = Modifier.animateItem()
        )
    }
}

@Composable
private fun ConversationSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .padding(top = 10.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = BitchatFontFamily
        ),
        placeholder = {
            Text(
                stringResource(R.string.search_conversations),
                fontFamily = BitchatFontFamily
            )
        },
        leadingIcon = {
            Icon(Icons.Outlined.Search, contentDescription = null)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.clear)
                    )
                }
            }
        } else {
            null
        },
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun ConversationSectionStatus(
    icon: @Composable () -> Unit,
    text: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = AboutCardShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = BitchatFontFamily
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationGroupLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding + 4.dp)
            .padding(top = 12.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = BitchatFontFamily,
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** a9, a10: asks before deleting; the Root owns whether it is shown (Q-P13). */
@Composable
internal fun DeleteConversationDialog(
    displayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null
            )
        },
        title = {
            Text(
                text = stringResource(R.string.delete_conversation_title),
                fontFamily = BitchatFontFamily
            )
        },
        text = {
            Text(
                text = stringResource(R.string.delete_conversation_message, displayName),
                fontFamily = BitchatFontFamily
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = BitchatFontFamily
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(android.R.string.cancel),
                    fontFamily = BitchatFontFamily
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSwipeItem(
    row: ConversationRowUi,
    isFirst: Boolean,
    isLast: Boolean,
    onAction: (MeshPeerListAction) -> Unit,
    onDeleteRequested: (ConversationRowUi) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val hapticFeedback = LocalHapticFeedback.current
    val deleteDescription = stringResource(R.string.delete_conversation_action)
    val toggleRead = {
        onAction(MeshPeerListAction.SwipeToggleRead(row.conversationID, row.unreadCount > 0))
    }
    val dismissState = rememberSwipeToDismissBoxState()
    val shape = RoundedCornerShape(
        topStart = if (isFirst) 14.dp else 0.dp,
        topEnd = if (isFirst) 14.dp else 0.dp,
        bottomStart = if (isLast) 14.dp else 0.dp,
        bottomEnd = if (isLast) 14.dp else 0.dp
    )

    // Keyed on the settled value: currentValue flips mid-drag, and a reset() started then is
    // interrupted by the drag and its fling, which leave the row swiped off.
    LaunchedEffect(dismissState.settledValue, row.conversationID) {
        when (dismissState.settledValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                toggleRead()
                dismissState.reset()
            }

            SwipeToDismissBoxValue.EndToStart -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onDeleteRequested(row)
                dismissState.reset()
            }

            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AboutHorizontalPadding)
            .clip(shape),
        color = colorScheme.surface,
        shape = shape
    ) {
        Column {
            if (!isFirst) SheetCardDivider()
            SwipeToDismissBox(
                state = dismissState,
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = true,
                backgroundContent = {
                    val markRead = row.unreadCount > 0
                    val startToEnd =
                        dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (startToEnd) {
                                    colorScheme.secondaryContainer
                                } else {
                                    colorScheme.errorContainer
                                }
                            )
                            .padding(horizontal = SheetRowHorizontal),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (startToEnd) {
                            Arrangement.Start
                        } else {
                            Arrangement.End
                        }
                    ) {
                        Icon(
                            imageVector = if (startToEnd) {
                                if (markRead) Icons.Outlined.MarkEmailRead
                                else Icons.Outlined.MarkEmailUnread
                            } else {
                                Icons.Outlined.Delete
                            },
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (startToEnd) {
                                stringResource(
                                    if (markRead) R.string.mark_read else R.string.mark_unread
                                )
                            } else {
                                stringResource(R.string.delete)
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = BitchatFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            ) {
                ConversationRow(
                    row = row,
                    deleteDescription = deleteDescription,
                    onClick = {
                        onAction(MeshPeerListAction.ConversationClicked(row.conversationID))
                    },
                    onTogglePinned = {
                        onAction(MeshPeerListAction.TogglePinned(row.conversationID))
                    },
                    onToggleMuted = {
                        onAction(MeshPeerListAction.ToggleMuted(row.conversationID))
                    },
                    onReadStateRequested = toggleRead,
                    onDeleteRequested = { onDeleteRequested(row) }
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    row: ConversationRowUi,
    deleteDescription: String,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleMuted: () -> Unit,
    onReadStateRequested: () -> Unit,
    onDeleteRequested: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme
    var showActions by remember { mutableStateOf(false) }
    val isWifiAware = row.isWifiAware
    val isDirect = row.isDirect
    val connectionDescription = meshConnectionDescription(row.connection)
    val basePreview = when (val kind = row.previewKind) {
        is ConversationPreviewKind.Draft -> stringResource(
            R.string.conversation_draft_preview,
            kind.text
        )
        ConversationPreviewKind.Image -> stringResource(R.string.notification_sent_image)
        ConversationPreviewKind.Voice -> stringResource(R.string.notification_sent_voice)
        ConversationPreviewKind.File -> stringResource(R.string.notification_sent_file)
        is ConversationPreviewKind.Literal -> kind.text
    }
    val messagePreview = if (row.previewIsOutgoing) {
        stringResource(R.string.conversation_you_preview, basePreview)
    } else {
        basePreview
    }
    val presenceDescription = when (row.presence) {
        ConversationPresence.Connected -> connectionDescription
        ConversationPresence.ReachableViaNostr ->
            stringResource(R.string.offline_reachable_via_nostr)
        ConversationPresence.NotInMesh -> stringResource(R.string.offline_not_in_mesh)
    }
    val assignedColor = colorForPeer(row.colorKey, palette)
    val baseNameRaw = row.baseName
    val suffix = row.suffix
    // The UI clock: recomputed every minute while the row is composed.
    val relativeTime by produceState(
        initialValue = conversationRelativeTime(row.latestMessageAt, System.currentTimeMillis()),
        row.latestMessageAt
    ) {
        while (true) {
            value = conversationRelativeTime(row.latestMessageAt, System.currentTimeMillis())
            delay(DateUtils.MINUTE_IN_MILLIS)
        }
    }
    val unreadDescription = if (row.unreadCount > 0) {
        stringResource(R.string.conversation_unread_count, row.unreadCount)
    } else {
        stringResource(R.string.conversation_read)
    }
    val pinDescription = stringResource(
        if (row.isPinned) R.string.unpin_conversation else R.string.pin_conversation
    )
    val muteDescription = stringResource(
        if (row.isMuted) R.string.unmute_conversation else R.string.mute_conversation
    )
    val readActionDescription = stringResource(
        if (row.unreadCount > 0) R.string.mark_read else R.string.mark_unread
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(
                    row.displayName,
                    unreadDescription,
                    messagePreview,
                    relativeTime,
                    presenceDescription
                ).joinToString(", ")
                stateDescription = presenceDescription
                customActions = listOf(
                    CustomAccessibilityAction(readActionDescription) {
                        onReadStateRequested()
                        true
                    },
                    CustomAccessibilityAction(pinDescription) {
                        onTogglePinned()
                        true
                    },
                    CustomAccessibilityAction(muteDescription) {
                        onToggleMuted()
                        true
                    },
                    CustomAccessibilityAction(deleteDescription) {
                        onDeleteRequested()
                        true
                    }
                )
            }
            .clickable(onClick = onClick)
            .padding(
                horizontal = SheetRowHorizontal,
                vertical = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(
            name = baseNameRaw,
            color = assignedColor,
            isFavorite = row.isFavorite,
            theyFavoritedUs = row.theyFavoritedUs,
            isVerified = row.isVerified,
            badge = {
                when (row.presence) {
                    ConversationPresence.Connected -> Icon(
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

                    ConversationPresence.ReachableViaNostr -> Icon(
                        painter = painterResource(R.drawable.ic_spec_globe),
                        contentDescription = stringResource(
                            R.string.offline_reachable_via_nostr
                        ),
                        modifier = Modifier.size(13.dp),
                        tint = palette.accentPurple
                    )

                    ConversationPresence.NotInMesh -> Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(R.string.offline_not_in_mesh),
                        modifier = Modifier.size(11.dp),
                        tint = palette.textTertiary
                    )
                }
            }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = truncateNickname(baseNameRaw),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = BitchatFontFamily,
                        fontWeight = if (row.unreadCount > 0) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        }
                    ),
                    color = assignedColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = BitchatFontFamily,
                            fontWeight = FontWeight.Medium
                        ),
                        color = assignedColor.copy(alpha = SUFFIX_ALPHA)
                    )
                }
                if (row.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = pinDescription,
                        modifier = Modifier.size(14.dp),
                        tint = palette.textTertiary
                    )
                }
                if (row.isMuted) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = muteDescription,
                        modifier = Modifier.size(14.dp),
                        tint = palette.textTertiary
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = messagePreview,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = BitchatFontFamily,
                        fontWeight = if (row.hasDraft) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        }
                    ),
                    color = if (row.hasDraft) {
                        palette.accentOrange
                    } else {
                        palette.textTertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = stringResource(
                        R.string.conversation_preview_timestamp,
                        relativeTime
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = BitchatFontFamily
                    ),
                    color = palette.textTertiary,
                    maxLines = 1
                )
            }
        }

        UnreadBadge(
            count = row.unreadCount,
            colorScheme = colorScheme,
            modifier = Modifier.padding(start = 4.dp)
        )

        Box {
            IconButton(
                onClick = { showActions = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.conversation_actions),
                    tint = palette.textTertiary
                )
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false }
            ) {
                DropdownMenuItem(
                    text = { Text(pinDescription) },
                    leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                    onClick = {
                        showActions = false
                        onTogglePinned()
                    }
                )
                DropdownMenuItem(
                    text = { Text(muteDescription) },
                    leadingIcon = {
                        Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
                    },
                    onClick = {
                        showActions = false
                        onToggleMuted()
                    }
                )
                DropdownMenuItem(
                    text = { Text(readActionDescription) },
                    leadingIcon = {
                        Icon(
                            if (row.unreadCount > 0) {
                                Icons.Outlined.MarkEmailRead
                            } else {
                                Icons.Outlined.MarkEmailUnread
                            },
                            contentDescription = null
                        )
                    },
                    onClick = {
                        showActions = false
                        onReadStateRequested()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.delete),
                            color = colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = colorScheme.error
                        )
                    },
                    onClick = {
                        showActions = false
                        onDeleteRequested()
                    }
                )
            }
        }
    }
}

/** A conversation's age, "5 min. ago" style, against [nowMillis]. */
internal fun conversationRelativeTime(timestamp: Long, nowMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        nowMillis,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
