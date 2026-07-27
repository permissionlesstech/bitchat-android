package com.bitchat.android.ui


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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

/** Height of the chat top bar. Taller than the old 42.dp so 44.dp tap targets fit properly. */
val ChatHeaderHeight = 52.dp

/**
 * The single icon size used by every glyph in the top bar.
 *
 * Previously the bar mixed 12/14/16/18.dp icons, which is why it read as a collection of
 * unrelated glyphs rather than one control strip. One size for everything, and large enough to
 * actually see.
 */
internal val HeaderIconSize = 22.dp

/**
 * Text size for the top bar's labels: nickname, channel name, peer count.
 *
 * A step up from the 15.sp body scale. The bar is the app's primary status readout and was
 * noticeably harder to read than the messages below it; the extra point costs nothing because
 * the bar's height is driven by [HeaderTapTarget], not by the text.
 */
private val HeaderTextSize = 17.sp

/** Minimum tap target for every interactive element in the header. */
private val HeaderTapTarget = 44.dp

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

/**
 * Connection status indicator.
 *
 * Only visible while the connection is still coming up (or has failed). A steady green "all
 * good" light is noise: the absence of the indicator already means everything is fine, and the
 * eye stops registering an always-on dot anyway. It fades and scales in/out so appearing and
 * disappearing reads as a state change rather than a glitch.
 */
