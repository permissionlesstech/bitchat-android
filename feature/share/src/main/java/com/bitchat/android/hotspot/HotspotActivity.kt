package com.bitchat.android.hotspot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.share.AppBuildInfo
import com.bitchat.android.ui.ObserveAsEvents
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.util.UniversalApkManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject

/**
 * Activity for managing Wi-Fi P2P hotspot for offline APK sharing.
 * Pure Compose implementation, no fragments.
 */
@AndroidEntryPoint
class HotspotActivity : ComponentActivity() {

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        private const val TAG = "HotspotActivity"
    }

    @Inject
    lateinit var appBuildInfo: AppBuildInfo

    @Inject
    lateinit var torManager: ArtiTorManager

    @Inject
    lateinit var okHttpProvider: OkHttpProvider

    private val viewModel: HotspotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get APK path from intent
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val apkFile = if (apkPath != null) {
            File(apkPath)
        } else {
            // Fallback: Try to get cached APK
            UniversalApkManager(this, appBuildInfo, torManager, okHttpProvider).getCachedApk()
        }

        if (apkFile == null || !apkFile.exists()) {
            // No APK available, show error and finish
            finish()
            return
        }

        // The ViewModel reads the path from its SavedStateHandle, which is seeded
        // from the intent extras when it is first created (below, in setContent).
        // Write the cached fallback there so both paths serve the same file.
        if (apkPath == null) {
            intent.putExtra(EXTRA_APK_PATH, apkFile.absolutePath)
        }

        setContent {
            BitchatTheme {
                HotspotRoot(
                    onClose = { finish() },
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification action to stop hotspot. Nothing sends it today (Q-H1).
        if (intent.action == "STOP_HOTSPOT") {
            viewModel.onAction(HotspotAction.StopRequested)
            finish()
        }
    }

}

/**
 * Hosts [HotspotScreen]: the permission gate in front of starting, the clipboard,
 * and [onClose] on [HotspotEvent.Close].
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
internal fun HotspotRoot(
    onClose: () -> Unit,
    viewModel: HotspotViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current

    val requiredPermissions = remember { HotspotPermissions.requiredForSdk() }
    val permissionState = rememberMultiplePermissionsState(requiredPermissions) { results ->
        // Auto-start only when every required permission was granted (Q-H4: no message otherwise).
        if (requiredPermissions.all { results[it] == true }) {
            viewModel.onAction(HotspotAction.StartClicked)
        }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            HotspotEvent.Close -> onClose()
        }
    }

    HotspotScreen(
        uiState = uiState,
        onAction = { action ->
            if (action == HotspotAction.StartClicked && !permissionState.allPermissionsGranted) {
                // Request permission (auto-start handled by the result callback above)
                permissionState.launchMultiplePermissionRequest()
            } else {
                viewModel.onAction(action)
            }
        },
        showPermissionRationale = !permissionState.allPermissionsGranted &&
            permissionState.shouldShowRationale,
        onCopy = { text -> clipboardManager.setText(AnnotatedString(text)) }
    )
}
