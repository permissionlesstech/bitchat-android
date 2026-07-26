package com.bitchat.android.ui


import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.button.BitChatBrandButton
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Header components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

/** Height of the chat top bar. Taller than the old 42.dp so 40.dp tap targets fit properly. */
val ChatHeaderHeight = 48.dp

/** Standard icon size in the header. */
private val HeaderIconSize = 18.dp

/** Minimum tap target for every interactive element in the header. */
private val HeaderTapTarget = 40.dp

/** Corner radius for the header's tappable label+icon clusters. */
private val HeaderClusterShape = RoundedCornerShape(8.dp)

/**
 * A minimum-48x40 tap target wrapping a small icon.
 *
 * The old header used bare 16.dp icons with `Modifier.clickable`, which produced tap targets far
 * below the accessibility minimum and made the channel/bookmark controls genuinely hard to hit.
 */
@Composable
private fun HeaderIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(HeaderTapTarget)
            .clip(CircleShape)
            .clickable(onClickLabel = contentDescription) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun TorStatusDot(
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val torProvider = remember { com.bitchat.android.net.ArtiTorManager.getInstance() }
    val torStatus by torProvider.statusFlow.collectAsState()
    
    if (torStatus.mode != com.bitchat.android.net.TorMode.OFF) {
        val targetColor = when {
            torStatus.running && torStatus.bootstrapPercent < 100 -> palette.accentOrange
            torStatus.running && torStatus.bootstrapPercent >= 100 -> palette.accentGreen
            else -> palette.accentRed
        }
        // Cross-fade rather than snap: Tor flips through bootstrapping states frequently and a
        // hard colour cut in the corner of the screen reads as a glitch.
        val dotColor by animateColorAsState(
            targetValue = targetColor,
            animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
            label = "torDotColor"
        )
        Canvas(
            modifier = modifier
        ) {
            val radius = size.minDimension / 2
            drawCircle(
                color = dotColor,
                radius = radius,
                center = Offset(size.width / 2, size.height / 2)
            )
        }
    }
}

@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val (icon, color, contentDescription) = when (sessionState) {
        "uninitialized" -> Triple(
            Icons.Outlined.NoEncryption,
            Color(0x87878700), // Grey - ready to establish
            stringResource(R.string.cd_ready_for_handshake)
        )
        "handshaking" -> Triple(
            Icons.Outlined.Sync,
            Color(0x87878700), // Grey - in progress
            stringResource(R.string.cd_handshake_in_progress)
        )
        "established" -> Triple(
            Icons.Filled.Lock,
            Color(0xFFFF9500), // Orange - secure
            stringResource(R.string.cd_encrypted)
        )
        else -> { // "failed" or any other state
            Triple(
                Icons.Outlined.Warning,
                Color(0xFFFF4444), // Red - error
                stringResource(R.string.cd_handshake_failed)
            )
        }
    }
    
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = color
    )
}

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    
    // Auto-scroll to end when text changes (simulates cursor following)
    LaunchedEffect(value) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.at_symbol),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.primary.copy(alpha = 0.7f)
        )
        
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { 
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 140.dp)
                .horizontalScroll(scrollState)
        )
    }
}

