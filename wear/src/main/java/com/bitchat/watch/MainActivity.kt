package com.bitchat.watch

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.SwipeToDismissKeys
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material3.AlertDialogContent
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.service.WearMeshForegroundService
import com.bitchat.watch.ui.ChatHomePager
import com.bitchat.watch.ui.ChatMenuScreen
import com.bitchat.watch.ui.ChatScreen
import com.bitchat.watch.ui.DmScreen
import com.bitchat.watch.ui.NicknameSetupScreen
import com.bitchat.watch.ui.PeopleScreen
import com.bitchat.watch.ui.UserDetailScreen
import com.bitchat.watch.ui.VerificationCodeScreen
import com.bitchat.watch.ui.WearChatState
import com.bitchat.watch.ui.FormEdgeButton
import com.bitchat.watch.ui.WearFormScreen
import com.bitchat.watch.ui.formHeader
import com.bitchat.watch.ui.sendPrivateMessage
import com.bitchat.watch.ui.sendPublicMessage
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.BitchatWearTheme
import com.bitchat.watch.ui.theme.LocalBitchatPalette

sealed interface WearScreen {
    data object Chat : WearScreen
    data object People : WearScreen
    data object Nickname : WearScreen
    data class Dm(val peerID: String) : WearScreen
    data class UserDetail(val peerID: String) : WearScreen
    data class Verification(val peerID: String) : WearScreen
    data class TextInput(val peerID: String?) : WearScreen
}

class MainActivity : ComponentActivity() {

