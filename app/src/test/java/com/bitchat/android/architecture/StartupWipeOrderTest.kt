package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hilt injects inside `super.onCreate()`, so the pending-wipe re-check must run before it, as the
 * first statement, and the replay of the panic's system-side cleanups, which Hilt provides, right
 * after it.
 */
class StartupWipeOrderTest {

    private val body: String = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val source = File(root, "app/src/main/java/com/bitchat/android/BitchatApplication.kt").readText()
        // Comments removed, block and trailing ones too, so none can stand in for a statement.
        source.substringAfter("override fun onCreate()").substringAfter("{")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")
    }

    private fun firstStatement(code: String): String = code.lines().map { it.trim() }.first { it.isNotEmpty() }

    private val hilt = body.indexOf("super.onCreate()")

    @Test
    fun theApplicationFinishesThePendingWipeFirstAndBeforeHilt() {
        val wipe = body.indexOf("StartupWipe.finishPendingWipe(")
        assertTrue("onCreate must call StartupWipe.finishPendingWipe", wipe >= 0)
        assertTrue("super.onCreate() missing", hilt >= 0)
        assertTrue("finishPendingWipe must come before super.onCreate()", wipe < hilt)
        val first = firstStatement(body)
        assertTrue(
            "The first statement of onCreate must finish the pending wipe: $first",
            first.contains("StartupWipe.finishPendingWipe("),
        )
    }

    @Test
    fun theApplicationReplaysThePanicCleanupsRightAfterHilt() {
        assertTrue("super.onCreate() missing", hilt >= 0)
        val firstAfterHilt = firstStatement(body.substring(hilt + "super.onCreate()".length))
        assertTrue(
            "The first statement after super.onCreate() must replay the cleanups: $firstAfterHilt",
            firstAfterHilt.contains("StartupWipe.replayCleanups("),
        )
    }
}
