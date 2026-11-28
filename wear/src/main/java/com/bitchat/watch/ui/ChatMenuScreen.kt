package com.bitchat.watch.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette

private const val MENU_PAGE = 0
private const val CHAT_PAGE = 1

/**
 * Home of the app: the public mesh chat, with an app menu one page to its left. Swiping the
 * chat to the right reveals the menu (the Wear pattern used by Fitbit, Health and similar
 * apps); swiping right again from the menu's left edge falls through to the system
 * swipe-to-dismiss, so leaving the app still works as everywhere else on the watch.
 *
 * [swipeEnabled] is false while the chat owns a horizontal gesture (push-to-talk drag,
 * full-screen image), so the pager never steals the pointer mid-gesture.
 */
@Composable
fun ChatHomePager(
    swipeEnabled: Boolean,
    menu: @Composable () -> Unit,
    chat: @Composable () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = CHAT_PAGE) { 2 }
    // No page indicator: the menu is a peek-behind page, not a peer of the chat, and the
    // dots would crowd the chat's edge action bar.
    HorizontalPagerScaffold(pagerState = pagerState, pageIndicator = null) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = swipeEnabled,
            // The crown scrolls chat history, never pages.
            rotaryScrollableBehavior = null,
        ) { page ->
            when (page) {
                MENU_PAGE -> menu()
                CHAT_PAGE -> chat()
            }
        }
    }
}

/**
 * The app's settings live here, beside the chat, rather than inside the People list: mesh
 * status, the live push-to-talk switch, and quitting.
 */
@Composable
fun ChatMenuScreen(onQuit: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalBitchatPalette.current
    val peers by AppStateStore.peers.collectAsState()
    val listState = rememberScalingLazyListState()
    var confirmQuit by rememberSaveable { mutableStateOf(false) }
    var liveVoiceEnabled by remember { mutableStateOf(LiveVoicePreferences.isEnabled(context)) }

    ScreenScaffold(scrollState = listState) { scaffoldPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = scaffoldPadding,
        ) {
            item {
                ListHeader {
                    Text(
                        text = "bitchat",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item {
                Text(
                    text = meshStatusText(peers.size),
                    style = ChatVisualTokens.SystemActionStyle,
                    color = palette.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            item {
                SwitchButton(
                    checked = liveVoiceEnabled,
                    onCheckedChange = { enabled ->
                        liveVoiceEnabled = enabled
                        LiveVoicePreferences.setEnabled(context, enabled)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = {
                        Text("Real-time", maxLines = 1)
                    },
                ) {
                    Text("Live talk", maxLines = 1)
                }
            }
            item {
                FilledTonalButton(
                    onClick = { confirmQuit = true },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "Ends mesh",
                            color = palette.textTertiary,
                            maxLines = 1,
                        )
                    },
                ) {
                    Text("Quit", maxLines = 1)
                }
            }
        }
    }

    AlertDialog(
        visible = confirmQuit,
        onDismissRequest = { confirmQuit = false },
        icon = {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
        },
        title = { Text("Quit bitchat?") },
        confirmButton = {
            // Quitting tears the mesh down, so confirm carries the error color, not primary.
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmQuit = false
                    onQuit()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { confirmQuit = false })
        },
    )
}

private fun meshStatusText(count: Int): String = when (count) {
    0 -> "Mesh on · scanning"
    1 -> "Mesh on · 1 peer"
    else -> "Mesh on · $count peers"
}
