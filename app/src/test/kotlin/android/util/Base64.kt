package android.util

/*
 * JVM test stub for android.util.Base64
 */
object Base64 {

    const val DEFAULT = 0
    const val NO_WRAP = 2

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        return java.util.Base64.getEncoder().encodeToString(input)
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        return java.util.Base64.getDecoder().decode(str)
    }
}
