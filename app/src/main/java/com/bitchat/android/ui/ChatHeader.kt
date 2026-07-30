package com.bitchat.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.theme.BitchatFontFamily
import androidx.annotation.DrawableRes
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.RowScope
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.BitChatBrandButton
import com.bitchat.android.core.ui.component.button.CloseButton
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

internal enum class HeaderCrowdingMode {
    Full,
    HideJoinedChannelCount,
    IconOnlyLocationChannel,
}

internal fun headerCrowdingMode(availableWidth: Dp): HeaderCrowdingMode = when {
    availableWidth < 360.dp -> HeaderCrowdingMode.IconOnlyLocationChannel
    availableWidth < 400.dp -> HeaderCrowdingMode.HideJoinedChannelCount
    else -> HeaderCrowdingMode.Full
}

internal fun locationChannelContentDescription(
    actionDescription: String,
    channelLabel: String,
    showLabel: Boolean,
): String = if (showLabel) {
    actionDescription
} else {
    "$channelLabel. $actionDescription"
}

/** Corner radius for the header's tappable label+icon clusters. */
private val HeaderClusterShape = RoundedCornerShape(8.dp)

/**
 * Edge insets for the bar.
 *
 * Asymmetric because the leading glyph sits in a 44.dp tap target whose padding already supplies
 * some optical inset, while the trailing action's does the same on the other side.
 */
internal val HeaderInsetStart = 12.dp
internal val HeaderInsetEnd = 8.dp

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
    val colorScheme = MaterialTheme.colorScheme
    val torStatus by remember { ArtiTorManager.getInstance() }.statusFlow.collectAsState()

    // ~28% of the loud accent mixed into the base tint keeps the hue without intensity.
    val mutedConnecting = lerp(normal, palette.accentOrange, 0.28f)
    val mutedFailed = lerp(normal, colorScheme.error, 0.30f)

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
 * does not shift; only opacity / a faint halo breathe. Glow strength itself cross-fades so
 * starting/stopping progress never pops.
 */
@Composable
internal fun TorAwareHeaderIcon(
    imageVector: ImageVector,
    tint: Color,
    isProgress: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val progressFade by animateFloatAsState(
        targetValue = if (isProgress) 1f else 0f,
        animationSpec = tween(BitchatMotion.EMPHASIZED_MS, easing = FastOutSlowInEasing),
        label = "torGlowFade"
    )
    val pulse = if (progressFade > 0.01f) {
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
        if (progressFade > 0.01f) {
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
                    .graphicsLayer { alpha = pulse * 0.85f * progressFade }
                    .background(glowBrush)
            )
        }
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(HeaderIconSize)
                .graphicsLayer {
                    // Idle = solid; in-progress = breathing opacity, lerped by [progressFade].
                    val breathing = 0.55f + pulse * 0.45f
                    alpha = 1f - progressFade * (1f - breathing)
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
    val progressFade by animateFloatAsState(
        targetValue = if (isProgress) 1f else 0f,
        animationSpec = tween(BitchatMotion.EMPHASIZED_MS, easing = FastOutSlowInEasing),
        label = "torPainterGlowFade"
    )
    val pulse = if (progressFade > 0.01f) {
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
        if (progressFade > 0.01f) {
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
                    .graphicsLayer { alpha = pulse * 0.85f * progressFade }
                    .background(glowBrush)
            )
        }
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(HeaderIconSize)
                .graphicsLayer {
                    val breathing = 0.55f + pulse * 0.45f
                    alpha = 1f - progressFade * (1f - breathing)
                },
            tint = tint
        )
    }
}

/**
 * Noise session status for private-chat headers.
 *
 * Same visual language as the main header's Tor-aware globe: tint cross-fades between states,
 * and a soft radial glow pulse while the handshake is in flight. The glyph itself is the open
 * lock until a session is established (or fails), then the closed lock — both share the same
 * baseline so a [Crossfade] reads as the shackle settling shut rather than an icon swap.
 */
