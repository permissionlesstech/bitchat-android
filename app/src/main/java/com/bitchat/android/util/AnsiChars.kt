import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.graphics.get
import androidx.core.graphics.scale

/**
 * A helper object providing compile-time constants and utility functions
 * for creating terminal-based art and user interfaces (TUI).
 *
 * This object includes constants for box-drawing, shades, and symbols,
 * as well as helper functions for drawing shapes, gradients, and converting
 * Android Bitmaps to ANSI/ASCII art in both grayscale and 256-color.
 */
object AnsiChars {

    // --- General Characters ---
    const val EMPTY_CHAR = ' ' // Standard space
    const val BRAILLE_BLANK = '⠀' // Braille Pattern Blank (U+2800), an invisible space.
    const val ESC = '\u001B' // ANSI Escape character

    // --- Single-Line Box Drawing ---
    const val BOX_HORIZONTAL = '─'
    const val BOX_VERTICAL = '│'
    const val BOX_DOWN_AND_RIGHT = '┌'
    const val BOX_DOWN_AND_LEFT = '┐'
    const val BOX_UP_AND_RIGHT = '└'
    const val BOX_UP_AND_LEFT = '┘'
    const val BOX_VERTICAL_AND_RIGHT = '├'
    const val BOX_VERTICAL_AND_LEFT = '┤'
    const val BOX_DOWN_AND_HORIZONTAL = '┬'
    const val BOX_UP_AND_HORIZONTAL = '┴'
    const val BOX_VERTICAL_AND_HORIZONTAL = '┼'

    // --- Rounded-Corner Box Drawing ---
    const val BOX_ROUNDED_DOWN_AND_RIGHT = '╭'
    const val BOX_ROUNDED_DOWN_AND_LEFT = '╮'
    const val BOX_ROUNDED_UP_AND_RIGHT = '╰'
    const val BOX_ROUNDED_UP_AND_LEFT = '╯'

    // --- Double-Line Box Drawing ---
    const val BOX_DOUBLE_HORIZONTAL = '═'
    const val BOX_DOUBLE_VERTICAL = '║'
    const val BOX_DOUBLE_DOWN_AND_RIGHT = '╔'
    const val BOX_DOUBLE_DOWN_AND_LEFT = '╗'
    const val BOX_DOUBLE_UP_AND_RIGHT = '╚'
    const val BOX_DOUBLE_UP_AND_LEFT = '╝'
    const val BOX_DOUBLE_VERTICAL_AND_RIGHT = '╠'
    const val BOX_DOUBLE_VERTICAL_AND_LEFT = '╣'
    const val BOX_DOUBLE_DOWN_AND_HORIZONTAL = '╦'
    const val BOX_DOUBLE_UP_AND_HORIZONTAL = '╩'
    const val BOX_DOUBLE_VERTICAL_AND_HORIZONTAL = '╬'

    // --- Block Elements ---
    const val BLOCK_FULL = '█'
    const val BLOCK_UPPER_HALF = '▀'
    const val BLOCK_LOWER_HALF = '▄'
    const val BLOCK_LEFT_HALF = '▌'
    const val BLOCK_RIGHT_HALF = '▐'
    const val BLOCK_UPPER_LEFT_QUADRANT = '▘'
    const val BLOCK_UPPER_RIGHT_QUADRANT = '▝'
    const val BLOCK_LOWER_LEFT_QUADRANT = '▖'
    const val BLOCK_LOWER_RIGHT_QUADRANT = '▗'
    const val BLOCK_QUADRANT_UPPER_LEFT_AND_LOWER_RIGHT = '▚'
    const val BLOCK_QUADRANT_UPPER_RIGHT_AND_LOWER_LEFT = '▞'


    // --- Shades ---
    const val SHADE_LIGHT = '░'
    const val SHADE_MEDIUM = '▒'
    const val SHADE_DARK = '▓'

    // --- Braille Patterns (useful for small icons) ---
    const val BRAILLE_DOTS_1 = '⠁'
    const val BRAILLE_DOTS_12 = '⠃'
    const val BRAILLE_DOTS_123 = '⠇'
    const val BRAILLE_DOTS_245 = '⠚' // Looks like a person/peer icon

