package com.bitchat.android.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.get
import androidx.core.graphics.scale
import kotlin.math.abs
import kotlin.random.Random

/**
 * A helper object providing compile-time constants and utility functions
 * for creating terminal-based art and user interfaces (TUI).
 *
 * This object includes a comprehensive set of constants for Code Page 437
 * (the original IBM PC character set), including box-drawing, shades, and symbols.
 * It also contains helper functions for converting Android Bitmaps to ANSI/ASCII art,
 * including advanced techniques like Braille art.
 */
object AnsiChars {

    // --- General & Control Characters ---
    const val EMPTY_CHAR = ' '
    const val BRAILLE_BLANK = '⠀' // An invisible character that still takes up space
    const val ESC = '\u001B'

    /**
     * Characters for drawing boxes and lines.
     * Based on the classic Code Page 437.
     */
    object Box {
        object Single {
            const val H = '─'; const val V = '│'
            const val DR = '┌'; const val DL = '┐'
            const val UR = '└'; const val UL = '┘'
            const val VR = '├'; const val VL = '┤'
            const val DH = '┬'; const val UH = '┴'; const val VH = '┼'
        }

        object Double {
            const val H = '═'; const val V = '║'
            const val DR = '╔'; const val DL = '╗'
            const val UR = '╚'; const val UL = '╝'
            const val VR = '╠'; const val VL = '╣'
            const val DH = '╦'; const val UH = '╩'; const val VH = '╬'
        }

        object Rounded {
            const val DR = '╭'; const val DL = '╮'
            const val UR = '╰'; const val UL = '╯'
        }

        object Mixed {
            const val V_S_H_D = '╪'; const val V_D_H_S = '╫'
            const val DR_S_V_D_H = '╒'; const val DR_D_V_S_H = '╓'
            const val DL_S_V_D_H = '╕'; const val DL_D_V_S_H = '╖'
            const val UR_S_V_D_H = '╘'; const val UR_D_V_S_H = '╙'
            const val UL_S_V_D_H = '╛'; const val UL_D_V_S_H = '╜'
            const val VR_S_V_D_H = '╞'; const val VR_D_V_S_H = '╟'
            const val VL_S_V_D_H = '╡'; const val VL_D_V_S_H = '╢'
            const val DH_S_V_D_H = '╤'; const val DH_D_V_S_H = '╥'
            const val UH_S_V_D_H = '╧'; const val UH_D_V_S_H = '╨'
        }
    }

    /**
     * Block elements for filling areas, creating gradients, or pixel-like effects.
     */
    object Block {
        const val FULL = '█'; const val UPPER_H = '▀'; const val LOWER_H = '▄'
        const val LEFT_H = '▌'; const val RIGHT_H = '▐'
        const val LOWER_1_8 = ' '; const val LOWER_1_4 = '▂'; const val LOWER_3_8 = '▃'
        const val LOWER_5_8 = '▅'; const val LOWER_3_4 = '▆'; const val LOWER_7_8 = '▇'
        const val UPPER_1_8 = '▔'

        object Quadrant {
            const val UL = '▘'; const val UR = '▝'; const val LL = '▖'; const val LR = '▗'
            const val UL_LR = '▚'; const val UR_LL = '▞'
            const val UL_UR_LL = '▛'; const val UL_UR_LR = '▜'
            const val UL_LL_LR = '▙'; const val UR_LL_LR = '▟'
        }
    }

    object Shade {
        const val LIGHT = '░'; const val MEDIUM = '▒'; const val DARK = '▓'
        val GRADIENT = listOf(LIGHT, MEDIUM, DARK, Block.FULL)
    }

    object Irc {
        const val BOLD = '\u0002'; const val COLOR = '\u0003'; const val REVERSE = '\u0016'
        const val UNDERLINE = '\u001F'; const val RESET = '\u000F'
    }

    object Shapes {
        const val CHECK = '✓'; const val CROSS = '✗'
        const val TRI_U = '▲'; const val TRI_D = '▼'; const val TRI_L = '◀'; const val TRI_R = '▶'
        const val SQ_S_F = '▪'; const val SQ_F = '■'; const val SQ_S = '▫'; const val SQ = '□'
        const val CIRC_F = '●'; const val CIRC = '○'
        const val DOT = '⋅'; const val BULLET = '•'
        const val DIAM_F = '◆'; const val DIAM = '◇'
    }

