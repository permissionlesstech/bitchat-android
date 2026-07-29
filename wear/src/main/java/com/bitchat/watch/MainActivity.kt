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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.bitchat.watch.ui.WearChatState
import com.bitchat.watch.ui.sendPrivateMessage
import com.bitchat.watch.ui.sendPublicMessage
import com.bitchat.watch.ui.theme.BitchatWearTheme

sealed interface WearScreen {
    data object Chat : WearScreen
    data object People : WearScreen
    data object Nickname : WearScreen
    data class Dm(val peerID: String) : WearScreen
    data class TextInput(val peerID: String?) : WearScreen
}

class MainActivity : ComponentActivity() {

    private var hasPermissions by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)
    private var nicknameChosen by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var notificationPromptDismissed by mutableStateOf(false)
    private var pendingDmPeer by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nicknameChosen = getSharedPreferences("bitchat_watch_prefs", Context.MODE_PRIVATE)
            .getBoolean("nickname_chosen", false)
        pendingDmPeer = privateMessagePeerFromIntent(intent)
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
                        openDmPeer = pendingDmPeer,
                        onOpenDmHandled = { pendingDmPeer = null }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WearChatState.setAppInForeground(true)
        WearChatState.openDmPeer?.let { peerID ->
            WearChatState.openDm(peerID)
            WearNotificationCoordinator.getInstance(applicationContext).clearConversation(peerID)
        }
        refreshState()
    }

    override fun onPause() {
        WearChatState.setAppInForeground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        privateMessagePeerFromIntent(intent)?.let { pendingDmPeer = it }
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

    private fun privateMessagePeerFromIntent(intent: Intent?): String? {
        if (intent?.getBooleanExtra(WearNotificationCoordinator.EXTRA_OPEN_DM, false) != true) {
            return null
        }
        return intent.getStringExtra(WearNotificationCoordinator.EXTRA_PEER_ID)
            ?.takeIf { it.isNotBlank() }
    }

    private fun notificationPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        fun requiredPermissions(): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
    }
}

@Composable
fun WearNavHost(openDmPeer: String?, onOpenDmHandled: () -> Unit) {
    var screen by remember { mutableStateOf<WearScreen>(WearScreen.Chat) }
    val backStack = remember { mutableStateListOf<WearScreen>() }

    fun navigate(to: WearScreen) {
        backStack.add(screen)
        screen = to
    }

    fun goBack(): Boolean {
        val previous = backStack.removeLastOrNull()
        return if (previous != null) {
            screen = previous
            true
        } else false
    }

    BackHandler(enabled = backStack.isNotEmpty()) { goBack() }

    LaunchedEffect(openDmPeer) {
        openDmPeer?.let { peerID ->
            backStack.clear()
            screen = WearScreen.Dm(peerID)
            onOpenDmHandled()
        }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            fadeIn(tween(com.bitchat.watch.ui.theme.BitchatMotion.EMPHASIZED_MS)) togetherWith
                fadeOut(tween(com.bitchat.watch.ui.theme.BitchatMotion.QUICK_MS))
        },
        label = "screenTransition"
    ) { current ->
        when (current) {
            is WearScreen.Chat -> ChatScreen(
                onOpenPeople = { navigate(WearScreen.People) },
                onOpenTextInput = { navigate(WearScreen.TextInput(null)) }
            )
            is WearScreen.People -> PeopleScreen(
                onOpenDm = { navigate(WearScreen.Dm(it)) },
                onEditNickname = { navigate(WearScreen.Nickname) }
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
                        goBack()
                    }
                )
            }
            is WearScreen.Dm -> DmScreen(
                peerID = current.peerID,
                onOpenTextInput = { navigate(WearScreen.TextInput(current.peerID)) }
            )
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
                        goBack()
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
