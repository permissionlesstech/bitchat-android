package com.bitchat.android.ui.media

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceWaveformLoaderTest {
    @Test fun `cold source loads without a cached waveform`() = runTest {
        val result = loadVoiceWaveform("synthetic-final", cached = { null }, extract = { path, callback ->
            assertEquals("synthetic-final", path)
            callback(floatArrayOf(0f, 1f))
        })
        assertEquals(120, result!!.size)
        assertEquals(0f, result.first())
        assertEquals(1f, result.last())
    }

    @Test fun `warm source avoids decoding`() = runTest {
        val result = loadVoiceWaveform("cached", cached = { FloatArray(120) { 0.5f } }, extract = { _, _ -> fail("Decoded cached media") })
        assertEquals(List(120) { 0.5f }, result)
    }

    @Test fun `late completion from a cancelled live source cannot replace the finished source`() = runTest {
        var oldCallback: ((FloatArray?) -> Unit)? = null
        val old = async { loadVoiceWaveform("live", cached = { null }, extract = { _, callback -> oldCallback = callback }) }
        runCurrent()
        old.cancel()
        val finished = loadVoiceWaveform("finished", cached = { null }, extract = { _, callback -> callback(FloatArray(120) { 1f }) })
        oldCallback!!(FloatArray(120) { 0f })
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(List(120) { 1f }, finished)
    }

    @Test fun `decode failures and empty samples are unavailable`() = runTest {
        assertNull(loadVoiceWaveform("bad", cached = { null }, extract = { _, callback -> callback(null) }))
        assertNull(loadVoiceWaveform("empty", cached = { floatArrayOf() }))
    }
}
