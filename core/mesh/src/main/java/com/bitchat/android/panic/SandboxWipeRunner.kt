package com.bitchat.android.panic

/**
 * Runs the sandbox wipe. The panic takes this rather than building [SandboxWipe] itself, so a
 * debug build can wrap the wipe with its test hooks and a test can pass one that does not confirm.
 */
fun interface SandboxWipeRunner {
    fun run(): WipeResult
}
