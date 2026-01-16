package com.bitchat.android.hotspot

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.theme.BitchatTheme
import com.bitchat.android.util.UniversalApkManager
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File

/**
 * Activity for managing Wi-Fi P2P hotspot for offline APK sharing.
 * Pure Compose implementation, no fragments.
 */
class HotspotActivity : ComponentActivity() {

    companion object {
        const val EXTRA_APK_PATH = "apk_path"
        private const val TAG = "HotspotActivity"
    }

    private val viewModel: HotspotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get APK path from intent
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val apkFile = if (apkPath != null) {
            File(apkPath)
        } else {
            // Fallback: Try to get cached APK
            UniversalApkManager(this).getCachedApk()
        }

        if (apkFile == null || !apkFile.exists()) {
            // No APK available, show error and finish
            finish()
            return
        }

        setContent {
            BitchatTheme {
                HotspotScreen(
                    viewModel = viewModel,
                    apkFile = apkFile,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification action to stop hotspot
        if (intent.action == "STOP_HOTSPOT") {
            viewModel.stopHotspot()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopHotspot()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotspotScreen(
    viewModel: HotspotViewModel,
    apkFile: File,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Share BitChat",
                        fontFamily = FontFamily.Monospace
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Crossfade(
            targetState = state,
            label = "HotspotStateCrossfade",
            modifier = Modifier.padding(padding)
        ) { currentState ->
            when (currentState) {
                is HotspotViewModel.HotspotState.Intro -> {
                    IntroScreen(
                        onStartHotspot = { viewModel.startHotspot(apkFile) }
                    )
                }
                is HotspotViewModel.HotspotState.Starting -> {
                    LoadingScreen()
                }
                is HotspotViewModel.HotspotState.Active -> {
                    ActiveHotspotScreen(state = currentState)
                }
                is HotspotViewModel.HotspotState.Error -> {
                    ErrorScreen(
                        message = currentState.message,
                        onRetry = { viewModel.resetToIntro() },
                        onClose = onClose
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun IntroScreen(onStartHotspot: () -> Unit) {
    // Determine which permission to request based on Android version
    val requiredPermission = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.NEARBY_WIFI_DEVICES
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> null // No runtime permission needed on Android < 10
    }

    val permissionState = requiredPermission?.let { rememberPermissionState(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Offline App Sharing",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "How it works:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                InfoItem("1. Your device creates a Wi-Fi hotspot")
                InfoItem("2. Others connect to your hotspot")
                InfoItem("3. They scan a QR code or enter a URL")
                InfoItem("4. BitChat downloads directly to their device")
            }
        }

        // Permission rationale (if needed)
        if (permissionState != null && !permissionState.status.isGranted && permissionState.status.shouldShowRationale) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ℹ️ Permission Required",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "BitChat needs nearby devices permission to create a Wi-Fi hotspot for sharing the app offline."
                        } else {
                            "BitChat needs location permission to create a Wi-Fi hotspot. This is required by Android for Wi-Fi scanning, but no location data is collected."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "⚠️ Note",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "This will create a Wi-Fi hotspot on your device. Your current Wi-Fi connection may be interrupted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                // Check permission before starting hotspot
                if (permissionState == null || permissionState.status.isGranted) {
                    // No permission needed or already granted
                    onStartHotspot()
                } else {
                    // Request permission
                    permissionState.launchPermissionRequest()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (permissionState != null && !permissionState.status.isGranted) {
                    "Grant Permission"
                } else {
                    "Start Hotspot"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun InfoItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Starting hotspot...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun ActiveHotspotScreen(state: HotspotViewModel.HotspotState.Active) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Wi-Fi", "Website")

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Status banner
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Hotspot Active",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${state.connectedPeers} device(s) connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Tabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        // Tab content
        when (selectedTab) {
            0 -> WifiTabContent(
                ssid = state.ssid,
                password = state.password
            )
            1 -> WebsiteTabContent(
                ipAddress = state.ipAddress,
                port = state.port
            )
        }
    }
}

@Composable
fun WifiTabContent(ssid: String, password: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Step 1: Connect to Wi-Fi",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Have others scan this QR code to connect:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        // QR Code
        val qrSize = with(LocalDensity.current) { 280.dp.toPx().toInt() }
        val wifiQr = remember(ssid, password, qrSize) {
            QrCodeGenerator.generateWifiQr(ssid, password, qrSize)
        }

        if (wifiQr != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                Image(
                    bitmap = wifiQr.asImageBitmap(),
                    contentDescription = "Wi-Fi QR Code",
                    modifier = Modifier.size(280.dp)
                )
            }
        }

        Text(
            text = "Or enter manually:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        // SSID
        CredentialCard(
            label = "Network Name (SSID)",
            value = ssid,
            onCopy = {
                clipboardManager.setText(AnnotatedString(ssid))
            }
        )

        // Password
        CredentialCard(
            label = "Password",
            value = password,
            onCopy = {
                clipboardManager.setText(AnnotatedString(password))
            }
        )
    }
}

@Composable
fun WebsiteTabContent(ipAddress: String, port: Int) {
    val url = "http://$ipAddress:$port"
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Step 2: Download BitChat",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "After connecting to the Wi-Fi, scan this QR code:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        // QR Code
        val qrSize = with(LocalDensity.current) { 280.dp.toPx().toInt() }
        val urlQr = remember(url, qrSize) {
            QrCodeGenerator.generateUrlQr(url, qrSize)
        }

        if (urlQr != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp)
            ) {
                Image(
                    bitmap = urlQr.asImageBitmap(),
                    contentDescription = "Website URL QR Code",
                    modifier = Modifier.size(280.dp)
                )
            }
        }

        Text(
            text = "Or open in browser:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        // URL
        CredentialCard(
            label = "Website URL",
            value = url,
            onCopy = {
                clipboardManager.setText(AnnotatedString(url))
            }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "📱 Instructions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "1. Make sure you're connected to the Wi-Fi network above\n" +
                            "2. Open a web browser on your device\n" +
                            "3. Visit the URL above or scan the QR code\n" +
                            "4. Tap 'Download BitChat'\n" +
                            "5. Install the downloaded APK",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun CredentialCard(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "❌",
            fontSize = 64.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Try Again")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onClose) {
            Text("Close")
        }
    }
}
