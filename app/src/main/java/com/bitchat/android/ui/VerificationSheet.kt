package com.bitchat.android.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.services.VerificationService
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return

    val isDark = isSystemInDarkTheme()
    val accent = if (isDark) Color.Green else Color(0xFF008000)
    
    var selectedTab by remember { mutableStateOf(0) } // 0 = My Code, 1 = Scan
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val npub = remember { viewModel.getCurrentNpub() }

    val qrString = remember(nickname, npub) {
        viewModel.buildMyQRString(nickname, npub)
    }

    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // Header
            VerificationHeader(
                accent = accent,
                onClose = onDismiss,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = accent,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = accent
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "My QR",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Scan",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Content
            Crossfade(
                targetState = selectedTab, 
                label = "VerificationTabCrossfade",
                modifier = Modifier.weight(1f)
            ) { tab ->
                when (tab) {
                    0 -> MyQrTabContent(
                        qrString = qrString,
                        nickname = nickname,
                        accent = accent
                    )
                    1 -> ScanTabContent(
                        accent = accent,
                        onScan = { code ->
                            val qr = VerificationService.verifyScannedQR(code)
                            if (qr != null && viewModel.beginQRVerification(qr)) {
                                selectedTab = 0
                            }
                        }
                    )
                }
            }
            
            // Unverify Action
            val peerID by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
            val fingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
            
            if (peerID != null) {
                val fingerprint = viewModel.meshService.getPeerFingerprint(peerID!!)
                if (fingerprint != null && fingerprints.contains(fingerprint)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.unverifyFingerprint(peerID!!) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.verify_remove),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationHeader(
    accent: Color,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.verify_title).uppercase(),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = accent
        )
        CloseButton(onClick = onClose)
    }
}

@Composable
private fun MyQrTabContent(
    qrString: String,
    nickname: String,
    accent: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.verify_my_qr_title),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = accent
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        if (qrString.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(20.dp) // Quiet zone
            ) {
                QRCodeImage(data = qrString, size = 260.dp)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.verify_qr_unavailable),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.Black.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // User Nickname
        Text(
            text = nickname,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Helper text
        Text(
            text = stringResource(R.string.app_name).lowercase(),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ScanTabContent(
    accent: Color,
    onScan: (String) -> Unit
) {
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (permissionState.status.isGranted) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                ScannerView(onScan = onScan)
                
                // Overlay border
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .border(2.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                )
                
                // Corner accents for the overlay
                Box(modifier = Modifier.size(260.dp)) {
                    // This could be drawn with Canvas for cooler effect, but simple border is cleaner for now
                }
                
                Text(
                    text = stringResource(R.string.verify_scan_prompt_friend),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = accent
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.verify_camera_permission),
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { permissionState.launchPermissionRequest() },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text(
                        text = stringResource(R.string.verify_request_camera),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerView(
    onScan: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lastValid by remember { mutableStateOf<String?>(null) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val surfaceRequests = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceRequest by surfaceRequests.collectAsState(initial = null)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val onCodeState = rememberUpdatedState(onScan)
    val analyzer = remember {
        QRCodeAnalyzer { text ->
            mainHandler.post {
                if (text == lastValid) return@post
                lastValid = text
                onCodeState.value(text)
            }
        }
    }

    DisposableEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequests.value = request }
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }.onFailure {
                    Log.w("VerificationSheet", "Failed to bind camera: ${it.message}")
                }
            },
            executor
        )

        onDispose {
            surfaceRequests.value = null
            runCatching { cameraProvider?.unbindAll() }
            cameraExecutor.shutdown()
        }
    }

    surfaceRequest?.let { request ->
        CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = ImplementationMode.EMBEDDED,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun QRCodeImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { generateQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    }
}

private fun generateQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        bitmapFromMatrix(matrix)
    } catch (_: Exception) {
        null
    }
}

private fun bitmapFromMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}

private class QRCodeAnalyzer(
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstOrNull()?.rawValue
                if (!text.isNullOrBlank()) onCode(text)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
