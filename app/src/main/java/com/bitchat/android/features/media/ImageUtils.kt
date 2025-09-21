package com.bitchat.android.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object ImageUtils {
    fun downscaleAndSaveToAppFiles(context: Context, uri: android.net.Uri, maxDim: Int = 512, quality: Int = 85): String? {
        return try {
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri) ?: return null
            val original = BitmapFactory.decodeStream(input)
            input.close()
            original ?: return null
            val w = original.width
            val h = original.height
            val scale = (maxOf(w, h).toFloat() / maxDim.toFloat()).coerceAtLeast(1f)
            val newW = (w / scale).toInt().coerceAtLeast(1)
            val newH = (h / scale).toInt().coerceAtLeast(1)
            val scaled = if (scale > 1f) Bitmap.createScaledBitmap(original, newW, newH, true) else original
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val outFile = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }
            if (scaled !== original) try { original.recycle() } catch (_: Exception) {}
            try { if (scaled != original) scaled.recycle() } catch (_: Exception) {}
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

