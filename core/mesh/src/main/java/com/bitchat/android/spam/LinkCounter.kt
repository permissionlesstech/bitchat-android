package com.bitchat.android.spam

/**
 * Counts links the way the chat linkifies them: scheme URLs, `www.` hosts and bare domains
 * such as `example.com/path`. Pure Kotlin so it also runs on the watch and in JVM tests.
 */
internal object LinkCounter {

    private val LINK = Regex(
        """(?i)(?<![\w@.\-])(?:https?://[^\s<>]+|www\.[^\s<>]+|[a-z0-9\-]+(?:\.[a-z0-9\-]+)*\.[a-z]{2,}(?:/[^\s<>]*)?)(?![\w@])"""
    )

    fun count(text: String): Int = LINK.findAll(text).count()
}
