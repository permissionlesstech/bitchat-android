package com.bitchat.android.wallet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.WriterException
import androidx.compose.ui.platform.LocalDensity

/**
 * QR Code generator component using ZXing library
 */
@Composable
fun QRCodeCanvas(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp
) {
    val density = LocalDensity.current
    
    // Use a larger fixed size for better quality
    val qrCodeBitMatrix = remember(text) {
        try {
            val sizePx = with(density) { 512.dp.toPx() }.toInt() // Fixed high resolution
            val writer = QRCodeWriter()
            writer.encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
        } catch (e: WriterException) {
            null
        }
    }
    
    Canvas(
        modifier = modifier
            .background(Color.White)
    ) {
        qrCodeBitMatrix?.let { bitMatrix ->
            // Use the actual canvas size, not a calculated size
            val canvasSize = minOf(this.size.width, this.size.height)
            drawQRCode(bitMatrix, canvasSize)
        }
    }
}

private fun DrawScope.drawQRCode(
    bitMatrix: com.google.zxing.common.BitMatrix,
    sizePx: Float
) {
    val cellSize = sizePx / bitMatrix.width
    
    for (x in 0 until bitMatrix.width) {
        for (y in 0 until bitMatrix.height) {
            if (bitMatrix[x, y]) {
                drawRect(
                    color = Color.Black,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        x = x * cellSize,
                        y = y * cellSize
                    ),
                    size = androidx.compose.ui.geometry.Size(cellSize, cellSize)
                )
            }
        }
    }
}
