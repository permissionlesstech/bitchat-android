package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.BitChatBrandButton
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Header components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

/** Height of the chat top bar. Taller than the old 42.dp so 44.dp tap targets fit properly. */
val ChatHeaderHeight = 52.dp

/**
 * The single visible glyph size used by every icon in the top bar.
 *
 * The Figma header pairs 16 px icons with compact labels. At our 17sp header scale, 19dp preserves
 * that icon-to-cap-height relationship without affecting the surrounding 44dp touch targets.
 */
internal val HeaderIconSize = 19.dp

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
            .pressScaleClickable(onClick = onClick, onClickLabel = contentDescription),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Tor health for location-channel header glyphs.
 *
 * Status colours are heavily muted (blended into [normal]) so they read as a soft signal
 * rather than an alarm. Connecting / not-yet-running also drives a slow glow pulse.
 */
internal data class TorConnectionVisual(
    val tint: Color,
    /** True while Tor is enabled but not fully bootstrapped — drives a pulse. */
    val isProgress: Boolean,
)

@Composable
internal fun rememberTorConnectionVisual(normal: Color): TorConnectionVisual {
    val palette = LocalBitchatPalette.current
    val torStatus by remember { ArtiTorManager.getInstance() }.statusFlow.collectAsState()

    // ~28% of the loud accent mixed into the base tint keeps the hue without intensity.
    val mutedConnecting = lerp(normal, palette.accentOrange, 0.28f)
    val mutedFailed = lerp(normal, palette.accentRed, 0.30f)

    val target = when {
        torStatus.mode == TorMode.OFF -> TorConnectionVisual(normal, isProgress = false)
        torStatus.running && torStatus.bootstrapPercent >= 100 ->
            TorConnectionVisual(normal, isProgress = false)
        torStatus.running -> TorConnectionVisual(mutedConnecting, isProgress = true)
        else -> TorConnectionVisual(mutedFailed, isProgress = true)
    }

    val animatedTint by animateColorAsState(
        targetValue = target.tint,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "torConnectionTint"
    )
    return TorConnectionVisual(tint = animatedTint, isProgress = target.isProgress)
}

/**
 * Soft, slow brightness pulse used while Tor is connecting. Keeps scale fixed so layout
 * does not shift; only opacity / a faint halo breathe.
 */
@Composable
internal fun TorAwareHeaderIcon(
    imageVector: ImageVector,
    tint: Color,
    isProgress: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val pulse = if (isProgress) {
        val transition = rememberInfiniteTransition(label = "torGlow")
        transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "torGlowPulse"
        ).value
    } else {
        1f
    }

    // Fixed layout footprint = icon size. Glow is drawn larger via requiredSize so it never
    // pushes neighbouring text when the pulse starts/stops.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(HeaderIconSize)
    ) {
        if (isProgress) {
            val glowBrush = remember(tint) {
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to tint.copy(alpha = 0.55f),
                        0.45f to tint.copy(alpha = 0.22f),
                        1.0f to Color.Transparent,
                    )
                )
            }
            Box(
                modifier = Modifier
                    .requiredSize(HeaderIconSize + 14.dp)
                    .graphicsLayer { alpha = pulse * 0.85f }
                    .background(glowBrush)
            )
        }
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(HeaderIconSize)
                .graphicsLayer {
                    alpha = if (isProgress) 0.55f + pulse * 0.45f else 1f
                },
            tint = tint
        )
    }
}

/** Painter-resource counterpart used by the extracted Figma SVG family. */
@Composable
internal fun TorAwareHeaderIcon(
    painter: Painter,
    tint: Color,
    isProgress: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val pulse = if (isProgress) {
        val transition = rememberInfiniteTransition(label = "torPainterGlow")
        transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "torPainterGlowPulse"
        ).value
    } else {
        1f
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(HeaderIconSize)
    ) {
        if (isProgress) {
            val glowBrush = remember(tint) {
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to tint.copy(alpha = 0.55f),
                        0.45f to tint.copy(alpha = 0.22f),
                        1.0f to Color.Transparent,
                    )
                )
            }
            Box(
                modifier = Modifier
                    .requiredSize(HeaderIconSize + 14.dp)
                    .graphicsLayer { alpha = pulse * 0.85f }
                    .background(glowBrush)
            )
        }
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(HeaderIconSize)
                .graphicsLayer {
                    alpha = if (isProgress) 0.55f + pulse * 0.45f else 1f
                },
            tint = tint
        )
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
                fontFamily = BitchatFontFamily,
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(HeaderClusterShape)
            .pressScaleClickable(onClick = onClick)
            .height(HeaderTapTarget)
            .padding(horizontal = 6.dp)
    ) {
        Icon(
            // The extracted people glyph stays legible at the compact header scale; the number
            // beside it carries the precise count.
            painter = painterResource(R.drawable.ic_spec_people),
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
                .pressScaleClickable(onClick = onSidebarClick)
                .heightIn(min = HeaderTapTarget)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 10.dp)
        )

        // Leave button - positioned on the right
        Text(
            text = stringResource(R.string.chat_leave),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 15.sp,
            color = palette.accentRed,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .clip(HeaderClusterShape)
                .pressScaleClickable(onClick = onLeaveChannel)
                .heightIn(min = HeaderTapTarget)
                .wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = 10.dp)
        )
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

            // Nudge toward the brand glyph: the 44.dp tap target leaves more optical gap than
            // spacing between the mark and the path label.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset(x = (-6).dp)
            ) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = HeaderTextSize,
                    // Dimmed: the slash is a separator, not content. At full brightness it competed
                    // with the nickname beside it.
                    color = colorScheme.primary.copy(alpha = 0.45f),
                    modifier = Modifier.padding(end = 2.dp)
                )

                NicknameEditor(
                    value = nickname,
                    onValueChange = onNicknameChange
                )
            }
        }

        // MARK: - Status cluster.
        //
        // Order, left to right: unread DMs, notes, channel, people.
        // Tor health is read from the location channel / notes icon colour rather than a
        // dedicated status dot.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Tight, because every child below is its own >=44.dp tap target.
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
 * Current channel indicator: a globe for geohash channels, a mesh glyph for the local mesh.
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
        // The local mesh is not a hashtag channel, and the mesh glyph already says what it is,
        // so it is plain "mesh".
        else -> stringResource(R.string.mesh_label)
    }
    val channelColor = if (isLocation) palette.accentGreen else palette.accentBlue
    // Tor status only tints the globe (location channels). The local mesh stays blue.
    val torVisual = if (isLocation) {
        rememberTorConnectionVisual(normal = channelColor)
    } else {
        TorConnectionVisual(tint = channelColor, isProgress = false)
    }
    val badgeIconRes = if (isLocation) {
        R.drawable.ic_spec_globe
    } else {
        R.drawable.ic_spec_range
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(HeaderClusterShape)
            .pressScaleClickable(
                onClick = onClick,
                onClickLabel = stringResource(R.string.location_channels_title)
            )
            .height(HeaderTapTarget)
            // No start padding: the notes icon is paired directly to the left; keep end
            // padding so the gap to PeerCounter matches other cluster separations.
            .padding(start = 0.dp, end = 6.dp)
    ) {
        TorAwareHeaderIcon(
            painter = painterResource(badgeIconRes),
            tint = torVisual.tint,
            isProgress = torVisual.isProgress,
            contentDescription = stringResource(R.string.cd_tor_status)
        )

        Text(
            text = badgeText,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = HeaderTextSize,
            fontWeight = FontWeight.Medium,
            color = channelColor,
            maxLines = 1
        )
    }
}