@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    val (targetTint, isProgress, contentDescription) = when {
        sessionState == "handshaking" -> Triple(
            palette.accentOrange,
            true,
            stringResource(R.string.cd_handshake_in_progress)
        )
        sessionState == "established" -> Triple(
            colorScheme.primary,
            false,
            stringResource(R.string.cd_encrypted)
        )
        sessionState?.startsWith("failed") == true -> Triple(
            colorScheme.error,
            false,
            stringResource(R.string.cd_handshake_failed)
        )
        else -> Triple(
            // Not yet started — quiet grey open lock.
            colorScheme.onSurfaceVariant,
            false,
            stringResource(R.string.cd_ready_for_handshake)
        )
    }

    // Closed once the handshake resolves (success or failure); open while idle or in flight.
    val lockIconRes = when {
        sessionState == "established" || sessionState?.startsWith("failed") == true ->
            R.drawable.ic_spec_lock
        else -> R.drawable.ic_spec_lock_open
    }

    // Match the tint wash so open → closed and grey → orange → green land together.
    val lockTransitionMs = 480

    val animatedTint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(durationMillis = lockTransitionMs, easing = FastOutSlowInEasing),
        label = "noiseSessionTint"
    )

    Crossfade(
        targetState = lockIconRes,
        animationSpec = tween(durationMillis = lockTransitionMs, easing = FastOutSlowInEasing),
        modifier = modifier,
        label = "noiseLockGlyph"
    ) { iconRes ->
        TorAwareHeaderIcon(
            painter = painterResource(iconRes),
            tint = animatedTint,
            isProgress = isProgress,
            contentDescription = contentDescription,
        )
    }
}

/**
 * Reachability glyph for a conversation, drawn from the same spec set the main header uses.
 *
 * Mirrors the main header's channel button: a globe for anything reached over the internet, the
 * range mark for the local mesh, and the more specific transport glyph when we know it.
 */
@DrawableRes
internal fun conversationTransportIcon(
    isReachedOverInternet: Boolean,
    isWifiAware: Boolean,
    isDirect: Boolean
): Int = when {
    isReachedOverInternet -> R.drawable.ic_spec_globe
    isWifiAware -> R.drawable.ic_spec_wifi
    isDirect -> R.drawable.ic_spec_bluetooth
    else -> R.drawable.ic_spec_routed
}

/**
 * The shared chrome for a conversation header — private chats and channels alike.
 *
 * Deliberately built from the same tokens as [MainHeader] rather than from `TopAppBar`: identical
 * height, identical 12/8.dp edge insets, the leading glyph in a [HeaderTapTarget]-sized slot so it
 * lands exactly where the brand mark does, the same -6.dp optical nudge pulling the title toward
 * that glyph, and the same [HeaderTextSize]. Anything less and the header visibly shifts as you
 * move between the main timeline and a conversation.
 *
 * Actions are right-aligned and unweighted, so a long title yields space to them rather than
 * pushing them off screen.
 */
@Composable
fun ConversationHeader(
    @DrawableRes leadingIconRes: Int,
    leadingIconTint: Color,
    title: String,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    leadingContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ChatHeaderHeight)
            .padding(start = HeaderInsetStart, end = HeaderInsetEnd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(HeaderTapTarget),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(leadingIconRes),
                    contentDescription = leadingContentDescription,
                    modifier = Modifier.size(HeaderIconSize),
                    tint = leadingIconTint
                )
            }

            // Same optical correction as the main header: the 44.dp tap target leaves more gap
            // than the design wants between glyph and label.
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = HeaderTextSize,
                fontWeight = FontWeight.Medium,
                color = colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(x = (-6).dp)
                    .then(
                        if (onTitleClick != null) {
                            Modifier
                                .clip(HeaderClusterShape)
                                .pressScaleClickable(onClick = onTitleClick)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        } else {
                            Modifier
                        }
                    )
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions
        )
    }
}

/** An action slot in a [ConversationHeader], matching the main header's icon buttons. */
@Composable
fun ConversationHeaderAction(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) = HeaderIconButton(
    onClick = onClick,
    contentDescription = contentDescription,
    modifier = modifier,
    content = content
)

