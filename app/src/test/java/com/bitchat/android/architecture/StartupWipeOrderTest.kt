package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hilt injects inside `super.onCreate()`, so the pending-wipe re-check must run before it, and the
 * replay of the panic's system-side cleanups, which Hilt provides, right after it.
 */
class StartupWipeOrderTest {

    private val body: String = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val source = File(root, "app/src/main/java/com/bitchat/android/BitchatApplication.kt").readText()
        source.substringAfter("override fun onCreate()").lines()
            .filterNot { it.trim().startsWith("//") }.joinToString("\n")
    }

    private val hilt = body.indexOf("super.onCreate()")

    @Test
    fun theApplicationFinishesThePendingWipeBeforeHilt() {
        val wipe = body.indexOf("StartupWipe.finishPendingWipe(")
        assertTrue("onCreate must call StartupWipe.finishPendingWipe", wipe >= 0)
        assertTrue("super.onCreate() missing", hilt >= 0)
        assertTrue("finishPendingWipe must come before super.onCreate()", wipe < hilt)
    }

    @Test
    fun theApplicationReplaysThePanicCleanupsRightAfterHilt() {
        assertTrue("super.onCreate() missing", hilt >= 0)
        val firstAfterHilt = body.substring(hilt + "super.onCreate()".length).lines()
            .map { it.trim() }.first { it.isNotEmpty() }
        assertTrue(
            "The first statement after super.onCreate() must replay the cleanups: $firstAfterHilt",
            firstAfterHilt.contains("StartupWipe.replayCleanups("),
        )
    }
}
