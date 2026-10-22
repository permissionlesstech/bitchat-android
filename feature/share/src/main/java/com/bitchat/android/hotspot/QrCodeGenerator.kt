package com.bitchat.android.hotspot

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Utility for generating QR codes for Wi-Fi connection and URL.
 */
object QrCodeGenerator {

    private const val TAG = "QrCodeGenerator"

    /**
     * Generate QR code for Wi-Fi connection.
     * Format: WIFI:S:{SSID};T:WPA;P:{PASSWORD};;
     *
     * This format is recognized by most Android/iOS devices for instant Wi-Fi connection.
     *
     * @param ssid Wi-Fi network name
     * @param password Wi-Fi password
     * @param sizePx Size of the QR code in pixels
     * @return Bitmap of the QR code, or null on error
     */
    fun generateWifiQr(ssid: String, password: String, sizePx: Int): Bitmap? {
        if (ssid.isBlank() || password.isBlank()) {
            Log.w(TAG, "SSID or password is blank")
            return null
        }

        // Escape special characters
        val escapedSsid = escapeWifiString(ssid)
        val escapedPassword = escapeWifiString(password)

        // Format: WIFI:S:{SSID};T:WPA;P:{PASSWORD};;
        val wifiString = "WIFI:S:$escapedSsid;T:WPA;P:$escapedPassword;;"

        Log.d(TAG, "Generating Wi-Fi QR code for SSID: $ssid")

        return generateQrBitmap(wifiString, sizePx)
    }

    /**
     * Generate QR code for URL.
     *
     * @param url Website URL (e.g., "http://192.168.49.1:9999")
     * @param sizePx Size of the QR code in pixels
     * @return Bitmap of the QR code, or null on error
     */
    fun generateUrlQr(url: String, sizePx: Int): Bitmap? {
        if (url.isBlank()) {
            Log.w(TAG, "URL is blank")
            return null
        }

        Log.d(TAG, "Generating URL QR code: $url")

        return generateQrBitmap(url, sizePx)
    }

    /**
     * Generate QR code bitmap from string data.
     *
     * @param data String data to encode
     * @param sizePx Size of the QR code in pixels
     * @return Bitmap of the QR code, or null on error
     */
    private fun generateQrBitmap(data: String, sizePx: Int): Bitmap? {
        if (data.isBlank() || sizePx <= 0) {
            Log.w(TAG, "Invalid data or size: data.length=${data.length}, sizePx=$sizePx")
            return null
        }

        return try {
            val matrix = QRCodeWriter().encode(
                data,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx
            )
            bitmapFromMatrix(matrix)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating QR code", e)
            null
        }
    }

    /**
     * Convert BitMatrix to Bitmap.
     * Pattern from VerificationSheet.kt.
     */
    private fun bitmapFromMatrix(matrix: BitMatrix): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val bitmap = createBitmap(width, height)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap[x, y] = if (matrix[x, y]) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
            }
        }

        return bitmap
    }

    /**
     * Escape special characters in Wi-Fi SSID/password for QR code format.
     * Special characters that need escaping: \ ; , " :
     */
    private fun escapeWifiString(input: String): String {
        return input
            .replace("\\", "\\\\")  // Backslash must be escaped first
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\"", "\\\"")
            .replace(":", "\\:")
    }
}
