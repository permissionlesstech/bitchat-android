package com.bitchat.android.spam

/** Compatibility entry point for the existing configuration and admission call sites. */
internal object EnglishGibberishDetector {
    internal const val THRESHOLD = MultilingualGibberishDetector.THRESHOLD
    fun isGibberish(content: String): Boolean = MultilingualGibberishDetector.isGibberish(content)
    internal fun score(content: String): Double? = MultilingualGibberishDetector.score(content)
}
