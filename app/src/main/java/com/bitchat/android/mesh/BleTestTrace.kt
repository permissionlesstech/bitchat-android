package com.bitchat.android.mesh

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bitchat.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in, device-local BLE test trace. It is inert in release builds and unless an
 * app-private config file is explicitly provisioned through the local test harness.
 * Events deliberately accept only bounded labels, numeric measurements and a digest
 * of a synthetic/test message ID; never pass peer IDs, addresses or message content.
 */
internal object BleTestTrace {
    const val TAG = "BitchatBleTrace"
    const val CONFIG_FILE = "ble-test-trace.json"

    private val lock = Any()
    private val sequence = AtomicLong()
    private val runIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val rolePattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$")
    private val labelPattern = Regex("^[A-Za-z][A-Za-z0-9_-]{0,39}$")
    private val testMarkerPattern = Regex("^(R40|MESH)[A-Za-z0-9_-]{3,59}$")
    private var configStamp: Long? = null
    private var activeRunId: String? = null
    private var activeRole: String? = null
    private var runStartElapsedMs = 0L

    fun isSafeRunId(value: String): Boolean = runIdPattern.matches(value)

    fun isSafeRole(value: String): Boolean = rolePattern.matches(value)

    private fun isSafeLabel(value: String): Boolean = labelPattern.matches(value)

    fun messageToken(messageId: String?): String? = messageId?.let(::digestToken)

    fun testMarkerToken(value: String?): String? =
        value?.takeIf(testMarkerPattern::matches)?.let(::digestToken)

    internal fun digestToken(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun configure(context: Context) {
        if (!BuildConfig.DEBUG) return
        val file = File(context.applicationContext.filesDir, CONFIG_FILE)
        val stamp = if (file.isFile) file.lastModified() else -1L
        synchronized(lock) {
            if (configStamp == stamp) return
            configStamp = stamp
            activeRunId = null
            activeRole = null
            if (!file.isFile) return
            val parsed = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
            if (parsed.optBoolean("enabled", false).not()) return
            val runId = parsed.optString("run_id")
            val role = parsed.optString("role", "device")
            if (!isSafeRunId(runId) || !isSafeRole(role)) return
            activeRunId = runId
            activeRole = role
            runStartElapsedMs = SystemClock.elapsedRealtime()
            sequence.set(0)
            val started = JSONObject()
                .put("run_id", runId)
                .put("role", role)
                .put("seq", sequence.incrementAndGet())
                .put("elapsed_ms", runStartElapsedMs)
                .put("event", "trace")
                .put("stage", "enabled")
                .put("outcome", "ok")
            Log.i(TAG, started.toString())
        }
    }

    fun record(
        context: Context,
        event: String,
        stage: String,
        outcome: String,
        code: Int? = null,
        count: Int? = null,
        bytes: Long? = null,
        durationMs: Long? = null,
        messageId: String? = null,
        testMarker: String? = null
    ) {
        configure(context)
        val runId: String
        val role: String
        val start: Long
        synchronized(lock) {
            runId = activeRunId ?: return
            role = activeRole ?: return
            start = runStartElapsedMs
        }
        if (!isSafeLabel(event) || !isSafeLabel(stage) || !isSafeLabel(outcome)) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val item = JSONObject()
            .put("run_id", runId)
            .put("role", role)
            .put("seq", sequence.incrementAndGet())
            .put("elapsed_ms", nowElapsed)
            .put("run_elapsed_ms", nowElapsed - start)
            .put("wall_ms", System.currentTimeMillis())
            .put("event", event)
            .put("stage", stage)
            .put("outcome", outcome)
        code?.let { item.put("code", it) }
        count?.let { item.put("count", it) }
        bytes?.let { item.put("bytes", it) }
        durationMs?.let { item.put("duration_ms", it) }
        messageToken(messageId)?.let { item.put("message_token", it) }
        testMarkerToken(testMarker)?.let { item.put("test_marker_token", it) }
        Log.i(TAG, item.toString())
    }
}
