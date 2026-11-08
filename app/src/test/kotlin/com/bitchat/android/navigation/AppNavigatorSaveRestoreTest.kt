package com.bitchat.android.navigation

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class AppNavigatorSaveRestoreTest {

    @Test
    fun `a snapshot restores the stack it was taken from`() {
        val navigator = AppNavigator()
        navigator.resetTo(ChatRoute)

        val saved = navigator.snapshot()
        val revived = AppNavigator()
        revived.restore(saved)

        assertEquals(listOf<Any>(ChatRoute), revived.backStack.toList())
    }

    @Test
    fun `restoring an empty snapshot leaves the stack alone`() {
        val navigator = AppNavigator()
        navigator.resetTo(ChatRoute)

        navigator.restore(emptyList())

        assertEquals(listOf<Any>(ChatRoute), navigator.backStack.toList())
    }
}