@Composable
fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    isConnected: Boolean,
    selectedLocationChannel: com.bitchat.android.geohash.ChannelID?,
    geohashPeople: List<GeoPerson>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current

    // Compute channel-aware people count and color (matches iOS logic exactly)
    val (peopleCount, countColor) = when (selectedLocationChannel) {
        is com.bitchat.android.geohash.ChannelID.Location -> {
            // Geohash channel: show geohash participants
            val count = geohashPeople.size
            Pair(count, if (count > 0) palette.accentGreen else palette.textTertiary)
        }
        is com.bitchat.android.geohash.ChannelID.Mesh,
        null -> {
            // Mesh channel: show Bluetooth-connected peers (excluding self)
            val count = connectedPeers.size
            Pair(count, if (isConnected && count > 0) palette.accentBlue else palette.textTertiary)
        }
    }

    // Peers come and go constantly; fading the tint avoids a flicker every time the count
    // crosses zero.
    val animatedCountColor by animateColorAsState(
        targetValue = countColor,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "peerCountColor"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(HeaderClusterShape)
            .clickable { onClick() }
            .height(HeaderTapTarget)
            .padding(horizontal = 6.dp)
    ) {
        Icon(
            // `Groups` reads as a crowd at 18.dp, where `Group`'s two-person silhouette turns
            // into an indistinct blob.
            imageVector = Icons.Filled.Groups,
            contentDescription = when (selectedLocationChannel) {
                is com.bitchat.android.geohash.ChannelID.Location -> stringResource(R.string.cd_geohash_participants)
                else -> stringResource(R.string.cd_connected_peers)
            },
            modifier = Modifier.size(HeaderIconSize),
            tint = animatedCountColor
        )

        Text(
            text = "$peopleCount",
            style = MaterialTheme.typography.bodyMedium,
            color = animatedCountColor,
            fontWeight = FontWeight.Medium
        )
        
        if (joinedChannels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.channel_count_prefix) + "${joinedChannels.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) palette.accentGreen else palette.accentRed,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ChatHeaderContent(
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    onBackClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    when {
        currentChannel != null -> {
            // Channel header
            ChannelHeader(
                channel = currentChannel,
                onBackClick = onBackClick,
                onLeaveChannel = { viewModel.leaveChannel(currentChannel) },
                onSidebarClick = onSidebarClick
            )
        }
        else -> {
            // Main header
            MainHeader(
                nickname = nickname,
                onNicknameChange = viewModel::setNickname,
                onTitleClick = onShowAppInfo,
                onTripleTitleClick = onTripleClick,
                onSidebarClick = onSidebarClick,
                onLocationChannelsClick = onLocationChannelsClick,
                onLocationNotesClick = onLocationNotesClick,
                viewModel = viewModel
            )
        }
    }
}



