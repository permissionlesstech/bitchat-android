package com.bitchat.android.panic

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class PanicMarkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aSetMarkerIsPendingForANewInstanceUntilCleared() {
        PanicMarker(context).clear()
        assertFalse(PanicMarker(context).pending)

        PanicMarker(context).set()
        assertTrue(PanicMarker(context).pending)

        PanicMarker(context).clear()
        assertFalse(PanicMarker(context).pending)
    }

    @Test
    fun aMarkerThatCannotBeDeletedIsNotCleared() {
        // A non-empty directory in the marker's place: the delete fails.
        val file = File(context.cacheDir, "undeletable/${PanicMarker.NAME}")
        File(file, "child").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val marker = PanicMarker(file)

        assertFalse(marker.clear())
        assertTrue(marker.pending)

        file.deleteRecursively()
        assertTrue(marker.clear())
    }

    @Test
    fun theWipeLeavesTheMarkerInPlace() {
        val marker = PanicMarker(context)
        marker.set()

        val result = SandboxWipe(context, keyStore = object : KeyAliases {
            override fun aliases() = emptyList<String>()
            override fun delete(alias: String) = Unit
        }).run()

        assertTrue(marker.pending)
        assertTrue(result.confirmed)
        marker.clear()
    }
}
