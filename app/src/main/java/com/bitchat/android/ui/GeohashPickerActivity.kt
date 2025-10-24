package com.bitchat.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import androidx.core.view.updateLayoutParams
import com.bitchat.android.geohash.Geohash
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.ui.theme.BASE_FONT_SIZE

@OptIn(ExperimentalMaterial3Api::class)
class GeohashPickerActivity : OrientationAwareActivity() {

    companion object {
        const val EXTRA_INITIAL_GEOHASH = "initial_geohash"
        const val EXTRA_RESULT_GEOHASH = "result_geohash"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialGeohash = intent.getStringExtra(EXTRA_INITIAL_GEOHASH)?.trim()?.lowercase()
        var geohashToFocus: String? = null
        var (initLat, initLon) = 0.0 to 0.0

        if (!initialGeohash.isNullOrEmpty()) {
            geohashToFocus = initialGeohash
            try {
                val (lat, lon) = Geohash.decodeToCenter(initialGeohash)
                initLat = lat
                initLon = lon
            } catch (_: Throwable) {}
        } else {
            // If no initial geohash, try to use the user's coarsest location
            val locationManager = LocationChannelManager.getInstance(applicationContext)
            val channels = locationManager.availableChannels.value
            if (!channels.isNullOrEmpty()) {
                val coarsestChannel = channels.minByOrNull { it.geohash.length }
                if (coarsestChannel != null) {
                    geohashToFocus = coarsestChannel.geohash
                    try {
                        val (lat, lon) = Geohash.decodeToCenter(coarsestChannel.geohash)
                        initLat = lat
                        initLon = lon
                    } catch (_: Throwable) {}
                }
            }
        }

        val initialPrecision = geohashToFocus?.length ?: 5

        setContent {
            MaterialTheme {
                var currentGeohash by remember { mutableStateOf(geohashToFocus ?: "") }
                var precision by remember { mutableStateOf(initialPrecision.coerceIn(1, 12)) }
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                // iOS system-like colors used across app
                val colorScheme = MaterialTheme.colorScheme
                val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
                val standardGreen = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)

                Scaffold { padding ->
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                                    settings.allowFileAccess = true
                                    settings.allowContentAccess = true
                                    webChromeClient = WebChromeClient()
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            // Initialize to last/initial geohash if provided, otherwise center
                                            if (!geohashToFocus.isNullOrEmpty()) {
                                                evaluateJavascript(
                                                    "window.focusGeohash('${geohashToFocus}')",
                                                    null
                                                )
                                            } else {
                                                evaluateJavascript(
                                                    "window.setCenter(${initLat}, ${initLon})",
                                                    null
                                                )
                                            }

                                            // Apply theme to map tiles
                                            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                                            val theme = if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
                                            evaluateJavascript("window.setMapTheme('" + theme + "')", null)
                                        }
                                    }
                                    addJavascriptInterface(object {
                                        @JavascriptInterface
                                        fun onGeohashChanged(geohash: String) {
                                            runOnUiThread {
                                                currentGeohash = geohash
                                            }
                                        }
                                    }, "Android")

                                    loadUrl("file:///android_asset/geohash_picker.html")
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            update = { webView ->
                                webViewRef = webView
                                // ensure it fills parent
                                webView.updateLayoutParams<ViewGroup.LayoutParams> {
                                    width = ViewGroup.LayoutParams.MATCH_PARENT
                                    height = ViewGroup.LayoutParams.MATCH_PARENT
                                }
                            },
                            onRelease = { webView ->
                                // Best-effort cleanup to avoid leaks and timers
                                try { webView.evaluateJavascript("window.cleanup && window.cleanup()", null) } catch (_: Throwable) {}
                                try { webView.stopLoading() } catch (_: Throwable) {}
                                try { webView.clearHistory() } catch (_: Throwable) {}
                                try { webView.clearCache(true) } catch (_: Throwable) {}
                                try { webView.loadUrl("about:blank") } catch (_: Throwable) {}
                                try { webView.removeAllViews() } catch (_: Throwable) {}
                                try { webView.destroy() } catch (_: Throwable) {}
                            }
                        )

                        // Floating info pill
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 20.dp)
                                .fillMaxWidth(0.75f),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 3.dp,
                            shadowElevation = 6.dp
                        ) {
                            Text(
                                text = stringResource(R.string.pan_zoom_instruction),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }

                        // Floating bottom controls
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp, start = 16.dp, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Geohash label (monospace, app style)
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(12.dp),
                                tonalElevation = 3.dp,
                                shadowElevation = 6.dp
                            ) {
                                Text(
                                    text = if (currentGeohash.isNotEmpty()) "#${currentGeohash}" else "select location",
                                    fontSize = BASE_FONT_SIZE.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }

                            // Button row
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Decrease precision
                                Button(
                                    onClick = {
                                        precision = (precision - 1).coerceAtLeast(1)
                                        webViewRef?.evaluateJavascript("window.setPrecision($precision)", null)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = standardGreen.copy(alpha = 0.12f),
                                        contentColor = standardGreen
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.cd_decrease_precision))
                                    }
                                }

                                // Increase precision
                                Button(
                                    onClick = {
                                        precision = (precision + 1).coerceAtMost(12)
                                        webViewRef?.evaluateJavascript("window.setPrecision($precision)", null)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = standardGreen.copy(alpha = 0.12f),
                                        contentColor = standardGreen
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_increase_precision))
                                    }
                                }

                                // Select button
                                Button(
                                    onClick = {
                                        webViewRef?.evaluateJavascript("window.getGeohash()") { value ->
                                            val gh = value?.trim('"') ?: currentGeohash
                                            val result = Intent().apply { putExtra(EXTRA_RESULT_GEOHASH, gh) }
                                            setResult(Activity.RESULT_OK, result)
                                            finish()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_select_geohash))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.select),
                                            fontSize = (BASE_FONT_SIZE - 2).sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
