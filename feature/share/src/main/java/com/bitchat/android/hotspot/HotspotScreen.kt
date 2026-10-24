package com.bitchat.android.hotspot

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Build
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.feature.share.R
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatTheme

/**
 * The hotspot screen, drawn from [uiState] alone. [HotspotRoot] owns the plumbing:
 * the permission request behind [HotspotAction.StartClicked] (and whether the rationale
 * card shows), the clipboard behind [onCopy], and finishing on [HotspotEvent.Close].
 * The selected tab is local, as before, and resets on rotation (Q-H6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HotspotScreen(
    uiState: HotspotUiState,
    onAction: (HotspotAction) -> Unit,
    modifier: Modifier = Modifier,
    showPermissionRationale: Boolean = false,
    onCopy: (String) -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Share BitChat",
                        fontFamily = BitchatFontFamily
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(HotspotAction.CloseClicked) }) {
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
            targetState = uiState,
            label = "HotspotStateCrossfade",
            modifier = Modifier.padding(padding)
        ) { currentState ->
            when (currentState) {
                is HotspotUiState.Intro -> {
                    IntroScreen(
                        onStartHotspot = { onAction(HotspotAction.StartClicked) },
                        showPermissionRationale = showPermissionRationale
                    )
                }
                is HotspotUiState.Starting -> {
                    LoadingScreen()
                }
                is HotspotUiState.ConfirmDisconnect -> {
                    ExistingGroupConfirmation(
                        onConfirm = { onAction(HotspotAction.ConfirmDisconnect) },
                        onCancel = { onAction(HotspotAction.CancelDisconnect) }
                    )
                }
                is HotspotUiState.Active -> {
                    ActiveHotspotScreen(state = currentState, onCopy = onCopy)
                }
                is HotspotUiState.Error -> {
                    ErrorScreen(
                        message = currentState.message,
                        onRetry = { onAction(HotspotAction.RetryClicked) },
                        onClose = { onAction(HotspotAction.CloseClicked) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExistingGroupConfirmation(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.hotspot_disconnect_title)) },
        text = { Text(stringResource(R.string.hotspot_disconnect_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.hotspot_disconnect_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun IntroScreen(onStartHotspot: () -> Unit, showPermissionRationale: Boolean) {
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
        if (showPermissionRationale) {
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
                        text = when {
                            Build.VERSION.SDK_INT >= HotspotPermissions.ANDROID_17_API_LEVEL ->
                                "BitChat needs nearby devices and local network access to create a Wi-Fi hotspot and serve the app to connected devices."
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                "BitChat needs nearby devices permission to create a Wi-Fi hotspot for sharing the app offline."
                            else ->
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
            // The host checks the permissions: it starts at once when they are
            // granted and otherwise requests them (and starts when all are granted).
            onClick = onStartHotspot,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                // Starting the hotspot is the user's action. Android will ask
                // for the required permission only when it has not already
                // been granted.
                text = "Start Hotspot",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun InfoItem(text: String) {
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
private fun LoadingScreen() {
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
private fun ActiveHotspotScreen(state: HotspotUiState.Active, onCopy: (String) -> Unit) {
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
                            fontFamily = BitchatFontFamily,
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
                password = state.password,
                onCopy = onCopy
            )
            1 -> WebsiteTabContent(
                ipAddress = state.ipAddress,
                port = state.port,
                onCopy = onCopy
            )
        }
    }
}

@Composable
private fun WifiTabContent(ssid: String, password: String, onCopy: (String) -> Unit) {
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
            onCopy = { onCopy(ssid) }
        )

        // Password
        CredentialCard(
            label = "Password",
            value = password,
            onCopy = { onCopy(password) }
        )
    }
}

@Composable
private fun WebsiteTabContent(ipAddress: String, port: Int, onCopy: (String) -> Unit) {
    val url = "http://$ipAddress:$port"
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
            onCopy = { onCopy(url) }
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
private fun CredentialCard(
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
                    fontFamily = BitchatFontFamily,
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
private fun ErrorScreen(
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

// --- Previews ---

/** A running hotspot with synthetic credentials. */
internal fun previewHotspotUiState(): HotspotUiState = HotspotUiState.Active(
    ssid = "bitchat-test",
    password = "synthetic-pass",
    ipAddress = "192.0.2.1",
    port = ApkWebServer.DEFAULT_PORT,
    connectedPeers = 1
)

@Preview(name = "Intro")
@Preview(name = "Intro (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HotspotIntroPreview() {
    BitchatTheme { HotspotScreen(uiState = HotspotUiState.Intro, onAction = {}) }
}

@Preview(name = "Active")
@Preview(name = "Active (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HotspotActivePreview() {
    BitchatTheme { HotspotScreen(uiState = previewHotspotUiState(), onAction = {}) }
}

@Preview(name = "Error")
@Preview(name = "Error (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun HotspotErrorPreview() {
    BitchatTheme {
        HotspotScreen(
            uiState = HotspotUiState.Error("Failed to get hotspot connection info"),
            onAction = {}
        )
    }
}
