package com.bitchat.android.features.torfiles

import android.app.Application
import android.util.Log
import com.bitchat.android.features.voice.VoiceNoteBus
import com.bitchat.android.features.voice.VoiceNoteEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Very small blocking HTTP 1.1 server for receiving file uploads via Tor.
 * Endpoint: POST /upload with headers:
 *   - X-Bitchat-Type: voice
 *   - X-Bitchat-PeerID: <senderPeerID>
 *   - X-Bitchat-Filename: <name>
 *   - Content-Length: <bytes>
 * Body: raw file bytes
 */
class TorFileTransferServer(
    private val application: Application,
    private val port: Int
) {
    companion object { private const val TAG = "TorFileServer" }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
        pool.execute { acceptLoop() }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        Log.i(TAG, "Listening on 127.0.0.1:$port")
        while (running.get()) {
            try {
                val s = serverSocket?.accept() ?: break
                pool.execute { handleClient(s) }
            } catch (_: Exception) {
                if (running.get()) Log.w(TAG, "Accept failed (continuing)")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 30_000
        BufferedInputStream(socket.getInputStream()).use { input ->
            BufferedOutputStream(socket.getOutputStream()).use { output ->
                try {
                    val request = readHttpRequest(input)
                    val method = request.first
                    val headers = request.second
                    if (method != "POST" || !headers[":path"].equals("/upload")) {
                        writeHttpResponse(output, 404, "Not Found", "Only POST /upload supported")
                        return
                    }

                    val length = headers["content-length"]?.toLongOrNull()
                    val peerID = headers["x-bitchat-peerid"] ?: ""
                    val type = headers["x-bitchat-type"] ?: ""
                    val filename = headers["x-bitchat-filename"] ?: ""

                    if (length == null || length <= 0) {
                        writeHttpResponse(output, 411, "Length Required", "Missing content-length")
                        return
                    }

                    val inDir = File(application.filesDir, "voicenotes/incoming")
                    inDir.mkdirs()
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val safeName = if (filename.isBlank()) "voice_$ts.m4a" else filename
                    val outFile = File(inDir, safeName)

                    outFile.outputStream().use { fos ->
                        var remaining = length
                        val buf = ByteArray(32 * 1024)
                        while (remaining > 0) {
                            val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
                            val r = input.read(buf, 0, toRead)
                            if (r <= 0) break
                            fos.write(buf, 0, r)
                            remaining -= r
                        }
                    }

                    writeHttpResponse(output, 200, "OK", "Saved")

                    // Notify UI
                    if (type == "voice" && peerID.isNotBlank()) {
                        VoiceNoteBus.emit(
                            VoiceNoteEvent(
                                fromPeerID = peerID,
                                filePath = outFile.absolutePath,
                                mimeType = "audio/mp4"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Client error: ${e.message}")
                    try { writeHttpResponse(output, 500, "Internal Error", e.message ?: "") } catch (_: Exception) {}
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun readHttpRequest(input: BufferedInputStream): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        val sb = StringBuilder()
        var lastCRLF = false
        // Read start-line and headers
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        val lines = sb.toString().split("\r\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return "" to emptyMap()
        val start = lines.first()
        val parts = start.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: "/"
        headers[":path"] = path
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(":")
            if (idx > 0) {
                val name = lines[i].substring(0, idx).trim().lowercase()
                val value = lines[i].substring(idx + 1).trim()
                headers[name] = value
            }
        }
        return method to headers
    }

    private fun writeHttpResponse(output: BufferedOutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        val headers = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray())
        output.write(bytes)
        output.flush()
    }
}

