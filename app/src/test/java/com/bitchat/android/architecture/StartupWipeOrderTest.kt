package com.bitchat.android.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hilt injects inside `super.onCreate()`, so the pending-wipe re-check must run before it. */
class StartupWipeOrderTest {

    @Test
    fun theApplicationFinishesThePendingWipeBeforeHilt() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val source = File(root, "app/src/main/java/com/bitchat/android/BitchatApplication.kt").readText()
        val body = source.substringAfter("override fun onCreate()").lines()
            .filterNot { it.trim().startsWith("//") }.joinToString("\n")
        val wipe = body.indexOf("StartupWipe.finishPendingWipe(")
        val hilt = body.indexOf("super.onCreate()")
        assertTrue("onCreate must call StartupWipe.finishPendingWipe", wipe >= 0)
        assertTrue("super.onCreate() missing", hilt >= 0)
        assertTrue("finishPendingWipe must come before super.onCreate()", wipe < hilt)
    }
}