/** A read-only status slot matching the footprint of [ConversationHeaderAction]. */
@Composable
fun ConversationHeaderStatus(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.size(HeaderTapTarget),
        contentAlignment = Alignment.Center,
        content = { content() }
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
                .weight(1f)
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
    modifier: Modifier = Modifier,
    showJoinedChannelCount: Boolean = true
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    // Compute channel-aware people count and color (matches iOS logic exactly)
    val (peopleCount, countColor) = when (selectedLocationChannel) {
        is com.bitchat.android.geohash.ChannelID.Location -> {
            // Geohash channel: show geohash participants
            val count = geohashPeople.size
            Pair(count, if (count > 0) colorScheme.primary else palette.textTertiary)
        }
        is com.bitchat.android.geohash.ChannelID.Mesh,
        null -> {
            // Mesh channel: show Bluetooth-connected peers (excluding self)
            val count = connectedPeers.size
            Pair(count, if (isConnected && count > 0) colorScheme.secondary else palette.textTertiary)
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

        if (showJoinedChannelCount && joinedChannels.isNotEmpty()) {
            AnimatedCount(
                count = joinedChannels.size,
                prefix = stringResource(R.string.channel_count_prefix),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = HeaderTextSize,
                color = if (isConnected) colorScheme.primary else colorScheme.error,
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

    // No back affordance: the close action on the right is the way out, exactly as in a private
    // chat. Leaving the channel outright lives on its row in the network sheet, so it does not
    // need a second, easily-mistaken home next to the exit.
    ConversationHeader(
        leadingIconRes = R.drawable.ic_spec_chat_bubbles,
        leadingIconTint = colorScheme.primary,
        leadingContentDescription = null,
        title = "#$channel",
        onTitleClick = onSidebarClick
    ) {
        CloseButton(onClick = onBackClick)
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

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val crowdingMode = headerCrowdingMode(maxWidth)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChatHeaderHeight)
                .padding(start = HeaderInsetStart, end = HeaderInsetEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Keep the brand and trailing actions fixed. Only the nickname yields under pressure.
            BitChatBrandButton(
                onClick = onTitleClick,
                onTripleClick = onTripleTitleClick,
                contentDescription = stringResource(R.string.cd_open_about),
                modifier = Modifier.size(HeaderTapTarget),
            )

            // Nudge toward the brand glyph: the 44.dp tap target leaves more optical gap than the
            // spacing between the mark and path label.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .offset(x = (-6).dp)
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
                    onValueChange = onNicknameChange,
                    modifier = Modifier.weight(1f)
                )
            }

            // Order, left to right: unread DMs, notes, channel, people. This cluster is measured
            // before the weighted nickname, so actions cannot be pushed off-screen by identity.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (hasUnreadPrivateMessages.isNotEmpty()) {
                    HeaderIconButton(
                        onClick = { viewModel.openLatestUnreadPrivateChat() },
                        contentDescription = stringResource(R.string.cd_unread_private_messages)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_spec_envelope),
                            contentDescription = stringResource(R.string.cd_unread_private_messages),
                            modifier = Modifier.size(HeaderIconSize),
                            tint = palette.accentOrange
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    LocationNotesButton(
                        viewModel = viewModel,
                        onClick = onLocationNotesClick
                    )

                    LocationChannelsButton(
                        viewModel = viewModel,
                        onClick = onLocationChannelsClick,
                        showLabel = crowdingMode != HeaderCrowdingMode.IconOnlyLocationChannel
                    )
                }

                PeerCounter(
                    connectedPeers = connectedPeers.filter { it != viewModel.myPeerID },
                    joinedChannels = joinedChannels,
                    hasUnreadChannels = hasUnreadChannels,
                    isConnected = isConnected,
                    selectedLocationChannel = selectedLocationChannel,
                    geohashPeople = geohashPeople,
                    onClick = onSidebarClick,
                    showJoinedChannelCount = crowdingMode == HeaderCrowdingMode.Full
                )
            }
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
    onClick: () -> Unit,
    showLabel: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

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
    val channelColor = if (isLocation) colorScheme.primary else colorScheme.secondary
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
    val actionDescription = stringResource(R.string.cd_open_location_channels)
    val contentDescription = locationChannelContentDescription(
        actionDescription = actionDescription,
        channelLabel = badgeText,
        showLabel = showLabel
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (showLabel) Arrangement.spacedBy(6.dp) else Arrangement.Center,
        modifier = Modifier
            .clip(HeaderClusterShape)
            .pressScaleClickable(
                onClick = onClick,
                onClickLabel = actionDescription
            )
            .height(HeaderTapTarget)
            .widthIn(min = HeaderTapTarget)
            .padding(end = if (showLabel) 6.dp else 0.dp)
    ) {
        TorAwareHeaderIcon(
            painter = painterResource(badgeIconRes),
            tint = torVisual.tint,
            isProgress = torVisual.isProgress,
            contentDescription = contentDescription
        )

        if (showLabel) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = HeaderTextSize,
                fontWeight = FontWeight.Medium,
                color = channelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 88.dp)
            )
        }
    }
}