    // --- ASCII Character Ramps for Image Conversion ---
    const val ASCII_RAMP_SIMPLE = "@%#*+=-:. " // Darkest to lightest
    const val ASCII_RAMP_DETAILED = "\$@B%8&WM#*oahkbdpqwmZO0QLCJUYXzcvunxrjft/\\|()1{}[]?-_+~<>i!lI;:,\"^`'. "

    // --- Other Symbols ---
    const val CHECKMARK = '✓'
    const val CROSS = '✗'
    const val TRIANGLE_UP = '▲'
    const val TRIANGLE_DOWN = '▼'
    const val TRIANGLE_LEFT = '◀'
    const val TRIANGLE_RIGHT = '▶'

    // --- IRC Control Codes ---
    const val IRC_BOLD = '\u0002'
    const val IRC_COLOR = '\u0003'
    const val IRC_REVERSE = '\u0016'
    const val IRC_UNDERLINE = '\u001F'
    const val IRC_RESET = '\u000F'


    /**
     * A simple, theme-aware color palette for the terminal UI.
     * These should be initialized with colors from the app's theme.
     */
    object Palette {
        var primary: Int = android.graphics.Color.GREEN
        var secondary: Int = android.graphics.Color.WHITE
        var background: Int = android.graphics.Color.BLACK
        var accent: Int = android.graphics.Color.CYAN

        fun initialize(isDarkTheme: Boolean) {
            if (isDarkTheme) {
                primary = android.graphics.Color.rgb(0x39, 0xFF, 0x14) // Bright Green
                secondary = android.graphics.Color.LTGRAY
                background = android.graphics.Color.BLACK
                accent = android.graphics.Color.rgb(0x00, 0xFF, 0xFF) // Cyan
            } else {
                primary = android.graphics.Color.DKGRAY
                secondary = android.graphics.Color.GRAY
                background = android.graphics.Color.WHITE
                accent = android.graphics.Color.BLUE
            }
        }

        fun getProximityColor(proximity: Int, maxProximity: Int = 4): Int {
            // Simple gradient from dim to bright based on proximity (0-4)
            val alpha = (60 + (proximity.toFloat() / maxProximity) * 195).toInt().coerceIn(0, 255)
            return android.graphics.Color.argb(alpha,
                android.graphics.Color.red(primary),
                android.graphics.Color.green(primary),
                android.graphics.Color.blue(primary)
            )
        }
    }


    // --- Helper Functions for Drawing ---

    /**
     * Creates a string by repeating a character a specified number of times.
     */
    fun line(char: Char, length: Int): String {
        if (length <= 0) return ""
        return char.toString().repeat(length)
    }

    /**
     * Draws a simple box using single-line characters.
     */
    fun simpleBox(width: Int, height: Int, rounded: Boolean = false): String {
        if (width < 2 || height < 2) return ""

        val tl = if(rounded) BOX_ROUNDED_DOWN_AND_RIGHT else BOX_DOWN_AND_RIGHT
        val tr = if(rounded) BOX_ROUNDED_DOWN_AND_LEFT else BOX_DOWN_AND_LEFT
        val bl = if(rounded) BOX_ROUNDED_UP_AND_RIGHT else BOX_UP_AND_RIGHT
        val br = if(rounded) BOX_ROUNDED_UP_AND_LEFT else BOX_UP_AND_LEFT

        val top = "$tl${line(BOX_HORIZONTAL, width - 2)}$tr"
        val bottom = "$bl${line(BOX_HORIZONTAL, width - 2)}$br"

        if (height == 2) return "$top\n$bottom"

        val middleContent = " ".repeat(width - 2)
        val middle = "$BOX_VERTICAL$middleContent$BOX_VERTICAL"
        val middleRows = List(height - 2) { middle }.joinToString("\n")

        return "$top\n$middleRows\n$bottom"
    }

    /**
     * Draws a box with a title embedded in the top border.
     */
    fun titledBox(title: String, width: Int, useDoubleLine: Boolean = false): String {
        val cleanTitle = " $title "
        if (width < cleanTitle.length + 2) return ""

        val hChar = if (useDoubleLine) BOX_DOUBLE_HORIZONTAL else BOX_HORIZONTAL
        val tlChar = if (useDoubleLine) BOX_DOUBLE_DOWN_AND_RIGHT else BOX_DOWN_AND_RIGHT
        val trChar = if (useDoubleLine) BOX_DOUBLE_DOWN_AND_LEFT else BOX_DOWN_AND_LEFT
        val blChar = if (useDoubleLine) BOX_DOUBLE_UP_AND_RIGHT else BOX_UP_AND_RIGHT
        val brChar = if (useDoubleLine) BOX_DOUBLE_UP_AND_LEFT else BOX_UP_AND_LEFT

        val remainingWidth = width - cleanTitle.length - 2
        val leftPad = remainingWidth / 2
        val rightPad = remainingWidth - leftPad

        val top = "$tlChar${line(hChar, leftPad)}$cleanTitle${line(hChar, rightPad)}$trChar"
        val bottom = "$blChar${line(hChar, width - 2)}$brChar"

        return "$top\n$bottom"
    }