@Composable
private fun ChannelHeader(
    channel: String,
    onBackClick: () -> Unit,
    onLeaveChannel: () -> Unit,
    onSidebarClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current

    Box(modifier = Modifier.fillMaxWidth()) {
        // Back: a chevron alone is unambiguous here and buys back ~40.dp of title space that
        // the old "< back" label consumed.
        HeaderIconButton(
            onClick = onBackClick,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(20.dp),
                tint = colorScheme.primary
            )
        }

        // Title - perfectly centered regardless of other elements
        Text(
            text = "#$channel",
            style = MaterialTheme.typography.titleMedium,
            color = colorScheme.primary,
            modifier = Modifier
                .align(Alignment.Center)
                .clip(HeaderClusterShape)
                .clickable { onSidebarClick() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Leave button - positioned on the right
        TextButton(
            onClick = onLeaveChannel,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Text(
                text = stringResource(R.string.chat_leave),
                style = MaterialTheme.typography.labelMedium,
                color = palette.accentRed
            )
        }
    }
}

@Composable
private fun MainHeader(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    onTitleClick: () -> Unit,
    onTripleTitleClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    viewModel: ChatViewModel
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val geohashPeople by viewModel.geohashPeople.collectAsStateWithLifecycle()

    // Bookmarks store for current geohash toggle (iOS parity)
    val context = androidx.compose.ui.platform.LocalContext.current
    val bookmarksStore = remember { com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(context) }
    val bookmarks by bookmarksStore.bookmarks.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // MARK: - Identity cluster
        BitChatBrandButton(
            onClick = onTitleClick,
            onTripleClick = onTripleTitleClick,
            contentDescription = stringResource(R.string.cd_open_about),
        )

        Text(
            text = "/",
            style = MaterialTheme.typography.bodyMedium,
            // Dimmed: the slash is a separator, not content. At full brightness it competed
            // with the nickname beside it.
            color = colorScheme.primary.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        NicknameEditor(
            value = nickname,
            onValueChange = onNicknameChange
        )

        Spacer(modifier = Modifier.weight(1f))

        // MARK: - Status cluster. Order matches the design: bookmark, channel, people.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Tight, because every child below is its own >=40.dp tap target.
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Unread private messages badge (click to open most recent DM)
            if (hasUnreadPrivateMessages.isNotEmpty()) {
                HeaderIconButton(
                    onClick = { viewModel.openLatestUnreadPrivateChat() },
                    contentDescription = stringResource(R.string.cd_unread_private_messages)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Email,
                        contentDescription = stringResource(R.string.cd_unread_private_messages),
                        modifier = Modifier.size(HeaderIconSize),
                        tint = palette.accentOrange
                    )
                }
            }

            // Location Notes button (extracted to separate component)
            LocationNotesButton(
                viewModel = viewModel,
                onClick = onLocationNotesClick
            )

            // Tor status dot. Wrapped in a fixed box so its optical position no longer depends
            // on ad-hoc asymmetric padding.
            Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                TorStatusDot(modifier = Modifier.size(6.dp))
            }

            // PoW status indicator
            PoWStatusIndicator(
                modifier = Modifier,
                style = PoWIndicatorStyle.COMPACT
            )

            // Bookmark toggle for current geohash (not shown for mesh)
            val currentGeohash: String? = when (val sc = selectedLocationChannel) {
                is com.bitchat.android.geohash.ChannelID.Location -> sc.channel.geohash
                else -> null
            }
            if (currentGeohash != null) {
                val isBookmarked = bookmarks.contains(currentGeohash)
                HeaderIconButton(
                    onClick = { bookmarksStore.toggle(currentGeohash) },
                    contentDescription = stringResource(R.string.cd_toggle_bookmark)
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = stringResource(R.string.cd_toggle_bookmark),
                        tint = if (isBookmarked) palette.accentGreen else palette.textSecondary,
                        modifier = Modifier.size(HeaderIconSize)
                    )
                }
            }

            LocationChannelsButton(
                viewModel = viewModel,
                onClick = onLocationChannelsClick
            )

            PeerCounter(
                connectedPeers = connectedPeers.filter { it != viewModel.myPeerID },
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                isConnected = isConnected,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onClick = onSidebarClick
            )
        }
    }
}

/**
 * Current channel indicator: a globe for geohash channels, a Bluetooth glyph for the local mesh.
 *
 * The design brief asked for the "addition of globe icon to represent channels". Previously this
 * was a text-only badge wrapped in an M3 [Button], which imposed a hidden 58.dp minimum width
 * and 40.dp minimum height that fought the header's explicit sizing.
 */
@Composable
private fun LocationChannelsButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit
) {
    val palette = LocalBitchatPalette.current

    // Get current channel selection from location manager
    val selectedChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val teleported by viewModel.isTeleported.collectAsStateWithLifecycle()

    val isLocation = selectedChannel is com.bitchat.android.geohash.ChannelID.Location
    val badgeText = when (val channel = selectedChannel) {
        is com.bitchat.android.geohash.ChannelID.Location -> "#${channel.channel.geohash}"
        else -> "#mesh"
    }
    val badgeColor = if (isLocation) palette.accentGreen else palette.accentBlue
    val badgeIcon = if (isLocation) Icons.Outlined.Public else Icons.Filled.Bluetooth

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(HeaderClusterShape)
            .clickable(onClickLabel = stringResource(R.string.location_channels_title)) { onClick() }
            .height(HeaderTapTarget)
            .padding(horizontal = 6.dp)
    ) {
        Icon(
            imageVector = badgeIcon,
            contentDescription = null,
            modifier = Modifier.size(HeaderIconSize),
            tint = badgeColor
        )

        Text(
            text = badgeText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = badgeColor,
            maxLines = 1
        )

        // Teleportation indicator (like iOS)
        if (teleported) {
            Icon(
                imageVector = Icons.Default.PinDrop,
                contentDescription = stringResource(R.string.cd_teleported),
                modifier = Modifier.size(12.dp),
                tint = badgeColor
            )
        }
    }
}
