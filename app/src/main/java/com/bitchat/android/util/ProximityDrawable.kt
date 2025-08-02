package com.bitchat.android.ui.drawables

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/**
 * A custom drawable that renders a 4-bar signal strength indicator.
 * The number of active bars is determined by the proximity level.
 *
 * @param proximity An integer from 0 to 4 representing signal strength.
 * @param activeColor The color of the active bars.
 * @param inactiveColor The color of the inactive bars.
 */
class ProximityDrawable(
    private val proximity: Int,
    private val activeColor: Int,
    private val inactiveColor: Int
) : Drawable() {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
    }

    // Defines the relative heights of the four bars.
    private val barHeightFactors = listOf(0.4f, 0.6f, 0.8f, 1.0f)

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val barWidth = width / 7f // Leaves space between bars
        val barSpacing = barWidth / 2f

        for (i in 0..3) {
            paint.color = if (i < proximity) activeColor else inactiveColor

            val barHeight = height * barHeightFactors[i]
            val left = (barWidth + barSpacing) * i
            val top = height - barHeight

            canvas.drawRoundRect(left, top, left + barWidth, height, 2f, 2f, paint)
        }
    }

    // Helper to convert this drawable to a Bitmap, which RemoteViews requires.
    fun toBitmap(width: Int, height: Int): Bitmap {
        return this.toBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}