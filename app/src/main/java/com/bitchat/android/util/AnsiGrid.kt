package com.bitchat.android.util

import android.graphics.Canvas
import android.graphics.Paint

/**
 * A helper class to manage a grid of characters for ANSI-style art.
 * This makes it easier to "draw" text, shapes, and other elements onto a character-based canvas.
 */
class AnsiGrid(
    val width: Int,
    val height: Int,
    val textPaint: Paint
) {
    // A grid of characters.
    private val grid: Array<Array<Char>> = Array(height) {
        Array(width) { AnsiChars.EMPTY_CHAR }
    }

    fun setChar(x: Int, y: Int, char: Char) {
        if (x in 0 until width && y in 0 until height) {
            grid[y][x] = char
        }
    }

    fun drawText(x: Int, y: Int, text: String) {
        text.forEachIndexed { index, char ->
            setChar(x + index, y, char)
        }
    }

    fun clear() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                grid[y][x] = AnsiChars.EMPTY_CHAR
            }
        }
    }

    /**
     * Renders the entire grid onto a Canvas, drawing character-by-character to enforce a strict grid.
     * @param canvas The Android Canvas to draw on.
     * @param charWidth The calculated width of a single character cell in pixels.
     * @param charHeight The calculated height of a single character cell in pixels.
     */
    fun render(canvas: Canvas, charWidth: Float, charHeight: Float) {
        val baselineOffset = textPaint.fontMetrics.descent

        for (y in 0 until height) {
            for (x in 0 until width) {
                val charToDraw = grid[y][x]
                // Skip drawing blank characters for a minor performance improvement.
                if (charToDraw == AnsiChars.EMPTY_CHAR || charToDraw == AnsiChars.BRAILLE_BLANK) {
                    continue
                }

                // Calculate the exact X and Y position for this character in the grid.
                val drawX = x * charWidth
                val drawY = (y + 1) * charHeight - baselineOffset

                // Draw the single character at the calculated position.
                canvas.drawText(charToDraw.toString(), drawX, drawY, textPaint)
            }
        }
    }
}