    /**
     * The complete set of iconic, mathematical, and international symbols from Code Page 437.
     */
    object Cp437Symbols {
        const val SMILEY_W = '☺'; const val SMILEY_B = '☻'; const val HEART = '♥'
        const val DIAMOND = '♦'; const val CLUB = '♣'; const val SPADE = '♠'
        const val BULLET_H = '○'; const val BULLET_F = '●'; const val MALE = '♂'; const val FEMALE = '♀'
        const val NOTE_1 = '♪'; const val NOTE_2 = '♫'; const val SUN = '☼'
        const val ARROW_R_F = '►'; const val ARROW_L_F = '◄'; const val ARROW_UD = '↕'
        const val EXCLAM_D = '‼'; const val PILCROW = '¶'; const val SECTION = '§'
        const val CURSOR_R = '▬'; const val ARROW_UD_B = '↨'; const val ARROW_U = '↑'
        const val ARROW_D = '↓'; const val ARROW_R = '→'; const val ARROW_L = '←'
        const val ANGLE_R = '∟'; const val ARROW_LR = '↔'; const val HOUSE = '⌂'
        const val C_CEDILLA_U = 'Ç'; const val U_DIAERESIS_L = 'ü'; const val E_ACUTE_L = 'é'
        const val A_CIRCUMFLEX_L = 'â'; const val A_DIAERESIS_L = 'ä'; const val A_GRAVE_L = 'à'
        const val A_RING_L = 'å'; const val C_CEDILLA_L = 'ç'; const val E_CIRCUMFLEX_L = 'ê'
        const val E_DIAERESIS_L = 'ë'; const val E_GRAVE_L = 'è'; const val I_DIAERESIS_L = 'ï'
        const val I_CIRCUMFLEX_L = 'î'; const val I_GRAVE_L = 'ì'; const val A_DIAERESIS_U = 'Ä'
        const val A_RING_U = 'Å'; const val E_ACUTE_U = 'É'; const val AE_L = 'æ'; const val AE_U = 'Æ'
        const val O_CIRCUMFLEX_L = 'ô'; const val O_DIAERESIS_L = 'ö'; const val O_GRAVE_L = 'ò'
        const val U_CIRCUMFLEX_L = 'û'; const val U_GRAVE_L = 'ù'; const val Y_DIAERESIS_L = 'ÿ'
        const val O_DIAERESIS_U = 'Ö'; const val U_DIAERESIS_U = 'Ü'; const val CENT = '¢'
        const val POUND = '£'; const val YEN = '¥'; const val PESETA = '₧'; const val F_HOOK = 'ƒ'
        const val A_ACUTE_L = 'á'; const val I_ACUTE_L = 'í'; const val O_ACUTE_L = 'ó'
        const val U_ACUTE_L = 'ú'; const val N_TILDE_L = 'ñ'; const val N_TILDE_U = 'Ñ'
        const val ORDINAL_F = 'ª'; const val ORDINAL_M = 'º'; const val Q_MARK_INV = '¿'
        const val NOT_REV = '⌐'; const val NOT = '¬'; const val HALF = '½'; const val QUARTER = '¼'
        const val EXCLAM_INV = '¡'; const val CHEVRON_L = '«'; const val CHEVRON_R = '»'
        const val ALPHA = 'α'; const val BETA_SHARP_S = 'ß'; const val GAMMA_U = 'Γ'; const val PI = 'π'
        const val SIGMA_U = 'Σ'; const val SIGMA_L = 'σ'; const val MU = 'µ'; const val TAU = 'τ'
        const val PHI_U = 'Φ'; const val THETA_U = 'Θ'; const val OMEGA_U = 'Ω'; const val DELTA_L = 'δ'
        const val INFINITY = '∞'; const val PHI_L = 'φ'; const val EPSILON = 'ε'
        const val INTERSECTION = '∩'; const val TRIPLE_BAR = '≡'; const val PLUS_MINUS = '±'
        const val GTE = '≥'; const val LTE = '≤'; const val INTEGRAL_T = '⌠'; const val INTEGRAL_B = '⌡'
        const val DIV = '÷'; const val ALMOST_EQ = '≈'; const val DEGREE = '°'
        const val BULLET_OP = '∙'; const val INTERPUNCT = '·'; const val SQRT = '√'
        const val POWER_N = 'ⁿ'; const val SQUARE = '²'; const val CURSOR_B = '■'
    }

    object AsciiRamp {
        const val SIMPLE = "@%#*+=-:. " // Darkest to lightest
        const val DETAILED = "\$@B%8&WM#*oahkbdpqwmZO0QLCJUYXzcvunxrjft/\\|()1{}[]?-_+~<>i!lI;:,\"^`'. "
    }

    /**
     * Interesting Unicode characters and sequences for special effects.
     */
    object Tricks {
        // For creating "corrupted" or "glitch" text (Zalgo)
        val ZALGO_UP = charArrayOf('\u030d', '\u030e', '\u0304', '\u0305', '\u033f', '\u0311', '\u0306', '\u0310', '\u0352', '\u0357', '\u0351', '\u0307', '\u0308', '\u030a', '\u0342', '\u0343', '\u0344', '\u034a', '\u034b', '\u034c', '\u0350', '\u0358', '\u035b', '\u035d', '\u035e', '\u035f', '\u0360', '\u0361', '\u0362')
        val ZALGO_DOWN = charArrayOf('\u0316', '\u0317', '\u0318', '\u0319', '\u031c', '\u031d', '\u031e', '\u031f', '\u0320', '\u0324', '\u0325', '\u0326', '\u0329', '\u032a', '\u032b', '\u032c', '\u032d', '\u032e', '\u032f', '\u0330', '\u0331', '\u0332', '\u0333', '\u0339', '\u033a', '\u033b', '\u033c')
        val ZALGO_MID = charArrayOf('\u0334', '\u0335', '\u0336', '\u0337', '\u0338', '\u033d', '\u033e', '\u0345', '\u0346', '\u0347', '\u0348', '\u0349', '\u034d', '\u034e', '\u0353', '\u0354', '\u0355', '\u0356', '\u0359', '\u035a', '\u035c')
    }

