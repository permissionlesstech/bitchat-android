package com.bitchat.android.wifiaware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Collections

class SyncedSocketContractTest {
    @Test
    fun `write emits big-endian length payload and empty keepalive frames`() {
        val output = ByteArrayOutputStream()
        val raw = socket(input = ByteArrayInputStream(byteArrayOf()), output = output)
        val synced = SyncedSocket(raw, readTimeoutMs = 1_234)

        synced.write(byteArrayOf(1, 2, 3))
        synced.write(ByteArray(0))

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, 1, 2, 3, 0, 0, 0, 0),
            output.toByteArray()
        )
        verify(raw).soTimeout = 1_234
    }

    @Test
    fun `readFully reconstructs one-byte partial reads and keepalives`() {
        val wire = framed(byteArrayOf(1, 2, 3, 4)) + framed(ByteArray(0))
        val partial = object : FilterInputStream(ByteArrayInputStream(wire)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(1, length))
        }
        val synced = SyncedSocket(socket(partial, ByteArrayOutputStream()))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), synced.read())
        assertArrayEquals(ByteArray(0), synced.read())
        assertNull(synced.read())
    }

    @Test
    fun `EOF truncated invalid and oversized frames fail closed`() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0, 0),
            byteArrayOf(0, 0, 0, 4, 1, 2),
            intPrefix(-1),
            intPrefix(65_537)
        )

        cases.forEach { wire ->
            val synced = SyncedSocket(
                socket(ByteArrayInputStream(wire), ByteArrayOutputStream())
            )
            assertNull(synced.read())
        }
    }

    @Test
    fun `write exceptions propagate and do not create a partial success`() {
        val failingOutput = object : OutputStream() {
            override fun write(value: Int) {
                throw IOException("scripted write failure")
            }
        }
        val synced = SyncedSocket(socket(ByteArrayInputStream(byteArrayOf()), failingOutput))

        assertThrows(IOException::class.java) {
            synced.write(byteArrayOf(1))
        }
    }

    @Test
    fun `concurrent writers produce complete non-interleaved frames`() {
        val output = ByteArrayOutputStream()
        val synced = SyncedSocket(socket(ByteArrayInputStream(byteArrayOf()), output))
        val payloads = (0 until 16).map { index ->
            ByteArray(index + 1) { index.toByte() }
        }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = payloads.map { payload ->
            Thread {
                runCatching { synced.write(payload) }
                    .exceptionOrNull()
                    ?.let(failures::add)
            }.also(Thread::start)
        }
        threads.forEach { thread ->
            thread.join(2_000)
            assertFalse("Writer thread did not complete", thread.isAlive)
        }
        assertTrue(failures.isEmpty())

        val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        val decoded = mutableListOf<ByteArray>()
        while (input.available() > 0) {
            val length = input.readInt()
            decoded += ByteArray(length).also(input::readFully)
        }
        assertEquals(
            payloads.map(ByteArray::toList).toSet(),
            decoded.map(ByteArray::toList).toSet()
        )
    }

    @Test
    fun `close and raw socket status are exposed`() {
        val raw = socket(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
        org.mockito.kotlin.whenever(raw.isClosed).thenReturn(false, true)
        org.mockito.kotlin.whenever(raw.isConnected).thenReturn(true)
        val synced = SyncedSocket(raw)

        assertFalse(synced.isClosed())
        assertTrue(synced.isConnected())
        synced.close()
        verify(raw).close()
        assertTrue(synced.isClosed())
    }

    private fun socket(input: InputStream, output: OutputStream): Socket = mock<Socket> {
        on { getInputStream() } doReturn input
        on { getOutputStream() } doReturn output
    }

    private fun framed(payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(payload.size)
                data.write(payload)
            }
        }.toByteArray()

    private fun intPrefix(value: Int): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(value) }
        }.toByteArray()
}