    // --- Color & Gradient Helpers ---

    /**
     * Wraps a string with ANSI 256-color escape codes.
     * @param text The string to colorize.
     * @param colorCode A color code between 0 and 255.
     * @return The colorized string.
     */
    fun colorize(text: String, colorCode: Int): String {
        val code = colorCode.coerceIn(0, 255)
        return "$ESC[38;5;${code}m$text$ESC[0m"
    }

    /**
     * Creates a horizontal gradient using shade characters.
     */
    fun gradient(width: Int, reversed: Boolean = false): String {
        if (width <= 0) return ""
        val gradientChars = listOf(SHADE_LIGHT, SHADE_MEDIUM, SHADE_DARK, BLOCK_FULL)
        val finalChars = if (reversed) gradientChars.reversed() else gradientChars

        val segmentWidth = width / finalChars.size
        val remainder = width % finalChars.size

        val sb = StringBuilder(width)
        finalChars.forEachIndexed { index, char ->
            val len = segmentWidth + if (index < remainder) 1 else 0
            sb.append(char.toString().repeat(len))
        }
        return sb.toString()
    }

    // --- Image Conversion Helpers ---

    /**
     * Converts an Android Bitmap into a grayscale ANSI/ASCII art string.
     */
    fun imageToAnsi(bitmap: Bitmap, targetWidth: Int, detailed: Boolean = false): String {
        if (targetWidth <= 0) return ""

        val ramp = if (detailed) ASCII_RAMP_DETAILED else ASCII_RAMP_SIMPLE
        val aspectRatio = bitmap.height.toDouble() / bitmap.width.toDouble()
        val targetHeight = (targetWidth * aspectRatio * 0.5).toInt().coerceAtLeast(1)
        val scaledBitmap = bitmap.scale(targetWidth, targetHeight)
        val sb = StringBuilder(targetWidth * targetHeight)

        for (y in 0 until scaledBitmap.height) {
            for (x in 0 until scaledBitmap.width) {
                val pixel = scaledBitmap[x, y]
                val brightness = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
                val rampIndex = (brightness / 255.0 * (ramp.length - 1)).roundToInt()
                sb.append(ramp.reversed()[rampIndex])
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Converts an Android Bitmap into a 256-color ANSI art string.
     * Uses the full block character for each "pixel" and colorizes it.
     */
    fun imageToAnsiColor(bitmap: Bitmap, targetWidth: Int): String {
        if (targetWidth <= 0) return ""

        val aspectRatio = bitmap.height.toDouble() / bitmap.width.toDouble()
        val targetHeight = (targetWidth * aspectRatio * 0.5).toInt().coerceAtLeast(1)
        val scaledBitmap = bitmap.scale(targetWidth, targetHeight)
        val sb = StringBuilder()

        for (y in 0 until scaledBitmap.height) {
            for (x in 0 until scaledBitmap.width) {
                val pixel = scaledBitmap[x, y]
                val colorCode = rgbToAnsi256(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                sb.append(colorize(BLOCK_FULL.toString(), colorCode))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Maps an RGB color to the nearest color in the xterm 256-color palette.
     */
    private fun rgbToAnsi256(r: Int, g: Int, b: Int): Int {
        // Grayscale colors
        if (abs(r - g) < 8 && abs(g - b) < 8) {
            val gray = (r + g + b) / 3
            if (gray > 238) return 231 // White
            if (gray < 18) return 16   // Black
            return 232 + ((gray - 8) / 10)
        }
        // 6x6x6 color cube
        val rAnsi = (r * 5 / 255)
        val gAnsi = (g * 5 / 255)
        val bAnsi = (b * 5 / 255)
        return 16 + (36 * rAnsi) + (6 * gAnsi) + bAnsi
    }
}
