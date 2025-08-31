package com.bitchat.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.updateLayoutParams
import com.bitchat.android.geohash.Geohash
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove

@OptIn(ExperimentalMaterial3Api::class)
class GeohashPickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_GEOHASH = "initial_geohash"
        const val EXTRA_RESULT_GEOHASH = "result_geohash"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialGeohash = intent.getStringExtra(EXTRA_INITIAL_GEOHASH)?.trim()?.lowercase()
        val initialPrecision = initialGeohash?.length ?: 5
        val (initLat, initLon) = if (!initialGeohash.isNullOrEmpty()) {
            Geohash.decodeToCenter(initialGeohash)
        } else 0.0 to 0.0

        setContent {
            MaterialTheme {
                var currentGeohash by remember { mutableStateOf(initialGeohash ?: "") }
                var precision by remember { mutableStateOf(initialPrecision.coerceIn(1, 12)) }
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = if (currentGeohash.isNotEmpty()) "#${currentGeohash}" else "Select location") },
                            actions = {
                                IconButton(onClick = {
                                    precision = (precision - 1).coerceAtLeast(1)
                                    webViewRef?.evaluateJavascript("window.setPrecision($precision)", null)
                                }) {
                                    Icon(Icons.Filled.Remove, contentDescription = "Decrease precision")
                                }
                                IconButton(onClick = {
                                    precision = (precision + 1).coerceAtMost(12)
                                    webViewRef?.evaluateJavascript("window.setPrecision($precision)", null)
                                }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Increase precision")
                                }
                                IconButton(onClick = {
                                    // Retrieve current geohash from the page and return
                                    webViewRef?.evaluateJavascript("window.getGeohash()") { value ->
                                        // value comes quoted, remove quotes
                                        val gh = value?.trim('"') ?: currentGeohash
                                        val result = Intent().apply { putExtra(EXTRA_RESULT_GEOHASH, gh) }
                                        setResult(Activity.RESULT_OK, result)
                                        finish()
                                    }
                                }) {
                                    Icon(Icons.Filled.Check, contentDescription = "Done")
                                }
                            }
                        )
                    }
                ) { padding ->
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
                                            // Initialize map center & precision
                                            evaluateJavascript(
                                                "window.setCenter(${initLat}, ${initLon}); window.setPrecision(${precision});",
                                                null
                                            )
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
                            modifier = Modifier.fillMaxSize(),
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
                    }
                }
            }
        }
    }
}
