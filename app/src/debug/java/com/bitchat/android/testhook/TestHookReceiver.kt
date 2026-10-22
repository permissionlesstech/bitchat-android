package com.bitchat.android.testhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/**
 * ADB-drivable test hook (debug builds only).
 *
 * Usage:
 *   adb shell am broadcast -a com.bitchat.droid.TEST_HOOK \
 *     --es cmd <command> --es id <cmd-id> [command extras...]
 *
 * Result is written to cache/testhook/results/<id>.json and logged under tag TestHook:
 *   adb shell run-as com.bitchat.droid cat cache/testhook/results/<id>.json
 */
class TestHookReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TestHook"
        const val ACTION = "com.bitchat.droid.TEST_HOOK"
        private const val DEFAULT_OVERALL_TIMEOUT_MS = 180_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra("cmd") ?: "ping"
        val id = intent.getStringExtra("id") ?: "cmd-${System.currentTimeMillis()}"
        val overallTimeout = intent.getLongExtra("overall_timeout_ms", DEFAULT_OVERALL_TIMEOUT_MS)

        Log.i(TAG, "CMD id=$id cmd=$cmd")

        val pendingResult = goAsync()
        Thread {
            val result = try {
                runBlocking {
                    withTimeout(overallTimeout) {
                        TestHookDriver.execute(context.applicationContext, cmd, intent)
                    }
                }
            } catch (e: Exception) {
                JSONObject()
                    .put("status", "error")
                    .put("cmd", cmd)
                    .put("error", "${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                val dir = File(context.cacheDir, "testhook/results").apply { mkdirs() }
                File(dir, "$id.json").writeText(result.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write result file for $id: ${e.message}")
            }
            Log.i(TAG, "RESULT id=$id $result")
        }.start()
        // Finish immediately: long-running commands continue on the worker thread and
        // report via the result file. Holding the broadcast open past the system
        // broadcast window would ANR the app.
        pendingResult.finish()
    }
}