@Composable
fun TorStatusDot(
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val torProvider = remember { com.bitchat.android.net.ArtiTorManager.getInstance() }
    val torStatus by torProvider.statusFlow.collectAsState()

    val isEnabled = torStatus.mode != com.bitchat.android.net.TorMode.OFF
    val isEstablished = torStatus.running && torStatus.bootstrapPercent >= 100
    val isVisible = isEnabled && !isEstablished

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
            scaleIn(
                initialScale = 0.4f,
                animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing)
            ),
        exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
            scaleOut(
                targetScale = 0.4f,
                animationSpec = tween(BitchatMotion.QUICK_MS, easing = FastOutSlowInEasing)
            ),
    ) {
        // Bootstrapping vs. failed. Cross-faded, because Tor flips between these frequently and
        // a hard colour cut in the corner of the screen reads as a rendering fault.
        val dotColor by animateColorAsState(
            targetValue = if (torStatus.running) palette.accentOrange else palette.accentRed,
            animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
            label = "torDotColor"
        )
        Canvas(modifier = modifier) {
            drawCircle(
                color = dotColor,
                radius = size.minDimension / 2,
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
    val palette = LocalBitchatPalette.current
    // The pre-redesign colours for the first two states were `0x87878700`, i.e. alpha 0x87 with
    // an all-but-transparent RGB - the icons were effectively invisible. They now use the
    // palette's secondary text colour.
    val (icon, color, contentDescription) = when (sessionState) {
        "uninitialized" -> Triple(
            Icons.Outlined.NoEncryption,
            palette.textSecondary,
            stringResource(R.string.cd_ready_for_handshake)
        )
        "handshaking" -> Triple(
            Icons.Outlined.Sync,
            palette.textSecondary,
            stringResource(R.string.cd_handshake_in_progress)
        )
        "established" -> Triple(
            Icons.Filled.Lock,
            palette.accentOrange,
            stringResource(R.string.cd_encrypted)
        )
        else -> { // "failed" or any other state
            Triple(
                Icons.Outlined.Warning,
                palette.accentRed,
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
            fontSize = HeaderTextSize,
            color = colorScheme.primary.copy(alpha = 0.7f)
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = HeaderTextSize
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
                .widthIn(max = 150.dp)
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
            // A single silhouette rather than a crowd: at 22.dp a multi-person glyph collapses
            // into an indistinct blob, and the number beside it already conveys "how many".
            imageVector = Icons.Filled.Person,
            contentDescription = when (selectedLocationChannel) {
                is com.bitchat.android.geohash.ChannelID.Location -> stringResource(R.string.cd_geohash_participants)
                else -> stringResource(R.string.cd_connected_peers)
            },
            modifier = Modifier.size(HeaderIconSize),
            tint = animatedCountColor
        )

        AnimatedCount(
            count = peopleCount,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = HeaderTextSize,
            color = animatedCountColor,
            fontWeight = FontWeight.Medium
        )

        if (joinedChannels.isNotEmpty()) {
            AnimatedCount(
                count = joinedChannels.size,
                prefix = stringResource(R.string.channel_count_prefix),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = HeaderTextSize,
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
                modifier = Modifier.size(HeaderIconSize),
                tint = colorScheme.primary
            )
        }

        // Title - perfectly centered regardless of other elements
        Text(
            text = "#$channel",
            style = MaterialTheme.typography.titleMedium,
            fontSize = HeaderTextSize,
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
                fontSize = 15.sp,
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // MARK: - Identity cluster.
        //
        // Weighted so it yields space to the status cluster rather than pushing it off screen.
        // Compose measures unweighted children first, so the icons on the right always get the
        // width they need and a long nickname simply scrolls within what is left.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BitChatBrandButton(
                onClick = onTitleClick,
                onTripleClick = onTripleTitleClick,
                contentDescription = stringResource(R.string.cd_open_about),
                modifier = Modifier.size(HeaderTapTarget),
            )

            Text(
                text = "/",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = HeaderTextSize,
                // Dimmed: the slash is a separator, not content. At full brightness it competed
                // with the nickname beside it.
                color = colorScheme.primary.copy(alpha = 0.45f),
                modifier = Modifier.padding(horizontal = 2.dp)
            )

            NicknameEditor(
                value = nickname,
                onValueChange = onNicknameChange
            )
        }

        // MARK: - Status cluster.
        //
        // Order, left to right: connection state, unread DMs, notes, channel, people.
        // The connection indicator leads because it is the only item that can invalidate
        // everything to its right, and because it appears and disappears on its own — a fixed
        // leftmost slot means the rest of the cluster never shifts when it does.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Tight, because every child below is its own >=44.dp tap target.
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Connection status. The Box holds the slot open at a constant width so the icons to
            // its right stay put whether or not the dot is currently showing.
            Box(
                modifier = Modifier.size(HeaderIconSize),
                contentAlignment = Alignment.Center
            ) {
                TorStatusDot(modifier = Modifier.size(8.dp))
            }

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

            // Location notes + channel badge: one tight unit so the document glyph and the
            // bluetooth/globe glyph sit at the same visual pitch as other header pairings.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                LocationNotesButton(
                    viewModel = viewModel,
                    onClick = onLocationNotesClick
                )

                // Bookmarking lives in the Location Channels sheet, one tap away via the channel
                // button. Duplicating it here bought a shortcut for a rare action at the cost
                // of a slot in the app's most crowded row.

                LocationChannelsButton(
                    viewModel = viewModel,
                    onClick = onLocationChannelsClick
                )
            }

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

    val isLocation = selectedChannel is com.bitchat.android.geohash.ChannelID.Location
    val badgeText = when (val channel = selectedChannel) {
        // Geohashes keep the '#' because that is how they are written and typed everywhere else.
        is com.bitchat.android.geohash.ChannelID.Location -> "#${channel.channel.geohash}"
        // The local mesh is not a hashtag channel, and the Bluetooth glyph already says what it
        // is, so it is plain "mesh".
        else -> stringResource(R.string.mesh_label)
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
            // No start padding: the notes icon is paired directly to the left; keep end
            // padding so the gap to PeerCounter matches other cluster separations.
            .padding(start = 0.dp, end = 6.dp)
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
            fontSize = HeaderTextSize,
            fontWeight = FontWeight.Medium,
            color = badgeColor,
            maxLines = 1
        )
    }
}