    private var hasPermissions by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)
    private var nicknameChosen by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var notificationPromptDismissed by mutableStateOf(false)
    private var pendingLaunchRequest by mutableStateOf<WearLaunchRequest?>(null)
    private var nextLaunchRequestID = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nicknameChosen = getSharedPreferences("bitchat_watch_prefs", Context.MODE_PRIVATE)
            .getBoolean("nickname_chosen", false)
        pendingLaunchRequest = restoreWearLaunchRequest(
            savedInstanceState?.getStringArrayList(KEY_PENDING_LAUNCH_REQUEST)
        )
        nextLaunchRequestID = maxOf(
            savedInstanceState?.getLong(KEY_NEXT_LAUNCH_REQUEST_ID, 0L) ?: 0L,
            pendingLaunchRequest?.id ?: 0L
        )
        val notificationPeer = consumePrivateMessagePeer(intent)
        if (savedInstanceState == null && notificationPeer != null) {
            requestLaunch(WearLaunchTarget.Dm(notificationPeer))
        }
        refreshState()
        setContent {
            BitchatWearTheme {
                // One TimeText for the app; chat screens hide it because their header owns the top.
                val timeTextAlpha = remember { mutableFloatStateOf(1f) }
                AppScaffold(
                    timeText = {
                        TimeText(Modifier.graphicsLayer { alpha = timeTextAlpha.floatValue })
                    }
                ) {
                    CompositionLocalProvider(LocalTimeTextAlpha provides timeTextAlpha) {
                        when {
                            !hasPermissions ->
                                PermissionRequestScreen(onGranted = { refreshState() })
                            !bluetoothEnabled ->
                                BluetoothEnableScreen(onEnabled = { refreshState() })
                            !nicknameChosen -> NicknameSetupScreen(
                                initialNickname =
                                    WearMeshService.getOrCreate(applicationContext).nickname
                            ) { name ->
                                WearMeshService.getOrCreate(applicationContext).setNickname(name)
                                getSharedPreferences("bitchat_watch_prefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("nickname_chosen", true).apply()
                                nicknameChosen = true
                            }
                            !notificationsGranted && !notificationPromptDismissed ->
                                NotificationPermissionScreen(
                                    onResult = { granted ->
                                        notificationPromptDismissed = !granted
                                        refreshState()
                                    },
                                    onSkip = { notificationPromptDismissed = true }
                                )
                            else -> WearNavHost(
                                launchRequest = pendingLaunchRequest,
                                onQuit = ::quit,
                                onLaunchRequestHandled = { requestID ->
                                    if (pendingLaunchRequest?.id == requestID) {
                                        pendingLaunchRequest = null
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_NEXT_LAUNCH_REQUEST_ID, nextLaunchRequestID)
        pendingLaunchRequest?.let {
            outState.putStringArrayList(
                KEY_PENDING_LAUNCH_REQUEST,
                it.toSavedStateValues()
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        com.bitchat.android.features.voice.LiveVoiceManager.getInstance(applicationContext)
            .setAppForeground(true)
        WearChatState.setAppInForeground(true)
        WearChatState.openDmPeer?.let { peerID ->
            WearChatState.openDm(peerID)
            WearNotificationCoordinator.getInstance(applicationContext).clearConversation(peerID)
        }
        refreshState()
    }

    override fun onPause() {
        com.bitchat.android.features.voice.LiveVoiceManager.getInstance(applicationContext)
            .setAppForeground(false)
        WearChatState.setAppInForeground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val notificationPeer = consumePrivateMessagePeer(intent)
        requestLaunch(
            notificationPeer?.let(WearLaunchTarget::Dm) ?: WearLaunchTarget.Chat
        )
    }

    private fun refreshState() {
        hasPermissions = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        notificationsGranted = notificationPermissionGranted()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        bluetoothEnabled = adapter?.isEnabled == true
        if (hasPermissions && bluetoothEnabled) {
            startMeshService()
        }
    }

    private fun startMeshService() {
        WearMeshService.getOrCreate(applicationContext)
        startForegroundService(Intent(this, WearMeshForegroundService::class.java))
    }

    /**
     * Quit from the in-app menu: stopping the foreground service tears the mesh down (see
     * [WearMeshForegroundService.onDestroy]); the next launch starts it again.
     */
    private fun quit() {
        stopService(Intent(this, WearMeshForegroundService::class.java))
        finishAndRemoveTask()
    }

    private fun consumePrivateMessagePeer(intent: Intent?): String? {
        val peerID = privateMessagePeerFromIntent(intent)
        intent?.removeExtra(WearNotificationCoordinator.EXTRA_OPEN_DM)
        intent?.removeExtra(WearNotificationCoordinator.EXTRA_PEER_ID)
        return peerID
    }

    private fun privateMessagePeerFromIntent(intent: Intent?): String? {
        if (intent?.getBooleanExtra(WearNotificationCoordinator.EXTRA_OPEN_DM, false) != true) {
            return null
        }
        return intent.getStringExtra(WearNotificationCoordinator.EXTRA_PEER_ID)
            ?.takeIf { it.isNotBlank() }
    }

    private fun requestLaunch(target: WearLaunchTarget) {
        pendingLaunchRequest = WearLaunchRequest(
            id = ++nextLaunchRequestID,
            target = target
        )
    }

    private fun notificationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val KEY_NEXT_LAUNCH_REQUEST_ID = "next_launch_request_id"
        private const val KEY_PENDING_LAUNCH_REQUEST = "pending_launch_request"

        fun requiredPermissions(): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
    }
}

internal sealed interface WearLaunchTarget {
    data object Chat : WearLaunchTarget
    data class Dm(val peerID: String) : WearLaunchTarget
}

internal data class WearLaunchRequest(
    val id: Long,
    val target: WearLaunchTarget
)

internal fun WearLaunchRequest.toSavedStateValues(): ArrayList<String> {
    val (type, peerID) = when (val launchTarget = target) {
        WearLaunchTarget.Chat -> "chat" to ""
        is WearLaunchTarget.Dm -> "dm" to launchTarget.peerID
    }
    return arrayListOf(id.toString(), type, peerID)
}

internal fun restoreWearLaunchRequest(values: List<String>?): WearLaunchRequest? {
    if (values?.size != 3) return null
    val id = values[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val target = when (values[1]) {
        "chat" -> WearLaunchTarget.Chat
        "dm" -> values[2]
            .takeIf(String::isNotBlank)
            ?.let(WearLaunchTarget::Dm)
            ?: return null
        else -> return null
    }
    return WearLaunchRequest(id = id, target = target)
}

internal class WearNavigationState(
    initialScreen: WearScreen = WearScreen.Chat,
    initialBackStack: List<WearScreen> = emptyList()
) {
    private val backStack = mutableStateListOf<WearScreen>().apply {
        addAll(initialBackStack)
    }

    var screen by mutableStateOf(initialScreen)
        private set

    val canGoBack: Boolean
        get() = screen != WearScreen.Chat || backStack.isNotEmpty()

    /** The screen [goBack] lands on: what a swipe-to-dismiss reveals underneath. */
    val previousScreen: WearScreen?
        get() = if (screen is WearScreen.Dm) WearScreen.Chat else backStack.lastOrNull()

    fun navigate(to: WearScreen) {
        backStack.add(screen)
        screen = to
    }

    fun openChat() {
        backStack.clear()
        screen = WearScreen.Chat
    }

    fun openDmFromNotification(peerID: String) {
        backStack.clear()
        backStack.add(WearScreen.Chat)
        screen = WearScreen.Dm(peerID)
    }

    fun goBack(): Boolean {
        if (screen is WearScreen.Dm) {
            openChat()
            return true
        }

        val previous = backStack.removeLastOrNull() ?: return false
        screen = previous
        return true
    }

    internal fun toSavedStateValues(): List<String> {
        return buildList {
            add(SAVED_STATE_VERSION)
            (listOf(screen) + backStack).forEach { savedScreen ->
                val (type, peerID) = encodeScreen(savedScreen)
                add(type)
                add(peerID)
            }
        }
    }

    companion object {
        private const val SAVED_STATE_VERSION = "1"

        val Saver = listSaver<WearNavigationState, String>(
            save = { it.toSavedStateValues() },
            restore = ::restore
        )

        internal fun restore(values: List<String>): WearNavigationState? {
            if (
                values.firstOrNull() != SAVED_STATE_VERSION ||
                values.size < 3 ||
                (values.size - 1) % 2 != 0
            ) {
                return null
            }

            val screens = mutableListOf<WearScreen>()
            var index = 1
            while (index < values.size) {
                val restoredScreen = decodeScreen(
                    type = values[index],
                    peerID = values[index + 1]
                ) ?: return null
                screens += restoredScreen
                index += 2
            }
            return WearNavigationState(
                initialScreen = screens.first(),
                initialBackStack = screens.drop(1)
            )
        }

        /** Bundle-safe identity for saveable state and transition keys. */
        internal fun keyOf(screen: WearScreen): String =
            encodeScreen(screen).let { (type, peerID) -> "$type:$peerID" }

        private fun encodeScreen(screen: WearScreen): Pair<String, String> = when (screen) {
            WearScreen.Chat -> "chat" to ""
            WearScreen.People -> "people" to ""
            WearScreen.Nickname -> "nickname" to ""
            is WearScreen.Dm -> "dm" to screen.peerID
            is WearScreen.UserDetail -> "user_detail" to screen.peerID
            is WearScreen.Verification -> "verification" to screen.peerID
            is WearScreen.TextInput -> screen.peerID?.let { "text_dm" to it }
                ?: ("text_public" to "")
        }

        private fun decodeScreen(type: String, peerID: String): WearScreen? = when (type) {
            "chat" -> WearScreen.Chat
            "people" -> WearScreen.People
            "nickname" -> WearScreen.Nickname
            "dm" -> peerID.takeIf(String::isNotBlank)?.let(WearScreen::Dm)
            "user_detail" -> peerID
                .takeIf(String::isNotBlank)
                ?.let(WearScreen::UserDetail)
            "verification" -> peerID
                .takeIf(String::isNotBlank)
                ?.let(WearScreen::Verification)
            "text_dm" -> peerID.takeIf(String::isNotBlank)?.let(WearScreen::TextInput)
            "text_public" -> WearScreen.TextInput(null)
            else -> null
        }
    }
}

@Composable
internal fun WearNavHost(
    launchRequest: WearLaunchRequest?,
    onQuit: () -> Unit,
    onLaunchRequestHandled: (Long) -> Unit
) {
    val navigation = rememberSaveable(saver = WearNavigationState.Saver) {
        WearNavigationState()
    }
    // How the current screen was reached decides its transition: a push zooms in, a back
    // zooms out, and a completed swipe has already animated, so it must not animate twice.
    var lastMove by remember { mutableStateOf(WearNavMove.Push) }
    // A screen that owns a horizontal drag (push-to-talk, image viewer) disables the swipe.
    var screenOwnsGesture by remember { mutableStateOf(false) }
    fun push(to: WearScreen) {
        lastMove = WearNavMove.Push
        navigation.navigate(to)
    }
    fun back(move: WearNavMove = WearNavMove.Back) {
        lastMove = move
        navigation.goBack()
    }

    BackHandler(enabled = navigation.canGoBack) { back() }

    LaunchedEffect(launchRequest) {
        launchRequest?.let { request ->
            lastMove = WearNavMove.Push
            when (val target = request.target) {
                WearLaunchTarget.Chat -> navigation.openChat()
                is WearLaunchTarget.Dm -> navigation.openDmFromNotification(target.peerID)
            }
            onLaunchRequestHandled(request.id)
        }
    }
    LaunchedEffect(navigation.screen) { screenOwnsGesture = false }

    val previous = navigation.previousScreen
    val swipeState = rememberSwipeToDismissBoxState()
    // The app clock does not travel with the screen; fade it as the swipe begins so it never
    // sits over the header of the screen being revealed.
    val timeTextAlpha = LocalTimeTextAlpha.current
    val swipeFadeDistance = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(swipeState) {
        snapshotFlow { swipeState.offset }.collect { offset ->
            timeTextAlpha.floatValue =
                if (offset.isNaN()) 1f else (1f - offset / swipeFadeDistance).coerceIn(0f, 1f)
        }
    }
    // Native Wear back: the screen follows the finger and reveals the one underneath, instead
    // of the system gesture firing a crossfade only after the finger lifts.
    SwipeToDismissBox(
        onDismissed = { back(WearNavMove.Swipe) },
        state = swipeState,
        backgroundKey = previous?.let(WearNavigationState::keyOf)
            ?: SwipeToDismissKeys.Background,
        contentKey = WearNavigationState.keyOf(navigation.screen),
        userSwipeEnabled = previous != null && !screenOwnsGesture,
    ) { isBackground ->
        // Background and foreground go through this one call site on purpose: the box keeps
        // each screen's saved state under its key, and an identical composition path lets the
        // revealed screen keep its scroll position when it becomes the foreground.
        val screen = if (isBackground) previous else navigation.screen
        if (screen != null) {
            ScreenEntrance(
                key = WearNavigationState.keyOf(screen),
                move = if (isBackground) WearNavMove.Swipe else lastMove
            ) {
                WearScreenContent(
                    screen = screen,
                    active = !isBackground,
                    onNavigate = if (isBackground) { _ -> } else ::push,
                    onBack = if (isBackground) { {} } else { { back() } },
                    onQuit = onQuit,
                    onGestureOwned = if (isBackground) { _ -> } else { owned ->
                        screenOwnsGesture = owned
                    }
                )
            }
        }
    }
}

/**
 * Plays the arrival of a screen once, when [key] first appears: a push zooms in from slightly
 * small, a back settles from slightly large, and a swipe-revealed screen is already in place.
 * Only the incoming screen animates, so two screens are never seen through each other.
 */
@Composable
private fun ScreenEntrance(key: String, move: WearNavMove, content: @Composable () -> Unit) {
    val progress = remember(key) { Animatable(if (move == WearNavMove.Swipe) 1f else 0f) }
    LaunchedEffect(key) {
        progress.animateTo(1f, tween(BitchatMotion.EMPHASIZED_MS, easing = WearEnterEasing))
    }
    val fromScale = if (move == WearNavMove.Back) 1.06f else 0.92f
    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            val scale = fromScale + (1f - fromScale) * p
            scaleX = scale
            scaleY = scale
        }
    ) {
        content()
    }
}

/** Fades the app-wide TimeText while a screen is being swiped away. */
internal val LocalTimeTextAlpha = staticCompositionLocalOf { mutableFloatStateOf(1f) }

internal enum class WearNavMove { Push, Back, Swipe }

/** Material's emphasized-decelerate curve: quick to arrive, gentle to settle. */
private val WearEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * One screen of the app. [active] is false while it is only revealed behind a swipe: it then
 * renders without claiming the visible conversation, so a cancelled swipe changes nothing.
 */
@Composable
private fun WearScreenContent(
    screen: WearScreen,
    active: Boolean,
    onNavigate: (WearScreen) -> Unit,
    onBack: () -> Unit,
    onQuit: () -> Unit,
    onGestureOwned: (Boolean) -> Unit,
) {
    when (screen) {
        is WearScreen.Chat -> {
            var chatOwnsGesture by remember { mutableStateOf(false) }
            ChatHomePager(
                swipeEnabled = !chatOwnsGesture,
                menu = { ChatMenuScreen(onQuit = onQuit) },
                chat = {
                    ChatScreen(
                        active = active,
                        onOpenPeople = { onNavigate(WearScreen.People) },
                        onOpenTextInput = { onNavigate(WearScreen.TextInput(null)) },
                        onHorizontalGestureActive = { chatOwnsGesture = it }
                    )
                }
            )
        }
        is WearScreen.People -> PeopleScreen(
            onOpenDm = { onNavigate(WearScreen.Dm(it)) },
            onEditNickname = { onNavigate(WearScreen.Nickname) }
        )
        is WearScreen.Nickname -> {
            val mesh = WearMeshService.peek()
            NicknameSetupScreen(
                initialNickname = mesh?.nickname ?: "",
                title = "Nickname",
                subtitle = "Seen by peers",
                confirmLabel = "Save",
                autoFocus = active,
                onConfirm = { name ->
                    mesh?.setNickname(name)
                    onBack()
                }
            )
        }
        is WearScreen.Dm -> DmScreen(
            peerID = screen.peerID,
            active = active,
            onOpenUserDetail = { onNavigate(WearScreen.UserDetail(screen.peerID)) },
            onOpenTextInput = { onNavigate(WearScreen.TextInput(screen.peerID)) },
            onHorizontalGestureActive = onGestureOwned
        )
        is WearScreen.UserDetail -> UserDetailScreen(
            peerID = screen.peerID,
            onOpenVerification = { onNavigate(WearScreen.Verification(screen.peerID)) }
        )
        is WearScreen.Verification -> VerificationCodeScreen(peerID = screen.peerID)
        is WearScreen.TextInput -> {
            val mesh = WearMeshService.peek()
            val sendScope = androidx.compose.runtime.rememberCoroutineScope()
            val recipient = screen.peerID?.let { peerID ->
                mesh?.getPeerNickname(peerID) ?: peerID.take(8)
            }
            com.bitchat.watch.ui.TextInputScreen(
                recipient = recipient,
                autoFocus = active,
                onSend = { text ->
                    mesh?.let { m ->
                        if (screen.peerID == null) {
                            sendPublicMessage(m, text)
                        } else {
                            val nick = m.getPeerNickname(screen.peerID) ?: screen.peerID
                            sendPrivateMessage(m, screen.peerID, nick, text, sendScope)
                        }
                    }
                    onBack()
                }
            )
        }
    }
}

@Composable
fun NotificationPermissionScreen(onResult: (Boolean) -> Unit, onSkip: () -> Unit) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onResult(granted)
        }

    // Two choices of equal weight: the Wear confirmation layout (✕ / ✓) fits both on one
    // screen without scrolling, where a stacked "Not now" pushed the primary action off.
    // The dialog layout brings its own top spacing; the app clock would sit on its icon.
    ScreenScaffold(timeText = {}) {
        AlertDialogContent(
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onResult(true)
                        }
                    }
                )
            },
            title = { Text("DM alerts") },
            dismissButton = { AlertDialogDefaults.DismissButton(onClick = onSkip) },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            text = { Text("Know when someone writes") },
        )
    }
}

@Composable
fun PermissionRequestScreen(onGranted: () -> Unit) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onGranted()
        }

    WearFormScreen(
        edgeButton = {
            FormEdgeButton(
                label = "Allow",
                onClick = { launcher.launch(MainActivity.requiredPermissions().toTypedArray()) }
            )
        }
    ) {
        formHeader(
            title = "bitchat",
            body = "Mesh chat over Bluetooth",
            icon = Icons.Filled.Bluetooth,
        )
    }
}

@Composable
fun BluetoothEnableScreen(onEnabled: () -> Unit) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onEnabled()
        }
    val warning = LocalBitchatPalette.current.accentOrange

    WearFormScreen(
        edgeButton = {
            FormEdgeButton(
                label = "Turn on",
                onClick = { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            )
        }
    ) {
        formHeader(
            title = "Bluetooth off",
            body = "Needed to mesh",
            icon = Icons.Filled.BluetoothDisabled,
            iconTint = warning,
        )
    }
}
