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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.service.WearMeshForegroundService
import com.bitchat.watch.ui.ChatScreen
import com.bitchat.watch.ui.DmScreen
import com.bitchat.watch.ui.NicknameSetupScreen
import com.bitchat.watch.ui.PeopleScreen
import com.bitchat.watch.ui.UserDetailScreen
import com.bitchat.watch.ui.VerificationCodeScreen
import com.bitchat.watch.ui.WearChatState
import com.bitchat.watch.ui.sendPrivateMessage
import com.bitchat.watch.ui.sendPublicMessage
import com.bitchat.watch.ui.theme.BitchatWearTheme

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
                when {
                    !hasPermissions -> PermissionRequestScreen(onGranted = { refreshState() })
                    !bluetoothEnabled -> BluetoothEnableScreen(onEnabled = { refreshState() })
                    !nicknameChosen -> NicknameSetupScreen(
                        initialNickname = WearMeshService.getOrCreate(applicationContext).nickname
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
    onLaunchRequestHandled: (Long) -> Unit
) {
    val navigation = rememberSaveable(saver = WearNavigationState.Saver) {
        WearNavigationState()
    }

    BackHandler(enabled = navigation.canGoBack) { navigation.goBack() }

    LaunchedEffect(launchRequest) {
        launchRequest?.let { request ->
            when (val target = request.target) {
                WearLaunchTarget.Chat -> navigation.openChat()
                is WearLaunchTarget.Dm -> navigation.openDmFromNotification(target.peerID)
            }
            onLaunchRequestHandled(request.id)
        }
    }

    AnimatedContent(
        targetState = navigation.screen,
        transitionSpec = {
            fadeIn(tween(com.bitchat.watch.ui.theme.BitchatMotion.EMPHASIZED_MS)) togetherWith
                fadeOut(tween(com.bitchat.watch.ui.theme.BitchatMotion.QUICK_MS))
        },
        label = "screenTransition"
    ) { current ->
        when (current) {
            is WearScreen.Chat -> ChatScreen(
                onOpenPeople = { navigation.navigate(WearScreen.People) },
                onOpenTextInput = { navigation.navigate(WearScreen.TextInput(null)) }
            )
            is WearScreen.People -> PeopleScreen(
                onOpenDm = { navigation.navigate(WearScreen.Dm(it)) },
                onEditNickname = { navigation.navigate(WearScreen.Nickname) }
            )
            is WearScreen.Nickname -> {
                val mesh = WearMeshService.peek()
                NicknameSetupScreen(
                    initialNickname = mesh?.nickname ?: "",
                    title = "You",
                    subtitle = "How nearby peers see you",
                    confirmLabel = "Save",
                    onConfirm = { name ->
                        mesh?.setNickname(name)
                        navigation.goBack()
                    }
                )
            }
            is WearScreen.Dm -> DmScreen(
                peerID = current.peerID,
                onOpenUserDetail = {
                    navigation.navigate(WearScreen.UserDetail(current.peerID))
                },
                onOpenTextInput = {
                    navigation.navigate(WearScreen.TextInput(current.peerID))
                }
            )
            is WearScreen.UserDetail -> UserDetailScreen(
                peerID = current.peerID,
                onOpenVerification = {
                    navigation.navigate(WearScreen.Verification(current.peerID))
                }
            )
            is WearScreen.Verification -> VerificationCodeScreen(peerID = current.peerID)
            is WearScreen.TextInput -> {
                val mesh = WearMeshService.peek()
                val sendScope = androidx.compose.runtime.rememberCoroutineScope()
                com.bitchat.watch.ui.TextInputScreen(
                    onSend = { text ->
                        mesh?.let { m ->
                            if (current.peerID == null) {
                                sendPublicMessage(m, text)
                            } else {
                                val nick = m.getPeerNickname(current.peerID) ?: current.peerID
                                sendPrivateMessage(m, current.peerID, nick, text, sendScope)
                            }
                        }
                        navigation.goBack()
                    }
                )
            }
        }
    }
}

@Composable
fun NotificationPermissionScreen(onResult: (Boolean) -> Unit, onSkip: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onResult(granted) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Message alerts",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Alerts for encrypted direct messages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
        )
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onResult(true)
                }
            }
        ) {
            Text("Enable")
        }
        TextButton(onClick = onSkip) {
            Text("Not now")
        }
    }
}

@Composable
fun PermissionRequestScreen(onGranted: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onGranted() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "bitchat",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Needs Bluetooth to mesh with nearby devices",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
        )
        Button(onClick = {
            launcher.launch(MainActivity.requiredPermissions().toTypedArray())
        }) {
            Text("Grant access")
        }
    }
}

@Composable
fun BluetoothEnableScreen(onEnabled: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onEnabled() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Bluetooth is off",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Button(
            onClick = { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text("Turn on")
        }
    }
}
