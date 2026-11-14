package com.bitchat.android.panic

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
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
