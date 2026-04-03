package android.util

/*
 * JVM test stub for android.util.Base64
 *
 * Mirrors the Android flag semantics so that unit tests behave the same
 * as production code (URL-safe alphabet, padding control, etc.).
 */
object Base64 {

    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoder = if (flags and URL_SAFE != 0) {
            java.util.Base64.getUrlEncoder()
        } else {
            java.util.Base64.getEncoder()
        }
        val result = encoder.encodeToString(input)
        return if (flags and NO_PADDING != 0) result.trimEnd('=') else result
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val decoder = if (flags and URL_SAFE != 0) {
            java.util.Base64.getUrlDecoder()
        } else {
            java.util.Base64.getDecoder()
        }
        return decoder.decode(str)
    }
}
