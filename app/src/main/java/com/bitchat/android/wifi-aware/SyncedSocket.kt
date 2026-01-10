package com.bitchat.android.wifiaware

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import android.util.Log

/**
 * A synchronized wrapper around a raw Socket that implements a framed protocol:
 * [4 bytes length][N bytes payload]
 */
class SyncedSocket(val rawSocket: Socket) {
    private val TAG = "SyncedSocket"
    private val writeLock = ReentrantLock()
    private val readLock = ReentrantLock()
    
    private val inputStream: DataInputStream
    private val outputStream: DataOutputStream

    init {
        // We wrap streams to create DataInput/Output helpers
        inputStream = DataInputStream(rawSocket.getInputStream())
        outputStream = DataOutputStream(rawSocket.getOutputStream())
    }

    /**
     * Writes a framed message to the socket.
     * Thread-safe.
     */
    fun write(data: ByteArray) {
        writeLock.withLock {
            Log.v(TAG, "Writing frame of size: ${data.size}")
            outputStream.writeInt(data.size)
            if (data.isNotEmpty()) {
                outputStream.write(data)
            }
            outputStream.flush()
        }
    }

    /**
     * Reads a framed message from the socket.
     * Blocks until a full frame is available.
     * Returns null if socket is closed or EOF.
     * Returns empty byte array for keep-alive (0 length frame).
     */
    fun read(): ByteArray? {
        readLock.withLock {
            try {
                // Read length prefix
                val length = try {
                    inputStream.readInt()
                } catch (e: java.io.EOFException) {
                    return null
                }
                Log.v(TAG, "Reading frame of size: $length")
                
                if (length < 0) throw IOException("Negative frame length: $length")
                if (length > 64 * 1024) throw IOException("Frame length exceeds 64KB limit: $length")
                
                if (length == 0) {
                    return ByteArray(0)
                }

                val buf = ByteArray(length)
                inputStream.readFully(buf)
                return buf
            } catch (e: IOException) {
                Log.e(TAG, "Socket read failed: ${e.message}")
                // Socket closed or error
                return null
            }
        }
    }
    
    fun close() {
        try { rawSocket.close() } catch (_: Exception) {}
    }

    fun isClosed() = rawSocket.isClosed
    fun isConnected() = rawSocket.isConnected
    val inetAddress get() = rawSocket.inetAddress
}
