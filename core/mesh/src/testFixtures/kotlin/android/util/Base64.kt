@file:JvmName("Base64")

package android.util

const val DEFAULT: Int = 0
const val NO_WRAP: Int = 2

fun encodeToString(input: ByteArray, flags: Int): String {
    val encoder = if (flags and NO_WRAP != 0) {
        java.util.Base64.getEncoder()
    } else {
        java.util.Base64.getMimeEncoder()
    }
    return encoder.encodeToString(input)
}

fun decode(input: String, flags: Int): ByteArray {
    return if (flags and NO_WRAP != 0) {
        java.util.Base64.getDecoder().decode(input)
    } else {
        java.util.Base64.getMimeDecoder().decode(input)
    }
}