    // --- Helper Functions and Tricks ---

    fun line(char: Char, length: Int): String {
        if (length <= 0) return ""
        return char.toString().repeat(length)
    }

    fun colorize(text: String, colorCode: Int): String {
        val code = colorCode.coerceIn(0, 255)
        return "$ESC[38;5;${code}m$text$ESC[0m"
    }

    private fun rgbToAnsi256(r: Int, g: Int, b: Int): Int {
        if (abs(r - g) < 8 && abs(g - b) < 8) {
            val gray = (r + g + b) / 3
            if (gray > 238) return 231; if (gray < 18) return 16
            return 232 + ((gray - 8) / 10)
        }
        val rAnsi = (r * 5 / 255); val gAnsi = (g * 5 / 255); val bAnsi = (b * 5 / 255)
        return 16 + (36 * rAnsi) + (6 * gAnsi) + bAnsi
    }

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
                sb.append(colorize(Block.FULL.toString(), colorCode))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Converts an image to grayscale ASCII art.
     * @param ramp The string of characters to use for shading, from darkest to lightest.
     */
    fun imageToGrayscale(bitmap: Bitmap, targetWidth: Int, ramp: String = AsciiRamp.SIMPLE): String {
        if (targetWidth <= 0) return ""
        val aspectRatio = bitmap.height.toDouble() / bitmap.width.toDouble()
        val targetHeight = (targetWidth * aspectRatio * 0.5).toInt().coerceAtLeast(1)
        val scaledBitmap = bitmap.scale(targetWidth, targetHeight)
        val sb = StringBuilder()
        for (y in 0 until scaledBitmap.height) {
            for (x in 0 until scaledBitmap.width) {
                val pixel = scaledBitmap[x, y]
                val gray = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                val rampIndex = (gray * (ramp.length - 1)) / 255
                sb.append(ramp[rampIndex])
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Converts an image to high-resolution text art using Braille characters.
     * Each Braille char is a 2x4 matrix of dots.
     */
    fun imageToBraille(bitmap: Bitmap, targetWidth: Int, invert: Boolean = false): String {
        if (targetWidth <= 0) return ""
        val charWidth = targetWidth * 2
        val aspectRatio = bitmap.height.toDouble() / bitmap.width.toDouble()
        val charHeight = (charWidth * aspectRatio * 0.5).toInt().coerceAtLeast(1)
        val scaledBitmap = bitmap.scale(charWidth, charHeight)
        val sb = StringBuilder()

        for (y in 0 until charHeight step 4) {
            for (x in 0 until charWidth step 2) {
                var brailleCode = 0x2800
                var dotValue = 0
                // Braille dots are numbered 1-8, column by column
                if (y + 0 < charHeight && x + 0 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x, y]) > 128) 1 else 0; brailleCode += dotValue * 1
                if (y + 1 < charHeight && x + 0 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x, y + 1]) > 128) 1 else 0; brailleCode += dotValue * 2
                if (y + 2 < charHeight && x + 0 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x, y + 2]) > 128) 1 else 0; brailleCode += dotValue * 4
                if (y + 0 < charHeight && x + 1 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x + 1, y]) > 128) 1 else 0; brailleCode += dotValue * 8
                if (y + 1 < charHeight && x + 1 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x + 1, y + 1]) > 128) 1 else 0; brailleCode += dotValue * 16
                if (y + 2 < charHeight && x + 1 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x + 1, y + 2]) > 128) 1 else 0; brailleCode += dotValue * 32
                if (y + 3 < charHeight && x + 0 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x, y + 3]) > 128) 1 else 0; brailleCode += dotValue * 64
                if (y + 3 < charHeight && x + 1 < charWidth) dotValue = if (Color.alpha(scaledBitmap[x + 1, y + 3]) > 128) 1 else 0; brailleCode += dotValue * 128

                if (invert) brailleCode = 0x28FF - (brailleCode - 0x2800)
                if (brailleCode == 0x2800) sb.append(EMPTY_CHAR) else sb.append(brailleCode.toChar())
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Applies "Zalgo" effect to text, making it look corrupted or demonic.
     */
    fun zalgo(text: String, up: Int = 3, mid: Int = 2, down: Int = 3): String {
        val sb = StringBuilder()
        text.forEach { char ->
            sb.append(char)
            repeat(Random.nextInt(up)) { sb.append(Tricks.ZALGO_UP.random()) }
            repeat(Random.nextInt(mid)) { sb.append(Tricks.ZALGO_MID.random()) }
            repeat(Random.nextInt(down)) { sb.append(Tricks.ZALGO_DOWN.random()) }
        }
        return sb.toString()
    }
}
