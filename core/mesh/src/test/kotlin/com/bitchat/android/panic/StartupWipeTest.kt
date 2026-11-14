package com.bitchat.android.panic

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class StartupWipeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val marker = PanicMarker(context)

    private fun seed(relative: String): File =
        File(context.dataDir, relative).apply {
            parentFile!!.mkdirs()
            writeText("x")
        }

    private object NoAliases : KeyAliases {
        override fun aliases(): List<String> = emptyList()
        override fun delete(alias: String) = Unit
    }

    private val confirmed = WipeResult(leftovers = emptyList(), aliasesLeft = 0)
    private val unconfirmed = WipeResult(leftovers = listOf("files/x"), aliasesLeft = 0)

    @Test
    fun noMarkerTouchesNothing() {
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
            .edit().putString("nickname", "anon1234").commit()
        val file = seed("files/synthetic_leftover")
        var runs = 0

        val clear = StartupWipe.finishPendingWipe(context, { runs++; confirmed }, marker)

        assertTrue(clear)
        assertEquals(0, runs)
        assertTrue(file.exists())
        assertEquals(
            "anon1234",
            context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).getString("nickname", null),
        )
    }

    @Test
    fun aPendingMarkerWipesThenClears() {
        marker.set()
        val file = seed("files/synthetic_leftover")

        val clear = StartupWipe.finishPendingWipe(
            context,
            wipe = { SandboxWipe(context, keyStore = NoAliases).run() },
            marker = marker,
        )

        assertTrue(clear)
        assertFalse(file.exists())
        assertFalse(marker.pending)
    }

    @Test
    fun aWipeThatNeverConfirmsLeavesTheMarker() {
        marker.set()
        var runs = 0

        val clear = StartupWipe.finishPendingWipe(context, { runs++; unconfirmed }, marker)

        assertFalse(clear)
        assertEquals(2, runs)
        assertTrue(marker.pending)
    }

    @Test
    fun aSecondAttemptThatConfirmsClearsTheMarker() {
        marker.set()
        var runs = 0

        val clear = StartupWipe.finishPendingWipe(context, { if (runs++ == 0) unconfirmed else confirmed }, marker)

        assertTrue(clear)
        assertEquals(2, runs)
        assertFalse(marker.pending)
    }

    @Test
    fun aWipeThatThrowsLeavesTheMarkerAndDoesNotCrash() {
        marker.set()

        val clear = StartupWipe.finishPendingWipe(context, { error("boom") }, marker)

        assertFalse(clear)
        assertTrue(marker.pending)
    }
}
