package com.bitchat.watch.testhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/**
 * ADB-drivable test hook for the watch app (debug builds only). Mirrors the phone's protocol:
 *
 *   adb shell am broadcast -a com.bitchat.watch.TEST_HOOK \
 *     --es cmd <command> --es id <cmd-id> [command extras...]
 *
 * Result is written to cache/testhook/results/<id>.json (readable via run-as com.bitchat.watch)
 * and logged under tag TestHook.
 */
class WearTestHookReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TestHook"
        const val ACTION = "com.bitchat.watch.TEST_HOOK"
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
                        WearTestHookDriver.execute(context.applicationContext, cmd, intent)
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
        pendingResult.finish()
    }
}
