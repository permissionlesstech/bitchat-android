package com.bitchat.android.hotspot

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Lightweight HTTP server for serving the universal APK over Wi-Fi P2P hotspot.
 * Based on NanoHTTPD.
 */
class ApkWebServer(
    private val context: Context,
    private val apkFile: File,
    private val port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ApkWebServer"
        const val DEFAULT_PORT = 9999
    }

    private val appVersion: String by lazy {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    // Cache the HTML landing page (generated once, reused for all requests)
    private val cachedHtml: String by lazy {
        generateLandingPageHtml()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"

        Log.d(TAG, "Request: ${session.method} $uri from ${session.remoteIpAddress}")

        return when {
            uri.endsWith(".apk") || uri == "/bitchat.apk" -> {
                serveApk()
            }
            uri == "/favicon.ico" -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            else -> {
                serveLandingPage()
            }
        }
    }

    /**
     * Serve the APK file.
     */
    private fun serveApk(): Response {
        return try {
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${apkFile.path}")
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "APK file not found"
                )
            }

            Log.d(TAG, "Serving APK: ${apkFile.name} (${apkFile.length() / 1024 / 1024}MB)")

            val inputStream = FileInputStream(apkFile)
            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.android.package-archive",
                inputStream,
                apkFile.length()
            )

            response.addHeader("Content-Disposition", "attachment; filename=\"bitchat-${appVersion}.apk\"")
            response.addHeader("Accept-Ranges", "bytes")

            response
        } catch (e: Exception) {
            Log.e(TAG, "Error serving APK", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error serving APK: ${e.message}"
            )
        }
    }

    /**
     * Serve the HTML landing page.
     */
    private fun serveLandingPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            cachedHtml
        )
    }

    /**
     * Generate HTML landing page.
     */
    private fun generateLandingPageHtml(): String {
        val apkSizeMb = apkFile.length() / 1024 / 1024

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>Download BitChat</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
            color: #333;
        }

        .container {
            background: white;
            border-radius: 20px;
            padding: 40px 30px;
            max-width: 500px;
            width: 100%;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
            text-align: center;
        }

        .logo {
            font-size: 64px;
            margin-bottom: 20px;
        }

        h1 {
            font-size: 32px;
            margin-bottom: 10px;
            color: #667eea;
        }

        .subtitle {
            font-size: 16px;
            color: #666;
            margin-bottom: 30px;
        }

        .info-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 15px;
            margin-bottom: 30px;
        }

        .info-box {
            background: #f5f7fa;
            padding: 15px;
            border-radius: 10px;
        }

        .info-label {
            font-size: 12px;
            color: #888;
            text-transform: uppercase;
            font-weight: 600;
            margin-bottom: 5px;
        }

        .info-value {
            font-size: 18px;
            font-weight: bold;
            color: #333;
        }

        .download-button {
            display: inline-block;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 18px 40px;
            border-radius: 50px;
            text-decoration: none;
            font-size: 18px;
            font-weight: 600;
            margin-bottom: 30px;
            transition: transform 0.2s, box-shadow 0.2s;
            box-shadow: 0 4px 15px rgba(102, 126, 234, 0.4);
        }

        .download-button:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 20px rgba(102, 126, 234, 0.5);
        }

        .download-button:active {
            transform: translateY(0);
        }

        .instructions {
            text-align: left;
            background: #f5f7fa;
            padding: 20px;
            border-radius: 10px;
            margin-top: 20px;
        }

        .instructions h3 {
            font-size: 16px;
            margin-bottom: 15px;
            color: #667eea;
        }

        .instructions ol {
            margin-left: 20px;
        }

        .instructions li {
            margin-bottom: 10px;
            line-height: 1.6;
            font-size: 14px;
            color: #555;
        }

        .warning {
            background: #fff3cd;
            border: 1px solid #ffc107;
            padding: 15px;
            border-radius: 10px;
            margin-top: 20px;
            font-size: 13px;
            color: #856404;
            text-align: left;
        }

        .warning strong {
            display: block;
            margin-bottom: 5px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="logo">🔒</div>
        <h1>BitChat</h1>
        <p class="subtitle">Secure Mesh Messaging</p>

        <div class="info-grid">
            <div class="info-box">
                <div class="info-label">Version</div>
                <div class="info-value">$appVersion</div>
            </div>
            <div class="info-box">
                <div class="info-label">Size</div>
                <div class="info-value">${apkSizeMb} MB</div>
            </div>
        </div>

        <a href="/bitchat.apk" class="download-button">
            📥 Download BitChat
        </a>

        <div class="instructions">
            <h3>📱 Installation Instructions</h3>
            <ol>
                <li>Tap the download button above</li>
                <li>Wait for the download to complete</li>
                <li>Open the downloaded APK file</li>
                <li>If prompted, enable "Install from unknown sources" for your browser</li>
                <li>Follow the installation prompts</li>
            </ol>
        </div>

        <div class="warning">
            <strong>⚠️ Note:</strong>
            If you already have BitChat installed, you may need to uninstall it first before installing this version. Make sure to backup your data if needed.
        </div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Start the server.
     */
    fun startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Web server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
            throw e
        }
    }

    /**
     * Stop the server.
     */
    fun stopServer() {
        try {
            stop()
            Log.d(TAG, "Web server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping web server", e)
        }
    }
}
